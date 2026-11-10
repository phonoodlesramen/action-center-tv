package com.phonoodlesramen.actioncenter.tvprovider

import android.app.Activity
import android.app.Application
import android.content.ContentResolver
import android.database.ContentObserver
import android.media.tv.TvContract
import android.os.Bundle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart

/**
 * When the rows are read again: once whenever the home screen comes to the front, then
 * on every change other apps make to the provider while it stays there (debounced, as an
 * app often rewrites many rows at once). Nothing is read while another app is in front.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
fun refreshes(inFront: Flow<Boolean>, providerChanges: Flow<Unit>, debounceMillis: Long = CHANGE_DEBOUNCE_MILLIS): Flow<Unit> =
    inFront.distinctUntilChanged().flatMapLatest { front ->
        if (front) providerChanges.debounce(debounceMillis).onStart { emit(Unit) } else emptyFlow()
    }

const val CHANGE_DEBOUNCE_MILLIS = 1_000L

/**
 * True while one of the app's activities is resumed. Starts true: the rows are first
 * asked for by the home screen as it opens, possibly after its own resume.
 */
class AppInFront(app: Application) {
    private val resumed = MutableStateFlow(true)
    val inFront: StateFlow<Boolean> = resumed

    init {
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    resumed.value = true
                }

                override fun onActivityPaused(activity: Activity) {
                    resumed.value = false
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}

/**
 * Other apps changing their Watch Next or preview rows. The provider is exported with
 * no read permission (AOSP TvProvider manifest), so observing needs no grant; if the
 * system refuses anyway, this just never fires and resumes still refresh.
 */
fun ContentResolver.tvProviderChanges(): Flow<Unit> = callbackFlow {
    val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            trySend(Unit)
        }
    }
    listOf(TvContract.WatchNextPrograms.CONTENT_URI, TvContract.PreviewPrograms.CONTENT_URI).forEach { uri ->
        runCatching { registerContentObserver(uri, true, observer) }
    }
    awaitClose { runCatching { unregisterContentObserver(observer) } }
}
