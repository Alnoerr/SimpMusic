package lansync.transport

import kotlinx.serialization.json.Json
import lansync.model.LibrarySnapshot
import lansync.model.SyncChange
import lansync.model.SyncEntityType
import lansync.model.SyncFavorite
import lansync.model.SyncOperationType
import lansync.model.SyncPlaylist
import lansync.model.SyncPlaylistEntry
import lansync.model.SyncSavedPlaylistReference
import lansync.model.SyncSong

class SyncMessageCodec(
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
) {
    fun decode(change: SyncChange): LibrarySnapshot {
        require(change.schemaVersion == 1) { "Unsupported change schema version" }
        require(change.entityId.isNotBlank() && change.operationId.isNotBlank()) { "Change identity is incomplete" }
        require(change.payload.length <= MAX_PAYLOAD_CHARS) { "Change payload is too large" }
        return when (change.entityType) {
            SyncEntityType.SONG -> {
                require(change.operation == SyncOperationType.UPSERT) { "Songs do not use tombstone operations" }
                val song = json.decodeFromString<SyncSong>(change.payload)
                require(song.id == change.entityId && song.version == change.version) { "Song payload does not match envelope" }
                LibrarySnapshot(songs = listOf(song))
            }
            SyncEntityType.FAVORITE -> {
                val favorite = json.decodeFromString<SyncFavorite>(change.payload)
                require(favorite.songId == change.entityId && favorite.version == change.version) {
                    "Favorite payload does not match envelope"
                }
                require((change.operation == SyncOperationType.TOMBSTONE) == !favorite.liked) {
                    "Favorite operation does not match state"
                }
                LibrarySnapshot(favorites = listOf(favorite))
            }
            SyncEntityType.PLAYLIST -> {
                val playlist = json.decodeFromString<SyncPlaylist>(change.payload)
                require(playlist.id == change.entityId && playlist.version == change.version) {
                    "Playlist payload does not match envelope"
                }
                require((change.operation == SyncOperationType.TOMBSTONE) == playlist.deleted) {
                    "Playlist operation does not match state"
                }
                LibrarySnapshot(playlists = listOf(playlist))
            }
            SyncEntityType.PLAYLIST_ENTRY -> {
                val entry = json.decodeFromString<SyncPlaylistEntry>(change.payload)
                require(entry.entryId == change.entityId && entry.version == change.version) {
                    "Playlist entry payload does not match envelope"
                }
                require((change.operation == SyncOperationType.TOMBSTONE) == entry.deleted) {
                    "Playlist entry operation does not match state"
                }
                LibrarySnapshot(playlistEntries = listOf(entry))
            }
            SyncEntityType.SAVED_PLAYLIST_REFERENCE -> {
                require(change.operation == SyncOperationType.UPSERT) {
                    "Saved playlist references use explicit saved state, not tombstone operations"
                }
                val reference = json.decodeFromString<SyncSavedPlaylistReference>(change.payload)
                require(reference.id == change.entityId && reference.version == change.version) {
                    "Saved playlist payload does not match envelope"
                }
                LibrarySnapshot(savedPlaylistReferences = listOf(reference))
            }
        }
    }

    fun encodeSong(operationId: String, sourceDeviceId: String, song: SyncSong): SyncChange =
        SyncChange(
            operationId = operationId,
            sourceDeviceId = sourceDeviceId,
            entityType = SyncEntityType.SONG,
            entityId = song.id,
            version = song.version,
            operation = SyncOperationType.UPSERT,
            payload = json.encodeToString(SyncSong.serializer(), song),
        )

    fun encodeFavorite(operationId: String, sourceDeviceId: String, favorite: SyncFavorite): SyncChange =
        SyncChange(
            operationId = operationId,
            sourceDeviceId = sourceDeviceId,
            entityType = SyncEntityType.FAVORITE,
            entityId = favorite.songId,
            version = favorite.version,
            operation = if (favorite.liked) SyncOperationType.UPSERT else SyncOperationType.TOMBSTONE,
            payload = json.encodeToString(SyncFavorite.serializer(), favorite),
        )

    fun encodePlaylist(operationId: String, sourceDeviceId: String, playlist: SyncPlaylist): SyncChange =
        SyncChange(
            operationId = operationId,
            sourceDeviceId = sourceDeviceId,
            entityType = SyncEntityType.PLAYLIST,
            entityId = playlist.id,
            version = playlist.version,
            operation = if (playlist.deleted) SyncOperationType.TOMBSTONE else SyncOperationType.UPSERT,
            payload = json.encodeToString(SyncPlaylist.serializer(), playlist),
        )

    fun encodePlaylistEntry(operationId: String, sourceDeviceId: String, entry: SyncPlaylistEntry): SyncChange =
        SyncChange(
            operationId = operationId,
            sourceDeviceId = sourceDeviceId,
            entityType = SyncEntityType.PLAYLIST_ENTRY,
            entityId = entry.entryId,
            version = entry.version,
            operation = if (entry.deleted) SyncOperationType.TOMBSTONE else SyncOperationType.UPSERT,
            payload = json.encodeToString(SyncPlaylistEntry.serializer(), entry),
        )

    fun encodeSavedPlaylistReference(
        operationId: String,
        sourceDeviceId: String,
        reference: SyncSavedPlaylistReference,
    ): SyncChange =
        SyncChange(
            operationId = operationId,
            sourceDeviceId = sourceDeviceId,
            entityType = SyncEntityType.SAVED_PLAYLIST_REFERENCE,
            entityId = reference.id,
            version = reference.version,
            operation = SyncOperationType.UPSERT,
            payload = json.encodeToString(SyncSavedPlaylistReference.serializer(), reference),
        )

    companion object {
        const val MAX_PAYLOAD_CHARS = 256_000
    }
}
