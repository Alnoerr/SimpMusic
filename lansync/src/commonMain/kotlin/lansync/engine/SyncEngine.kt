package lansync.engine

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lansync.api.LanSyncTransport
import lansync.api.MusicLibraryAdapter
import lansync.model.LibrarySnapshot
import lansync.storage.Outbox
import lansync.storage.SyncJournal
import kotlin.time.Duration.Companion.milliseconds

enum class SyncEngineState {
    IDLE,
    CONNECTING,
    SYNCHRONIZING,
    UP_TO_DATE,
    OFFLINE,
    ERROR,
    STOPPED,
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
class SyncEngine(
    private val adapter: MusicLibraryAdapter,
    private val transport: LanSyncTransport,
    private val journal: SyncJournal,
    deviceId: String,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val outbox = Outbox(journal)
    private val tracker = ChangeTracker(deviceId)
    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private val _state = MutableStateFlow(SyncEngineState.IDLE)
    private val _lastError = MutableStateFlow<String?>(null)
    private var watcher: Job? = null
    private var previousLocal: LibrarySnapshot? = null

    val state: StateFlow<SyncEngineState> = _state.asStateFlow()
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    val isRunning: Boolean get() = watcher?.isActive == true

    suspend fun start() {
        mutex.withLock {
            if (watcher?.isActive == true) return
            reconcileLocked()
            watcher = scope.launch { watch() }
        }
    }

    suspend fun syncNow() = mutex.withLock { reconcileLocked() }

    suspend fun requestSync() {
        triggers.trySend(Unit)
        if (watcher?.isActive != true) syncNow()
    }

    suspend fun stop() {
        mutex.withLock {
            watcher?.cancel()
            watcher = null
            _state.value = SyncEngineState.STOPPED
        }
        transport.close()
    }

    private suspend fun reconcileLocked() {
        _state.value = SyncEngineState.SYNCHRONIZING
        try {
            var local = adapter.readSnapshot()
            val state = journal.read()
            if (!state.initialReconciliationComplete) {
                val remote = transport.getSnapshot()
                val merged = ConflictResolver.merge(local, remote, initialImport = true)
                adapter.applyRemoteChanges(merged)
                outbox.enqueue(tracker.diff(remote, merged))
                previousLocal = merged
                local = merged
            } else {
                // After a process restart there is no in-memory baseline. Re-offer the current
                // snapshot: deterministic operation IDs let the host deduplicate old state while
                // still capturing edits made while this process was stopped.
                val baseline = previousLocal ?: LibrarySnapshot()
                outbox.enqueue(tracker.diff(baseline, local))
                previousLocal = local
            }

            pushPendingLocked()
            local = pullPendingLocked(local)

            val updated = journal.read()
            if (!updated.initialReconciliationComplete) {
                journal.write(updated.copy(initialReconciliationComplete = true))
            }
            // Keep the exact state processed by this pass. Re-reading Room here can swallow a
            // local edit that arrives while remote changes are being applied.
            previousLocal = local
            _lastError.value = null
            _state.value = SyncEngineState.UP_TO_DATE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _lastError.value = error.message ?: error::class.simpleName
            _state.value = SyncEngineState.OFFLINE
            throw error
        }
    }

    private suspend fun pushPendingLocked() {
        while (true) {
            val pending = outbox.pending()
            if (pending.isEmpty()) return
            val result = transport.pushChanges(pending)
            outbox.confirm(result.acknowledgedOperationIds)
            check(result.acknowledgedOperationIds.isNotEmpty()) { "Host acknowledged no pending operations" }
        }
    }

    private suspend fun pullPendingLocked(startingSnapshot: LibrarySnapshot): LibrarySnapshot {
        var cursor = journal.read().remoteCursor
        var current = startingSnapshot
        while (true) {
            val batch = transport.getChanges(cursor)
            if (batch.snapshotRequired) {
                current = ConflictResolver.merge(current, transport.getSnapshot())
                adapter.applyRemoteChanges(current)
                cursor = current.sequence
                val state = journal.read()
                journal.write(state.copy(remoteCursor = cursor))
                continue
            }
            if (batch.changes.isEmpty()) return current
            var incoming = LibrarySnapshot()
            batch.changes.forEach { sequenced ->
                incoming = ConflictResolver.merge(incoming, SyncMessageCodecHolder.codec.decode(sequenced.change))
            }
            current = ConflictResolver.merge(current, incoming)
            adapter.applyRemoteChanges(current)
            cursor = batch.changes.last().sequence
            val state = journal.read()
            journal.write(state.copy(remoteCursor = cursor))
        }
    }

    private suspend fun watch() = coroutineScope {
        launch {
            adapter.observeLibrary().debounce(250.milliseconds).collectLatest { triggers.trySend(Unit) }
        }
        launch {
            transport.observeRemoteChanges().collectLatest { triggers.trySend(Unit) }
        }
        launch {
            for (ignored in triggers) {
                mutex.withLock {
                    runCatching { reconcileLocked() }
                        .onFailure { if (it is CancellationException) throw it }
                }
            }
        }
    }

    private object SyncMessageCodecHolder {
        val codec = lansync.transport.SyncMessageCodec()
    }
}
