package lansync.engine

import lansync.model.LibrarySnapshot
import lansync.model.SyncFavorite
import lansync.model.SyncPlaylist
import lansync.model.SyncPlaylistEntry
import lansync.model.SyncSavedPlaylistReference
import lansync.model.SyncSong

object ConflictResolver {
    fun merge(
        local: LibrarySnapshot,
        remote: LibrarySnapshot,
        initialImport: Boolean = false,
    ): LibrarySnapshot {
        val songs = mergeSongs(local.songs, remote.songs)
        val favorites = if (initialImport) {
            mergeInitialFavorites(local.favorites, remote.favorites)
        } else {
            mergeFavorites(local.favorites, remote.favorites)
        }
        return LibrarySnapshot(
            songs = songs,
            favorites = favorites,
            playlists = mergeVersioned(local.playlists, remote.playlists, SyncPlaylist::id, SyncPlaylist::version),
            playlistEntries = mergeVersioned(
                local.playlistEntries,
                remote.playlistEntries,
                SyncPlaylistEntry::entryId,
                SyncPlaylistEntry::version,
            ).sortedWith(compareBy(SyncPlaylistEntry::orderKey, SyncPlaylistEntry::entryId)),
            savedPlaylistReferences = mergeVersioned(
                local.savedPlaylistReferences,
                remote.savedPlaylistReferences,
                SyncSavedPlaylistReference::id,
                SyncSavedPlaylistReference::version,
            ),
            sequence = maxOf(local.sequence, remote.sequence),
        )
    }

    private fun mergeSongs(local: List<SyncSong>, remote: List<SyncSong>): List<SyncSong> {
        val result = local.associateByTo(linkedMapOf(), SyncSong::id)
        remote.forEach { incoming ->
            val existing = result[incoming.id]
            result[incoming.id] = when {
                existing == null -> incoming
                incoming.version > existing.version -> incoming.mergeMissingMetadataFrom(existing)
                else -> existing.mergeMissingMetadataFrom(incoming)
            }
        }
        return result.values.toList()
    }

    private fun mergeInitialFavorites(
        local: List<SyncFavorite>,
        remote: List<SyncFavorite>,
    ): List<SyncFavorite> {
        val result = local.associateByTo(linkedMapOf(), SyncFavorite::songId)
        remote.forEach { incoming ->
            val existing = result[incoming.songId]
            result[incoming.songId] = when {
                existing == null -> incoming
                existing.liked && !incoming.liked -> existing
                incoming.version > existing.version -> incoming
                else -> existing
            }
        }
        return result.values.toList()
    }

    private fun mergeFavorites(
        local: List<SyncFavorite>,
        remote: List<SyncFavorite>,
    ): List<SyncFavorite> {
        val result = local.associateByTo(linkedMapOf(), SyncFavorite::songId)
        remote.forEach { incoming ->
            val existing = result[incoming.songId]
            if (existing == null || incoming.version > existing.version) {
                result[incoming.songId] = incoming
            }
        }
        return result.values.toList()
    }

    private fun <T> mergeVersioned(
        local: List<T>,
        remote: List<T>,
        id: (T) -> String,
        version: (T) -> lansync.model.LogicalVersion,
    ): List<T> {
        val result = local.associateByTo(linkedMapOf(), id)
        remote.forEach { incoming ->
            val existing = result[id(incoming)]
            if (existing == null || version(incoming) > version(existing)) result[id(incoming)] = incoming
        }
        return result.values.toList()
    }

    private fun SyncSong.mergeMissingMetadataFrom(other: SyncSong): SyncSong = copy(
        title = title.ifBlank { other.title },
        artists = artists.ifEmpty { other.artists },
        album = album ?: other.album,
        albumId = albumId ?: other.albumId,
        artistIds = artistIds.ifEmpty { other.artistIds },
        duration = duration.ifBlank { other.duration },
        durationSeconds = durationSeconds.takeIf { it > 0 } ?: other.durationSeconds,
        thumbnails = thumbnails ?: other.thumbnails,
        videoType = videoType.ifBlank { other.videoType },
        category = category ?: other.category,
        resultType = resultType ?: other.resultType,
        canvasUrl = canvasUrl ?: other.canvasUrl,
        canvasThumbUrl = canvasThumbUrl ?: other.canvasThumbUrl,
    )
}
