package lansync.adapter

import kotlinx.coroutines.flow.combine
import lansync.api.MusicLibraryAdapter
import lansync.model.LibrarySnapshot

class CompositeMusicLibraryAdapter(
    private val favorites: MusicLibraryAdapter,
    private val playlists: MusicLibraryAdapter,
) : MusicLibraryAdapter {
    override suspend fun readSnapshot() = merge(favorites.readSnapshot(), playlists.readSnapshot())

    override suspend fun applyRemoteChanges(snapshot: LibrarySnapshot) {
        favorites.applyRemoteChanges(snapshot)
        playlists.applyRemoteChanges(snapshot)
    }

    override fun observeLibrary() =
        combine(favorites.observeLibrary(), playlists.observeLibrary(), ::merge)

    private fun merge(first: LibrarySnapshot, second: LibrarySnapshot) = LibrarySnapshot(
        songs = (first.songs + second.songs).associateBy { it.id }.values.toList(),
        favorites = first.favorites,
        playlists = second.playlists,
        playlistEntries = second.playlistEntries,
        savedPlaylistReferences = second.savedPlaylistReferences,
        sequence = maxOf(first.sequence, second.sequence),
    )
}
