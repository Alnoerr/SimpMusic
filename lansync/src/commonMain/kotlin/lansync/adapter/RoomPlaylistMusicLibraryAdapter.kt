package lansync.adapter

import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.PairSongLocalPlaylist
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.extension.now
import com.maxrave.domain.utils.isRadioPlaylistId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lansync.api.MusicLibraryAdapter
import lansync.model.LibrarySnapshot
import lansync.model.LogicalVersion
import lansync.model.SyncPlaylist
import lansync.model.SyncPlaylistEntry
import lansync.model.SyncSavedPlaylistReference
import lansync.model.SyncSong
import lansync.model.YOUTUBE_MUSIC_SOURCE
import lansync.model.normalizedSongGlobalId
import lansync.model.playlistOrderKey
import lansync.storage.PlaylistEntryMapping
import lansync.storage.PlaylistMapping
import lansync.storage.PlaylistState
import lansync.storage.SavedPlaylistReferenceMapping
import lansync.storage.PlaylistStateStore
import okio.ByteString.Companion.encodeUtf8

class RoomPlaylistMusicLibraryAdapter(
    private val playlistsRepository: PlaylistsRepository,
    private val songsRepository: FavoritesRepository,
    private val stateStore: PlaylistStateStore,
    private val deviceId: String,
    private val savedPlaylistsRepository: SavedPlaylistRepository? = null,
) : MusicLibraryAdapter {
    private val mutex = Mutex()
    private val playlists = linkedMapOf<String, PlaylistMapping>()
    private val entries = linkedMapOf<String, PlaylistEntryMapping>()
    private val savedReferences = linkedMapOf<String, SavedPlaylistReferenceMapping>()
    private var logicalClock = 0L
    private var loaded = false

    override suspend fun readSnapshot(): LibrarySnapshot =
        snapshotFromLibrary(
            playlistsRepository.readLibrary(),
            savedPlaylistsRepository?.observePlaylists()?.first().orEmpty(),
        )

    override fun observeLibrary(): Flow<LibrarySnapshot> {
        val savedFlow = savedPlaylistsRepository
            ?.observePlaylists()
            ?: flowOf(emptyList())
        return combine(playlistsRepository.observeLibrary(), savedFlow) { _, saved ->
            snapshotFromLibrary(playlistsRepository.readLibrary(), saved)
        }.distinctUntilChanged()
    }

    override suspend fun applyRemoteChanges(snapshot: LibrarySnapshot) {
        mutex.withLock {
            ensureLoadedLocked()
            snapshot.playlists.forEach { incoming ->
                logicalClock = maxOf(logicalClock, incoming.version.counter)
                val current = playlists[incoming.id]
                if (current == null || incoming.version > current.playlist.version) {
                    playlists[incoming.id] = PlaylistMapping(incoming.id, current?.localId, incoming)
                }
            }
            snapshot.playlistEntries.forEach { incoming ->
                logicalClock = maxOf(logicalClock, incoming.version.counter)
                val current = entries[incoming.entryId]
                if (current == null || incoming.version > current.entry.version) {
                    entries[incoming.entryId] = PlaylistEntryMapping(incoming.entryId, current?.localPairId, incoming)
                }
            }
            snapshot.savedPlaylistReferences.forEach { incoming ->
                logicalClock = maxOf(logicalClock, incoming.version.counter)
                val current = savedReferences[incoming.id]
                if (current == null || incoming.version > current.reference.version) {
                    savedReferences[incoming.id] = SavedPlaylistReferenceMapping(incoming.id, incoming)
                }
            }

            val songsById = snapshot.songs.associateBy(SyncSong::id)
            playlists.values.toList().forEach { mapping ->
                val playlist = mapping.playlist
                if (playlist.deleted) {
                    mapping.localId?.let { playlistsRepository.deletePlaylist(it) }
                    playlists[playlist.id] = mapping.copy(localId = null)
                    return@forEach
                }

                val desiredEntries = activeEntries(playlist.id)
                desiredEntries.forEach { entry ->
                    val song = songsById[entry.songId]
                    val videoId = song?.sourceTrackId ?: entry.songId.substringAfter(':')
                    if (songsRepository.getSong(videoId) == null && song != null) {
                        songsRepository.insertSong(song.toSongEntity())
                    }
                }
                val localId = mapping.localId ?: playlistsRepository.insertPlaylist(
                    LocalPlaylistEntity(
                        title = playlist.name,
                        thumbnail = playlist.thumbnailUrl,
                        downloadedAt = null,
                        downloadState = DownloadState.STATE_NOT_DOWNLOADED,
                        tracks = desiredEntries.map { it.songId.substringAfter(':') },
                    ),
                ).also { check(it >= 0) { "Unable to create synchronized playlist" } }

                playlistsRepository.updatePlaylist(localId, playlist.name, playlist.thumbnailUrl)
                val current = playlistsRepository.readLibrary()
                val currentIds = current.entries
                    .filter { it.playlistId == localId }
                    .sortedWith(compareBy({ it.position }, { it.id }))
                    .map { it.songId }
                val desiredIds = desiredEntries.map { it.songId.substringAfter(':') }
                if (currentIds != desiredIds) playlistsRepository.replaceEntries(localId, desiredIds)
                playlists[playlist.id] = mapping.copy(localId = localId)
            }
            remapLocalRowsLocked(playlistsRepository.readLibrary())
            applySavedReferencesLocked()
            persistLocked()
        }
    }

    private suspend fun snapshotFromLibrary(
        library: LocalPlaylistLibrary,
        savedLibrary: List<PlaylistEntity>,
    ): LibrarySnapshot = mutex.withLock {
        ensureLoadedLocked()
        reconcileLocalLocked(library)
        reconcileSavedLocked(savedLibrary)
        buildSnapshotLocked()
    }

    private fun reconcileSavedLocked(library: List<PlaylistEntity>) {
        library.filter { it.id.isNotBlank() && !it.id.isRadioPlaylistId() }.forEach { playlist ->
            val syncId = savedPlaylistSyncId(playlist.id)
            val previous = savedReferences[syncId]?.reference
            val changed = previous == null ||
                previous.sourcePlaylistId != playlist.id ||
                previous.title != playlist.title ||
                previous.description != playlist.description ||
                previous.thumbnailUrl != playlist.thumbnails.takeIf { it.isNotBlank() } ||
                previous.saved != playlist.liked
            val version = if (changed) nextVersionLocked() else previous.version
            savedReferences[syncId] = SavedPlaylistReferenceMapping(
                syncId = syncId,
                reference = SyncSavedPlaylistReference(
                    id = syncId,
                    sourcePlaylistId = playlist.id,
                    title = playlist.title,
                    description = playlist.description,
                    thumbnailUrl = playlist.thumbnails.takeIf { it.isNotBlank() },
                    saved = playlist.liked,
                    version = version,
                ),
            )
        }
    }

    private suspend fun applySavedReferencesLocked() {
        val repository = savedPlaylistsRepository ?: return
        val local = repository.observePlaylists().first()
        savedReferences.values.forEach { mapping ->
            val reference = mapping.reference
            val old = local.firstOrNull { it.id == reference.sourcePlaylistId }
            if (!reference.saved && old == null) return@forEach
            val entity = PlaylistEntity(
                id = reference.sourcePlaylistId,
                author = old?.author,
                description = reference.description,
                duration = old?.duration ?: "",
                durationSeconds = old?.durationSeconds ?: 0,
                privacy = old?.privacy ?: "PRIVATE",
                thumbnails = reference.thumbnailUrl ?: old?.thumbnails.orEmpty(),
                title = reference.title,
                trackCount = old?.trackCount ?: 0,
                tracks = old?.tracks,
                year = old?.year,
                liked = reference.saved,
                inLibrary = old?.inLibrary ?: now(),
                favoriteAt = if (reference.saved) old?.favoriteAt ?: now() else null,
                downloadedAt = old?.downloadedAt,
                downloadState = old?.downloadState ?: DownloadState.STATE_NOT_DOWNLOADED,
            )
            repository.insertOrReplace(entity)
        }
    }

    private fun savedPlaylistSyncId(sourcePlaylistId: String): String =
        "ytmusic-playlist:" + sourcePlaylistId.trim()

    private suspend fun reconcileLocalLocked(library: LocalPlaylistLibrary) {
        val localPlaylists = library.playlists.filter { it.youtubePlaylistId == null }
        val visiblePlaylistIds = localPlaylists.mapTo(mutableSetOf(), LocalPlaylistEntity::id)
        val mappingByLocalId = playlists.values.mapNotNull { mapping ->
            mapping.localId?.let { it to mapping }
        }.toMap()

        localPlaylists.forEach { local ->
            val existing = mappingByLocalId[local.id]
            val previous = existing?.playlist
            val changed = previous == null || previous.deleted ||
                previous.name != local.title || previous.thumbnailUrl != local.thumbnail
            val version = if (changed) nextVersionLocked() else previous.version
            val syncId = existing?.syncId ?: "localplaylist:${stableUuid("$deviceId:playlist:${local.id}")}"
            playlists[syncId] = PlaylistMapping(
                syncId = syncId,
                localId = local.id,
                playlist = SyncPlaylist(syncId, local.title, local.thumbnail, deleted = false, version = version),
            )
        }

        playlists.values.toList().forEach { mapping ->
            val localId = mapping.localId ?: return@forEach
            if (localId !in visiblePlaylistIds && !mapping.playlist.deleted) {
                val tombstone = mapping.playlist.copy(deleted = true, version = nextVersionLocked())
                playlists[mapping.syncId] = mapping.copy(localId = null, playlist = tombstone)
                entries.values.filter { it.entry.playlistId == mapping.syncId && !it.entry.deleted }.forEach {
                    entries[it.syncId] = it.copy(
                        localPairId = null,
                        entry = it.entry.copy(deleted = true, version = nextVersionLocked()),
                    )
                }
            }
        }

        val activeMappingsByLocalId = playlists.values.mapNotNull { mapping ->
            mapping.localId?.let { it to mapping }
        }.toMap()
        val visiblePairIds = library.entries.mapTo(mutableSetOf(), PairSongLocalPlaylist::id)
        val entryByLocalPairId = entries.values.mapNotNull { mapping ->
            mapping.localPairId?.let { it to mapping }
        }.toMap()
        val claimedSyncIds = mutableSetOf<String>()

        library.entries.sortedWith(compareBy({ it.playlistId }, { it.position }, { it.id })).forEach { local ->
            val parent = activeMappingsByLocalId[local.playlistId] ?: return@forEach
            val orderKey = playlistOrderKey(local.position)
            val songId = normalizedSongGlobalId(YOUTUBE_MUSIC_SOURCE, local.songId)
            val existing = entryByLocalPairId[local.id]?.takeIf { it.syncId !in claimedSyncIds }
                ?: entries.values.firstOrNull {
                    it.syncId !in claimedSyncIds && !it.entry.deleted &&
                        it.entry.playlistId == parent.syncId && it.entry.songId == songId &&
                        it.entry.orderKey == orderKey
                }
            existing?.syncId?.let(claimedSyncIds::add)
            val previous = existing?.entry
            val changed = previous == null || previous.deleted || previous.playlistId != parent.syncId ||
                previous.songId != songId || previous.orderKey != orderKey
            val version = if (changed) nextVersionLocked() else previous.version
            val syncId = existing?.syncId ?: "playlistentry:${stableUuid("$deviceId:entry:${local.id}")}"
            entries[syncId] = PlaylistEntryMapping(
                syncId = syncId,
                localPairId = local.id,
                entry = SyncPlaylistEntry(syncId, parent.syncId, songId, orderKey, deleted = false, version = version),
            )
            claimedSyncIds += syncId
        }

        val activePlaylistSyncIds = activeMappingsByLocalId.values.mapTo(mutableSetOf(), PlaylistMapping::syncId)
        entries.values.toList().forEach { mapping ->
            if (mapping.entry.deleted || mapping.entry.playlistId !in activePlaylistSyncIds) return@forEach
            val localPairId = mapping.localPairId
            if (localPairId == null || localPairId !in visiblePairIds) {
                entries[mapping.syncId] = mapping.copy(
                    localPairId = null,
                    entry = mapping.entry.copy(deleted = true, version = nextVersionLocked()),
                )
            }
        }
        persistLocked()
    }

    private fun remapLocalRowsLocked(library: LocalPlaylistLibrary) {
        playlists.values.filter { !it.playlist.deleted && it.localId != null }.forEach { playlistMapping ->
            val localRows = library.entries.filter { it.playlistId == playlistMapping.localId }
            val localById = localRows.associateBy(PairSongLocalPlaylist::id)
            val activeSyncRows = activeEntries(playlistMapping.syncId)
            val activeIds = activeSyncRows.mapTo(mutableSetOf(), SyncPlaylistEntry::entryId)
            val claimedLocalIds = mutableSetOf<Int>()

            entries.values.filter { it.entry.playlistId == playlistMapping.syncId }.forEach { current ->
                if (current.syncId !in activeIds) {
                    entries[current.syncId] = current.copy(localPairId = null)
                    return@forEach
                }
                val local = current.localPairId?.let(localById::get)
                val matches = local != null &&
                    normalizedSongGlobalId(YOUTUBE_MUSIC_SOURCE, local.songId) == current.entry.songId &&
                    playlistOrderKey(local.position) == current.entry.orderKey
                if (matches) {
                    claimedLocalIds += local.id
                } else {
                    entries[current.syncId] = current.copy(localPairId = null)
                }
            }

            activeSyncRows.forEach { sync ->
                val current = entries.getValue(sync.entryId)
                if (current.localPairId != null) return@forEach
                val local = localRows.firstOrNull {
                    it.id !in claimedLocalIds &&
                        normalizedSongGlobalId(YOUTUBE_MUSIC_SOURCE, it.songId) == sync.songId &&
                        playlistOrderKey(it.position) == sync.orderKey
                } ?: return@forEach
                entries[sync.entryId] = current.copy(localPairId = local.id)
                claimedLocalIds += local.id
            }
        }
    }

    private suspend fun buildSnapshotLocked(): LibrarySnapshot {
        val active = entries.values.map(PlaylistEntryMapping::entry).filterNot(SyncPlaylistEntry::deleted)
        val songs = active.mapNotNull { entry ->
            songsRepository.getSong(entry.songId.substringAfter(':'))?.toSyncSong(entry.version)
        }.associateBy(SyncSong::id).values.toList()
        return LibrarySnapshot(
            songs = songs,
            playlists = playlists.values.map(PlaylistMapping::playlist),
            playlistEntries = entries.values.map(PlaylistEntryMapping::entry),
            savedPlaylistReferences = savedReferences.values.map(SavedPlaylistReferenceMapping::reference),
        )
    }

    private fun activeEntries(playlistId: String): List<SyncPlaylistEntry> =
        entries.values.map(PlaylistEntryMapping::entry)
            .filter { it.playlistId == playlistId && !it.deleted }
            .sortedWith(compareBy(SyncPlaylistEntry::orderKey, SyncPlaylistEntry::entryId))

    private suspend fun ensureLoadedLocked() {
        if (loaded) return
        val state = stateStore.read()
        logicalClock = state.logicalClock
        playlists.clear()
        entries.clear()
        state.playlists.forEach { playlists[it.syncId] = it }
        state.entries.forEach { entries[it.syncId] = it }
        savedReferences.clear()
        state.savedReferences.forEach { savedReferences[it.syncId] = it }
        loaded = true
    }

    private suspend fun persistLocked() {
        val localPairIds = entries.values.mapNotNull(PlaylistEntryMapping::localPairId)
        check(localPairIds.size == localPairIds.distinct().size) { "Playlist entry mapping is not one-to-one" }
        stateStore.write(
            PlaylistState(
                logicalClock = logicalClock,
                playlists = playlists.values.sortedBy(PlaylistMapping::syncId),
                entries = entries.values.sortedBy(PlaylistEntryMapping::syncId),
                savedReferences = savedReferences.values.sortedBy(SavedPlaylistReferenceMapping::syncId),
            ),
        )
    }

    private fun nextVersionLocked() = LogicalVersion(++logicalClock, deviceId)

    private fun stableUuid(seed: String): String {
        val bytes = seed.encodeUtf8().sha256().toByteArray().copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = bytes.joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            "${HEX[value ushr 4]}${HEX[value and 0x0f]}"
        }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private fun SongEntity.toSyncSong(version: LogicalVersion) = SyncSong(
        id = normalizedSongGlobalId(YOUTUBE_MUSIC_SOURCE, videoId),
        sourceTrackId = videoId,
        title = title,
        artists = artistName.orEmpty(),
        album = albumName,
        albumId = albumId,
        artistIds = artistId.orEmpty(),
        duration = duration,
        durationSeconds = durationSeconds,
        isAvailable = isAvailable,
        isExplicit = isExplicit,
        likeStatus = likeStatus,
        thumbnails = thumbnails,
        videoType = videoType,
        category = category,
        resultType = resultType,
        canvasUrl = canvasUrl,
        canvasThumbUrl = canvasThumbUrl,
        version = version,
    )

    private fun SyncSong.toSongEntity() = SongEntity(
        videoId = sourceTrackId,
        albumId = albumId,
        albumName = album,
        artistId = artistIds,
        artistName = artists,
        duration = duration,
        durationSeconds = durationSeconds,
        isAvailable = isAvailable,
        isExplicit = isExplicit,
        likeStatus = likeStatus,
        thumbnails = thumbnails,
        title = title,
        videoType = videoType,
        category = category,
        resultType = resultType,
        liked = false,
        favoriteAt = null,
        downloadedAt = null,
        canvasUrl = canvasUrl,
        canvasThumbUrl = canvasThumbUrl,
    )

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}
