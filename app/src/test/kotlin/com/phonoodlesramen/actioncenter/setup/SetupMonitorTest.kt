package com.phonoodlesramen.actioncenter.setup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SetupMonitor]: each resume reads the steps again, remembers HomeWatch, and decides where Allow goes. */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupMonitorTest {
    private class FakeChecks : SetupChecks {
        var home = false
        var homeWatch = false
        val granted = mutableSetOf<SetupPermission>()
        var usage = false
        var link = true
        var installs = false
        var broken = false

        override fun homeIsOurs() = if (broken) error("no package manager") else home
        override fun homeWatchOn() = homeWatch
        override fun granted(permission: SetupPermission) = permission in granted
        override fun usageAccess() = usage
        override fun accessibilityLink() = link
        override fun canInstallUpdates() = installs
    }

    private val checks = FakeChecks()
    private val store = SetupStore.InMemory()

    /** Reads again, and lets [SetupMonitor.status] catch up on the test's scheduler. */
    private suspend fun TestScope.read(monitor: SetupMonitor): SetupStatus {
        monitor.refresh()
        runCurrent()
        return monitor.status.value
    }

    @Test
    fun `a new TV reads as nothing done, and the reads follow TV Settings`() = runTest {
        val monitor = SetupMonitor(checks, store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        assertEquals(
            SetupStatus(home = HomeRoute.OFF, tvListings = false, pictures = false, usageAccess = false, installUpdates = false),
            read(monitor),
        )

        checks.homeWatch = true
        checks.granted += SetupPermission.TV_LISTINGS
        checks.usage = true
        checks.installs = true
        val after = read(monitor)
        assertEquals(HomeRoute.HOMEWATCH, after.home)
        assertTrue(after.tvListings)
        assertFalse(after.pictures)
        assertTrue(after.usageAccess)
        assertTrue(after.installUpdates)
        assertEquals(1, after.left)
    }

    @Test
    fun `Install unknown apps is optional, so it's never one of the steps left`() = runTest {
        val monitor = SetupMonitor(checks, store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        checks.home = true
        checks.granted += SetupPermission.entries
        checks.usage = true
        val status = read(monitor)
        assertFalse(status.installUpdates)
        assertEquals(0, status.left)
        assertEquals(null, status.chip(kid = false))
    }

    @Test
    fun `HomeWatch going off after it was on reads as turned off by Android`() = runTest {
        val monitor = SetupMonitor(checks, store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        checks.homeWatch = true
        read(monitor)
        checks.homeWatch = false
        assertEquals(HomeRoute.WENT_OFF, read(monitor).home)

        // The stock launcher turned off later (the computer route): Home is fine, no warning.
        checks.home = true
        assertEquals(HomeRoute.HOME, read(monitor).home)
    }

    @Test
    fun `a check that throws reads as not done, and the others still read`() = runTest {
        val monitor = SetupMonitor(checks, store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        checks.broken = true
        checks.usage = true
        val status = read(monitor)
        assertEquals(HomeRoute.OFF, status.home)
        assertTrue(status.usageAccess)
    }

    @Test
    fun `hiding the chip shows in the status`() = runTest {
        val monitor = SetupMonitor(checks, store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        monitor.setChipHidden(true)
        assertTrue(monitor.status.first { it.chipHidden }.chipHidden)
    }

    @Test
    fun `Allow asks Android first, then App info once Android won't ask again`() = runTest {
        val monitor = SetupMonitor(checks, store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        assertEquals(AllowRoute.ASK, monitor.allowRoute(SetupPermission.PICTURES, showsRationale = false))
        // Refused once: Android would explain, so it asks again.
        assertEquals(AllowRoute.ASK, monitor.allowRoute(SetupPermission.PICTURES, showsRationale = true))
        // Refused for good: no rationale after asking.
        assertEquals(AllowRoute.APP_INFO, monitor.allowRoute(SetupPermission.PICTURES, showsRationale = false))
        // Each permission on its own.
        assertEquals(AllowRoute.ASK, monitor.allowRoute(SetupPermission.TV_LISTINGS, showsRationale = false))
    }
}
