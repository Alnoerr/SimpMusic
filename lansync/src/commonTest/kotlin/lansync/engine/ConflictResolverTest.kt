package lansync.engine

import lansync.model.LibrarySnapshot
import lansync.model.LogicalVersion
import lansync.model.SyncFavorite
import lansync.model.SyncPlaylist
import lansync.model.SyncPlaylistEntry
import lansync.model.SyncSong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConflictResolverTest {
    @Test
    fun logicalCounterThenDeviceIdResolvesConflictsDeterministically() {
        val local = LibrarySnapshot(
            favorites = listOf(SyncFavorite("song", liked = true, LogicalVersion(4, "a"))),
        )
        val remote = LibrarySnapshot(
            favorites = listOf(SyncFavorite("song", liked = false, LogicalVersion(4, "b"))),
        )

        val mergedBothWays = listOf(
            ConflictResolver.merge(local, remote),
            ConflictResolver.merge(remote, local),
        )

        mergedBothWays.forEach { assertFalse(it.favorites.single().liked) }
    }

    @Test
    fun initialImportPreservesExistingFavoriteButNormalSyncHonorsTombstone() {
        val local = LibrarySnapshot(
            favorites = listOf(SyncFavorite("song", liked = true, LogicalVersion(1, "phone"))),
        )
        val remote = LibrarySnapshot(
            favorites = listOf(SyncFavorite("song", liked = false, LogicalVersion(2, "desktop"))),
        )

        assertTrue(ConflictResolver.merge(local, remote, initialImport = true).favorites.single().liked)
        assertFalse(ConflictResolver.merge(local, remote).favorites.single().liked)
    }

    @Test
    fun concurrentPlaylistEntriesMergeAndOrderDeterministically() {
        val playlist = SyncPlaylist("localplaylist:id", "List", version = LogicalVersion(1, "a"))
        val first = SyncPlaylistEntry("entry:a", playlist.id, "ytmusic:a", "0000000001", version = LogicalVersion(2, "a"))
        val second = SyncPlaylistEntry("entry:b", playlist.id, "ytmusic:b", "0000000001", version = LogicalVersion(2, "b"))
        val local = LibrarySnapshot(playlists = listOf(playlist), playlistEntries = listOf(first))
        val remote = LibrarySnapshot(playlists = listOf(playlist), playlistEntries = listOf(second))

        val left = ConflictResolver.merge(local, remote).playlistEntries
        val right = ConflictResolver.merge(remote, local).playlistEntries

        assertEquals(listOf("entry:a", "entry:b"), left.map(SyncPlaylistEntry::entryId))
        assertEquals(left, right)
    }

    @Test
    fun playlistTombstoneBeatsAnOlderEdit() {
        val active = SyncPlaylist("localplaylist:id", "Edited", version = LogicalVersion(5, "phone"))
        val deleted = active.copy(deleted = true, version = LogicalVersion(6, "desktop"))

        assertTrue(
            ConflictResolver.merge(
                LibrarySnapshot(playlists = listOf(active)),
                LibrarySnapshot(playlists = listOf(deleted)),
            ).playlists.single().deleted,
        )
    }

    @Test
    fun newerEmptyMetadataDoesNotEraseUsefulOlderMetadata() {
        val old = SyncSong(
            id = "ytmusic:song",
            sourceTrackId = "song",
            title = "Title",
            artists = listOf("Artist"),
            album = "Album",
            thumbnails = "thumb",
            version = LogicalVersion(1, "phone"),
        )
        val newer = old.copy(
            title = "",
            artists = emptyList(),
            album = null,
            thumbnails = null,
            version = LogicalVersion(2, "desktop"),
        )

        val merged = ConflictResolver.merge(
            LibrarySnapshot(songs = listOf(old)),
            LibrarySnapshot(songs = listOf(newer)),
        ).songs.single()

        assertEquals("Title", merged.title)
        assertEquals(listOf("Artist"), merged.artists)
        assertEquals("Album", merged.album)
        assertEquals("thumb", merged.thumbnails)
        assertEquals(LogicalVersion(2, "desktop"), merged.version)
    }
}
