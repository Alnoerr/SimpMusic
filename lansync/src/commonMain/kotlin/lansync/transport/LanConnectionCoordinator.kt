package lansync.transport

import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lansync.api.MusicLibraryAdapter
import lansync.discovery.DiscoveredLanHost
import lansync.discovery.LanHostDiscovery
import lansync.engine.SyncEngine
import lansync.engine.SyncEngineState
import lansync.model.LAN_PROTOCOL_VERSION
import lansync.model.PairResponse
import lansync.model.ServerInfo
import lansync.security.DeviceCredentials
import lansync.security.LanCredentialStore
import lansync.storage.SyncJournal

enum class LanCoordinatorState {
    STOPPED,
    NOT_PAIRED,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    ERROR,
}

fun interface LanSyncClientFactory {
    fun create(endpoint: LanEndpoint, pinnedFingerprint: String, deviceAuthToken: String): LanSyncHttpClient
}

class LanConnectionCoordinator(
    private val discovery: LanHostDiscovery,
    private val credentialStore: LanCredentialStore,
    private val adapter: MusicLibraryAdapter,
    private val journal: SyncJournal,
    private val clientFactory: LanSyncClientFactory,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private val credentials = MutableStateFlow<DeviceCredentials?>(null)
    private val _state = MutableStateFlow(LanCoordinatorState.STOPPED)
    private val _engineState = MutableStateFlow(SyncEngineState.STOPPED)
    private val _lastError = MutableStateFlow<String?>(null)
    private val _lastSuccessfulSyncMillis = MutableStateFlow<Long?>(null)
    private val _pendingChanges = MutableStateFlow(0)
    private var observerJob: Job? = null
    private var engineStateJob: Job? = null
    private var session: Session? = null

    val state: StateFlow<LanCoordinatorState> = _state.asStateFlow()
    val engineState: StateFlow<SyncEngineState> = _engineState.asStateFlow()
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    val lastSuccessfulSyncMillis: StateFlow<Long?> = _lastSuccessfulSyncMillis.asStateFlow()
    val pendingChanges: StateFlow<Int> = _pendingChanges.asStateFlow()
    val availableHosts: StateFlow<List<DiscoveredLanHost>> = discovery.hosts

    suspend fun start() {
        mutex.withLock {
            if (observerJob?.isActive == true) return
            credentials.value = credentialStore.loadCredentials()
            refreshPending()
            _state.value = if (credentials.value == null) LanCoordinatorState.NOT_PAIRED else LanCoordinatorState.DISCOVERING
            observerJob = scope.launch {
                discovery.hosts.collectLatest { available ->
                    val saved = credentials.value
                    if (saved == null) {
                        _state.value = LanCoordinatorState.NOT_PAIRED
                        return@collectLatest
                    }
                    if (!saved.autoSync) {
                        _state.value = LanCoordinatorState.DISCOVERING
                        return@collectLatest
                    }
                    val discovered = available
                        .firstOrNull { it.compatible && it.deviceId == saved.pairedDesktopId }
                        ?.endpoints
                        ?.firstOrNull()
                    val endpoint = discovered ?: LanEndpoint(saved.lastKnownHost, saved.lastKnownPort)
                    connectWithRetry(saved, endpoint)
                }
            }
        }
        discovery.start()
    }

    suspend fun stop() {
        val job = mutex.withLock { observerJob.also { observerJob = null } }
        job?.cancelAndJoin()
        discovery.stop()
        mutex.withLock {
            closeSessionLocked()
            credentials.value = credentialStore.loadCredentials()
            _state.value = LanCoordinatorState.STOPPED
            _engineState.value = SyncEngineState.STOPPED
        }
    }

    suspend fun savePairedCredentials(
        localDeviceId: String,
        serverInfo: ServerInfo,
        response: PairResponse,
        endpoint: LanEndpoint,
    ) {
        require(response.peerId == localDeviceId) { "Pairing response device identity is inconsistent" }
        require(serverInfo.certificateFingerprint == response.certificateFingerprint) {
            "Pairing response host fingerprint is inconsistent"
        }
        val saved = DeviceCredentials(
            localDeviceId = localDeviceId,
            pairedDesktopId = serverInfo.device.deviceId,
            deviceAuthToken = response.accessToken,
            pinnedDesktopFingerprint = response.certificateFingerprint,
            lastKnownHost = endpoint.host,
            lastKnownPort = endpoint.port,
            displayName = serverInfo.device.displayName,
        )
        credentialStore.saveCredentials(saved)
        credentials.value = saved
        _state.value = LanCoordinatorState.DISCOVERING
        connectBestAvailable(saved, endpoint)
    }

    suspend fun connectTemporary(
        localDeviceId: String,
        serverInfo: ServerInfo,
        response: PairResponse,
        endpoint: LanEndpoint,
    ) {
        val temporary = DeviceCredentials(
            localDeviceId = localDeviceId,
            pairedDesktopId = serverInfo.device.deviceId,
            deviceAuthToken = response.accessToken,
            pinnedDesktopFingerprint = response.certificateFingerprint,
            lastKnownHost = endpoint.host,
            lastKnownPort = endpoint.port,
            displayName = serverInfo.device.displayName,
            autoSync = false,
        )
        credentials.value = temporary
        connectOnce(temporary, endpoint)
    }

    suspend fun connectManual(host: String, port: Int) {
        val saved = credentials.value ?: credentialStore.loadCredentials()
            ?: error("Pair the Desktop before using manual connection")
        val endpoint = LanEndpoint(host.trim(), port)
        val updated = saved.copy(lastKnownHost = endpoint.host, lastKnownPort = endpoint.port)
        if (credentialStore.loadCredentials() != null) credentialStore.saveCredentials(updated)
        credentials.value = updated
        connectOnce(updated, endpoint)
    }

    suspend fun syncNow() {
        val existing = mutex.withLock { session }
        if (existing == null) {
            val saved = credentials.value ?: credentialStore.loadCredentials()
                ?: error("Pair a Desktop before synchronizing")
            connectOnce(saved, LanEndpoint(saved.lastKnownHost, saved.lastKnownPort))
        }
        val active = mutex.withLock { session } ?: error("Desktop host is unavailable")
        active.engine.syncNow()
        markSuccessfulSync()
    }

    suspend fun disconnect() {
        val job = mutex.withLock { observerJob.also { observerJob = null } }
        job?.cancelAndJoin()
        discovery.stop()
        mutex.withLock {
            closeSessionLocked()
            _state.value = if (credentialStore.loadCredentials() == null) {
                LanCoordinatorState.NOT_PAIRED
            } else {
                LanCoordinatorState.DISCOVERING
            }
        }
    }

    suspend fun updateSavedDevice(displayName: String? = null, autoSync: Boolean? = null) {
        val current = credentialStore.loadCredentials() ?: error("No trusted Desktop is saved")
        val updated = current.copy(
            displayName = displayName?.trim()?.takeIf(String::isNotEmpty) ?: current.displayName,
            autoSync = autoSync ?: current.autoSync,
        )
        credentialStore.saveCredentials(updated)
        credentials.value = updated
        if (updated.autoSync && observerJob?.isActive != true) start()
    }

    suspend fun disconnectAndForget() {
        disconnect()
        credentialStore.clearCredentials()
        credentials.value = null
        _state.value = LanCoordinatorState.NOT_PAIRED
    }

    suspend fun savedCredentials(): DeviceCredentials? = credentialStore.loadCredentials()

    private suspend fun connectBestAvailable(saved: DeviceCredentials, fallback: LanEndpoint) {
        val endpoint = discovery.hosts.value
            .firstOrNull { it.compatible && it.deviceId == saved.pairedDesktopId }
            ?.endpoints
            ?.firstOrNull()
            ?: fallback
        connectOnce(saved, endpoint)
    }

    private suspend fun connectWithRetry(saved: DeviceCredentials, endpoint: LanEndpoint) {
        var backoff = 1L
        while (currentCoroutineContext().isActive && credentials.value == saved) {
            try {
                connectOnce(saved, endpoint)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _lastError.value = error.message ?: error::class.simpleName
                _state.value = LanCoordinatorState.ERROR
                delay(backoff.seconds)
                backoff = min(backoff * 2, 30)
            }
        }
    }

    private suspend fun connectOnce(saved: DeviceCredentials, endpoint: LanEndpoint): Unit = mutex.withLock {
        val current = session
        if (current?.endpoint == endpoint && current.engine.isRunning) return

        closeSessionLocked()
        _state.value = LanCoordinatorState.CONNECTING
        val client = clientFactory.create(endpoint, saved.pinnedDesktopFingerprint, saved.deviceAuthToken)
        try {
            val info = client.serverInfo()
            check(info.protocolVersion == LAN_PROTOCOL_VERSION) { "LAN sync protocol mismatch" }
            check(info.device.deviceId == saved.pairedDesktopId) { "Discovered host identity does not match paired Desktop" }
            check(info.certificateFingerprint == saved.pinnedDesktopFingerprint) { "Pinned host fingerprint changed" }
            val status = client.serverStatus()
            check(status.authenticatedDeviceId == saved.localDeviceId) { "Authenticated device identity mismatch" }

            val engine = SyncEngine(adapter, client, journal, saved.localDeviceId, scope)
            engine.start()
            if (credentialStore.loadCredentials() != null &&
                (saved.lastKnownHost != endpoint.host || saved.lastKnownPort != endpoint.port)
            ) {
                val updated = saved.copy(lastKnownHost = endpoint.host, lastKnownPort = endpoint.port)
                credentialStore.saveCredentials(updated)
                credentials.value = updated
            }
            session = Session(endpoint, engine)
            observeEngine(engine)
            _lastError.value = null
            _state.value = LanCoordinatorState.CONNECTED
            markSuccessfulSync()
        } catch (error: Throwable) {
            client.close()
            throw error
        }
    }

    private fun observeEngine(engine: SyncEngine) {
        engineStateJob?.cancel()
        engineStateJob = scope.launch {
            engine.state.collectLatest { value ->
                _engineState.value = value
                refreshPending()
                if (value == SyncEngineState.UP_TO_DATE) {
                    _lastSuccessfulSyncMillis.value = nowMillis()
                }
            }
        }
    }

    private suspend fun markSuccessfulSync() {
        _lastSuccessfulSyncMillis.value = nowMillis()
        refreshPending()
    }

    private suspend fun refreshPending() {
        _pendingChanges.value = journal.read().outbox.size
    }

    private suspend fun closeSessionLocked() {
        engineStateJob?.cancel()
        engineStateJob = null
        session?.engine?.stop()
        session = null
    }

    private data class Session(
        val endpoint: LanEndpoint,
        val engine: SyncEngine,
    )
}
