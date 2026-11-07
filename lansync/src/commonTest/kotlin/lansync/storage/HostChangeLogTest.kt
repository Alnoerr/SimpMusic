package lansync.storage

import lansync.model.LogicalVersion
import lansync.model.SyncChange
import lansync.model.SyncEntityType
import lansync.model.SyncOperationType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostChangeLogTest {
    @Test
    fun duplicatePushIsAcknowledgedWithoutAllocatingAnotherSequence() = runTest {
        val log = InMemoryHostChangeLog()
        val change = change("op-1", 1)

        val first = log.append(listOf(change))
        val duplicate = log.append(listOf(change))

        assertEquals(1, first.latestSequence)
        assertEquals(1, duplicate.latestSequence)
        assertEquals(setOf("op-1"), duplicate.acknowledgedOperationIds)
        assertEquals(listOf(1L), log.changesAfter(0).changes.map { it.sequence })
    }

    @Test
    fun changesAreReturnedAfterCursorInDurableOrder() = runTest {
        val log = InMemoryHostChangeLog()
        log.append(listOf(change("op-1", 1), change("op-2", 2), change("op-3", 3)))

        val batch = log.changesAfter(sequence = 1, limit = 1)

        assertEquals(3, batch.latestSequence)
        assertEquals(listOf("op-2"), batch.changes.map { it.change.operationId })
        assertTrue(!batch.snapshotRequired)
    }

    private fun change(id: String, counter: Long) = SyncChange(
        operationId = id,
        sourceDeviceId = "phone",
        entityType = SyncEntityType.FAVORITE,
        entityId = "ytmusic:song",
        version = LogicalVersion(counter, "phone"),
        operation = SyncOperationType.UPSERT,
        payload = "{}",
    )
}
