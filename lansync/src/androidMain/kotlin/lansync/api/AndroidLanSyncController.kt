package lansync.api

import android.content.Context
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lansync.adapter.CompositeMusicLibraryAdapter
import lansync.adapter.FavoritesRepository
import lansync.adapter.PlaylistsRepository
import lansync.adapter.RoomFavoritesMusicLibraryAdapter
import lansync.adapter.RoomPlaylistMusicLibraryAdapter
import lansync.adapter.SavedPlaylistRepository
import lansync.discovery.AndroidNsdDiscovery
import lansync.discovery.DiscoveryState
import lansync.engine.SyncEngineState
import lansync.model.DeviceInfo
import lansync.model.LanPairingPayloadCodec
import lansync.security.JsonLanSyncConfigurationStore
import lansync.security.LanSyncConfiguration
import lansync.security.androidLanCredentialStore
import lansync.storage.JsonFileFavoriteStateStore
import lansync.storage.JsonFilePlaylistStateStore
import lansync.storage.JsonFileSyncJournal
import lansync.transport.LanConnectionCoordinator
import lansync.transport.LanCoordinatorState
import lansync.transport.LanSyncHttpClient
import lansync.transport.pinnedAndroidHttpClient
import lansync.transport.pinnedAndroidLanSyncClientFactory
import okio.Path.Companion.toPath

class AndroidLanSyncController(
    context: Context,
    private val repository: FavoritesRepository,
    private val playlistsRepository: PlaylistsRepository,
    private val savedPlaylistsRepository: SavedPlaylistRepository,
    private val defaultDeviceId: String,
    private val localDisplayName: String,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : LanSyncController {
    private val root = File(context.filesDir, "lansync")
    private val configurationStore =
        JsonLanSyncConfigurationStore(File(root, "configuration.json").absolutePath.toPath())
    private val credentialStore = androidLanCredentialStore(context)
    private val discovery = AndroidNsdDiscovery(context.applicationContext)
    private val journal = JsonFileSyncJournal(File(root, "journal.json").absolutePath.toPath())
    private val mutex = Mutex()
    private val _state = MutableStateFlow(LanSyncUiState())
    private val _trustedDevices = MutableStateFlow<List<TrustedDeviceSummary>>(emptyList())
    private var configuration: LanSyncConfiguration? = null
    private var coordinator: LanConnectionCoordinator? = null
    private var observerJob: Job? = null
    private var initialized = false

    override val state: StateFlow<LanSyncUiState> = _state.asStateFlow()
    override val trustedDevices: StateFlow<List<TrustedDeviceSummary>> = _trustedDevices.asStateFlow()

    override suspend fun initialize() {
        mutex.withLock {
            if (initialized) return
            val loaded = configurationStore.read() ?: LanSyncConfiguration(localDeviceId = defaultDeviceId).also {
                configurationStore.write(it)
            }
            configuration = loaded
        coordinator = createCoordinator(loaded.localDeviceId)
        observeCoordinator(coordinator!!)
        refreshTrustedDevice()
        initialized = true
        if (loaded.enabled) {
            _state.value = _state.value.copy(enabled = true, status = LanSyncStatus.DISCOVERING)
            coordinator!!.start()
            } else {
                _state.value = LanSyncUiState(enabled = false, status = LanSyncStatus.DISABLED)
            }
        }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        initialize()
        val current = configuration ?: error("LAN sync is not initialized")
        configuration = current.copy(enabled = enabled)
        configurationStore.write(configuration!!)
        _state.value = _state.value.copy(
            enabled = enabled,
            status = if (enabled) LanSyncStatus.DISCOVERING else LanSyncStatus.DISABLED,
            error = null,
        )
        if (enabled) coordinator!!.start() else coordinator!!.stop()
    }

    override suspend fun syncNow() {
        initialize()
        check(configuration?.enabled == true) { "Enable LAN synchronization first" }
        try {
            coordinator!!.syncNow()
            persistLastSync()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            updateError(error)
            throw error
        }
    }

    override suspend fun pair(qrPayload: String, trustDevice: Boolean) {
        initialize()
        val payload = LanPairingPayloadCodec.decode(qrPayload)
        payload.requireUsable(nowMillis())
        val localDeviceId = configuration!!.localDeviceId
        val client = LanSyncHttpClient(
            httpClient = pinnedAndroidHttpClient(payload.certificateFingerprint),
            endpoint = payload.endpoint,
            credential = null,
        )
        try {
            // Identity is verified by the pinned TLS handshake before the invitation secret leaves.
            val info = client.serverInfo()
            check(info.device.deviceId == payload.hostDeviceId) { "Pairing host identity changed" }
            check(info.certificateFingerprint == payload.certificateFingerprint) { "Pairing fingerprint changed" }
            val response = client.pair(
                invitationId = payload.invitationId,
                secret = payload.invitationSecret,
                client = DeviceInfo(localDeviceId, localDisplayName),
                rememberDevice = trustDevice,
            )
            if (trustDevice) {
                coordinator!!.savePairedCredentials(localDeviceId, info, response, payload.endpoint)
            } else {
                coordinator!!.connectTemporary(localDeviceId, info, response, payload.endpoint)
            }
            _state.value = _state.value.copy(
                enabled = configuration?.enabled == true,
                connectedDeviceName = info.device.displayName,
                status = LanSyncStatus.CONNECTED,
                error = null,
            )
            refreshTrustedDevice()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            updateError(error)
            throw error
        } finally {
            client.close()
        }
    }

    override suspend fun createPairingQrPayload(): String =
        error("Pairing codes are created by SimpMusic Desktop")

    override suspend fun cancelPairing() = Unit

    override suspend fun disconnect() {
        initialize()
        coordinator!!.disconnect()
        _state.value = _state.value.copy(status = LanSyncStatus.HOST_UNAVAILABLE)
    }

    override suspend fun forgetDevice(deviceId: String) {
        initialize()
        val saved = coordinator!!.savedCredentials()
        if (saved?.pairedDesktopId == deviceId) coordinator!!.disconnectAndForget()
        refreshTrustedDevice()
        _state.value = _state.value.copy(
            connectedDeviceName = null,
            status = if (configuration?.enabled == true) LanSyncStatus.NOT_PAIRED else LanSyncStatus.DISABLED,
        )
    }

    override suspend fun renameDevice(deviceId: String, displayName: String) {
        val saved = coordinator?.savedCredentials() ?: return
        if (saved.pairedDesktopId == deviceId) coordinator!!.updateSavedDevice(displayName = displayName)
        refreshTrustedDevice()
    }

    override suspend fun setDeviceAutoSync(deviceId: String, enabled: Boolean) {
        val saved = coordinator?.savedCredentials() ?: return
        if (saved.pairedDesktopId == deviceId) coordinator!!.updateSavedDevice(autoSync = enabled)
        refreshTrustedDevice()
    }

    private fun createCoordinator(deviceId: String): LanConnectionCoordinator {
        val adapter = CompositeMusicLibraryAdapter(
            favorites = RoomFavoritesMusicLibraryAdapter(
                repository = repository,
                stateStore = JsonFileFavoriteStateStore(File(root, "favorite-state.json").absolutePath.toPath()),
                deviceId = deviceId,
            ),
            playlists = RoomPlaylistMusicLibraryAdapter(
                playlistsRepository = playlistsRepository,
                songsRepository = repository,
                stateStore = JsonFilePlaylistStateStore(File(root, "playlist-state.json").absolutePath.toPath()),
                deviceId = deviceId,
            savedPlaylistsRepository = savedPlaylistsRepository,
            ),
        )
        return LanConnectionCoordinator(
            discovery = discovery,
            credentialStore = credentialStore,
            adapter = adapter,
            journal = journal,
            clientFactory = pinnedAndroidLanSyncClientFactory(),
            scope = scope,
            nowMillis = nowMillis,
        )
    }

    private fun observeCoordinator(value: LanConnectionCoordinator) {
        observerJob?.cancel()
        observerJob = scope.launch {
            value.state.collectLatest {
                refreshUiState(value)
                refreshTrustedDevice()
            }
        }
        scope.launch { value.engineState.collectLatest { refreshUiState(value) } }
        scope.launch { value.availableHosts.collectLatest { refreshUiState(value); refreshTrustedDevice() } }
    }

    private suspend fun refreshUiState(value: LanConnectionCoordinator) {
        val enabled = configuration?.enabled == true
        if (!enabled) {
            _state.value = _state.value.copy(enabled = false, status = LanSyncStatus.DISABLED)
            return
        }
        val saved = value.savedCredentials()
        val status = when {
            discovery.state.value == DiscoveryState.PERMISSION_DENIED -> LanSyncStatus.PERMISSION_REQUIRED
            value.engineState.value == SyncEngineState.SYNCHRONIZING -> LanSyncStatus.SYNCHRONIZING
            value.engineState.value == SyncEngineState.UP_TO_DATE -> LanSyncStatus.UP_TO_DATE
            value.state.value == LanCoordinatorState.NOT_PAIRED -> LanSyncStatus.NOT_PAIRED
            value.state.value == LanCoordinatorState.DISCOVERING -> LanSyncStatus.DISCOVERING
            value.state.value == LanCoordinatorState.CONNECTING -> LanSyncStatus.CONNECTING
            value.state.value == LanCoordinatorState.CONNECTED -> LanSyncStatus.CONNECTED
            value.state.value == LanCoordinatorState.ERROR -> LanSyncStatus.HOST_UNAVAILABLE
            else -> LanSyncStatus.HOST_UNAVAILABLE
        }
        _state.value = _state.value.copy(
            enabled = true,
            status = status,
            connectedDeviceName = saved?.displayName,
            availableDeviceCount = value.availableHosts.value.size,
            lastSuccessfulSyncMillis = value.lastSuccessfulSyncMillis.value ?: configuration?.lastSuccessfulSyncMillis,
            pendingChanges = value.pendingChanges.value,
            error = value.lastError.value,
        )
        if (status == LanSyncStatus.UP_TO_DATE) persistLastSync()
    }

    private suspend fun refreshTrustedDevice() {
        val saved = coordinator?.savedCredentials()
        _trustedDevices.value = if (saved == null) {
            emptyList()
        } else {
            val available = coordinator!!.availableHosts.value.any { it.deviceId == saved.pairedDesktopId }
            listOf(
                TrustedDeviceSummary(
                    deviceId = saved.pairedDesktopId,
                    displayName = saved.displayName,
                    connected = _state.value.status in setOf(
                        LanSyncStatus.CONNECTED,
                        LanSyncStatus.SYNCHRONIZING,
                        LanSyncStatus.UP_TO_DATE,
                    ),
                    available = available,
                    autoSync = saved.autoSync,
                    lastSuccessfulSyncMillis = _state.value.lastSuccessfulSyncMillis,
                ),
            )
        }
    }

    private suspend fun persistLastSync() {
        val timestamp = coordinator?.lastSuccessfulSyncMillis?.value ?: nowMillis()
        val current = configuration ?: return
        if (current.lastSuccessfulSyncMillis == timestamp) return
        configuration = current.copy(lastSuccessfulSyncMillis = timestamp)
        configurationStore.write(configuration!!)
    }

    private fun updateError(error: Throwable) {
        val message = error.message ?: error::class.simpleName
        val status = when {
            message.orEmpty().contains("fingerprint", true) ||
                message.orEmpty().contains("certificate", true) -> LanSyncStatus.AUTHENTICATION_FAILED
            message.orEmpty().contains("401") -> LanSyncStatus.AUTHENTICATION_FAILED
            message.orEmpty().contains("protocol", true) -> LanSyncStatus.PROTOCOL_MISMATCH
            else -> LanSyncStatus.ERROR
        }
        _state.value = _state.value.copy(status = status, error = message)
    }
}
