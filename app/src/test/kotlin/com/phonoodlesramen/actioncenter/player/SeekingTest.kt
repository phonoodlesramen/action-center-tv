package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.guide.GuideSlot
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where ⏪ and ⏩ land (P1): the program's start and live are the limits, and a hold speeds up. */
class SeekingTest {
    private val minute = 60_000L
    private val second = 1_000L

    /** Ridge Line, 8:30-9:22, as the sample guide has it. */
    private val slot = GuideSlot(
        startMillis = 0, endMillis = 52 * minute, title = "Ridge Line", episode = "S3 E1 · Thaw", itemKey = "7303",
        showKey = null, art = null, contentRating = "TV-MA", filler = false, offAir = null,
    )

    @Test
    fun `live is how far the schedule has got, and the program's end once it's over`() {
        assertEquals(11 * minute, Seeking.liveAt(slot, 11 * minute))
        assertEquals(52 * minute, Seeking.liveAt(slot, 90 * minute))
        assertEquals(0L, Seeking.liveAt(slot, -minute))
    }

    @Test
    fun `a tap goes back 10 seconds and forward 30`() {
        assertEquals(10 * minute - 10 * second, Seeking.skip(10 * minute, forward = false, liveMs = 11 * minute))
        assertEquals(5 * minute + 30 * second, Seeking.skip(5 * minute, forward = true, liveMs = 11 * minute))
    }

    @Test
    fun `back stops at the program's start`() {
        assertEquals(0L, Seeking.skip(4 * second, forward = false, liveMs = 11 * minute))
        assertEquals(0L, Seeking.skip(0, forward = false, liveMs = 11 * minute))
    }

    @Test
    fun `forward stops at live`() {
        assertEquals(11 * minute, Seeking.skip(11 * minute - 5 * second, forward = true, liveMs = 11 * minute))
        assertEquals(11 * minute, Seeking.skip(11 * minute, forward = true, liveMs = 11 * minute))
    }

    @Test
    fun `holding scrubs at x2, then x4 after 2 seconds, then x8 after 4`() {
        assertEquals(2, Seeking.speedAt(0))
        assertEquals(2, Seeking.speedAt(500))
        assertEquals(2, Seeking.speedAt(1_999))
        assertEquals(4, Seeking.speedAt(2_000))
        assertEquals(4, Seeking.speedAt(3_999))
        assertEquals(8, Seeking.speedAt(4_000))
        assertEquals(8, Seeking.speedAt(60_000))
    }

    @Test
    fun `x2 moves 30 s of the program a second, x4 a minute, x8 two`() {
        assertEquals(15 * second, Seeking.distance(500))
        assertEquals(60 * second, Seeking.distance(2_000))
        // 2 s at x2, then 1 s at x4.
        assertEquals(2 * minute, Seeking.distance(3_000))
        // 2 s at x2, 2 s at x4, then 1 s at x8.
        assertEquals(5 * minute, Seeking.distance(5_000))
        assertEquals(0L, Seeking.distance(-100))
    }

    @Test
    fun `the marker stays between the program's start and live`() {
        assertEquals(10 * minute - 15 * second, Seeking.scrubTo(10 * minute, forward = false, heldMillis = 500, liveMs = 11 * minute))
        assertEquals(0L, Seeking.scrubTo(2 * minute, forward = false, heldMillis = 5_000, liveMs = 11 * minute))
        assertEquals(11 * minute, Seeking.scrubTo(10 * minute, forward = true, heldMillis = 5_000, liveMs = 11 * minute))
        assertEquals(6 * minute, Seeking.scrubTo(5 * minute, forward = true, heldMillis = 2_000, liveMs = 11 * minute))
    }
}
