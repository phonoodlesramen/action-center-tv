package com.phonoodlesramen.actioncenter.lastapp

import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.apps.usage.UsageEvent
import com.phonoodlesramen.actioncenter.apps.usage.UsageEventSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The last app watched, from usage events like a SHIELD's and a Google TV's real ones: the
 * home, choosers, Recents, the keyboard and the screensaver between apps.
 */
class LastVisitTest {
    private val youTubeTv = "com.google.android.youtube.tvunplugged"
    private val netflix = "com.netflix.ninja"
    private val own = "com.phonoodlesramen.actioncenter"
    private val home = "$own.HomeActivity"
    private val chooser = "android"
    private val recents = "com.android.systemui"
    private val screensaver = "com.google.android.apps.tv.dreamx"
    private val googleTv = "com.google.android.apps.tv.launcherx"
    private val settings = "com.android.tv.settings"

    private fun resumed(packageName: String, at: Long, className: String = "$packageName.MainActivity") =
        UsageEvent(packageName, at, resumed = true, className = className)

    private fun paused(packageName: String, at: Long) = UsageEvent(packageName, at, resumed = false)

    private fun screenOff(at: Long) = UsageEvent("android", at, resumed = false, screenOff = true)

    private fun last(vararg events: UsageEvent, now: Long = 1_000L) =
        LastVisit.of(events.asSequence(), now) { it.packageName == youTubeTv || it.packageName == netflix }

    @Test
    fun `the app you left for the home is the last visit, left when the home came up`() {
        val visit = last(
            resumed(netflix, 10), paused(netflix, 20), resumed(own, 20, home),
            resumed(youTubeTv, 30), paused(youTubeTv, 40), resumed(own, 41, home),
        )
        assertEquals(AppVisit(youTubeTv, frontAt = 30, leftAt = 41), visit)
    }

    @Test
    fun `screens that aren't watching end a visit but never take its place`() {
        val visit = last(
            resumed(youTubeTv, 10), resumed(chooser, 20), resumed(own, 25, home),
            resumed(recents, 30), resumed(screensaver, 40),
        )
        assertEquals(AppVisit(youTubeTv, 10, 20), visit)
    }

    @Test
    fun `the screen going off ends a visit`() {
        assertEquals(AppVisit(youTubeTv, 10, 50), last(resumed(youTubeTv, 10), screenOff(50), resumed(own, 500, home)))
    }

    @Test
    fun `moving between one app's activities is one visit`() {
        val visit = last(resumed(youTubeTv, 10), resumed(youTubeTv, 20, "$youTubeTv.Player"), resumed(own, 30, home))
        assertEquals(AppVisit(youTubeTv, 10, 30), visit)
    }

    @Test
    fun `coming back to the app is a new visit`() {
        val visit = last(resumed(youTubeTv, 10), resumed(own, 20, home), resumed(youTubeTv, 30), resumed(own, 40, home))
        assertEquals(AppVisit(youTubeTv, 30, 40), visit)
    }

    @Test
    fun `another app watched after it takes its place`() {
        val visit = last(resumed(youTubeTv, 10), resumed(own, 20, home), resumed(netflix, 30), resumed(own, 40, home))
        assertEquals(AppVisit(netflix, 30, 40), visit)
    }

    @Test
    fun `a visit Android hasn't seen end yet ends now`() {
        assertEquals(AppVisit(youTubeTv, 10, 99), last(resumed(youTubeTv, 10), now = 99))
    }

    @Test
    fun `nothing watched is no visit`() {
        assertNull(last())
        assertNull(last(resumed(own, 10, home), resumed(chooser, 20)))
    }

    // Read from Android: which apps count.

    private fun app(packageName: String) = LaunchableApp(packageName, "$packageName.MainActivity", packageName)

    /** YouTube TV, Netflix, Settings and Google TV's launcher have icons; the screensaver has none. */
    private val apps = listOf(app(youTubeTv), app(netflix), app(settings), app(googleTv), app("com.google.android.katniss"))

    private class Source(
        private val events: List<UsageEvent>,
        private val granted: Boolean = true,
        private val fails: Boolean = false,
    ) : UsageEventSource {
        var read: LongRange? = null

        override fun granted() = granted

        override fun homePackages() = setOf("com.google.android.apps.tv.launcherx", "com.phonoodlesramen.actioncenter")

        override fun events(from: Long, to: Long): Sequence<UsageEvent> {
            if (fails) throw SecurityException("no")
            read = from..to
            return events.asSequence()
        }
    }

    private val now = 100 * 60 * 60_000L

    private fun recent(source: Source) = UsageRecentApps(source, own) { now }

    @Test
    fun `it reads the last day, and an app with an icon counts`() = runTest {
        val source = Source(listOf(resumed(youTubeTv, now - 3_000), resumed(own, now - 1_000, home)))
        assertEquals(AppVisit(youTubeTv, now - 3_000, now - 1_000), recent(source).last(apps))
        assertEquals(now - UsageRecentApps.LOOK_BACK_MILLIS..now, source.read)
    }

    @Test
    fun `Home apps, Settings, Google search and apps without an icon never count`() = runTest {
        val source = Source(
            listOf(
                resumed(youTubeTv, now - 9_000),
                resumed(settings, now - 8_000),
                resumed("com.google.android.katniss", now - 7_000),
                resumed(googleTv, now - 6_000),
                resumed(screensaver, now - 5_000),
                resumed(own, now - 4_000, home),
            ),
        )
        assertEquals(AppVisit(youTubeTv, now - 9_000, now - 8_000), recent(source).last(apps))
    }

    @Test
    fun `the guide player counts, as watching something else`() = runTest {
        val source = Source(listOf(resumed(youTubeTv, now - 9_000), resumed(own, now - 8_000, UsageRecentApps.PLAYER), resumed(own, now - 1_000, home)))
        assertEquals(AppVisit(own, now - 8_000, now - 1_000), recent(source).last(apps))
    }

    @Test
    fun `without usage access, or when Android refuses, there's no visit`() = runTest {
        assertNull(recent(Source(listOf(resumed(youTubeTv, now - 9_000)), granted = false)).last(apps))
        assertNull(recent(Source(emptyList(), fails = true)).last(apps))
    }
}
