package lansync.security

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

@Serializable
data class LanSyncConfiguration(
    val enabled: Boolean = false,
    val localDeviceId: String,
    val lastSuccessfulSyncMillis: Long? = null,
)

interface LanSyncConfigurationStore {
    suspend fun read(): LanSyncConfiguration?
    suspend fun write(configuration: LanSyncConfiguration)
}

class JsonLanSyncConfigurationStore(
    private val path: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
) : LanSyncConfigurationStore {
    private val mutex = Mutex()

    override suspend fun read(): LanSyncConfiguration? = mutex.withLock {
        if (!fileSystem.exists(path)) return@withLock null
        try {
            fileSystem.read(path) { json.decodeFromString<LanSyncConfiguration>(readUtf8()) }
        } catch (cause: Throwable) {
            throw IllegalStateException("LAN sync configuration is corrupt: $path", cause)
        }
    }

    override suspend fun write(configuration: LanSyncConfiguration) = mutex.withLock {
        path.parent?.let(fileSystem::createDirectories)
        val temp = path.parent?.resolve(".${path.name}.tmp") ?: ".${path.name}.tmp".toPath()
        try {
            fileSystem.write(temp) {
                writeUtf8(json.encodeToString(LanSyncConfiguration.serializer(), configuration))
                flush()
            }
            fileSystem.atomicMove(temp, path)
        } finally {
            if (fileSystem.exists(temp)) fileSystem.delete(temp)
        }
    }
}
