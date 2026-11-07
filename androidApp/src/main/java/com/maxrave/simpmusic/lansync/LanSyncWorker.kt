package com.maxrave.simpmusic.lansync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlin.coroutines.cancellation.CancellationException
import lansync.api.LanSyncController
import lansync.api.LanSyncStatus
import org.koin.core.context.GlobalContext

class LanSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val controller = runCatching { GlobalContext.get().get<LanSyncController>() }
            .getOrElse { return Result.retry() }
        controller.initialize()
        if (!controller.state.value.enabled || controller.trustedDevices.value.none { it.autoSync }) {
            return Result.success()
        }
        return try {
            controller.syncNow()
            when (controller.state.value.status) {
                LanSyncStatus.UP_TO_DATE, LanSyncStatus.CONNECTED -> Result.success()
                LanSyncStatus.NOT_PAIRED, LanSyncStatus.DISABLED -> Result.success()
                else -> Result.retry()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Result.retry()
        }
    }
}
