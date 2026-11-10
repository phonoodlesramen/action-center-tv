package com.phonoodlesramen.actioncenter.plex.cache

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.phonoodlesramen.actioncenter.ActionCenterApp

/** Syncs the Plex cache in the background: every 30 minutes, and when the home comes back after a minute or more. */
class PlexSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when ((applicationContext as ActionCenterApp).container.plexSync.sync()) {
        // After the retries, report the failure: WorkManager keeps the periodic schedule either way.
        SyncResult.OFFLINE, SyncResult.FAILED -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        SyncResult.UPDATED, SyncResult.NOT_LINKED, SyncResult.SIGNED_OUT -> Result.success()
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}
