package lansync.storage

import lansync.model.ChangeBatch
import lansync.model.SequencedChange
import lansync.model.SyncChange
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

interface HostChangeLog {
    suspend fun append(changes: List<SyncChange>): AppendResult
    suspend fun changesAfter(sequence: Long, limit: Int = 500): ChangeBatch
    suspend fun latestSequence(): Long
}

data class AppendResult(
    val latestSequence: Long,
    val acknowledgedOperationIds: Set<String>,
)

@Serializable
private data class HostChangeLogState(
    val schemaVersion: Int = 1,
    val nextSequence: Long = 1,
    val entries: List<SequencedChange> = emptyList(),
)

class InMemoryHostChangeLog : HostChangeLog {
    private val mutex = Mutex()
    private var state = HostChangeLogState()

    override suspend fun append(changes: List<SyncChange>): AppendResult =
        mutex.withLock {
            val result = appendTo(state, changes)
            state = result.first
            result.second
        }

    override suspend fun changesAfter(sequence: Long, limit: Int): ChangeBatch =
        mutex.withLock { state.batchAfter(sequence, limit) }

    override suspend fun latestSequence(): Long = mutex.withLock { state.nextSequence - 1 }
}

class JsonFileHostChangeLog(
    private val path: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
) : HostChangeLog {
    private val mutex = Mutex()

    override suspend fun append(changes: List<SyncChange>): AppendResult = mutex.withLock {
        val current = readLocked()
        val result = appendTo(current, changes)
        if (result.first != current) writeLocked(result.first)
        result.second
    }

    override suspend fun changesAfter(sequence: Long, limit: Int): ChangeBatch =
        mutex.withLock { readLocked().batchAfter(sequence, limit) }

    override suspend fun latestSequence(): Long = mutex.withLock { readLocked().nextSequence - 1 }

    private fun readLocked(): HostChangeLogState {
        if (!fileSystem.exists(path)) return HostChangeLogState()
        return try {
            fileSystem.read(path) { json.decodeFromString<HostChangeLogState>(readUtf8()) }
        } catch (cause: Throwable) {
            throw IllegalStateException("LAN sync host change log is corrupt: $path", cause)
        }
    }

    private fun writeLocked(state: HostChangeLogState) {
        path.parent?.let(fileSystem::createDirectories)
        val temp = path.parent?.resolve(".${path.name}.tmp") ?: ".${path.name}.tmp".toPath()
        fileSystem.write(temp) { writeUtf8(json.encodeToString(HostChangeLogState.serializer(), state)) }
        fileSystem.atomicMove(temp, path)
    }
}

private fun appendTo(
    state: HostChangeLogState,
    changes: List<SyncChange>,
): Pair<HostChangeLogState, AppendResult> {
    if (changes.isEmpty()) {
        return state to AppendResult(state.nextSequence - 1, emptySet())
    }
    val existingIds = state.entries.mapTo(mutableSetOf()) { it.change.operationId }
    val acceptedIds = linkedSetOf<String>()
    val appended = mutableListOf<SequencedChange>()
    var next = state.nextSequence
    changes.forEach { change ->
        acceptedIds += change.operationId
        if (existingIds.add(change.operationId)) {
            appended += SequencedChange(next++, change)
        }
    }
    val updated = if (appended.isEmpty()) state else state.copy(
        nextSequence = next,
        entries = state.entries + appended,
    )
    return updated to AppendResult(updated.nextSequence - 1, acceptedIds)
}

private fun HostChangeLogState.batchAfter(sequence: Long, limit: Int): ChangeBatch {
    require(sequence >= 0) { "Sequence must be non-negative" }
    require(limit in 1..5_000) { "Limit must be between 1 and 5000" }
    val latest = nextSequence - 1
    return ChangeBatch(
        afterSequence = sequence,
        latestSequence = latest,
        changes = entries.asSequence().filter { it.sequence > sequence }.take(limit).toList(),
    )
}
