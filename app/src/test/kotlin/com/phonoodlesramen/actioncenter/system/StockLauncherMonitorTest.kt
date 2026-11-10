package com.phonoodlesramen.actioncenter.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockLauncherMonitorTest {
    @Test
    fun `checks the stock tvlauncher package`() {
        var asked: String? = null
        val monitor = StockLauncherMonitor { packageName ->
            asked = packageName
            PackageState.DISABLED
        }

        monitor.state()

        assertEquals("com.google.android.tvlauncher", asked)
    }

    @Test
    fun `the stock launcher is back when it is enabled`() {
        assertTrue(StockLauncherMonitor { PackageState.ENABLED }.stockLauncherIsBack())
    }

    @Test
    fun `a disabled stock launcher is not back`() {
        assertFalse(StockLauncherMonitor { PackageState.DISABLED }.stockLauncherIsBack())
    }

    @Test
    fun `a missing stock launcher is not back`() {
        assertFalse(StockLauncherMonitor { PackageState.MISSING }.stockLauncherIsBack())
    }
}
