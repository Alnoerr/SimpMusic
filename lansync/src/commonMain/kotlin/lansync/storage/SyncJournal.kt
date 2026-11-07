package lansync.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lansync.model.SyncChange
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

@Serializable
data class SyncJournalState(
    val schemaVersion: Int = 1,
    val outbox: List<SyncChange> = emptyList(),
    val confirmedOperationIds: Set<String> = emptySet(),
    val remoteCursor: Long = 0,
    val initialReconciliationComplete: Boolean = false,
)

interface SyncJournal {
    suspend fun read(): SyncJournalState
    suspend fun write(state: SyncJournalState)
}

class InMemorySyncJournal(initial: SyncJournalState = SyncJournalState()) : SyncJournal {
    private val mutex = Mutex()
    private var state = initial
    override suspend fun read(): SyncJournalState = mutex.withLock { state }
    override suspend fun write(state: SyncJournalState) {
        mutex.withLock { this.state = state }
    }
}

class JsonFileSyncJournal(
    private val path: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
) : SyncJournal {
    private val mutex = Mutex()

    override suspend fun read(): SyncJournalState = mutex.withLock {
        if (!fileSystem.exists(path)) return@withLock SyncJournalState()
        try {
            fileSystem.read(path) { json.decodeFromString<SyncJournalState>(readUtf8()) }
        } catch (cause: Throwable) {
            throw IllegalStateException("LAN sync journal is corrupt: $path", cause)
        }
    }

    override suspend fun write(state: SyncJournalState) = mutex.withLock {
        path.parent?.let(fileSystem::createDirectories)
        val temp = path.parent?.resolve(".${path.name}.tmp") ?: ".${path.name}.tmp".toPath()
        fileSystem.write(temp) {
            writeUtf8(json.encodeToString(SyncJournalState.serializer(), state))
            flush()
        }
        fileSystem.atomicMove(temp, path)
    }
}

class Outbox(private val journal: SyncJournal) {
    suspend fun enqueue(changes: List<SyncChange>) {
        if (changes.isEmpty()) return
        val state = journal.read()
        val values = state.outbox.associateByTo(linkedMapOf(), SyncChange::operationId)
        changes.forEach { values.putIfAbsent(it.operationId, it) }
        journal.write(state.copy(outbox = values.values.toList()))
    }

    suspend fun pending(limit: Int = 500): List<SyncChange> = journal.read().outbox.take(limit)

    suspend fun confirm(ids: Set<String>) {
        if (ids.isEmpty()) return
        val state = journal.read()
        journal.write(
            state.copy(
                outbox = state.outbox.filterNot { it.operationId in ids },
                confirmedOperationIds = state.confirmedOperationIds + ids,
            ),
        )
    }
}
