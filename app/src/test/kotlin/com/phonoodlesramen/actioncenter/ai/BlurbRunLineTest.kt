package com.phonoodlesramen.actioncenter.ai

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings' last-run line, and when the nightly run first comes. */
class BlurbRunLineTest {
    private val zone = ZoneId.of("America/New_York")

    /** Tuesday 27 October 2026, 8:15 PM. */
    private val now = ZonedDateTime.of(LocalDateTime.of(2026, 10, 27, 20, 15), zone)

    private fun at(day: Int, hour: Int, minute: Int) = ZonedDateTime.of(LocalDateTime.of(2026, 10, day, hour, minute), zone)

    private fun run(at: ZonedDateTime, blurbs: Int = 28, usage: TokenUsage = TokenUsage(5_030, 2_500), error: String? = null) =
        BlurbRun(at.toInstant().toEpochMilli(), blurbs, usage, error)

    @Test
    fun `a night's run says when, how many, the tokens and about what it cost`() {
        assertEquals("Last night at 4:02 AM: 28 blurbs, 7,530 tokens, about $0.002", BlurbRunLine.of(run(at(27, 4, 2)), now))
        assertEquals("Today at 7:45 PM: 1 blurb, 7,530 tokens, about $0.002", BlurbRunLine.of(run(at(27, 19, 45), blurbs = 1), now))
    }

    @Test
    fun `a failure says when and why`() {
        assertEquals(
            "Last night at 11:30 PM: ${ClaudeBlurbs.KEY_REFUSED}",
            BlurbRunLine.of(run(at(26, 23, 30), error = ClaudeBlurbs.KEY_REFUSED), now),
        )
    }

    @Test
    fun `when, from last night to last week`() {
        assertEquals("Today at 9:14 AM", BlurbRunLine.whenText(at(27, 9, 14), now))
        assertEquals("Yesterday at 3:00 PM", BlurbRunLine.whenText(at(26, 15, 0), now))
        assertEquals("Last night at 6:30 PM", BlurbRunLine.whenText(at(26, 18, 30), now))
        assertEquals("Fri, Oct 23 at 4:01 AM", BlurbRunLine.whenText(at(23, 4, 1), now))
        // Still the small hours: tonight's run is today's.
        assertEquals("Today at 4:02 AM", BlurbRunLine.whenText(at(27, 4, 2), at(27, 4, 30)))
    }

    @Test
    fun `about a dollar amount`() {
        assertEquals("under $0.001", BlurbRunLine.dollars(0.0002))
        assertEquals("about $0.002", BlurbRunLine.dollars(0.00175))
        assertEquals("about $0.05", BlurbRunLine.dollars(0.0512))
    }

    @Test
    fun `the first nightly run waits for the next 4 AM`() {
        assertEquals(Duration.ofHours(7).plusMinutes(45), BlurbSchedule.delayUntil(LocalTime.of(4, 0), now))
        assertEquals(Duration.ofHours(1), BlurbSchedule.delayUntil(LocalTime.of(4, 0), at(27, 3, 0)))
        assertEquals(Duration.ofHours(24), BlurbSchedule.delayUntil(LocalTime.of(4, 0), at(27, 4, 0)))
        // Clocks go back an hour on Sunday 1 November: the night is an hour longer.
        assertEquals(Duration.ofHours(8).plusMinutes(45), BlurbSchedule.delayUntil(LocalTime.of(4, 0), at(31, 20, 15)))
    }
}
