package lansync.server

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lansync.api.MusicLibraryAdapter
import lansync.engine.ConflictResolver
import lansync.model.ChangeBatch
import lansync.model.LibrarySnapshot
import lansync.model.PushChangesResponse
import lansync.model.SyncChange
import lansync.storage.HostChangeLog
import lansync.transport.SyncMessageCodec

class LanSyncHost(
    private val adapter: MusicLibraryAdapter,
    private val changeLog: HostChangeLog,
    private val applyCheckpoint: HostApplyCheckpointStore,
    private val codec: SyncMessageCodec = SyncMessageCodec(),
) {
    private val mutex = Mutex()
    private val _changes = MutableSharedFlow<Long>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val changes: Flow<Long> = _changes.asSharedFlow()

    suspend fun recover() = mutex.withLock { replayPendingLocked() }

    suspend fun acceptChanges(authenticatedDeviceId: String, changes: List<SyncChange>): PushChangesResponse =
        mutex.withLock {
            require(changes.size <= MAX_PUSH_CHANGES) { "Too many changes in one request" }
            changes.forEach { change ->
                require(change.sourceDeviceId == authenticatedDeviceId) { "Source device does not match credential" }
                codec.decode(change)
            }
            val result = changeLog.append(changes)
            replayPendingLocked()
            if (result.acknowledgedOperationIds.isNotEmpty()) _changes.tryEmit(result.latestSequence)
            PushChangesResponse(result.latestSequence, result.acknowledgedOperationIds)
        }

    suspend fun changesAfter(sequence: Long, limit: Int): ChangeBatch =
        changeLog.changesAfter(sequence, limit.coerceIn(1, MAX_PULL_CHANGES))

    suspend fun snapshot(): LibrarySnapshot = mutex.withLock {
        materializeLogLocked().copy(sequence = changeLog.latestSequence())
    }

    suspend fun latestSequence(): Long = changeLog.latestSequence()

    private suspend fun replayPendingLocked() {
        val applied = applyCheckpoint.read()
        val latest = changeLog.latestSequence()
        if (applied >= latest) return
        val current = adapter.readSnapshot()
        val materialized = materializeLogLocked()
        adapter.applyRemoteChanges(ConflictResolver.merge(current, materialized))
        applyCheckpoint.write(latest)
    }

    private suspend fun materializeLogLocked(): LibrarySnapshot {
        var cursor = 0L
        var snapshot = LibrarySnapshot()
        while (true) {
            val batch = changeLog.changesAfter(cursor, MAX_PULL_CHANGES)
            if (batch.changes.isEmpty()) return snapshot
            batch.changes.forEach {
                snapshot = ConflictResolver.merge(snapshot, codec.decode(it.change))
                cursor = it.sequence
            }
        }
    }

    companion object {
        const val MAX_PUSH_CHANGES = 500
        const val MAX_PULL_CHANGES = 500
    }
}
