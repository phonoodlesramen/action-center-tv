package com.phonoodlesramen.actioncenter.context

import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextScheduleTest {
    // 2026-10-26 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)
    private val monday = 26
    private val thursday = 29
    private val friday = 30
    private val saturday = 31
    private val sunday = 25
    private fun hours(start: Int, end: Int, startMinute: Int = 0, endMinute: Int = 0) =
        Hours(LocalTime.of(start, startMinute), LocalTime.of(end, endMinute))

    @Test
    fun `the default schedule is Phase 1's, plus weekend evenings`() {
        val schedule = ContextSchedule.DEFAULT
        assertNull(schedule.problem())
        assertEquals(ContextMode.WEEKNIGHT_EVENING, schedule.modeAt(at(monday, 20)))
        assertEquals(ContextMode.WEEKEND_EVENING, schedule.modeAt(at(friday, 20)))
        assertEquals(ContextMode.WEEKEND_MORNING, schedule.modeAt(at(sunday, 9)))
        assertEquals(ContextMode.LATE_NIGHT, schedule.modeAt(at(saturday, 23)))
        assertEquals(ContextMode.DAYTIME, schedule.modeAt(at(saturday, 15)))
        assertNull(schedule.hours(ContextMode.DAYTIME))
    }

    @Test
    fun `moved hours change the mode`() {
        val schedule = ContextSchedule.DEFAULT
            .with(ContextMode.LATE_NIGHT, hours(23, 5, startMinute = 30))
            .with(ContextMode.WEEKNIGHT_EVENING, hours(17, 23, endMinute = 30))

        assertNull(schedule.problem())
        assertEquals(ContextMode.WEEKNIGHT_EVENING, schedule.modeAt(at(monday, 17)))
        assertEquals(ContextMode.WEEKNIGHT_EVENING, schedule.modeAt(at(monday, 23)))
        assertEquals(ContextMode.LATE_NIGHT, schedule.modeAt(at(monday, 23, 30)))
    }

    @Test
    fun `an evening past midnight belongs to the day it started on`() {
        val schedule = ContextSchedule.DEFAULT
            .with(ContextMode.LATE_NIGHT, hours(1, 5))
            .with(ContextMode.WEEKEND_EVENING, hours(18, 1))
            .with(ContextMode.WEEKNIGHT_EVENING, hours(18, 0))

        assertNull(schedule.problem())
        // Saturday 0:30 is still Friday's evening; Friday 0:30 is Thursday's, which ended at midnight.
        assertEquals(ContextMode.WEEKEND_EVENING, schedule.modeAt(at(saturday, 0, 30)))
        assertEquals(ContextMode.DAYTIME, schedule.modeAt(at(friday, 0, 30)))
        assertEquals(ContextMode.LATE_NIGHT, schedule.modeAt(at(saturday, 1)))
        assertEquals(ContextMode.WEEKNIGHT_EVENING, schedule.modeAt(at(thursday, 23, 59)))
    }

    @Test
    fun `hours that clash on a day two modes share are a problem`() {
        val late = ContextSchedule.DEFAULT.with(ContextMode.LATE_NIGHT, hours(22, 5))
        assertEquals("Weeknight evening would overlap Late night", late.problem())

        val morning = ContextSchedule.DEFAULT.with(ContextMode.WEEKEND_MORNING, hours(4, 12))
        assertEquals("Weekend morning would overlap Late night", morning.problem())
    }

    @Test
    fun `modes on different days may share hours`() {
        // Weeknights and weekend evenings never fall on the same day.
        val schedule = ContextSchedule.DEFAULT
            .with(ContextMode.WEEKNIGHT_EVENING, hours(17, 22))
            .with(ContextMode.WEEKEND_EVENING, hours(17, 22))
        assertNull(schedule.problem())
    }

    @Test
    fun `a mode needs some time`() {
        val schedule = ContextSchedule.DEFAULT.with(ContextMode.WEEKEND_MORNING, hours(9, 9))
        assertEquals("Weekend morning needs a start and an end", schedule.problem())
    }
}
