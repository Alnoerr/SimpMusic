package lansync.adapter

import com.maxrave.domain.data.entities.SongEntity
import kotlinx.coroutines.flow.Flow

interface FavoritesRepository {
    fun observeSongs(): Flow<List<SongEntity>>
    suspend fun readSongs(): List<SongEntity>
    suspend fun getSong(videoId: String): SongEntity?
    suspend fun insertSong(song: SongEntity)
    suspend fun applyLocalLikeStatus(videoId: String, liked: Boolean)
}
