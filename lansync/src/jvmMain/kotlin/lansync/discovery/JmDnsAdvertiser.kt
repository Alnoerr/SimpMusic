package lansync.discovery

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lansync.model.LAN_PROTOCOL_VERSION

interface LanServiceAdvertiser {
    suspend fun start(port: Int, deviceId: String)
    suspend fun stop()
}

class JmDnsAdvertiser(
    private val scope: CoroutineScope,
    private val addressProvider: () -> List<InetAddress> = ::usableLanAddresses,
    private val refreshInterval: Duration = 15.seconds,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LanServiceAdvertiser {
    private val mutex = Mutex()
    private val registrations = linkedMapOf<InetAddress, JmDNS>()
    private val _lastError = MutableStateFlow<String?>(null)
    private var refreshJob: Job? = null
    private var port = 0
    private var deviceId = ""

    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    val activeRegistrationCount: Int get() = registrations.size

    override suspend fun start(port: Int, deviceId: String) {
        require(port in 1..65535) { "Port is invalid" }
        require(deviceId.isNotBlank()) { "Device ID is required" }
        mutex.withLock {
            if (refreshJob?.isActive == true) return
            this.port = port
            this.deviceId = deviceId
            try {
                refreshLocked()
                _lastError.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _lastError.value = error.message ?: error::class.simpleName
            }
            refreshJob = scope.launch(ioDispatcher) {
                while (isActive) {
                    delay(refreshInterval)
                    mutex.withLock {
                        try {
                            refreshLocked()
                            _lastError.value = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            _lastError.value = error.message ?: error::class.simpleName
                        }
                    }
                }
            }
        }
    }

    override suspend fun stop() {
        val job = mutex.withLock {
            refreshJob.also { refreshJob = null }
        }
        job?.cancelAndJoin()
        mutex.withLock {
            withContext(ioDispatcher) {
                registrations.values.forEach(::closeQuietly)
                registrations.clear()
            }
            port = 0
            deviceId = ""
        }
    }

    private suspend fun refreshLocked() = withContext(ioDispatcher) {
        val addresses = addressProvider().distinct().toSet()
        val removed = registrations.keys.filterNot(addresses::contains)
        removed.forEach { address -> registrations.remove(address)?.let(::closeQuietly) }

        addresses.filterNot(registrations::containsKey).forEach { address ->
            val dns = JmDNS.create(address, "SimpMusic-${deviceId.take(12)}")
            try {
                val properties = mapOf(
                    "protocol" to LAN_PROTOCOL_VERSION.toString(),
                    "deviceId" to deviceId,
                    "port" to port.toString(),
                )
                dns.registerService(
                    ServiceInfo.create(
                        LAN_SYNC_SERVICE_TYPE,
                        "SimpMusic Sync ${deviceId.take(8)}",
                        port,
                        0,
                        0,
                        properties,
                    ),
                )
                registrations[address] = dns
            } catch (error: Throwable) {
                closeQuietly(dns)
                throw error
            }
        }
    }

    private fun closeQuietly(dns: JmDNS) {
        runCatching { dns.unregisterAllServices() }
        runCatching { dns.close() }
    }
}

fun usableLanAddresses(): List<InetAddress> =
    Collections.list(NetworkInterface.getNetworkInterfaces())
        .asSequence()
        .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
        // Windows commonly puts overlay/VPN adapters before the physical LAN. Advertise every
        // usable address, but put ordinary Ethernet/Wi-Fi first so a QR address hint is reachable.
        .sortedWith(compareBy(::lanInterfacePenalty, NetworkInterface::getIndex))
        .flatMap { Collections.list(it.inetAddresses).asSequence() }
        .filterIsInstance<Inet4Address>()
        .filterNot { it.isLoopbackAddress || it.isMulticastAddress }
        .toList()

internal fun lanInterfacePenalty(networkInterface: NetworkInterface): Int {
    val label = "${networkInterface.name} ${networkInterface.displayName}".lowercase()
    val overlayMarkers = listOf(
        "zerotier",
        "tailscale",
        "wireguard",
        "outline",
        "singbox",
        " tun",
        "tap",
        "vpn",
        "wsl",
        "default switch",
        "docker",
        "vmware",
        "virtualbox",
        "vbox",
    )
    return if (overlayMarkers.any(label::contains)) 100 else 0
}
