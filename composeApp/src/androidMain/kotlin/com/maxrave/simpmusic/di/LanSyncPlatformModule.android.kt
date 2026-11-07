package com.maxrave.simpmusic.di

import android.content.Context
import android.os.Build
import android.provider.Settings
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
import lansync.api.AndroidLanSyncController
import lansync.api.LanSyncController
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun lanSyncPlatformModule(): Module = module {
    single<FavoritesRepository> { SongRepositoryFavoritesRepository(get()) }
    single<PlaylistsRepository> { LocalPlaylistRepositoryAdapter(get()) }
    single<SavedPlaylistRepository> { PlaylistRepositorySavedPlaylistAdapter(get()) }
    single<LanSyncController> {
        val context = get<Context>()
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf(String::isNotBlank)
            ?: "android-${UUID.randomUUID()}"
        AndroidLanSyncController(
            context = context,
            repository = get(),
            playlistsRepository = get(),
            savedPlaylistsRepository = get(),
            defaultDeviceId = deviceId,
            localDisplayName = Build.MODEL.takeIf(String::isNotBlank) ?: "Android",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
}
