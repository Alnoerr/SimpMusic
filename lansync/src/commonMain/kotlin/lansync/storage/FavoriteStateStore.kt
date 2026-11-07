package lansync.storage

import lansync.model.FavoriteSyncState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

@Serializable
data class FavoriteState(
    val logicalClock: Long = 0,
    val favorites: List<FavoriteSyncState> = emptyList(),
)

interface FavoriteStateStore {
    suspend fun read(): FavoriteState
    suspend fun write(state: FavoriteState)
}

class InMemoryFavoriteStateStore(
    initial: FavoriteState = FavoriteState(),
) : FavoriteStateStore {
    private val mutex = Mutex()
    private var state = initial

    override suspend fun read(): FavoriteState = mutex.withLock { state }

    override suspend fun write(state: FavoriteState) {
        mutex.withLock { this.state = state }
    }
}

class JsonFileFavoriteStateStore(
    private val path: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
) : FavoriteStateStore {
    private val mutex = Mutex()

    override suspend fun read(): FavoriteState = mutex.withLock {
        if (!fileSystem.exists(path)) return@withLock FavoriteState()
        try {
            fileSystem.read(path) { json.decodeFromString<FavoriteState>(readUtf8()) }
        } catch (cause: Throwable) {
            throw IllegalStateException("LAN favorite state is corrupt: $path", cause)
        }
    }

    override suspend fun write(state: FavoriteState) = mutex.withLock {
        path.parent?.let(fileSystem::createDirectories)
        val temp = path.parent?.resolve(".${path.name}.tmp") ?: ".${path.name}.tmp".toPath()
        fileSystem.write(temp) { writeUtf8(json.encodeToString(FavoriteState.serializer(), state)) }
        fileSystem.atomicMove(temp, path)
    }
}
