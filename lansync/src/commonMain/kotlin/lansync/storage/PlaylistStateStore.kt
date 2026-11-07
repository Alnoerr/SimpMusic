package lansync.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lansync.model.SyncPlaylist
import lansync.model.SyncPlaylistEntry
import lansync.model.SyncSavedPlaylistReference
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

@Serializable
data class PlaylistMapping(
    val syncId: String,
    val localId: Long? = null,
    val playlist: SyncPlaylist,
)

@Serializable
data class PlaylistEntryMapping(
    val syncId: String,
    val localPairId: Int? = null,
    val entry: SyncPlaylistEntry,
)

@Serializable
data class SavedPlaylistReferenceMapping(
    val syncId: String,
    val reference: SyncSavedPlaylistReference,
)

@Serializable
data class PlaylistState(
    val logicalClock: Long = 0,
    val playlists: List<PlaylistMapping> = emptyList(),
    val entries: List<PlaylistEntryMapping> = emptyList(),
    val savedReferences: List<SavedPlaylistReferenceMapping> = emptyList(),
)

interface PlaylistStateStore {
    suspend fun read(): PlaylistState
    suspend fun write(state: PlaylistState)
}

class InMemoryPlaylistStateStore(
    initial: PlaylistState = PlaylistState(),
) : PlaylistStateStore {
    private val mutex = Mutex()
    private var state = initial

    override suspend fun read(): PlaylistState = mutex.withLock { state }

    override suspend fun write(state: PlaylistState) {
        mutex.withLock { this.state = state }
    }
}

class JsonFilePlaylistStateStore(
    private val path: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
) : PlaylistStateStore {
    private val mutex = Mutex()

    override suspend fun read(): PlaylistState = mutex.withLock {
        if (!fileSystem.exists(path)) return@withLock PlaylistState()
        try {
            fileSystem.read(path) { json.decodeFromString<PlaylistState>(readUtf8()) }
        } catch (cause: Throwable) {
            throw IllegalStateException("LAN playlist state is corrupt: $path", cause)
        }
    }

    override suspend fun write(state: PlaylistState) = mutex.withLock {
        path.parent?.let(fileSystem::createDirectories)
        val temp = path.parent?.resolve(".${path.name}.tmp") ?: ".${path.name}.tmp".toPath()
        fileSystem.write(temp) { writeUtf8(json.encodeToString(PlaylistState.serializer(), state)) }
        fileSystem.atomicMove(temp, path)
    }
}
