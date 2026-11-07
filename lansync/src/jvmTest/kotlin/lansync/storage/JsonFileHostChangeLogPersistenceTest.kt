package lansync.storage

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import lansync.model.LogicalVersion
import lansync.model.SyncChange
import lansync.model.SyncEntityType
import lansync.model.SyncOperationType
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonFileHostChangeLogPersistenceTest {
    @Test
    fun sequencesAndOperationDeduplicationSurviveReload() = runTest {
        val path = Files.createTempDirectory("simpmusic-host-log").resolve("host-log.json").toString().toPath()
        val first = JsonFileHostChangeLog(path)
        first.append(listOf(change("op-1", 1), change("op-2", 2)))
        assertEquals(2, first.latestSequence())

        val restarted = JsonFileHostChangeLog(path)
        assertEquals(2, restarted.latestSequence())
        assertEquals(2, restarted.append(listOf(change("op-1", 1))).latestSequence)
        assertEquals(3, restarted.append(listOf(change("op-3", 3))).latestSequence)
        assertEquals(
            listOf("op-1", "op-2", "op-3"),
            restarted.changesAfter(0).changes.map { it.change.operationId },
        )
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
