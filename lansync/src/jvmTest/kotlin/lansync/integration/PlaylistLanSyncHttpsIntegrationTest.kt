package lansync.integration

import DatabaseDao
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.maxrave.data.db.Converters
import com.maxrave.data.db.MusicDatabase
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.PairSongLocalPlaylist
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import lansync.adapter.CompositeMusicLibraryAdapter
import lansync.adapter.FavoritesRepository
import lansync.adapter.LocalPlaylistLibrary
import lansync.adapter.PlaylistsRepository
import lansync.adapter.RoomFavoritesMusicLibraryAdapter
import lansync.adapter.RoomPlaylistMusicLibraryAdapter
import lansync.adapter.SavedPlaylistRepository
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
import lansync.storage.InMemoryPlaylistStateStore
import lansync.storage.InMemorySyncJournal
import lansync.storage.JsonFileHostChangeLog
import lansync.storage.JsonFileSyncJournal
import lansync.transport.LanEndpoint
import lansync.transport.LanSyncHttpClient
import lansync.transport.pinnedJvmHttpClient
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PlaylistLanSyncHttpsIntegrationTest {
    @Test
    fun realRoomPlaylistsConvergeThroughAuthenticatedHttps() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.hostSongs.insertSong(song("host"))
            fixture.clientSongs.insertSong(song("a"))
            fixture.clientSongs.insertSong(song("b"))
            fixture.clientSongs.insertSong(song("c"))

            fixture.hostPlaylists.create("Host list", "host-cover", listOf("host"))
            fixture.clientPlaylists.create("Phone list", "phone-cover", listOf("a", "b", "a"))
            fixture.hostSavedPlaylists.insert(playlist("PL-SAVED", "Saved reference", liked = true))

            val port = fixture.start()
            fixture.hostEngine.start()
            var clientEngine = fixture.newClientEngine(port)
            clientEngine.start()

            awaitCondition {
                fixture.hostPlaylists.titles() == setOf("Host list", "Phone list") &&
                    fixture.clientPlaylists.titles() == setOf("Host list", "Phone list")
            }
            assertEquals(listOf("a", "b", "a"), fixture.hostPlaylists.tracks("Phone list"))
            assertEquals(listOf("a", "b", "a"), fixture.clientPlaylists.tracks("Phone list"))
            awaitCondition { fixture.clientSavedPlaylists.isLiked("PL-SAVED") }
            fixture.hostSavedPlaylists.setLiked("PL-SAVED", false)
            awaitCondition {
                fixture.clientSavedPlaylists.exists("PL-SAVED") &&
                    !fixture.clientSavedPlaylists.isLiked("PL-SAVED")
            }
            fixture.clientSavedPlaylists.setLiked("PL-SAVED", true)
            awaitCondition { fixture.hostSavedPlaylists.isLiked("PL-SAVED") }
            val phoneHostId = fixture.hostPlaylists.id("Phone list")
            val phoneClientId = fixture.clientPlaylists.id("Phone list")
            assertNotEquals(phoneHostId, phoneClientId)

            fixture.hostPlaylists.rename(phoneHostId, "Renamed", "renamed-cover")
            awaitCondition {
                fixture.clientPlaylists.idOrNull("Renamed") != null &&
                    fixture.clientPlaylists.thumbnail("Renamed") == "renamed-cover" &&
                    fixture.hostEngine.state.value == lansync.engine.SyncEngineState.UP_TO_DATE &&
                    clientEngine.state.value == lansync.engine.SyncEngineState.UP_TO_DATE
            }
            delay(500)

            check(fixture.hostPlaylists.move(phoneHostId, fromIndex = 1, toIndex = 2))
            val reordered = kotlinx.coroutines.withTimeoutOrNull(20_000) {
                while (fixture.clientPlaylists.tracks("Renamed") != listOf("a", "a", "b")) delay(50)
                true
            }
            check(reordered == true) {
                "Reorder did not converge: host=${fixture.hostPlaylists.tracks("Renamed")}, " +
                    "client=${fixture.clientPlaylists.tracks("Renamed")}, " +
                    "hostState=${fixture.hostEngine.state.value}/${fixture.hostEngine.lastError.value}, " +
                    "clientState=${clientEngine.state.value}/${clientEngine.lastError.value}"
            }
            awaitCondition {
                fixture.hostEngine.state.value == lansync.engine.SyncEngineState.UP_TO_DATE &&
                    clientEngine.state.value == lansync.engine.SyncEngineState.UP_TO_DATE
            }
            delay(500)

            fixture.hostPlaylists.remove(phoneHostId, "b")
            assertEquals(listOf("a", "a"), fixture.hostPlaylists.tracks("Renamed"))
            val removed = kotlinx.coroutines.withTimeoutOrNull(20_000) {
                while (fixture.clientPlaylists.tracks("Renamed") != listOf("a", "a")) delay(50)
                true
            }
            check(removed == true) {
                "Removal did not converge: host=${fixture.hostPlaylists.tracks("Renamed")}, " +
                    "client=${fixture.clientPlaylists.tracks("Renamed")}, " +
                    "hostState=${fixture.hostEngine.state.value}/${fixture.hostEngine.lastError.value}, " +
                    "clientState=${clientEngine.state.value}/${clientEngine.lastError.value}"
            }

            clientEngine.stop()
            fixture.server.stop()
            fixture.hostSongs.insertSong(song("host-offline"))
            fixture.clientSongs.insertSong(song("client-offline"))
            fixture.hostPlaylists.append(phoneHostId, "host-offline")
            fixture.clientPlaylists.append(fixture.clientPlaylists.id("Renamed"), "client-offline")

            val restartedPort = fixture.server.start()
            clientEngine = fixture.newClientEngine(restartedPort)
            clientEngine.start()
            awaitCondition {
                val expected = setOf("a", "host-offline", "client-offline")
                fixture.hostPlaylists.tracks("Renamed").toSet() == expected &&
                    fixture.clientPlaylists.tracks("Renamed").toSet() == expected
            }

            fixture.hostPlaylists.delete(phoneHostId)
            awaitCondition { fixture.clientPlaylists.idOrNull("Renamed") == null }
            clientEngine.syncNow()
            assertEquals(null, fixture.clientPlaylists.idOrNull("Renamed"))
            assertEquals(1, fixture.hostPlaylists.count("Host list"))
            assertEquals(1, fixture.clientPlaylists.count("Host list"))
            clientEngine.stop()
        } finally {
            fixture.close()
        }
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(20_000) {
            while (!condition()) delay(50)
        }
    }

    private class Fixture {
        val root: Path = Files.createTempDirectory("simpmusic-lansync-playlists")
        val identity = HostIdentityStore(root.resolve("host.p12")).loadOrCreate("test-password".toCharArray())
        val pairing = DesktopPairingManager(
            identity.publicKeyFingerprint,
            JsonTrustedPeerStore(root.resolve("peers.json")),
        )
        private val hostDb = database(root.resolve("host.db"))
        private val clientDb = database(root.resolve("client.db"))
        val hostSongs = DaoFavoritesRepository(hostDb.getDatabaseDao())
        val clientSongs = DaoFavoritesRepository(clientDb.getDatabaseDao())
        val hostPlaylists = DaoPlaylistsRepository(hostDb.getDatabaseDao())
        val clientPlaylists = DaoPlaylistsRepository(clientDb.getDatabaseDao())
        val hostSavedPlaylists = DaoSavedPlaylistRepository(hostDb.getDatabaseDao())
        val clientSavedPlaylists = DaoSavedPlaylistRepository(clientDb.getDatabaseDao())
        val hostAdapter = composite(hostSongs, hostPlaylists, hostSavedPlaylists, "desktop")
        val clientAdapter = composite(clientSongs, clientPlaylists, clientSavedPlaylists, "phone")
        private val hostLog = JsonFileHostChangeLog(root.resolve("host-log.json").toString().toPath())
        private val host = LanSyncHost(
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
        private var accessToken: String? = null

        suspend fun start() = server.start()

        suspend fun newClientEngine(port: Int): SyncEngine {
            if (accessToken == null) {
                val invitation = pairing.createInvitation()
                val bootstrap = LanSyncHttpClient(
                    pinnedJvmHttpClient(identity.publicKeyFingerprint),
                    LanEndpoint("127.0.0.1", port),
                    null,
                )
                accessToken = bootstrap.pair(
                    invitation.invitationId,
                    invitation.secret,
                    DeviceInfo("phone", "Phone"),
                ).accessToken
                bootstrap.close()
            }
            return SyncEngine(
                clientAdapter,
                LanSyncHttpClient(
                    pinnedJvmHttpClient(identity.publicKeyFingerprint),
                    LanEndpoint("127.0.0.1", port),
                    accessToken,
                ),
                JsonFileSyncJournal(root.resolve("client-journal.json").toString().toPath()),
                "phone",
                scope,
            )
        }

        suspend fun close() {
            runCatching { hostEngine.stop() }
            runCatching { server.stop() }
            scope.cancel()
            hostDb.close()
            clientDb.close()
        }

        private fun composite(
            songs: FavoritesRepository,
            playlists: PlaylistsRepository,
            saved: SavedPlaylistRepository,
            deviceId: String,
        ) = CompositeMusicLibraryAdapter(
            RoomFavoritesMusicLibraryAdapter(songs, InMemoryFavoriteStateStore(), deviceId),
            RoomPlaylistMusicLibraryAdapter(
                playlists,
                songs,
                InMemoryPlaylistStateStore(),
                deviceId,
                saved,
            ),
        )

        private fun database(path: Path): MusicDatabase =
            Room.databaseBuilder<MusicDatabase>(path.toString())
                .addTypeConverter(Converters())
                .setDriver(BundledSQLiteDriver())
                .build()
    }

    private class DaoFavoritesRepository(
        private val dao: DatabaseDao,
    ) : FavoritesRepository {
        override fun observeSongs() = dao.observeSongsForSync()
        override suspend fun readSongs() = dao.observeSongsForSync().first()
        override suspend fun getSong(videoId: String) = dao.getSong(videoId)
        override suspend fun insertSong(song: SongEntity) {
            dao.insertSong(song)
        }
        override suspend fun applyLocalLikeStatus(videoId: String, liked: Boolean) {
            dao.updateLiked(if (liked) 1 else 0, videoId)
        }
    }

    private class DaoSavedPlaylistRepository(
        private val dao: DatabaseDao,
    ) : SavedPlaylistRepository {
        override fun observePlaylists() = dao.observePlaylistsForSync()

        override suspend fun insertOrReplace(playlist: PlaylistEntity) {
            dao.insertAndReplacePlaylist(playlist)
        }

        suspend fun insert(playlist: PlaylistEntity) = dao.insertAndReplacePlaylist(playlist)
        suspend fun setLiked(id: String, liked: Boolean) = dao.updatePlaylistLiked(if (liked) 1 else 0, id)
        suspend fun exists(id: String) = dao.getPlaylist(id) != null
        suspend fun isLiked(id: String) = dao.getPlaylist(id)?.liked == true
    }

    private class DaoPlaylistsRepository(
        private val dao: DatabaseDao,
    ) : PlaylistsRepository {
        override fun observeLibrary() =
            combine(
                dao.observeLocalPlaylistsForSync(),
                dao.observeLocalPlaylistEntriesForSync(),
                ::LocalPlaylistLibrary,
            )

        override suspend fun readLibrary() = observeLibrary().first()
        override suspend fun insertPlaylist(playlist: LocalPlaylistEntity) = dao.insertLocalPlaylist(playlist)
        override suspend fun updatePlaylist(id: Long, title: String, thumbnail: String?) =
            dao.updateLocalPlaylistMetadataForSync(id, title, thumbnail)
        override suspend fun replaceEntries(id: Long, videoIds: List<String>) =
            dao.replaceLocalPlaylistEntriesForSync(id, videoIds)
        override suspend fun deletePlaylist(id: Long) = dao.deleteLocalPlaylist(id)

        suspend fun create(title: String, thumbnail: String?, tracks: List<String>) =
            dao.insertLocalPlaylistWithTracks(
                LocalPlaylistEntity(title = title, thumbnail = thumbnail, downloadedAt = null, tracks = tracks),
                tracks,
            )

        suspend fun titles() = readLibrary().playlists.map { it.title }.toSet()
        suspend fun id(title: String) = readLibrary().playlists.single { it.title == title }.id
        suspend fun idOrNull(title: String) = readLibrary().playlists.singleOrNull { it.title == title }?.id
        suspend fun count(title: String) = readLibrary().playlists.count { it.title == title }
        suspend fun thumbnail(title: String) = readLibrary().playlists.single { it.title == title }.thumbnail
        suspend fun tracks(title: String): List<String> {
            val id = id(title)
            return readLibrary().entries
                .filter { it.playlistId == id }
                .sortedWith(compareBy({ it.position }, { it.id }))
                .map { it.songId }
        }
        suspend fun rename(id: Long, title: String, thumbnail: String?) =
            dao.updateLocalPlaylistMetadataForSync(id, title, thumbnail)
        suspend fun move(id: Long, fromIndex: Int, toIndex: Int) =
            dao.moveSongInPlaylist(id, fromIndex, toIndex)
        suspend fun remove(id: Long, songId: String) = dao.deletePairSongLocalPlaylist(id, songId)
        suspend fun append(id: Long, songId: String) {
            val current = tracks(readLibrary().playlists.single { it.id == id }.title)
            dao.insertPairSongLocalPlaylist(PairSongLocalPlaylist(playlistId = id, songId = songId, position = current.size))
            dao.updateLocalPlaylistTracks(current + songId, id)
        }
        suspend fun delete(id: Long) = dao.deleteLocalPlaylist(id)
    }

    private fun playlist(id: String, title: String, liked: Boolean) = PlaylistEntity(
        id = id,
        title = title,
        description = "Saved reference",
        thumbnails = "",
        liked = liked,
    )

    private fun song(id: String) = SongEntity(
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
        liked = false,
    )
}
