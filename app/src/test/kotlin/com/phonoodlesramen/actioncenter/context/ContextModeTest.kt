package com.phonoodlesramen.actioncenter.context

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextModeTest {
    // 2026-10-26 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)
    private val monday = 26
    private val thursday = 29
    private val friday = 30
    private val saturday = 31
    private val sunday = 25

    @Test
    fun `weeknight evening runs Sunday to Thursday, 6pm until half past 10`() {
        assertEquals(ContextMode.WEEKNIGHT_EVENING, ContextMode.at(at(sunday, 18)))
        assertEquals(ContextMode.WEEKNIGHT_EVENING, ContextMode.at(at(thursday, 22, 29)))
        assertEquals(ContextMode.DAYTIME, ContextMode.at(at(monday, 17, 59)))
    }

    @Test
    fun `Friday and Saturday evenings are weekend evenings (M2)`() {
        assertEquals(ContextMode.WEEKEND_EVENING, ContextMode.at(at(friday, 20)))
        assertEquals(ContextMode.WEEKEND_EVENING, ContextMode.at(at(saturday, 18)))
        assertEquals(ContextMode.DAYTIME, ContextMode.at(at(friday, 17, 59)))
    }

    @Test
    fun `late night runs from half past 10 until 5am every day, across midnight`() {
        assertEquals(ContextMode.LATE_NIGHT, ContextMode.at(at(thursday, 22, 30)))
        assertEquals(ContextMode.LATE_NIGHT, ContextMode.at(at(monday, 0)))
        assertEquals(ContextMode.LATE_NIGHT, ContextMode.at(at(saturday, 4, 59)))
        assertEquals(ContextMode.DAYTIME, ContextMode.at(at(saturday, 5)))
    }

    @Test
    fun `weekend morning runs Saturday and Sunday, 7am until noon`() {
        assertEquals(ContextMode.WEEKEND_MORNING, ContextMode.at(at(saturday, 7)))
        assertEquals(ContextMode.WEEKEND_MORNING, ContextMode.at(at(sunday, 11, 59)))
        assertEquals(ContextMode.DAYTIME, ContextMode.at(at(saturday, 12)))
        assertEquals(ContextMode.DAYTIME, ContextMode.at(at(monday, 9)))
    }

    @Test
    fun `greeting follows the time of day`() {
        assertEquals("Good morning", greetingAt(at(monday, 5).toLocalTime()))
        assertEquals("Good morning", greetingAt(at(monday, 11, 59).toLocalTime()))
        assertEquals("Good afternoon", greetingAt(at(monday, 12).toLocalTime()))
        assertEquals("Good evening", greetingAt(at(monday, 18).toLocalTime()))
        assertEquals("Good evening", greetingAt(at(monday, 2).toLocalTime()))
    }
}
