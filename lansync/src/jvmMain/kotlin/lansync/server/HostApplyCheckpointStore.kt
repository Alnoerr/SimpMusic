package lansync.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

interface HostApplyCheckpointStore {
    suspend fun read(): Long
    suspend fun write(sequence: Long)
}

class InMemoryHostApplyCheckpointStore(
    private var sequence: Long = 0,
) : HostApplyCheckpointStore {
    override suspend fun read(): Long = sequence
    override suspend fun write(sequence: Long) {
        require(sequence >= this.sequence) { "Host apply checkpoint cannot move backwards" }
        this.sequence = sequence
    }
}

class FileHostApplyCheckpointStore(
    private val path: Path,
) : HostApplyCheckpointStore {
    override suspend fun read(): Long =
        if (Files.exists(path)) Files.readString(path).trim().toLongOrNull()
            ?: error("Host apply checkpoint is corrupt: $path") else 0

    override suspend fun write(sequence: Long) {
        require(sequence >= read()) { "Host apply checkpoint cannot move backwards" }
        path.parent?.let(Files::createDirectories)
        val temp = path.resolveSibling(".${path.fileName}.tmp")
        java.io.FileOutputStream(temp.toFile()).use {
            it.write(sequence.toString().encodeToByteArray())
            it.fd.sync()
        }
        runCatching {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
