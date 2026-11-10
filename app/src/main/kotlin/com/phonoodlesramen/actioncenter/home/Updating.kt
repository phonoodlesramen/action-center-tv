package com.phonoodlesramen.actioncenter.home

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The top bar's "Updating" from a sync's running flag
 * ([com.phonoodlesramen.actioncenter.plex.cache.PlexSync.running]). It shows once a run has
 * gone on [SHOW_AFTER_MILLIS] and then stays [KEEP_MILLIS] at least, so a quick run (or
 * one with nothing to do) never blinks. Once the sync is done, it's gone.
 */
object Updating {
    const val SHOW_AFTER_MILLIS = 500L
    const val KEEP_MILLIS = 1_000L

    fun shown(running: Flow<Boolean>): Flow<Boolean> = channelFlow {
        val now = MutableStateFlow(false)
        launch { running.collect { now.value = it } }
        send(false)
        while (true) {
            now.first { it }
            delay(SHOW_AFTER_MILLIS)
            if (!now.value) continue
            send(true)
            delay(KEEP_MILLIS)
            now.first { !it }
            send(false)
        }
    }.distinctUntilChanged()
}
