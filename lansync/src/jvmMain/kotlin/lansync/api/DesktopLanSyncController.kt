package lansync.api

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
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
import lansync.discovery.JmDnsAdvertiser
import lansync.discovery.usableLanAddresses
import lansync.engine.SyncEngine
import lansync.engine.SyncEngineState
import lansync.model.DeviceInfo
import lansync.model.LanPairingPayload
import lansync.model.LanPairingPayloadCodec
import lansync.security.DesktopPairingManager
import lansync.security.HostIdentityStore
import lansync.security.JsonLanSyncConfigurationStore
import lansync.security.JsonTrustedPeerStore
import lansync.security.LanSyncConfiguration
import lansync.security.PairingInvitation
import lansync.security.desktopLanSyncDirectory
import lansync.server.FileHostApplyCheckpointStore
import lansync.server.HostLocalTransport
import lansync.server.LanSyncHost
import lansync.server.LanSyncServer
import lansync.server.LanSyncServerConfig
import lansync.storage.JsonFileFavoriteStateStore
import lansync.storage.JsonFileHostChangeLog
import lansync.storage.JsonFilePlaylistStateStore
import lansync.storage.JsonFileSyncJournal
import okio.Path.Companion.toPath

class DesktopLanSyncController(
    private val repository: FavoritesRepository,
    private val playlistsRepository: PlaylistsRepository,
    private val savedPlaylistsRepository: SavedPlaylistRepository,
    private val defaultDeviceId: String,
    private val localDisplayName: String,
    private val scope: CoroutineScope,
    private val directory: Path = desktopLanSyncDirectory(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : LanSyncController {
    private val mutex = Mutex()
    private val configurationStore =
        JsonLanSyncConfigurationStore(directory.resolve("configuration.json").toString().toPath())
    private val peerStore = JsonTrustedPeerStore(directory.resolve("trusted-devices.json"))
    private val _state = MutableStateFlow(LanSyncUiState())
    private val _trustedDevices = MutableStateFlow<List<TrustedDeviceSummary>>(emptyList())
    private var configuration: LanSyncConfiguration? = null
    private var server: LanSyncServer? = null
    private var hostEngine: SyncEngine? = null
    private var pairingManager: DesktopPairingManager? = null
    private var currentInvitation: PairingInvitation? = null
    private var engineStateJob: Job? = null
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
            buildHost(loaded.localDeviceId)
            refreshTrustedDevices()
            initialized = true
            if (loaded.enabled) startLocked() else _state.value = LanSyncUiState()
        }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        initialize()
        mutex.withLock {
            val current = configuration ?: error("LAN sync is not initialized")
            configuration = current.copy(enabled = enabled)
            configurationStore.write(configuration!!)
            if (enabled) startLocked() else stopLocked()
        }
    }

    override suspend fun syncNow() {
        initialize()
        check(configuration?.enabled == true) { "Enable LAN synchronization first" }
        try {
            hostEngine?.syncNow() ?: error("LAN synchronization host is unavailable")
            persistLastSync()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = _state.value.copy(status = LanSyncStatus.ERROR, error = error.message)
            throw error
        }
    }

    override suspend fun pair(qrPayload: String, trustDevice: Boolean) =
        error("SimpMusic Desktop creates pairing codes; Android scans them")

    override suspend fun createPairingQrPayload(): String {
        initialize()
        check(configuration?.enabled == true && server?.boundPort != 0) { "Enable the LAN server first" }
        currentInvitation?.let { pairingManager?.cancelInvitation(it.invitationId) }
        val invitation = pairingManager!!.createInvitation().also { currentInvitation = it }
        val address = usableLanAddresses().firstOrNull()?.hostAddress
            ?: error("No reachable LAN address is available")
        return LanPairingPayloadCodec.encode(
            LanPairingPayload(
                hostDeviceId = configuration!!.localDeviceId,
                hostDisplayName = localDisplayName,
                host = address,
                port = server!!.boundPort,
                certificateFingerprint = invitation.hostFingerprint,
                invitationId = invitation.invitationId,
                invitationSecret = invitation.secret,
                expiresAtEpochMillis = invitation.expiresAtEpochMillis,
            ),
        )
    }

    override suspend fun cancelPairing() {
        currentInvitation?.let { pairingManager?.cancelInvitation(it.invitationId) }
        currentInvitation = null
    }

    override suspend fun disconnect() {
        // The host has multiple possible clients. Revocation/forget is the explicit way to terminate
        // one device; the global switch terminates every active session.
    }

    override suspend fun forgetDevice(deviceId: String) {
        pairingManager?.revoke(deviceId)
        refreshTrustedDevices()
    }

    override suspend fun renameDevice(deviceId: String, displayName: String) {
        pairingManager?.rename(deviceId, displayName)
        refreshTrustedDevices()
    }

    override suspend fun setDeviceAutoSync(deviceId: String, enabled: Boolean) {
        pairingManager?.setAutoSync(deviceId, enabled)
        refreshTrustedDevices()
    }

    private fun buildHost(deviceId: String) {
        Files.createDirectories(directory)
        val identity = HostIdentityStore(directory.resolve("host-identity.p12"))
            .loadOrCreate(loadOrCreateIdentityPassword())
        val pairing = DesktopPairingManager(identity.publicKeyFingerprint, peerStore, nowMillis)
        pairingManager = pairing
        val adapter = CompositeMusicLibraryAdapter(
            favorites = RoomFavoritesMusicLibraryAdapter(
                repository = repository,
                stateStore = JsonFileFavoriteStateStore(directory.resolve("favorite-state.json").toString().toPath()),
                deviceId = deviceId,
            ),
            playlists = RoomPlaylistMusicLibraryAdapter(
                playlistsRepository = playlistsRepository,
                songsRepository = repository,
                stateStore = JsonFilePlaylistStateStore(directory.resolve("playlist-state.json").toString().toPath()),
                deviceId = deviceId,
            savedPlaylistsRepository = savedPlaylistsRepository,
            ),
        )
        val host = LanSyncHost(
            adapter = adapter,
            changeLog = JsonFileHostChangeLog(directory.resolve("host-change-log.json").toString().toPath()),
            applyCheckpoint = FileHostApplyCheckpointStore(directory.resolve("host-apply-checkpoint")),
        )
        val advertiser = JmDnsAdvertiser(scope)
        server = LanSyncServer(
            config = LanSyncServerConfig(
                bindAddress = "0.0.0.0",
                port = 0,
                device = DeviceInfo(deviceId, localDisplayName),
                keyStorePath = directory.resolve("host-identity.p12"),
            ),
            identity = identity,
            host = host,
            pairingManager = pairing,
            advertiser = advertiser,
        )
        val journal = JsonFileSyncJournal(directory.resolve("journal.json").toString().toPath())
        hostEngine = SyncEngine(
            adapter = adapter,
            transport = HostLocalTransport(deviceId, host),
            journal = journal,
            deviceId = deviceId,
            scope = scope,
        )
        engineStateJob?.cancel()
        engineStateJob = scope.launch {
            hostEngine!!.state.collectLatest { engineState ->
                val status = when (engineState) {
                    SyncEngineState.SYNCHRONIZING -> LanSyncStatus.SYNCHRONIZING
                    SyncEngineState.UP_TO_DATE -> LanSyncStatus.UP_TO_DATE
                    SyncEngineState.OFFLINE -> LanSyncStatus.HOST_UNAVAILABLE
                    SyncEngineState.ERROR -> LanSyncStatus.ERROR
                    else -> if (configuration?.enabled == true) LanSyncStatus.CONNECTED else LanSyncStatus.DISABLED
                }
                _state.value = _state.value.copy(
                    status = status,
                    error = hostEngine?.lastError?.value,
                )
                if (engineState == SyncEngineState.UP_TO_DATE) persistLastSync()
            }
        }
    }

    private suspend fun startLocked() {
        if (server?.boundPort == 0) server!!.start()
        if (hostEngine?.isRunning != true) hostEngine!!.start()
        _state.value = _state.value.copy(
            enabled = true,
            status = if (hostEngine?.state?.value == SyncEngineState.UP_TO_DATE) {
                LanSyncStatus.UP_TO_DATE
            } else {
                LanSyncStatus.CONNECTED
            },
            serverAddress = usableLanAddresses().firstOrNull()?.hostAddress,
            serverPort = server!!.boundPort,
            lastSuccessfulSyncMillis = configuration?.lastSuccessfulSyncMillis,
            error = null,
        )
        refreshTrustedDevices()
    }

    private suspend fun stopLocked() {
        cancelPairing()
        hostEngine?.stop()
        server?.stop()
        _state.value = _state.value.copy(
            enabled = false,
            status = LanSyncStatus.DISABLED,
            serverAddress = null,
            serverPort = null,
            error = null,
        )
    }

    private suspend fun refreshTrustedDevices() {
        _trustedDevices.value = pairingManager?.trustedDevices().orEmpty().map {
            TrustedDeviceSummary(
                deviceId = it.deviceId,
                displayName = it.displayName,
                autoSync = it.autoSync,
                lastSuccessfulSyncMillis = it.lastSuccessfulSyncMillis,
            )
        }
    }

    private suspend fun persistLastSync() {
        val current = configuration ?: return
        val timestamp = nowMillis()
        configuration = current.copy(lastSuccessfulSyncMillis = timestamp)
        configurationStore.write(configuration!!)
        _state.value = _state.value.copy(lastSuccessfulSyncMillis = timestamp)
    }

    private fun loadOrCreateIdentityPassword(): CharArray {
        val path = directory.resolve("host-identity.password")
        if (Files.exists(path)) return Files.readString(path).trim().toCharArray()
        val password = ByteArray(32).also(SecureRandom()::nextBytes)
            .let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
        Files.writeString(path, password)
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ),
            )
        }
        return password.toCharArray()
    }
}
