package lansync.adapter

import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.PairSongLocalPlaylist
import kotlinx.coroutines.flow.Flow

data class LocalPlaylistLibrary(
    val playlists: List<LocalPlaylistEntity>,
    val entries: List<PairSongLocalPlaylist>,
)

interface PlaylistsRepository {
    fun observeLibrary(): Flow<LocalPlaylistLibrary>
    suspend fun readLibrary(): LocalPlaylistLibrary
    suspend fun insertPlaylist(playlist: LocalPlaylistEntity): Long
    suspend fun updatePlaylist(id: Long, title: String, thumbnail: String?)
    suspend fun replaceEntries(id: Long, videoIds: List<String>)
    suspend fun deletePlaylist(id: Long)
}
