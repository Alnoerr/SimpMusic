package lansync.adapter

import com.maxrave.domain.data.entities.SongEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import lansync.model.LibrarySnapshot
import lansync.model.LogicalVersion
import lansync.model.SyncFavorite
import lansync.model.SyncSong
import lansync.storage.InMemoryFavoriteStateStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomFavoritesMusicLibraryAdapterTest {
    @Test
    fun unlikeTombstoneAndLogicalClockSurviveRestart() = runTest {
        val repository = FakeRepository(listOf(song("one", liked = true)))
        val store = InMemoryFavoriteStateStore()
        val first = RoomFavoritesMusicLibraryAdapter(repository, store, "phone")

        val likedVersion = first.readSnapshot().favorites.single().version
        repository.setLiked("one", false)
        val unlikeVersion = first.readSnapshot().favorites.single().version
        val restarted = RoomFavoritesMusicLibraryAdapter(repository, store, "phone")
        repository.setLiked("one", true)
        val relikeVersion = restarted.readSnapshot().favorites.single().version

        assertTrue(unlikeVersion > likedVersion)
        assertTrue(relikeVersion > unlikeVersion)
    }

    @Test
    fun remoteFavoriteUsesSyncOnlyRepositoryMethodsAndIsIdempotent() = runTest {
        val repository = FakeRepository()
        val adapter = RoomFavoritesMusicLibraryAdapter(repository, InMemoryFavoriteStateStore(), "desktop")
        val version = LogicalVersion(9, "phone")
        val remote = LibrarySnapshot(
            songs = listOf(SyncSong("ytmusic:one", sourceTrackId = "one", title = "One", version = version)),
            favorites = listOf(SyncFavorite("ytmusic:one", liked = true, version = version)),
        )

        adapter.applyRemoteChanges(remote)
        adapter.applyRemoteChanges(remote)

        assertEquals(1, repository.rows.value.size)
        assertTrue(repository.rows.value.single().liked)
        assertEquals(1, repository.likeWrites)
    }

    @Test
    fun remoteTombstoneDoesNotResurrectAfterRestart() = runTest {
        val repository = FakeRepository(listOf(song("one", liked = true)))
        val store = InMemoryFavoriteStateStore()
        val adapter = RoomFavoritesMusicLibraryAdapter(repository, store, "desktop")
        adapter.readSnapshot()
        adapter.applyRemoteChanges(
            LibrarySnapshot(
                favorites = listOf(SyncFavorite("ytmusic:one", false, LogicalVersion(10, "phone"))),
            ),
        )

        val restarted = RoomFavoritesMusicLibraryAdapter(repository, store, "desktop")
        assertFalse(restarted.readSnapshot().favorites.single().liked)
    }

    private fun song(id: String, liked: Boolean) = SongEntity(
        videoId = id,
        title = id,
        artistName = listOf("Artist"),
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

    private class FakeRepository(initial: List<SongEntity> = emptyList()) : FavoritesRepository {
        val rows = MutableStateFlow(initial)
        var likeWrites = 0

        override fun observeSongs(): Flow<List<SongEntity>> = rows
        override suspend fun readSongs() = rows.value
        override suspend fun getSong(videoId: String) = rows.value.firstOrNull { it.videoId == videoId }

        override suspend fun insertSong(song: SongEntity) {
            if (rows.value.none { it.videoId == song.videoId }) rows.value += song
        }

        override suspend fun applyLocalLikeStatus(videoId: String, liked: Boolean) {
            val current = rows.value.firstOrNull { it.videoId == videoId } ?: return
            if (current.liked == liked) return
            likeWrites++
            rows.value = rows.value.map { if (it.videoId == videoId) it.copy(liked = liked) else it }
        }

        fun setLiked(videoId: String, liked: Boolean) {
            rows.value = rows.value.map { if (it.videoId == videoId) it.copy(liked = liked) else it }
        }
    }
}
