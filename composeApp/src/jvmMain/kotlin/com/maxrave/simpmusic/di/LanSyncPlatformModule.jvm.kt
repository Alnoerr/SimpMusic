package com.maxrave.simpmusic.di

import java.net.InetAddress
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import lansync.adapter.FavoritesRepository
import lansync.adapter.LocalPlaylistRepositoryAdapter
import lansync.adapter.PlaylistsRepository
import lansync.adapter.PlaylistRepositorySavedPlaylistAdapter
import lansync.adapter.SavedPlaylistRepository
import lansync.adapter.SongRepositoryFavoritesRepository
import lansync.api.DesktopLanSyncController
import lansync.api.LanSyncController
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun lanSyncPlatformModule(): Module = module {
    single<FavoritesRepository> { SongRepositoryFavoritesRepository(get()) }
    single<PlaylistsRepository> { LocalPlaylistRepositoryAdapter(get()) }
    single<SavedPlaylistRepository> { PlaylistRepositorySavedPlaylistAdapter(get()) }
    single<LanSyncController> {
        DesktopLanSyncController(
            repository = get(),
            playlistsRepository = get(),
            savedPlaylistsRepository = get(),
            defaultDeviceId = "desktop-${UUID.randomUUID()}",
            localDisplayName = runCatching { InetAddress.getLocalHost().hostName }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?: "SimpMusic Desktop",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
}
