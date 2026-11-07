package lansync.adapter

import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow

interface SavedPlaylistRepository {
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    suspend fun insertOrReplace(playlist: PlaylistEntity)
}

class PlaylistRepositorySavedPlaylistAdapter(
    private val repository: PlaylistRepository,
) : SavedPlaylistRepository {
    override fun observePlaylists(): Flow<List<PlaylistEntity>> = repository.observePlaylistsForSync()

    override suspend fun insertOrReplace(playlist: PlaylistEntity) {
        repository.insertOrReplacePlaylistForSync(playlist)
    }
}
