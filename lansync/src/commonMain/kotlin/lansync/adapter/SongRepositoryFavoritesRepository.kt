package lansync.adapter

import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.repository.SongRepository
import kotlinx.coroutines.flow.first

class SongRepositoryFavoritesRepository(
    private val repository: SongRepository,
) : FavoritesRepository {
    override fun observeSongs() = repository.observeSongsForSync()
    override suspend fun readSongs() = repository.observeSongsForSync().first()
    override suspend fun getSong(videoId: String) = repository.getSongAsFlow(videoId).first()

    override suspend fun insertSong(song: SongEntity) {
        repository.insertSong(song).first()
    }

    override suspend fun applyLocalLikeStatus(videoId: String, liked: Boolean) {
        repository.applyLocalLikeStatus(videoId, liked)
    }
}
