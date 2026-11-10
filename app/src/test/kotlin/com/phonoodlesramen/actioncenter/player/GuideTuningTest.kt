package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideTuningTest {
    private val start = 1_793_046_600_000L // 8:30 PM UTC
    private val minute = 60_000L

    private fun slot(from: Long, minutes: Int, key: String? = "k$from", filler: Boolean = false) =
        GuideSlot(from, from + minutes * minute, "Title $from", null, key, null, null, null, filler, null)

    private val program = slot(start, 52)
    private val upNext = slot(start + 52 * minute, 8, key = null, filler = true)
    private val nextProgram = slot(start + 60 * minute, 22)
    private val channel = GuideChannel("tv-mix", 3, "TV Mix", null, listOf(program, upNext, nextProgram))

    @Test
    fun `tuning in joins the program as far in as the slot has run`() {
        assertEquals(11 * minute, GuideTuning.startPosition(program, start + 11 * minute, durationMs = 52 * minute))
        assertEquals(11 * minute + 345, GuideTuning.startPosition(program, start + 11 * minute + 345, durationMs = null))
    }

    @Test
    fun `within two seconds of the start it plays from the top`() {
        assertEquals(0L, GuideTuning.startPosition(program, start + 1_999, durationMs = null))
        assertEquals(2_000L, GuideTuning.startPosition(program, start + 2_000, durationMs = null))
        assertEquals(0L, GuideTuning.startPosition(program, start - 5_000, durationMs = null))
    }

    @Test
    fun `a file with nothing left has no start, so the Up next card shows`() {
        val trailer = slot(start, 22, filler = true)
        // A 2.5-minute trailer joined 10 minutes into its filler slot.
        assertNull(GuideTuning.startPosition(trailer, start + 10 * minute, durationMs = 150_000))
        // Within the last two seconds counts as finished too.
        assertNull(GuideTuning.startPosition(trailer, start + 148_500, durationMs = 150_000))
        assertEquals(60_000L, GuideTuning.startPosition(trailer, start + minute, durationMs = 150_000))
        // An unknown length: the player finds out.
        assertEquals(10 * minute, GuideTuning.startPosition(trailer, start + 10 * minute, durationMs = null))
    }

    @Test
    fun `a file that ends well before its slot gives way to the Up next card, one that ends a moment early doesn't`() {
        val trailer = slot(start, 22, filler = true)
        assertEquals(true, GuideTuning.endedEarly(trailer, start + 150_000))
        assertEquals(false, GuideTuning.endedEarly(program, program.endMillis - 5_000))
        assertEquals(true, GuideTuning.endedEarly(program, program.endMillis - 5_001))
    }

    @Test
    fun `the next slot is made ready 30 seconds before the boundary and switched to at it`() {
        val handoff = GuideTuning.handoff(program, start + 11 * minute)
        assertEquals(program.endMillis - 30_000, handoff.prepareAtMillis)
        assertEquals(program.endMillis, handoff.switchAtMillis)
    }

    @Test
    fun `tuning in during the last 30 seconds makes the next slot ready at once`() {
        val now = program.endMillis - 10_000
        assertEquals(GuideTuning.Handoff(now, program.endMillis), GuideTuning.handoff(program, now))
        // Late (a slow lookup): never after the boundary.
        assertEquals(GuideTuning.Handoff(program.endMillis, program.endMillis), GuideTuning.handoff(program, program.endMillis + 5_000))
    }

    @Test
    fun `the following slot is the one that starts at the boundary, filler included`() {
        assertEquals(upNext, GuideTuning.following(channel, program))
        assertEquals(nextProgram, GuideTuning.following(channel, upNext))
        assertNull(GuideTuning.following(channel, nextProgram))
    }

    @Test
    fun `next skips filler`() {
        assertEquals(nextProgram, GuideTuning.nextProgram(channel, program.endMillis))
        assertEquals(nextProgram, GuideTuning.nextProgram(channel, upNext.startMillis))
        assertNull(GuideTuning.nextProgram(channel, nextProgram.endMillis))
    }

    @Test
    fun `Up and Down step through the lineup in order and wrap at both ends`() {
        val lineup = listOf("movies", "scifi", "tv-mix").map { GuideChannel(it, 0, it, null, emptyList()) }
        assertEquals("scifi", GuideTuning.channelAfter(lineup, "movies", +1)?.id)
        assertEquals("movies", GuideTuning.channelAfter(lineup, "scifi", -1)?.id)
        assertEquals("movies", GuideTuning.channelAfter(lineup, "tv-mix", +1)?.id)
        assertEquals("tv-mix", GuideTuning.channelAfter(lineup, "movies", -1)?.id)
    }

    @Test
    fun `from a channel that's gone it lands on the first, and an empty lineup has none`() {
        val lineup = listOf("movies", "scifi").map { GuideChannel(it, 0, it, null, emptyList()) }
        assertEquals("movies", GuideTuning.channelAfter(lineup, "gone", +1)?.id)
        assertEquals("movies", GuideTuning.channelAfter(lineup, null, -1)?.id)
        assertNull(GuideTuning.channelAfter(emptyList(), "movies", +1))
    }

    @Test
    fun `the picture's shape allows for non-square pixels`() {
        assertEquals(16f / 9f, GuideTuning.aspect(1920, 1080, 1f)!!, 0.001f)
        assertEquals(2.39f, GuideTuning.aspect(1920, 804, 1f)!!, 0.01f)
        // Anamorphic DVD: 720×480 at 32:27 pixels is 16:9.
        assertEquals(16f / 9f, GuideTuning.aspect(720, 480, 32f / 27f)!!, 0.001f)
        assertNull(GuideTuning.aspect(0, 0, 1f))
    }
}
