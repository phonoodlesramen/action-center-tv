package com.phonoodlesramen.actioncenter.guide

import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideSlotsTest {
    private val minute = 60_000L
    private val now = SampleGuide.WEEKNIGHT

    private fun slot(start: Long, end: Long, title: String = "Big Atlas") =
        GuideSlot(start, end, title, null, "1", null, null, null, filler = false, offAir = null)

    @Test
    fun `now, progress, time left and the join offset come from the slot`() {
        val slot = slot(now - 30 * minute, now + 90 * minute)

        assertEquals(0.25f, slot.progressAt(now), 0.001f)
        assertEquals(90, slot.minutesLeftAt(now))
        assertEquals(1, slot.minutesLeftAt(now + 89 * minute + 30_000))
        assertEquals(30 * minute, slot.offsetAt(now))
        assertEquals(0L, slot.offsetAt(now - 60 * minute))
    }

    @Test
    fun `a channel finds what's on, nothing in a gap, and what's next`() {
        val a = slot(now - 30 * minute, now + 10 * minute, "A")
        val b = slot(now + 20 * minute, now + 50 * minute, "B")
        val c = slot(now + 50 * minute, now + 80 * minute, "C")
        val channel = GuideChannel("1", 1, "Movies", null, listOf(a, b, c))

        assertEquals(a, channel.slotAt(now))
        assertNull("a gap", channel.slotAt(now + 15 * minute))
        assertNull("past the schedule", channel.slotAt(now + 90 * minute))
        assertEquals(listOf(b, c), channel.upNext(now, 2))
    }

    @Test
    fun `the sample lineup is contiguous, on the half hour, and covers the next 24 hours`() {
        val lineup = SampleGuide.ready()

        assertEquals(6, lineup.channels.size)
        for (channel in lineup.channels) {
            channel.slots.zipWithNext { x, y -> assertEquals("${channel.name} is contiguous", x.endMillis, y.startMillis) }
            channel.slots.filterNot { it.filler }.forEach { assertEquals("${channel.name} starts on the half hour", 0L, it.startMillis % (30 * minute)) }
            assertTrue(channel.slots.first().startMillis <= now - 60 * minute)
            assertTrue(channel.slots.last().endMillis >= now + 24 * 60 * minute)
            assertTrue("${channel.name} has something on now", channel.slotAt(now) != null)
        }
        assertTrue("one program is off air", lineup.channels.flatMap { it.slots }.any { it.offAir != null })
        assertTrue("filler of both kinds", lineup.channels.flatMap { it.slots }.filter { it.filler }.map { it.itemKey == null }.toSet().size == 2)
    }
}
