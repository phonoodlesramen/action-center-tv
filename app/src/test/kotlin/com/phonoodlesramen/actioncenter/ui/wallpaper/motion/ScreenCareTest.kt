package com.phonoodlesramen.actioncenter.ui.wallpaper.motion

import kotlin.math.abs
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCareTest {
    @Test
    fun `text moves every minute, a few dp at most`() {
        var previous = ScreenCare.drift(0)
        for (minute in 1 until 24 * 60) {
            val drift = ScreenCare.drift(minute)
            assertNotEquals("Minute $minute", previous, drift)
            assertTrue("Minute $minute: $drift", abs(drift.first) <= ScreenCare.MAX_X_DP && abs(drift.second) <= ScreenCare.MAX_Y_DP)
            previous = drift
        }
    }

    @Test
    fun `over an hour it uses most of its room`() {
        val drifts = (0 until 60).map(ScreenCare::drift)

        assertTrue(drifts.maxOf { it.first } - drifts.minOf { it.first } > ScreenCare.MAX_X_DP)
        assertTrue(drifts.maxOf { it.second } - drifts.minOf { it.second } > ScreenCare.MAX_Y_DP)
    }
}
