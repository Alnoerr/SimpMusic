package lansync.adapter

import com.maxrave.domain.data.entities.SongEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lansync.api.MusicLibraryAdapter
import lansync.model.FavoriteSyncState
import lansync.model.LibrarySnapshot
import lansync.model.LogicalVersion
import lansync.model.SyncFavorite
import lansync.model.SyncSong
import lansync.model.YOUTUBE_MUSIC_SOURCE
import lansync.model.normalizedSongGlobalId
import lansync.storage.FavoriteState
import lansync.storage.FavoriteStateStore

class RoomFavoritesMusicLibraryAdapter(
    private val repository: FavoritesRepository,
    private val stateStore: FavoriteStateStore,
    private val deviceId: String,
) : MusicLibraryAdapter {
    private val mutex = Mutex()
    private val states = linkedMapOf<String, FavoriteSyncState>()
    private var logicalClock = 0L
    private var loaded = false

    override suspend fun readSnapshot(): LibrarySnapshot = snapshotFromRows(repository.readSongs())

    override suspend fun applyRemoteChanges(snapshot: LibrarySnapshot) {
        mutex.withLock {
            ensureLoadedLocked()
            val songsById = snapshot.songs.associateBy(SyncSong::id)
            snapshot.favorites.forEach { incoming ->
                logicalClock = maxOf(logicalClock, incoming.version.counter)
                val current = states[incoming.songId]
                if (current != null && current.favorite.version > incoming.version) return@forEach
                val song = songsById[incoming.songId] ?: current?.song
                states[incoming.songId] = FavoriteSyncState(incoming, song)

                val videoId = song?.sourceTrackId ?: incoming.songId.substringAfter(':', incoming.songId)
                val existing = repository.getSong(videoId)
                if (incoming.liked && song != null) {
                    if (existing == null) repository.insertSong(song.toSongEntity())
                    if (existing?.liked != true) repository.applyLocalLikeStatus(videoId, liked = true)
                } else if (!incoming.liked && existing?.liked == true) {
                    repository.applyLocalLikeStatus(videoId, liked = false)
                }
            }
            persistLocked()
        }
    }

    override fun observeLibrary(): Flow<LibrarySnapshot> =
        repository.observeSongs().map(::snapshotFromRows).distinctUntilChanged()

    private suspend fun snapshotFromRows(rows: List<SongEntity>): LibrarySnapshot = mutex.withLock {
        ensureLoadedLocked()
        var changed = false
        rows.forEach { row ->
            val id = row.syncId()
            val previous = states[id]
            if (previous == null && !row.liked) return@forEach
            val version = if (previous == null || previous.favorite.liked != row.liked) {
                LogicalVersion(++logicalClock, deviceId)
            } else {
                previous.favorite.version
            }
            val state = FavoriteSyncState(
                favorite = SyncFavorite(id, row.liked, version),
                song = row.toSyncSong(version),
            )
            if (states[id] != state) {
                states[id] = state
                changed = true
            }
        }
        if (changed) persistLocked()
        snapshotLocked()
    }

    private suspend fun ensureLoadedLocked() {
        if (loaded) return
        val stored = stateStore.read()
        logicalClock = stored.logicalClock
        states.clear()
        stored.favorites.forEach { states[it.favorite.songId] = it }
        loaded = true
    }

    private suspend fun persistLocked() {
        stateStore.write(FavoriteState(logicalClock, states.values.sortedBy { it.favorite.songId }))
    }

    private fun snapshotLocked(): LibrarySnapshot {
        val values = states.values.toList()
        return LibrarySnapshot(
            songs = values.mapNotNull(FavoriteSyncState::song).distinctBy(SyncSong::id),
            favorites = values.map(FavoriteSyncState::favorite),
        )
    }

    private fun SongEntity.syncId() = normalizedSongGlobalId(YOUTUBE_MUSIC_SOURCE, videoId)

    private fun SongEntity.toSyncSong(version: LogicalVersion) = SyncSong(
        id = syncId(),
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
}
