package lansync.integration

import DatabaseDao
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.maxrave.data.db.Converters
import com.maxrave.data.db.MusicDatabase
import com.maxrave.domain.data.entities.SongEntity
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.websocket.Frame
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import lansync.adapter.FavoritesRepository
import lansync.adapter.RoomFavoritesMusicLibraryAdapter
import lansync.engine.SyncEngine
import lansync.model.DeviceInfo
import lansync.security.DesktopPairingManager
import lansync.security.HostIdentityStore
import lansync.security.JsonTrustedPeerStore
import lansync.server.FileHostApplyCheckpointStore
import lansync.server.HostLocalTransport
import lansync.server.LanSyncHost
import lansync.server.LanSyncServer
import lansync.server.LanSyncServerConfig
import lansync.storage.InMemoryFavoriteStateStore
import lansync.storage.InMemorySyncJournal
import lansync.storage.JsonFileHostChangeLog
import lansync.storage.JsonFileSyncJournal
import lansync.transport.LanEndpoint
import lansync.transport.LanSyncHttpClient
import lansync.transport.pinnedJvmHttpClient
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LanSyncHttpsIntegrationTest {
    @Test
    fun httpsPairingAuthenticationPinningAndServerShutdownAreEnforced() = runBlocking {
        val fixture = Fixture()
        try {
            val port = fixture.start()
            val endpoint = LanEndpoint("127.0.0.1", port)
            val pinned = LanSyncHttpClient(
                pinnedJvmHttpClient(fixture.identity.publicKeyFingerprint),
                endpoint,
                credential = null,
            )
            assertEquals(fixture.identity.publicKeyFingerprint, pinned.serverInfo().certificateFingerprint)
            val unauthorized = LanSyncHttpClient(
                pinnedJvmHttpClient(fixture.identity.publicKeyFingerprint),
                endpoint,
                credential = "unknown.invalid-credential",
            )
            assertFailsWith<ClientRequestException> { unauthorized.getSnapshot() }
            unauthorized.close()

            val invitation = fixture.pairing.createInvitation()
            val paired = pinned.pair(invitation.invitationId, invitation.secret, DeviceInfo("phone", "Phone"))
            pinned.close()

            val authenticated = LanSyncHttpClient(
                pinnedJvmHttpClient(fixture.identity.publicKeyFingerprint),
                endpoint,
                paired.accessToken,
            )
            authenticated.getSnapshot()
            assertTrue(fixture.pairing.revoke("phone"))
            assertFailsWith<ClientRequestException> { authenticated.getSnapshot() }
            authenticated.close()

            val wrongPin = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
            val mismatched = LanSyncHttpClient(pinnedJvmHttpClient(wrongPin), endpoint, null)
            assertFailsWith<Throwable> { mismatched.serverInfo() }
            mismatched.close()

            fixture.server.stop()
            ServerSocket(port).use { assertEquals(port, it.localPort) }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun revocationClosesAnAlreadyAuthenticatedWebSocket() = runBlocking {
        val fixture = Fixture()
        try {
            val port = fixture.start()
            val endpoint = LanEndpoint("127.0.0.1", port)
            val invitation = fixture.pairing.createInvitation()
            val bootstrap = LanSyncHttpClient(
                pinnedJvmHttpClient(fixture.identity.publicKeyFingerprint),
                endpoint,
                null,
            )
            val paired = bootstrap.pair(invitation.invitationId, invitation.secret, DeviceInfo("phone", "Phone"))
            bootstrap.close()

            val rawClient = pinnedJvmHttpClient(fixture.identity.publicKeyFingerprint)
            val opened = CompletableDeferred<Unit>()
            val websocket = async {
                runCatching {
                    rawClient.webSocket(
                        urlString = endpoint.webSocketUrl,
                        request = { bearerAuth(paired.accessToken) },
                    ) {
                        opened.complete(Unit)
                        for (frame in incoming) {
                            if (frame is Frame.Close) break
                        }
                    }
                }
            }
            withTimeout(5_000) { opened.await() }
            assertTrue(fixture.pairing.revoke("phone"))
            withTimeout(5_000) { websocket.await() }
            assertEquals(null, fixture.pairing.authenticate(paired.accessToken))
            rawClient.close()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun twoIndependentRoomDatabasesSynchronizeBothDirectionsAndTombstones() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.hostRepository.insertSong(song("host-song", liked = true))
            fixture.clientRepository.insertSong(song("client-song", liked = true))
            val port = fixture.start()

            fixture.hostEngine.start()
            val invitation = fixture.pairing.createInvitation()
            val bootstrap = LanSyncHttpClient(
                pinnedJvmHttpClient(fixture.identity.publicKeyFingerprint),
                LanEndpoint("127.0.0.1", port),
                null,
            )
            val paired = bootstrap.pair(invitation.invitationId, invitation.secret, DeviceInfo("phone", "Phone"))
            bootstrap.close()

            val transport = LanSyncHttpClient(
                pinnedJvmHttpClient(fixture.identity.publicKeyFingerprint),
                LanEndpoint("127.0.0.1", port),
                paired.accessToken,
            )
            val clientJournal = JsonFileSyncJournal(
                fixture.root.resolve("client-journal.json").toString().toPath(),
            )
            val clientEngine = SyncEngine(
                fixture.clientAdapter,
                transport,
                clientJournal,
                "phone",
                fixture.scope,
            )
            clientEngine.start()

            awaitCondition {
                fixture.hostRepository.getSong("client-song")?.liked == true &&
                    fixture.clientRepository.getSong("host-song")?.liked == true
            }

            fixture.hostRepository.applyLocalLikeStatus("host-song", false)
            awaitCondition { fixture.clientRepository.getSong("host-song")?.liked == false }

            fixture.clientRepository.insertSong(song("client-later", liked = true))
            val propagation = runCatching {
                awaitCondition { fixture.hostRepository.getSong("client-later")?.liked == true }
            }
            if (propagation.isFailure) {
                error(
                    "Client local observation failed: state=${clientEngine.state.value}, running=${clientEngine.isRunning}, " +
                        "error=${clientEngine.lastError.value}, hostSequence=${fixture.hostLog.latestSequence()}, " +
                        "clientSongs=${fixture.clientAdapter.readSnapshot().songs.map { it.sourceTrackId }}, " +
                        "hostSongs=${fixture.hostAdapter.readSnapshot().songs.map { it.sourceTrackId }}",
                )
            }

            assertEquals(3, fixture.hostRepository.readSongs().size)
            assertEquals(3, fixture.clientRepository.readSongs().size)
            assertTrue(fixture.hostLog.latestSequence() >= 6)

            clientEngine.stop()
            fixture.server.stop()
            fixture.clientRepository.insertSong(song("client-offline", liked = true))
            fixture.hostRepository.insertSong(song("host-offline", liked = true))

            val restartedPort = fixture.server.start()
            val restartedEngine = SyncEngine(
                fixture.clientAdapter,
                LanSyncHttpClient(
                    pinnedJvmHttpClient(fixture.identity.publicKeyFingerprint),
                    LanEndpoint("127.0.0.1", restartedPort),
                    paired.accessToken,
                ),
                JsonFileSyncJournal(fixture.root.resolve("client-journal.json").toString().toPath()),
                "phone",
                fixture.scope,
            )
            restartedEngine.start()
            awaitCondition {
                fixture.hostRepository.getSong("client-offline")?.liked == true &&
                    fixture.clientRepository.getSong("host-offline")?.liked == true
            }
            assertEquals(5, fixture.hostRepository.readSongs().size)
            assertEquals(5, fixture.clientRepository.readSongs().size)
            restartedEngine.stop()
        } finally {
            fixture.close()
        }
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(15_000) {
            while (!condition()) delay(50)
        }
    }

    private class Fixture {
        val root: Path = Files.createTempDirectory("simpmusic-lansync-e2e")
        val identity = HostIdentityStore(root.resolve("host.p12")).loadOrCreate("test-password".toCharArray())
        val pairing = DesktopPairingManager(
            identity.publicKeyFingerprint,
            JsonTrustedPeerStore(root.resolve("peers.json")),
        )
        private val hostDb = database(root.resolve("host.db"))
        private val clientDb = database(root.resolve("client.db"))
        val hostRepository = DaoFavoritesRepository(hostDb.getDatabaseDao())
        val clientRepository = DaoFavoritesRepository(clientDb.getDatabaseDao())
        val hostAdapter = RoomFavoritesMusicLibraryAdapter(hostRepository, InMemoryFavoriteStateStore(), "desktop")
        val clientAdapter = RoomFavoritesMusicLibraryAdapter(clientRepository, InMemoryFavoriteStateStore(), "phone")
        val hostLog = JsonFileHostChangeLog(root.resolve("host-log.json").toString().toPath())
        val host = LanSyncHost(
            hostAdapter,
            hostLog,
            FileHostApplyCheckpointStore(root.resolve("host-applied.txt")),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hostEngine = SyncEngine(
            hostAdapter,
            HostLocalTransport("desktop", host),
            InMemorySyncJournal(),
            "desktop",
            scope,
        )
        val server = LanSyncServer(
            LanSyncServerConfig(
                bindAddress = "127.0.0.1",
                port = 0,
                device = DeviceInfo("desktop", "Desktop"),
                keyStorePath = root.resolve("host.p12"),
            ),
            identity,
            host,
            pairing,
        )

        suspend fun start(): Int = server.start()

        suspend fun close() {
            runCatching { hostEngine.stop() }
            runCatching { server.stop() }
            scope.cancel()
            hostDb.close()
            clientDb.close()
        }

        private fun database(path: Path): MusicDatabase =
            Room.databaseBuilder<MusicDatabase>(path.toString())
                .addTypeConverter(Converters())
                .setDriver(BundledSQLiteDriver())
                .build()
    }

    private class DaoFavoritesRepository(
        private val dao: DatabaseDao,
    ) : FavoritesRepository {
        override fun observeSongs(): Flow<List<SongEntity>> = dao.observeSongsForSync()
        override suspend fun readSongs(): List<SongEntity> = dao.observeSongsForSync().first()
        override suspend fun getSong(videoId: String): SongEntity? = dao.getSong(videoId)
        override suspend fun insertSong(song: SongEntity) {
            dao.insertSong(song)
        }
        override suspend fun applyLocalLikeStatus(videoId: String, liked: Boolean) {
            dao.updateLiked(if (liked) 1 else 0, videoId)
        }
    }

    private fun song(id: String, liked: Boolean) = SongEntity(
        videoId = id,
        title = "Title $id",
        artistName = listOf("Artist"),
        albumName = "Album",
        duration = "1:00",
        durationSeconds = 60,
        isAvailable = true,
        isExplicit = false,
        likeStatus = "INDIFFERENT",
        videoType = "MUSIC_VIDEO_TYPE_ATV",
        category = null,
        resultType = null,
        liked = liked,
    )
}

