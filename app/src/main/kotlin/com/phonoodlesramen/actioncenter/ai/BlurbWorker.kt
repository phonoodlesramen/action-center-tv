package com.phonoodlesramen.actioncenter.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.phonoodlesramen.actioncenter.ActionCenterApp
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Writes the night's AI blurbs in the background (M11): never on the UI path, and it never throws. */
class BlurbWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when ((applicationContext as ActionCenterApp).container.blurbWriter.write()) {
        // After the retries, the failure is on Settings' line; the nightly schedule stays either way.
        BlurbOutcome.RETRY -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        BlurbOutcome.SKIPPED, BlurbOutcome.WRITTEN, BlurbOutcome.FAILED -> Result.success()
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}

/** When [BlurbWorker] runs (M11): once a night, and now when asked. */
interface BlurbSchedule {
    /** Once a night at about [AT], on an unmetered network. Idempotent: an existing schedule is kept. */
    fun nightly()

    /** Once now (Write now, or just turned on with a key), on an unmetered network; replaces a run that's waiting. */
    fun runNow()

    /** Turned off: neither runs again until it's back on. */
    fun cancel()

    companion object {
        val AT: LocalTime = LocalTime.of(4, 0)

        /** From [now] until the next [at] on the local clock (tomorrow's if it has passed). */
        fun delayUntil(at: LocalTime, now: ZonedDateTime): Duration {
            val today = now.toLocalDate().atTime(at).atZone(now.zone)
            val next = if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(at).atZone(now.zone)
            return Duration.between(now, next)
        }
    }
}

/**
 * [BlurbSchedule] with WorkManager: a 24-hour periodic request whose first run waits for
 * the next 4 AM, and a one-off request for now. Both need an unmetered network, and back
 * off for 10 minutes and more between retries.
 */
class WorkBlurbSchedule(
    private val workManager: () -> WorkManager,
    private val clock: () -> ZonedDateTime = ZonedDateTime::now,
) : BlurbSchedule {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

    override fun nightly() {
        workManager().enqueueUniquePeriodicWork(
            NIGHTLY,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequest.Builder(BlurbWorker::class.java, 24, TimeUnit.HOURS)
                .setInitialDelay(BlurbSchedule.delayUntil(BlurbSchedule.AT, clock()))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build(),
        )
    }

    override fun runNow() {
        workManager().enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequest.Builder(BlurbWorker::class.java)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build(),
        )
    }

    override fun cancel() {
        workManager().cancelUniqueWork(NIGHTLY)
        workManager().cancelUniqueWork(NOW)
    }

    private companion object {
        const val NIGHTLY = "ai-blurbs"
        const val NOW = "ai-blurbs-now"
        const val BACKOFF_MINUTES = 10L
    }
}
