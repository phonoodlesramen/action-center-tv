package com.phonoodlesramen.actioncenter.ui.wallpaper.motion

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * One idle rule for every screen that takes over when the remote is left alone: Now
 * showing today, the Plex Guide later. Idle means no key for [timeoutMillis] since the
 * later of the last key and the last wake (the screen coming back, or the takeover being
 * dismissed).
 *
 * Nothing here holds the screen on (no `FLAG_KEEP_SCREEN_ON`, no wake lock): the Shield's
 * own screensaver stays the backstop for the TV.
 */
class IdlePolicy(val timeoutMillis: Long, val pollMillis: Long = POLL_MILLIS) {
    fun isIdle(nowMillis: Long, lastInputMillis: Long, wokeAtMillis: Long): Boolean =
        nowMillis - max(lastInputMillis, wokeAtMillis) >= timeoutMillis

    /** How long to wait before looking again: until idle is due, but at most [pollMillis] and at least a second. */
    fun nextCheckMillis(nowMillis: Long, lastInputMillis: Long, wokeAtMillis: Long): Long =
        (max(lastInputMillis, wokeAtMillis) + timeoutMillis - nowMillis).coerceIn(MIN_WAIT_MILLIS, max(MIN_WAIT_MILLIS, pollMillis))

    companion object {
        /** Looking every 15 seconds is plenty for a timeout in minutes, and costs nothing. */
        const val POLL_MILLIS = 15_000L
        private const val MIN_WAIT_MILLIS = 1_000L

        fun minutes(minutes: Int) = IdlePolicy(minutes.coerceAtLeast(1) * 60_000L)
    }
}

/**
 * A screen that takes over when the home is idle ([IdlePolicy]) and gives it back on the
 * first key press, which it swallows: down, repeats and up, so the card underneath never
 * sees half a click.
 */
@Stable
open class IdleTakeover(private val clock: () -> Long = System::currentTimeMillis) {
    /** Compose reads this to draw; the watch loop waits on [shown]. */
    var showing by mutableStateOf(false)
        private set
    private val shown = MutableStateFlow(false)
    private var wokeAtMillis = clock()
    private val swallowed = mutableSetOf<Long>()

    fun show() {
        showing = true
        shown.value = true
    }

    /** Back to the screen underneath; idle counts again from now. */
    fun wake() {
        wokeAtMillis = clock()
        showing = false
        shown.value = false
    }

    /** For every key on the screen, before anything else sees it. True swallows it. */
    fun onKey(event: KeyEvent): Boolean = onKey(
        down = event.type == KeyEventType.KeyDown,
        up = event.type == KeyEventType.KeyUp,
        repeat = event.nativeKeyEvent.repeatCount > 0,
        keyCode = event.key.keyCode,
    )

    internal fun onKey(down: Boolean, up: Boolean, repeat: Boolean, keyCode: Long): Boolean = when {
        // The press that ends the takeover: swallow its repeats and its release too.
        down && showing -> {
            swallowed += keyCode
            wake()
            true
        }
        down && repeat -> keyCode in swallowed
        down -> {
            // A new press: a release that never came (the screen left meanwhile) is forgotten.
            swallowed -= keyCode
            false
        }
        up && keyCode in swallowed -> {
            swallowed -= keyCode
            true
        }
        // A release without its press (held from before) mustn't reach the cards either.
        up && showing -> {
            wake()
            true
        }
        else -> false
    }

    /**
     * Shows the takeover each time the screen goes idle, until cancelled. Run it only
     * while the screen is resumed: coming back counts as a wake, and leaving (Home,
     * another app, the screensaver) gives the screen back.
     *
     * @param lastInputMillis when the remote was last pressed (wall clock).
     */
    suspend fun watch(policy: IdlePolicy, lastInputMillis: () -> Long) {
        wake()
        swallowed.clear()
        try {
            while (true) {
                val now = clock()
                val last = lastInputMillis()
                if (policy.isIdle(now, last, wokeAtMillis)) {
                    show()
                    shown.first { !it }
                } else {
                    delay(policy.nextCheckMillis(now, last, wokeAtMillis))
                }
            }
        } finally {
            if (showing) wake()
        }
    }
}
