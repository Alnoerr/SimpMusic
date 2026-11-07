package lansync.discovery

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.DiscoveryRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidNsdDiscovery(
    context: Context,
    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager,
    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager,
    private val callbackExecutor: Executor = Executor(Runnable::run),
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val targetSdk: Int = context.applicationInfo.targetSdkVersion,
) : LanHostDiscovery {
    private val mutex = Mutex()
    private val knownHosts = linkedMapOf<String, DiscoveredLanHost>()
    private val pendingResolutions = mutableSetOf<String>()
    private val _hosts = MutableStateFlow<List<DiscoveredLanHost>>(emptyList())
    private val _state = MutableStateFlow(DiscoveryState.STOPPED)

    @Volatile
    private var running = false
    private var modernCallback: NsdManager.ServiceInfoCallback? = null
    private var legacyListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override val hosts: StateFlow<List<DiscoveredLanHost>> = _hosts.asStateFlow()
    override val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    override suspend fun start(): Unit = mutex.withLock {
        if (running) return
        running = true
        _state.value = DiscoveryState.STARTING
        withContext(mainDispatcher) {
            try {
                if (supportsCombinedDiscovery()) startModern() else startLegacy()
            } catch (_: SecurityException) {
                running = false
                _state.value = DiscoveryState.PERMISSION_DENIED
                releaseMulticastLock()
            } catch (_: Throwable) {
                running = false
                _state.value = DiscoveryState.ERROR
                releaseMulticastLock()
            }
        }
    }

    @SuppressLint("NewApi")
    override suspend fun stop(): Unit = mutex.withLock {
        if (!running && modernCallback == null && legacyListener == null) {
            _state.value = DiscoveryState.STOPPED
            return
        }
        running = false
        withContext(mainDispatcher) {
            modernCallback?.let { callback ->
                runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
            }
            modernCallback = null
            legacyListener?.let { listener ->
                runCatching { nsdManager.stopServiceDiscovery(listener) }
            }
            legacyListener = null
            releaseMulticastLock()
        }
        synchronized(knownHosts) {
            knownHosts.clear()
            pendingResolutions.clear()
            publishLocked()
        }
        _state.value = DiscoveryState.STOPPED
    }

    @SuppressLint("NewApi")
    private fun startModern() {
        val flags = if (targetSdk >= 37) DiscoveryRequest.FLAG_SHOW_PICKER else 0
        val request = DiscoveryRequest.Builder(LAN_SYNC_ANDROID_SERVICE_TYPE)
            .setFlags(flags)
            .build()
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                updateResolved(serviceInfo, serviceInfo.hostAddresses)
            }

            override fun onServiceLost() = Unit

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                remove(serviceInfo.serviceName)
            }

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                running = false
                _state.value = if (errorCode == PERMISSION_DENIED_CODE) {
                    DiscoveryState.PERMISSION_DENIED
                } else {
                    DiscoveryState.ERROR
                }
            }

            override fun onServiceInfoCallbackUnregistered() {
                if (running) _state.value = DiscoveryState.ERROR
            }
        }
        modernCallback = callback
        nsdManager.registerServiceInfoCallback(request, callbackExecutor, callback)
        _state.value = DiscoveryState.RUNNING
    }

    @Suppress("DEPRECATION")
    private fun startLegacy() {
        acquireMulticastLockIfNeeded()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _state.value = DiscoveryState.RUNNING
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!running || !serviceInfo.serviceType.startsWith("_simpmusic._tcp")) return
                val name = serviceInfo.serviceName
                synchronized(knownHosts) {
                    if (!pendingResolutions.add(name)) return
                }
                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            synchronized(knownHosts) { pendingResolutions.remove(name) }
                            @Suppress("DEPRECATION")
                            updateResolved(resolved, listOfNotNull(resolved.host))
                        }

                        override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                            synchronized(knownHosts) { pendingResolutions.remove(name) }
                        }
                    },
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                remove(serviceInfo.serviceName)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                running = false
                _state.value = if (errorCode == PERMISSION_DENIED_CODE) {
                    DiscoveryState.PERMISSION_DENIED
                } else {
                    DiscoveryState.ERROR
                }
                runCatching { nsdManager.stopServiceDiscovery(this) }
                releaseMulticastLock()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                _state.value = DiscoveryState.ERROR
            }

            override fun onDiscoveryStopped(serviceType: String) {
                if (running) _state.value = DiscoveryState.ERROR
            }
        }
        legacyListener = listener
        nsdManager.discoverServices(
            LAN_SYNC_ANDROID_SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            listener,
        )
    }

    private fun updateResolved(info: NsdServiceInfo, addresses: List<InetAddress>) {
        if (!running) return
        val deviceId = info.attributeText("deviceId") ?: return
        val protocol = info.attributeText("protocol")?.toIntOrNull() ?: return
        val values = addresses.mapNotNull { it.hostAddress }.distinct()
        if (values.isEmpty() || info.port !in 1..65535) return
        synchronized(knownHosts) {
            knownHosts[info.serviceName] = DiscoveredLanHost(
                serviceName = info.serviceName,
                deviceId = deviceId,
                protocolVersion = protocol,
                addresses = values,
                port = info.port,
            )
            publishLocked()
        }
    }

    private fun remove(serviceName: String) {
        synchronized(knownHosts) {
            knownHosts.remove(serviceName)
            pendingResolutions.remove(serviceName)
            publishLocked()
        }
    }

    private fun publishLocked() {
        _hosts.value = knownHosts.values.sortedBy(DiscoveredLanHost::serviceName)
    }

    private fun acquireMulticastLockIfNeeded() {
        if (tiramisuExtension() >= 7) return
        multicastLock = wifiManager?.createMulticastLock("SimpMusicLanSync")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    private fun supportsCombinedDiscovery(): Boolean =
        Build.VERSION.SDK_INT >= 37 || tiramisuExtension() >= 22

    private fun tiramisuExtension(): Int =
        if (Build.VERSION.SDK_INT >= 30) {
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU)
        } else {
            0
        }

    private fun NsdServiceInfo.attributeText(name: String): String? =
        attributes[name]?.toString(StandardCharsets.UTF_8)?.takeIf(String::isNotBlank)

    private companion object {
        const val PERMISSION_DENIED_CODE = 7
    }
}
