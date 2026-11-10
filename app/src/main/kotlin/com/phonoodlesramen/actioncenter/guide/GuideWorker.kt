package com.phonoodlesramen.actioncenter.guide

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.phonoodlesramen.actioncenter.ActionCenterApp
import com.phonoodlesramen.actioncenter.safety.summary
import kotlin.coroutines.cancellation.CancellationException

/**
 * Extends the Plex Guide's schedule in the background every few hours, so the next 24
 * hours stay covered while the home isn't showing. It reads only the cache. While the TV is
 * paired with a guide station (Phase 8), it checks the station's copy instead.
 */
class GuideWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as ActionCenterApp).container.keepGuideFresh()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(GuideEngine.LOG_TAG, "guide worker: ${e.summary()}")
        Result.failure()
    }
}
