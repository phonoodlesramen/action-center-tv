package com.phonoodlesramen.actioncenter.ui.wallpaper.motion

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import androidx.compose.ui.input.key.KeyEvent
import com.phonoodlesramen.actioncenter.ui.wallpaper.NowShowingState
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Now showing's idle rule and its key handling, with real Android key events. */
@RunWith(RobolectricTestRunner::class)
class IdleTest {
    private var now = 1_000_000L
    private val state = NowShowingState { now }

    private fun key(action: Int, code: Int, repeat: Int = 0) =
        KeyEvent(android.view.KeyEvent(0L, 0L, action, code, repeat))

    private fun press(code: Int): List<Boolean> = listOf(
        state.onKey(key(ACTION_DOWN, code)),
        state.onKey(key(ACTION_DOWN, code, repeat = 1)),
        state.onKey(key(ACTION_UP, code)),
    )

    @Test
    fun `keys pass through while the cards show`() {
        assertEquals(listOf(false, false, false), press(KEYCODE_DPAD_CENTER))
        assertFalse(state.showing)
    }

    @Test
    fun `the first press while it shows only brings the cards back, and none of it reaches them`() {
        state.show()

        // OK would otherwise click Play on its release.
        assertEquals(listOf(true, true, true), press(KEYCODE_DPAD_CENTER))
        assertFalse(state.showing)
        // The next press is the cards' again.
        assertEquals(listOf(false, false, false), press(KEYCODE_DPAD_DOWN))
    }

    @Test
    fun `Back while it shows doesn't leave the home`() {
        state.show()

        assertEquals(listOf(true, true, true), press(KEYCODE_BACK))
    }

    @Test
    fun `a release whose press came before it showed is swallowed and wakes it`() {
        state.show()

        assertTrue(state.onKey(key(ACTION_UP, KEYCODE_DPAD_DOWN)))
        assertFalse(state.showing)
    }

    @Test
    fun `a swallowed press whose release never came doesn't eat the next press`() {
        state.show()
        assertTrue(state.onKey(key(ACTION_DOWN, KEYCODE_DPAD_CENTER)))

        // The release went elsewhere (the home was paused); a fresh press works.
        assertFalse(state.onKey(key(ACTION_DOWN, KEYCODE_DPAD_CENTER)))
        assertFalse(state.onKey(key(ACTION_UP, KEYCODE_DPAD_CENTER)))
    }

    @Test
    fun `idle counts from the later of the last key and the last wake`() {
        val policy = IdlePolicy.minutes(5)

        assertFalse(policy.isIdle(nowMillis = 299_999, lastInputMillis = 0, wokeAtMillis = 0))
        assertTrue(policy.isIdle(nowMillis = 300_000, lastInputMillis = 0, wokeAtMillis = 0))
        assertFalse(policy.isIdle(nowMillis = 300_000, lastInputMillis = 0, wokeAtMillis = 60_000))
        assertFalse(policy.isIdle(nowMillis = 300_000, lastInputMillis = 60_000, wokeAtMillis = 0))
    }

    @Test
    fun `it looks again every 15 seconds at most, sooner when idle is close`() {
        val policy = IdlePolicy.minutes(5)

        assertEquals(15_000L, policy.nextCheckMillis(nowMillis = 0, lastInputMillis = 0, wokeAtMillis = 0))
        assertEquals(4_000L, policy.nextCheckMillis(nowMillis = 296_000, lastInputMillis = 0, wokeAtMillis = 0))
        assertEquals(1_000L, policy.nextCheckMillis(nowMillis = 299_900, lastInputMillis = 0, wokeAtMillis = 0))
        assertEquals(60_000L, IdlePolicy.minutes(0).timeoutMillis)
    }

    @Test
    fun `watch shows it after the idle minutes, and a key press starts the count again`() = runTest {
        now = 0L
        val watched = NowShowingState { testScheduler.currentTime }
        var lastInput = 0L
        val job = launch { watched.watch(IdlePolicy.minutes(5)) { lastInput } }

        advanceTimeBy(4 * 60_000L)
        runCurrent()
        assertFalse(watched.showing)
        // A key at 4 minutes: still not idle at 5.
        lastInput = testScheduler.currentTime
        advanceTimeBy(60_000L)
        runCurrent()
        assertFalse(watched.showing)
        advanceTimeBy(4 * 60_000L + 15_000L)
        runCurrent()
        assertTrue(watched.showing)

        // Dismissed: idle again only after another five minutes.
        watched.onKey(key(ACTION_DOWN, KEYCODE_DPAD_CENTER))
        runCurrent()
        assertFalse(watched.showing)
        advanceTimeBy(4 * 60_000L)
        runCurrent()
        assertFalse(watched.showing)
        advanceTimeBy(60_000L + 15_000L)
        runCurrent()
        assertTrue(watched.showing)

        // Leaving the home (cancelling the watch) gives the cards back.
        job.cancel()
        runCurrent()
        assertFalse(watched.showing)
    }
}
