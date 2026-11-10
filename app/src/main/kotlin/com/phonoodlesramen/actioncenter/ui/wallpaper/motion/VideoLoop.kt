package com.phonoodlesramen.actioncenter.ui.wallpaper.motion

import java.io.File

/** Video loop's file and player lifecycle, apart from ExoPlayer so they're unit-tested. */
object VideoLoopFiles {
    /**
     * The chosen file ([chosen], a name in the folder) if it's still there, else the first
     * video in the folder by name. Null with no videos, or no storage grant (an empty list).
     */
    fun choose(chosen: String?, files: List<File>): File? =
        chosen?.let { name -> files.firstOrNull { it.name == name } } ?: files.firstOrNull()
}

/** A player as [VideoLoopSession] drives it: ExoPlayer on the Shield, a fake in tests. */
interface LoopPlayer<S : Any> {
    /** Shows the video on [surface]; null takes it off. */
    fun attach(surface: S?)

    fun release()
}

/**
 * At most one player, and only between the home's ON_START and ON_STOP, so the loop never
 * holds a hardware decoder (or its memory) while another app plays.
 *
 * @param open makes a player for a file; it starts playing as soon as it can.
 * @param clock for [failed]'s give-up rule.
 */
class VideoLoopSession<S : Any>(
    private val open: (File) -> LoopPlayer<S>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var player: LoopPlayer<S>? = null
        private set

    /** What to play; a change while started swaps the player. */
    var file: File? = null
        set(value) {
            if (field == value) return
            field = value
            failures = 0
            if (started) reopen()
        }

    /** Where to show it; the player, if any, moves there. */
    var surface: S? = null
        set(value) {
            if (field === value) return
            field = value
            player?.attach(value)
        }

    private var started = false
    private var failures = 0
    private var lastFailureMillis = 0L

    fun start() {
        if (started) return
        started = true
        failures = 0
        reopen()
    }

    fun stop() {
        if (!started) return
        started = false
        close()
    }

    /**
     * The player hit an error (an unreadable or cut-short file). Starts the loop again from
     * the top, unless it failed again within [RETRY_WINDOW_MILLIS] [MAX_QUICK_FAILURES]
     * times running: then the loop stays off until the next start. True when it restarted.
     */
    fun failed(): Boolean {
        val now = clock()
        failures = if (now - lastFailureMillis < RETRY_WINDOW_MILLIS) failures + 1 else 1
        lastFailureMillis = now
        if (!started) return false
        return if (failures >= MAX_QUICK_FAILURES) {
            close()
            false
        } else {
            reopen()
            true
        }
    }

    private fun reopen() {
        close()
        val next = file ?: return
        player = open(next).also { it.attach(surface) }
    }

    private fun close() {
        player?.let {
            it.attach(null)
            it.release()
        }
        player = null
    }

    companion object {
        const val RETRY_WINDOW_MILLIS = 30_000L
        const val MAX_QUICK_FAILURES = 3
    }
}
