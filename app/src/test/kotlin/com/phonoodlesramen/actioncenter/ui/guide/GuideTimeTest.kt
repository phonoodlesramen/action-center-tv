package com.phonoodlesramen.actioncenter.ui.guide

import com.phonoodlesramen.actioncenter.guide.GuideSlot
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/** How the full guide writes its times. */
class GuideTimeTest {
    private val zone = ZoneOffset.UTC
    private val now = SampleGuide.WEEKNIGHT

    private fun at(hour: Int, minute: Int, days: Long = 0) =
        LocalDateTime.of(2026, 10, 26, hour, minute).plusDays(days).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun slot(start: Long, end: Long) = GuideSlot(start, end, "A title", null, "key", null, null, null, filler = false, offAir = null)

    @Test
    fun `a time range names AM or PM once, or twice across midnight`() {
        assertEquals("8:30 – 10:41 PM", GuideTime.range(slot(at(20, 30), at(22, 41)), zone))
        assertEquals("11:30 PM – 1:25 AM", GuideTime.range(slot(at(23, 30), at(1, 25, days = 1)), zone))
        assertEquals("11:30 AM – 1:00 PM", GuideTime.range(slot(at(11, 30), at(13, 0)), zone))
    }

    @Test
    fun `lengths and time left read like the home's`() {
        assertEquals("2h 11m", GuideTime.length(slot(at(20, 30), at(22, 41))))
        assertEquals("52m", GuideTime.length(slot(at(20, 30), at(21, 22))))
        assertEquals("2h", GuideTime.length(slot(at(20, 0), at(22, 0))))
        assertEquals("2h left", GuideTime.left(slot(at(20, 30), at(22, 41)), now))
        assertEquals("41m left", GuideTime.left(slot(at(20, 30), at(21, 22)), now))
    }

    @Test
    fun `starts at says the hour alone later tonight, and more when it could mislead`() {
        assertEquals("Starts at 11:00", GuideTime.startsAt(at(23, 0), now, zone))
        assertEquals("Starts tomorrow at 7:00 AM", GuideTime.startsAt(at(7, 0, days = 1), now, zone))
        assertEquals("Starts at 9:30 PM", GuideTime.startsAt(at(21, 30), at(8, 41), zone))
        assertEquals("Starts in 2h 19m", GuideTime.startsIn(at(23, 0), now))
        assertEquals("Starts in 1m", GuideTime.startsIn(now + 1_000, now))
        assertEquals("Ended at 8:06", GuideTime.endedAt(at(20, 6), zone))
    }

    @Test
    fun `the window's day and the ruler's marks`() {
        assertEquals("Today", GuideTime.day(at(20, 30), now, zone))
        assertEquals("Tomorrow", GuideTime.day(at(7, 0, days = 1), now, zone))
        assertEquals("Yesterday", GuideTime.day(at(23, 0, days = -1), at(0, 30), zone))
        assertEquals("8:30 PM", GuideTime.mark(at(20, 30), null, zone))
        assertEquals("9:00", GuideTime.mark(at(21, 0), at(20, 30), zone))
        assertEquals("12:00 AM", GuideTime.mark(at(0, 0, days = 1), at(23, 30), zone))
    }
}
