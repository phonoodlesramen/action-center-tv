package com.phonoodlesramen.actioncenter.weather

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.phonoodlesramen.actioncenter.ActionCenterApp

/** Fetches the weather in the background, so the home screen never waits on the network. */
class WeatherWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val weather = (applicationContext as ActionCenterApp).container.weather
        return when (weather.refresh()) {
            Refresh.FAILED -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            Refresh.UPDATED, Refresh.NO_PLACE -> Result.success()
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}
