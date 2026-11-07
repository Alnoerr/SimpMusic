package lansync.engine

import lansync.model.LibrarySnapshot
import lansync.model.SyncFavorite
import lansync.model.SyncPlaylist
import lansync.model.SyncPlaylistEntry
import lansync.model.SyncSavedPlaylistReference
import lansync.model.SyncSong
import lansync.transport.SyncMessageCodec

class ChangeTracker(
    private val deviceId: String,
    private val codec: SyncMessageCodec = SyncMessageCodec(),
) {
    fun diff(before: LibrarySnapshot, after: LibrarySnapshot): List<lansync.model.SyncChange> {
        val changes = mutableListOf<lansync.model.SyncChange>()
        diffRecords(before.songs, after.songs, SyncSong::id).forEach { song ->
            changes += codec.encodeSong(operationId("song", song.id, song.version.counter, song.version.deviceId), deviceId, song)
        }
        diffRecords(before.favorites, after.favorites, SyncFavorite::songId).forEach { favorite ->
            changes += codec.encodeFavorite(
                operationId("favorite", favorite.songId, favorite.version.counter, favorite.version.deviceId),
                deviceId,
                favorite,
            )
        }
        diffRecords(before.playlists, after.playlists, SyncPlaylist::id).forEach { playlist ->
            changes += codec.encodePlaylist(
                operationId("playlist", playlist.id, playlist.version.counter, playlist.version.deviceId),
                deviceId,
                playlist,
            )
        }
        diffRecords(before.playlistEntries, after.playlistEntries, SyncPlaylistEntry::entryId).forEach { entry ->
            changes += codec.encodePlaylistEntry(
                operationId("playlist_entry", entry.entryId, entry.version.counter, entry.version.deviceId),
                deviceId,
                entry,
            )
        }
        diffRecords(
            before.savedPlaylistReferences,
            after.savedPlaylistReferences,
            SyncSavedPlaylistReference::id,
        ).forEach { reference ->
            changes += codec.encodeSavedPlaylistReference(
                operationId("saved_playlist_reference", reference.id, reference.version.counter, reference.version.deviceId),
                deviceId,
                reference,
            )
        }
        return changes
    }

    private fun <T> diffRecords(before: List<T>, after: List<T>, id: (T) -> String): List<T> {
        val old = before.associateBy(id)
        return after.filter { old[id(it)] != it }
    }

    private fun operationId(type: String, entityId: String, counter: Long, versionDeviceId: String): String =
        "$deviceId:$type:$entityId:$counter:$versionDeviceId"
}
