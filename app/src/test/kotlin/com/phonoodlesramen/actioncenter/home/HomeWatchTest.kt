package com.phonoodlesramen.actioncenter.home

import android.view.KeyEvent
import com.phonoodlesramen.actioncenter.home.HomeWatch.KeyResult
import com.phonoodlesramen.actioncenter.home.HomeWatch.Open
import com.phonoodlesramen.actioncenter.home.HomeWatch.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWatchTest {
    private var now = 1_000_000L
    private val googleTv = HomeWatch.Screen("com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx.home.HomeActivity")
    private val watch = HomeWatch { now }.apply { stockHome = googleTv }

    /** A press and release of [keyCode], held [heldMillis]; what the release did. */
    private fun press(keyCode: Int, heldMillis: Long = 80): KeyResult {
        val down = watch.onKey(keyCode, down = true, repeatCount = 0, heldMillis = 0, canceled = false)
        assertEquals("down and up are taken or passed alike", keyCode in TAKEN, down.consume)
        now += heldMillis
        return watch.onKey(keyCode, down = false, repeatCount = 0, heldMillis = heldMillis, canceled = false)
    }

    private fun googleTvShows() = watch.onWindow(googleTv.packageName, googleTv.className)

    @Test
    fun `a Home press opens Action Center, and the system never sees it`() {
        assertEquals(KeyResult(consume = true, open = Open.ACTION_CENTER), press(KeyEvent.KEYCODE_HOME))
    }

    @Test
    fun `holding Home opens the TV's own home, which stays until Home is pressed again`() {
        assertEquals(KeyResult(consume = true, open = Open.STOCK_HOME), press(KeyEvent.KEYCODE_HOME, heldMillis = HomeWatch.HOLD_MILLIS))
        assertEquals(Window.CHOSEN, googleTvShows())

        // Into an app and Back out of it: still the home you chose.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        now += 60_000
        press(KeyEvent.KEYCODE_BACK)
        assertEquals(Window.CHOSEN, googleTvShows())

        assertEquals(Open.ACTION_CENTER, press(KeyEvent.KEYCODE_HOME).open)
        press(KeyEvent.KEYCODE_BACK)
        assertEquals(Window.OPEN, googleTvShows())
    }

    @Test
    fun `a cancelled Home opens nothing`() {
        watch.onKey(KeyEvent.KEYCODE_HOME, down = true, repeatCount = 0, heldMillis = 0, canceled = false)

        assertEquals(KeyResult(consume = true), watch.onKey(KeyEvent.KEYCODE_HOME, down = false, repeatCount = 0, heldMillis = 80, canceled = true))
    }

    @Test
    fun `the All apps button opens Action Center's All apps`() {
        assertEquals(KeyResult(consume = true, open = Open.ALL_APPS), press(KeyEvent.KEYCODE_ALL_APPS))
    }

    @Test
    fun `every other key passes`() {
        for (key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            assertEquals(KeyResult(consume = false), press(key))
        }
    }

    @Test
    fun `the TV's home showing up unasked gives way to Action Center, after Back, waking, or no key at all`() {
        press(KeyEvent.KEYCODE_BACK)
        assertEquals(Window.OPEN, googleTvShows())

        press(KeyEvent.KEYCODE_POWER)
        assertEquals(Window.OPEN, googleTvShows())

        // No key at all (the Assistant, an app's Home intent).
        val fresh = HomeWatch { now }.apply { stockHome = googleTv }
        assertEquals(Window.OPEN, fresh.onWindow(googleTv.packageName, googleTv.className))
    }

    @Test
    fun `right after one of the TV's own buttons its home stays, and the key is kept for the log`() {
        press(KeyEvent.KEYCODE_GUIDE)
        now += 1_000

        assertEquals(Window.OWN_BUTTON, googleTvShows())
        assertEquals(KeyEvent.KEYCODE_GUIDE, watch.ownButton)
        press(KeyEvent.KEYCODE_BACK)
        assertEquals("chosen now, until Home", Window.CHOSEN, googleTvShows())
    }

    @Test
    fun `a button pressed long before doesn't count`() {
        press(KeyEvent.KEYCODE_GUIDE)
        now += HomeWatch.OWN_BUTTON_MILLIS + 1

        assertEquals(Window.OPEN, googleTvShows())
    }

    @Test
    fun `only the TV's home itself, not its other screens or other apps`() {
        assertEquals(Window.OTHER, watch.onWindow(googleTv.packageName, "com.google.android.apps.tv.launcherx.entity.EntityActivity"))
        assertEquals(Window.OTHER, watch.onWindow("com.vt.arttv.artmode", "com.vt.arttv.artmode.ArtModeActivity"))
    }

    @Test
    fun `at start-up, the TV's home on screen opens Action Center, and nothing else does`() {
        assertTrue(watch.onStart(googleTv.packageName))
        assertFalse(watch.onStart("com.vt.tv.input"))
        assertFalse(watch.onStart(null))
    }

    @Test
    fun `where Action Center is the Home, every key passes and nothing opens`() {
        val shield = HomeWatch { now }

        assertEquals(KeyResult(consume = false), shield.onKey(KeyEvent.KEYCODE_HOME, down = true, repeatCount = 0, heldMillis = 0, canceled = false))
        assertEquals(KeyResult(consume = false), shield.onKey(KeyEvent.KEYCODE_HOME, down = false, repeatCount = 0, heldMillis = 80, canceled = false))
        assertEquals(Window.OTHER, shield.onWindow(googleTv.packageName, googleTv.className))
        assertFalse(shield.onStart(googleTv.packageName))
    }

    @Test
    fun `the TV's home is what Home resolves to, unless that's Action Center or the chooser`() {
        val ours = "com.phonoodlesramen.actioncenter"

        assertEquals(googleTv, HomeWatch.stockHomeOf(googleTv.packageName, googleTv.className, ours))
        assertNull(HomeWatch.stockHomeOf(ours, "$ours.HomeActivity", ours))
        assertNull(HomeWatch.stockHomeOf("android", "com.android.internal.app.ResolverActivity", ours))
        assertNull(HomeWatch.stockHomeOf(null, null, ours))
    }

    private companion object {
        val TAKEN = setOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_ALL_APPS)
    }
}
