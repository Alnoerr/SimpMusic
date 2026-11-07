package lansync.server

import kotlinx.coroutines.flow.Flow
import lansync.api.LanSyncTransport
import lansync.model.ChangeBatch
import lansync.model.LibrarySnapshot
import lansync.model.PushChangesResponse
import lansync.model.SyncChange

class HostLocalTransport(
    private val hostDeviceId: String,
    private val host: LanSyncHost,
) : LanSyncTransport {
    override suspend fun getSnapshot(): LibrarySnapshot = host.snapshot()

    override suspend fun getChanges(afterSequence: Long, limit: Int): ChangeBatch =
        host.changesAfter(afterSequence, limit)

    override suspend fun pushChanges(changes: List<SyncChange>): PushChangesResponse =
        host.acceptChanges(hostDeviceId, changes)

    override fun observeRemoteChanges(): Flow<Long> = host.changes

    override suspend fun close() = Unit
}
