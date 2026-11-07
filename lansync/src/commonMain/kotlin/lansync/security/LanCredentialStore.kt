package lansync.security

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lansync.model.LAN_PROTOCOL_VERSION
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

@Serializable
data class DeviceCredentials(
    val localDeviceId: String,
    val pairedDesktopId: String,
    val deviceAuthToken: String,
    val pinnedDesktopFingerprint: String,
    val lastKnownHost: String,
    val lastKnownPort: Int,
    val displayName: String = "Desktop",
    val autoSync: Boolean = true,
    val protocolVersion: Int = LAN_PROTOCOL_VERSION,
) {
    init {
        require(localDeviceId.isNotBlank()) { "Local device ID is required" }
        require(pairedDesktopId.isNotBlank()) { "Paired Desktop ID is required" }
        require(deviceAuthToken.isNotBlank()) { "Device authentication token is required" }
        require(pinnedDesktopFingerprint.isNotBlank()) { "Pinned Desktop fingerprint is required" }
        require(lastKnownHost.isNotBlank()) { "Last known host is required" }
        require(lastKnownPort in 1..65535) { "Last known port is invalid" }
    }

    override fun toString(): String =
        "DeviceCredentials(localDeviceId=$localDeviceId, pairedDesktopId=$pairedDesktopId, " +
            "deviceAuthToken=<redacted>, pinnedDesktopFingerprint=$pinnedDesktopFingerprint, " +
            "lastKnownHost=$lastKnownHost, lastKnownPort=$lastKnownPort, displayName=$displayName, " +
            "autoSync=$autoSync, protocolVersion=$protocolVersion)"
}

interface LanCredentialStore {
    suspend fun saveCredentials(credentials: DeviceCredentials)
    suspend fun loadCredentials(): DeviceCredentials?
    suspend fun clearCredentials()
}

class InMemoryLanCredentialStore(
    initial: DeviceCredentials? = null,
) : LanCredentialStore {
    private val mutex = Mutex()
    private var credentials = initial

    override suspend fun saveCredentials(credentials: DeviceCredentials) {
        mutex.withLock { this.credentials = credentials }
    }

    override suspend fun loadCredentials(): DeviceCredentials? = mutex.withLock { credentials }

    override suspend fun clearCredentials() {
        mutex.withLock { credentials = null }
    }
}

class JsonLanCredentialStore(
    private val path: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
    private val afterWrite: (Path) -> Unit = {},
) : LanCredentialStore {
    private val mutex = Mutex()

    override suspend fun saveCredentials(credentials: DeviceCredentials) = mutex.withLock {
        path.parent?.let(fileSystem::createDirectories)
        val temp = path.parent?.resolve(".${path.name}.tmp") ?: ".${path.name}.tmp".toPath()
        try {
            fileSystem.write(temp) {
                writeUtf8(json.encodeToString(DeviceCredentials.serializer(), credentials))
                flush()
            }
            fileSystem.atomicMove(temp, path)
            afterWrite(path)
        } finally {
            if (fileSystem.exists(temp)) fileSystem.delete(temp)
        }
    }

    override suspend fun loadCredentials(): DeviceCredentials? = mutex.withLock {
        if (!fileSystem.exists(path)) return@withLock null
        try {
            val size = fileSystem.metadata(path).size
            check(size == null || size <= MAX_CREDENTIAL_FILE_BYTES) { "Credential file exceeds the size limit" }
            fileSystem.read(path) {
                json.decodeFromString(DeviceCredentials.serializer(), readUtf8())
            }
        } catch (cause: Throwable) {
            throw IllegalStateException("LAN credential file is corrupt: $path", cause)
        }
    }

    override suspend fun clearCredentials() = mutex.withLock {
        if (fileSystem.exists(path)) fileSystem.delete(path)
        val temp = path.parent?.resolve(".${path.name}.tmp") ?: ".${path.name}.tmp".toPath()
        if (fileSystem.exists(temp)) fileSystem.delete(temp)
    }

    companion object {
        private const val MAX_CREDENTIAL_FILE_BYTES = 64L * 1024
    }
}
