package lansync.security

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

class JsonTrustedPeerStore(
    private val path: Path,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
) : TrustedPeerStore {
    private val mutex = Mutex()

    override suspend fun readAll(): List<TrustedPeer> = mutex.withLock { readLocked() }

    override suspend fun writeAll(peers: List<TrustedPeer>) = mutex.withLock {
        path.parent?.let(Files::createDirectories)
        val temp = path.resolveSibling(".${path.fileName}.tmp")
        Files.newOutputStream(temp).use { stream ->
            stream.write(json.encodeToString(PeerFile(peers = peers)).encodeToByteArray())
            stream.flush()
        }
        runCatching {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
        restrictToOwner(path)
    }

    private fun readLocked(): List<TrustedPeer> {
        if (!Files.exists(path)) return emptyList()
        return try {
            json.decodeFromString<PeerFile>(Files.readString(path)).peers
        } catch (cause: Throwable) {
            throw IllegalStateException("Trusted-peer store is corrupt: $path", cause)
        }
    }

    private fun restrictToOwner(target: Path) {
        runCatching {
            val view = Files.getFileAttributeView(target, PosixFileAttributeView::class.java)
                ?: return
            view.setPermissions(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
        }
    }
}

@Serializable
private data class PeerFile(
    val schemaVersion: Int = 1,
    val peers: List<TrustedPeer> = emptyList(),
)
