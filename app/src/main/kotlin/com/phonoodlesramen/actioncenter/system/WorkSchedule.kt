package com.phonoodlesramen.actioncenter.system

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Background refresh for the caches the home screen reads (weather, Plex, the Plex
 * Guide's schedule): every 30 minutes while online by default, plus a one-off run when
 * the data is stale. The screen never waits on the network.
 */
class WorkSchedule(
    private val workManager: () -> WorkManager,
    private val name: String,
    private val worker: Class<out ListenableWorker>,
    /** How often it runs; WorkManager's minimum is 15 minutes. */
    private val everyMinutes: Long = 30,
    /** False for work on local data only, such as extending the Plex Guide. */
    needsNetwork: Boolean = true,
) {
    private val constraints =
        if (needsNetwork) Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build() else Constraints.NONE

    /** Idempotent: an existing schedule is kept. */
    fun keepFresh() {
        workManager().enqueueUniquePeriodicWork(
            name,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequest.Builder(worker, everyMinutes, TimeUnit.MINUTES).setConstraints(constraints).build(),
        )
    }

    /** [replace]: start over now (Sync now), instead of keeping a run that's waiting to retry. */
    fun refreshNow(replace: Boolean = false) {
        workManager().enqueueUniqueWork(
            "$name-now",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(worker).setConstraints(constraints).build(),
        )
    }
}
