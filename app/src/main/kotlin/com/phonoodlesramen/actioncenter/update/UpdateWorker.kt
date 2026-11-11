package com.phonoodlesramen.actioncenter.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.phonoodlesramen.actioncenter.ActionCenterApp
import java.util.concurrent.TimeUnit

/**
 * The daily check for updates, and Install automatically's download ([DOWNLOAD] in its input).
 * Only Install automatically downloads by itself.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val updates = (applicationContext as ActionCenterApp).container.updates
        // A failure is About's line, and the next day's check tries again: never a crash, never a retry storm.
        runCatching { if (inputData.getBoolean(DOWNLOAD, false)) updates.downloadAhead() else updates.check() }
        return Result.success()
    }

    companion object {
        const val DOWNLOAD = "download"
    }
}

/** When [UpdateWorker] runs. */
interface UpdateSchedule {
    /** Once a day, with a network. Idempotent: an existing schedule is kept. */
    fun daily()

    /** Updates is Off: no daily check. */
    fun cancel()

    /** Install automatically found one: download it once there's an unmetered network and room. */
    fun downloadSoon()
}

/**
 * [UpdateSchedule] with WorkManager: the check every 24 hours on any network, the first a little
 * after the app first starts; the download once, on an unmetered network with storage not low.
 */
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
        workManager().cancelUniqueWork(DOWNLOAD_NAME)
    }

    override fun downloadSoon() {
        workManager().enqueueUniqueWork(
            DOWNLOAD_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(UpdateWorker::class.java)
                .setInputData(workDataOf(UpdateWorker.DOWNLOAD to true))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true).build(),
                )
                .build(),
        )
    }

    private companion object {
        const val NAME = "update-check"
        const val DOWNLOAD_NAME = "update-download"
        const val FIRST_AFTER_MINUTES = 15L
    }
}
