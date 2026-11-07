package lansync.adapter

import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.repository.LocalPlaylistRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

class LocalPlaylistRepositoryAdapter(
    private val repository: LocalPlaylistRepository,
) : PlaylistsRepository {
    override fun observeLibrary() =
        combine(
            repository.observeLocalPlaylistsForSync(),
            repository.observeLocalPlaylistEntriesForSync(),
            ::LocalPlaylistLibrary,
        )

    override suspend fun readLibrary() = observeLibrary().first()

    override suspend fun insertPlaylist(playlist: LocalPlaylistEntity) =
        repository.insertLocalPlaylistForSync(playlist)

    override suspend fun updatePlaylist(id: Long, title: String, thumbnail: String?) =
        repository.updateLocalPlaylistForSync(id, title, thumbnail)

    override suspend fun replaceEntries(id: Long, videoIds: List<String>) =
        repository.replaceLocalPlaylistEntriesForSync(id, videoIds)

    override suspend fun deletePlaylist(id: Long) =
        repository.deleteLocalPlaylistForSync(id)
}
