package com.phonoodlesramen.actioncenter.apps

import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** M5: opens around this time on this kind of day, recent weeks counting more. */
class UsageScoreTest {
    /** Monday 26 October 2026. */
    private val monday = LocalDate.of(2026, 10, 26)
    private val week = UsageHours.weekStart(monday)

    private fun at(day: DayOfWeek, hour: Int, minute: Int = 30) = monday.plusDays(day.value - 1L).atTime(hour, minute)

    private fun opened(packageName: String, day: DayOfWeek, hour: Int, opens: Int = 1, weeksBack: Int = 0) =
        UsageRow(packageName, week - 7L * weeksBack, (day.value - 1) * 24 + hour, opens)

    private fun around(time: LocalDateTime, vararg rows: UsageRow) = UsageScore.around(UsageCounts(rows.toList()), time)

    @Test
    fun `the hour now and the hours either side count, on every weekday`() {
        val score = around(
            at(WEDNESDAY, 21),
            opened("before", WEDNESDAY, 20),
            opened("now", WEDNESDAY, 21),
            opened("after", WEDNESDAY, 22),
            opened("monday", MONDAY, 21),
            opened("friday", FRIDAY, 22),
            opened("too early", WEDNESDAY, 19),
            opened("too late", WEDNESDAY, 23),
        )

        assertEquals(setOf("before", "now", "after", "monday", "friday"), score.keys)
    }

    @Test
    fun `just after midnight takes in the hour before it`() {
        val score = around(at(TUESDAY, 0), opened("monday night", MONDAY, 23), opened("thursday night", THURSDAY, 23), opened("2 AM", TUESDAY, 2))

        assertEquals(setOf("monday night", "thursday night"), score.keys)
    }

    @Test
    fun `Sunday night and Monday morning are different kinds of day`() {
        val sunday = opened("sunday 11 PM", SUNDAY, 23)
        val monday = opened("monday midnight", MONDAY, 0)
        val saturday = opened("saturday midnight", SATURDAY, 0)

        assertEquals("Sunday 11:30 PM", setOf("sunday 11 PM", "saturday midnight"), around(at(SUNDAY, 23), sunday, monday, saturday).keys)
        assertEquals("Monday 12:30 AM", setOf("monday midnight"), around(at(MONDAY, 0), sunday, monday, saturday).keys)
    }

    @Test
    fun `a weekday evening leaves out weekend evenings, and the other way round`() {
        val rows = arrayOf(opened("weekday", THURSDAY, 21, opens = 2), opened("weekend", SATURDAY, 21, opens = 3))

        assertEquals(mapOf("weekday" to 2.0), around(at(TUESDAY, 21), *rows))
        assertEquals(mapOf("weekend" to 3.0), around(at(SUNDAY, 21), *rows))
    }

    @Test
    fun `this week counts fully, fading to a quarter 7 weeks back, and older weeks not at all`() {
        assertEquals(1.0, UsageScore.weekWeight(week, week)!!, 1e-9)
        assertEquals(1.0 - 0.75 / 7, UsageScore.weekWeight(week, week - 7)!!, 1e-9)
        assertEquals(0.25, UsageScore.weekWeight(week, week - 49)!!, 1e-9)
        assertNull(UsageScore.weekWeight(week, week - 56))
        assertNull("a later week, after a clock change", UsageScore.weekWeight(week, week + 7))

        val score = around(
            at(MONDAY, 21),
            opened("youtube", MONDAY, 21, opens = 2),
            opened("youtube", TUESDAY, 21, opens = 4, weeksBack = 7),
            opened("youtube", TUESDAY, 21, opens = 100, weeksBack = 8),
        )

        assertEquals(2 * 1.0 + 4 * 0.25, score.getValue("youtube"), 1e-9)
    }

    @Test
    fun `all day counts every hour of every day, with the same week weights`() {
        val counts = UsageCounts(
            listOf(
                opened("netflix", MONDAY, 9),
                opened("netflix", SATURDAY, 21, opens = 2, weeksBack = 1),
                opened("netflix", SUNDAY, 3, opens = 9, weeksBack = 8),
                opened("kodi", WEDNESDAY, 14, weeksBack = 7),
            ),
        )

        val allDay = UsageScore.at(counts, at(TUESDAY, 21)).allDay

        assertEquals(1.0 + 2 * (1.0 - 0.75 / 7), allDay.getValue("netflix"), 1e-9)
        assertEquals(0.25, allDay.getValue("kodi"), 1e-9)
    }

    @Test
    fun `now leads with opens around this time, then all-day opens, and level apps tie`() {
        val counts = UsageCounts(
            listOf(
                opened("twitch", MONDAY, 21),
                opened("youtube", MONDAY, 21),
                opened("youtube", SATURDAY, 10),
                opened("kodi", SATURDAY, 10, opens = 9),
                opened("spotify", SATURDAY, 10, opens = 2),
                opened("plezy", SATURDAY, 10, opens = 2),
            ),
        )

        val now = UsageScore.at(counts, at(TUESDAY, 21)).now
        val order = now.entries.sortedByDescending { it.value }.map { it.key }

        assertEquals(listOf("youtube", "twitch", "kodi"), order.take(3))
        assertEquals("level on both: launches and A-Z decide", now.getValue("spotify"), now.getValue("plezy"), 0.0)
        assertEquals(setOf("twitch", "youtube", "kodi", "spotify", "plezy"), now.keys)
    }

    @Test
    fun `no counts, no scores`() {
        assertEquals(AppScores(), UsageScore.at(UsageCounts(), at(FRIDAY, 20)))
    }

    @Test
    fun `hours of the week start on Monday`() {
        assertEquals(0, UsageHours.hourOfWeek(at(MONDAY, 0)))
        assertEquals(167, UsageHours.hourOfWeek(at(SUNDAY, 23)))
        assertEquals(week, UsageHours.weekStart(monday.plusDays(6)))
        assertEquals(week + 7, UsageHours.weekStart(monday.plusDays(7)))
        assertEquals(week - 49, UsageScore.oldestWeek(week))
    }
}
