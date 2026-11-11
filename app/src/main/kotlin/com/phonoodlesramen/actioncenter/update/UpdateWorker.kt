package com.phonoodlesramen.actioncenter.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.phonoodlesramen.actioncenter.ActionCenterApp
import java.util.concurrent.TimeUnit

/** The daily check for updates. It only checks: nothing downloads or installs without a press. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // A failure is About's line, and the next day's check tries again: never a crash, never a retry storm.
        runCatching { (applicationContext as ActionCenterApp).container.updates.check() }
        return Result.success()
    }
}

/** When [UpdateWorker] runs. */
interface UpdateSchedule {
    /** Once a day, with a network. Idempotent: an existing schedule is kept. */
    fun daily()

    /** Updates is Off: no daily check. */
    fun cancel()
}

/** [UpdateSchedule] with WorkManager: every 24 hours on any network, the first a little after the app first starts. */
class WorkUpdateSchedule(private val workManager: () -> WorkManager) : UpdateSchedule {
    override fun daily() {
        workManager().enqueueUniquePeriodicWork(
            NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequest.Builder(UpdateWorker::class.java, 24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(FIRST_AFTER_MINUTES, TimeUnit.MINUTES)
                .build(),
        )
    }

    override fun cancel() {
        workManager().cancelUniqueWork(NAME)
    }

    private companion object {
        const val NAME = "update-check"
        const val FIRST_AFTER_MINUTES = 15L
    }
}
