package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.guide.GuideSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** G11's arithmetic: Ridge Line, 8:30-9:22 PM, watched behind live. */
class BehindLiveTest {
    private val start = 1_793_046_600_000L // 8:30 PM UTC
    private val minute = 60_000L
    private val ridgeLine = GuideSlot(start, start + 52 * minute, "Ridge Line", "S3 E1 · Thaw", "k3", null, null, "TV-MA", false, null)

    @Test
    fun `started over at 8 41, it plays from the start, 11 minutes behind, and ends 11 minutes after its slot`() {
        val behind = BehindLive.startedOver(ridgeLine, start + 11 * minute)

        assertTrue(behind.startedOver)
        assertFalse(behind.paused)
        assertEquals(0L, behind.positionAt(start + 11 * minute))
        assertEquals(11 * minute, behind.behindAt(start + 11 * minute))
        // Playing, it stays as far behind as it started.
        assertEquals(20 * minute, behind.positionAt(start + 31 * minute))
        assertEquals(11 * minute, behind.behindAt(start + 31 * minute))
        // The program ends at 9:33, so the channel rejoins live then.
        assertEquals(start + 63 * minute, behind.endsAtMillis)
    }

    @Test
    fun `paused, the picture stands still while live moves away, and nothing ends`() {
        val paused = BehindLive.pausedLive(ridgeLine, start + 11 * minute)

        assertTrue(paused.paused)
        assertEquals(11 * minute, paused.positionAt(start + 30 * minute))
        assertEquals(0L, paused.behindAt(start + 11 * minute))
        assertEquals(19 * minute, paused.behindAt(start + 30 * minute))
        assertNull(paused.endsAtMillis)
    }

    @Test
    fun `resumed after 12 minutes paused, it carries on 12 minutes behind and ends 12 minutes late`() {
        val resumed = BehindLive.pausedLive(ridgeLine, start + 11 * minute).resumedAt(start + 23 * minute)

        assertFalse(resumed.paused)
        assertEquals(12 * minute, resumed.behindAt(start + 23 * minute))
        assertEquals(12 * minute, resumed.behindAt(start + 40 * minute))
        assertEquals(start + 64 * minute, resumed.endsAtMillis)
        // A second pause adds to it.
        val again = resumed.pausedAt(start + 30 * minute).resumedAt(start + 35 * minute)
        assertEquals(17 * minute, again.behindAt(start + 35 * minute))
        assertEquals(start + 69 * minute, again.endsAtMillis)
    }

    @Test
    fun `the player's own position wins over the schedule's when it's known`() {
        val paused = BehindLive.pausedLive(ridgeLine, start + 11 * minute, positionMs = 10 * minute + 30_000)

        assertEquals(10 * minute + 30_000, paused.positionAt(start + 11 * minute))
        assertEquals(30_000L, paused.behindAt(start + 11 * minute))
        val startedOver = BehindLive.startedOver(ridgeLine, start + 11 * minute)
        assertEquals(5 * minute, startedOver.pausedAt(start + 16 * minute, positionMs = 5 * minute).positionAt(start + 40 * minute))
    }

    @Test
    fun `the banner's bar and time left follow the picture, not the schedule`() {
        val behind = BehindLive.startedOver(ridgeLine, start + 11 * minute)

        assertEquals(0f, behind.progressAt(start + 11 * minute))
        assertEquals(52, behind.minutesLeftAt(start + 11 * minute))
        assertEquals(0.5f, behind.progressAt(start + 37 * minute))
        assertEquals(26, behind.minutesLeftAt(start + 37 * minute))
        // Past the program's length: done.
        assertEquals(1f, behind.progressAt(start + 70 * minute))
        assertEquals(0, behind.minutesLeftAt(start + 70 * minute))
    }

    @Test
    fun `resuming what isn't paused changes nothing`() {
        val behind = BehindLive.startedOver(ridgeLine, start + 11 * minute)
        assertEquals(behind, behind.resumedAt(start + 30 * minute))
    }
}
