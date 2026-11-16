package com.phonoodlesramen.actioncenter.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeping your place (P5): when you were watching, when you're back quickly, and what Plex hears. */
class GuidePlacesTest {
    private val minute = 60_000L

    @Test
    fun `three minutes played, or a choice, is watching, and surfing past isn't`() {
        assertFalse(PlaceRules.watching(watchedMs = 2 * minute + 59_000, chose = false))
        assertTrue(PlaceRules.watching(watchedMs = 3 * minute, chose = false))
        // Started over, paused, skipped or picked up, a moment in.
        assertTrue(PlaceRules.watching(watchedMs = 5_000, chose = true))
    }

    @Test
    fun `back within two minutes is a slip of the thumb`() {
        val left = 1_000_000L
        assertTrue(PlaceRules.quickReturn(left, left + 2 * minute))
        assertFalse(PlaceRules.quickReturn(left, left + 2 * minute + 1))
        // A clock that went back counts as now.
        assertTrue(PlaceRules.quickReturn(left, left - minute))
    }

    @Test
    fun `under 30 seconds behind live there's nothing to pick up`() {
        assertFalse(PlaceRules.missed(positionMs = 16 * minute, liveMs = 16 * minute + 29_999))
        assertTrue(PlaceRules.missed(positionMs = 16 * minute, liveMs = 16 * minute + 30_000))
    }

    @Test
    fun `Plex hears where you left a program you saw from its start, and nothing else`() {
        val watchedAt = 46 * minute
        assertEquals(20 * minute, PlaceRules.leftOff(positionMs = 20 * minute, watchedMs = 20 * minute, watchedAtMs = watchedAt))
        // Tuned in a few minutes after it began: still from its start, near enough.
        assertEquals(20 * minute, PlaceRules.leftOff(positionMs = 20 * minute, watchedMs = 15 * minute, watchedAtMs = watchedAt))
        // Joined midway: a resume point would start it where you never saw the beginning.
        assertNull(PlaceRules.leftOff(positionMs = 20 * minute, watchedMs = 14 * minute, watchedAtMs = watchedAt))
        // Under a minute in, Plex ignores it; past the watched point, it counts as watched or not at all.
        assertNull(PlaceRules.leftOff(positionMs = 59_000, watchedMs = 59_000, watchedAtMs = watchedAt))
        assertNull(PlaceRules.leftOff(positionMs = watchedAt, watchedMs = watchedAt, watchedAtMs = watchedAt))
    }
}
