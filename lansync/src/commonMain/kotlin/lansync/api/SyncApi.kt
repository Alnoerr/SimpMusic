package lansync.api

import kotlinx.coroutines.flow.Flow
import lansync.model.LibrarySnapshot
import lansync.model.SyncChange

interface MusicLibraryAdapter {
    suspend fun readSnapshot(): LibrarySnapshot
    suspend fun applyRemoteChanges(snapshot: LibrarySnapshot)
    fun observeLibrary(): Flow<LibrarySnapshot>
}

interface LanSyncTransport {
    suspend fun getSnapshot(): LibrarySnapshot
    suspend fun getChanges(afterSequence: Long, limit: Int = 500): lansync.model.ChangeBatch
    suspend fun pushChanges(changes: List<SyncChange>): lansync.model.PushChangesResponse
    fun observeRemoteChanges(): Flow<Long>
    suspend fun close()
}
