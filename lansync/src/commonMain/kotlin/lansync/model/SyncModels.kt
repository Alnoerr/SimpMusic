package lansync.model

import kotlinx.serialization.Serializable

const val LAN_PROTOCOL_VERSION = 2
const val YOUTUBE_MUSIC_SOURCE = "ytmusic"

@Serializable
data class LogicalVersion(
    val counter: Long,
    val deviceId: String,
) : Comparable<LogicalVersion> {
    init {
        require(counter >= 0) { "Logical counter must be non-negative" }
        require(deviceId.isNotBlank()) { "Device ID must not be blank" }
    }

    override fun compareTo(other: LogicalVersion): Int =
        compareValuesBy(this, other, LogicalVersion::counter, LogicalVersion::deviceId)
}

@Serializable
data class SyncSong(
    val id: String,
    val source: String = YOUTUBE_MUSIC_SOURCE,
    val sourceTrackId: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val albumId: String? = null,
    val artistIds: List<String> = emptyList(),
    val duration: String = "",
    val durationSeconds: Int = 0,
    val isAvailable: Boolean = true,
    val isExplicit: Boolean = false,
    val likeStatus: String = "INDIFFERENT",
    val thumbnails: String? = null,
    val videoType: String = "",
    val category: String? = null,
    val resultType: String? = null,
    val canvasUrl: String? = null,
    val canvasThumbUrl: String? = null,
    val version: LogicalVersion,
    val schemaVersion: Int = 1,
)

@Serializable
data class SyncFavorite(
    val songId: String,
    val liked: Boolean,
    val version: LogicalVersion,
)

@Serializable
data class FavoriteSyncState(
    val favorite: SyncFavorite,
    val song: SyncSong? = null,
)

@Serializable
data class SyncPlaylist(
    val id: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val deleted: Boolean = false,
    val version: LogicalVersion,
)

@Serializable
data class SyncPlaylistEntry(
    val entryId: String,
    val playlistId: String,
    val songId: String,
    val orderKey: String,
    val deleted: Boolean = false,
    val version: LogicalVersion,
)

@Serializable
data class SyncSavedPlaylistReference(
    val id: String,
    val sourcePlaylistId: String,
    val title: String,
    val description: String = "",
    val thumbnailUrl: String? = null,
    val saved: Boolean,
    val version: LogicalVersion,
    val schemaVersion: Int = 1,
)

@Serializable
data class LibrarySnapshot(
    val songs: List<SyncSong> = emptyList(),
    val favorites: List<SyncFavorite> = emptyList(),
    val playlists: List<SyncPlaylist> = emptyList(),
    val playlistEntries: List<SyncPlaylistEntry> = emptyList(),
    val savedPlaylistReferences: List<SyncSavedPlaylistReference> = emptyList(),
    val sequence: Long = 0,
)

@Serializable
enum class SyncEntityType {
    SONG,
    FAVORITE,
    PLAYLIST,
    PLAYLIST_ENTRY,
    SAVED_PLAYLIST_REFERENCE,
}

@Serializable
enum class SyncOperationType {
    UPSERT,
    TOMBSTONE,
}

@Serializable
data class SyncChange(
    val operationId: String,
    val sourceDeviceId: String,
    val entityType: SyncEntityType,
    val entityId: String,
    val version: LogicalVersion,
    val operation: SyncOperationType,
    val payload: String,
    val schemaVersion: Int = 1,
)

@Serializable
data class SequencedChange(
    val sequence: Long,
    val change: SyncChange,
)

fun normalizedSongGlobalId(source: String, sourceTrackId: String): String =
    "${source.trim().lowercase().replace("youtube_music", YOUTUBE_MUSIC_SOURCE)}:${sourceTrackId.trim()}"

fun playlistOrderKey(position: Int): String = position.toString().padStart(10, '0')
