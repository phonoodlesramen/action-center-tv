package com.phonoodlesramen.actioncenter.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [IdleRule] (A3): when Install automatically may install by itself. */
class IdleRuleTest {
    private val now = 10 * IdleRule.UNTOUCHED_MILLIS

    private fun may(snapshot: IdleSnapshot) = IdleRule.mayInstall(snapshot, now)

    @Test
    fun `asleep, the screensaver, or Action Center untouched for 30 minutes`() {
        assertTrue(may(IdleSnapshot(screenOff = true)))
        assertTrue(may(IdleSnapshot(dreaming = true)))
        assertTrue(may(IdleSnapshot(homeInFront = true, lastTouchMillis = now - IdleRule.UNTOUCHED_MILLIS)))
    }

    @Test
    fun `not while anything plays or the guide player is open, even asleep`() {
        assertFalse(may(IdleSnapshot(screenOff = true, playing = true)))
        assertFalse(may(IdleSnapshot(dreaming = true, guidePlayerOpen = true)))
        assertFalse(may(IdleSnapshot(homeInFront = true, playing = true)))
    }

    @Test
    fun `not when it was touched lately, or another app is in front`() {
        assertFalse(may(IdleSnapshot(homeInFront = true, lastTouchMillis = now - IdleRule.UNTOUCHED_MILLIS + 1)))
        assertFalse(may(IdleSnapshot(homeInFront = false, lastTouchMillis = 0)))
        assertFalse(may(IdleSnapshot()))
    }
}
