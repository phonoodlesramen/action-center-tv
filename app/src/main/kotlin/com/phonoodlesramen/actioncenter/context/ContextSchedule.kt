package com.phonoodlesramen.actioncenter.context

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/** A mode's daily hours. An [end] before [start] runs past midnight. */
data class Hours(val start: LocalTime, val end: LocalTime) {
    val overnight: Boolean get() = end < start

    /** At or after [start] and before [end], across midnight when [overnight]. */
    operator fun contains(time: LocalTime): Boolean =
        if (overnight) time >= start || time < end else time >= start && time < end
}

/**
 * When each context mode runs (M1, M2). Settings › Time & modes changes the hours; the
 * days are fixed:
 * - weeknight evenings, Sunday to Thursday (nights before a workday);
 * - weekend evenings, Friday and Saturday;
 * - weekend mornings, Saturday and Sunday;
 * - late night, every day.
 *
 * Daytime is any other time. An evening that runs past midnight belongs to the day it
 * started on.
 */
data class ContextSchedule(
    val weeknightEvening: Hours = Hours(LocalTime.of(18, 0), LocalTime.of(22, 30)),
    val weekendEvening: Hours = Hours(LocalTime.of(18, 0), LocalTime.of(22, 30)),
    val lateNight: Hours = Hours(LocalTime.of(22, 30), LocalTime.of(5, 0)),
    val weekendMorning: Hours = Hours(LocalTime.of(7, 0), LocalTime.of(12, 0)),
) {
    /** Null for [ContextMode.DAYTIME], which has no hours of its own. */
    fun hours(mode: ContextMode): Hours? = when (mode) {
        ContextMode.WEEKNIGHT_EVENING -> weeknightEvening
        ContextMode.WEEKEND_EVENING -> weekendEvening
        ContextMode.LATE_NIGHT -> lateNight
        ContextMode.WEEKEND_MORNING -> weekendMorning
        ContextMode.DAYTIME -> null
    }

    fun with(mode: ContextMode, hours: Hours): ContextSchedule = when (mode) {
        ContextMode.WEEKNIGHT_EVENING -> copy(weeknightEvening = hours)
        ContextMode.WEEKEND_EVENING -> copy(weekendEvening = hours)
        ContextMode.LATE_NIGHT -> copy(lateNight = hours)
        ContextMode.WEEKEND_MORNING -> copy(weekendMorning = hours)
        ContextMode.DAYTIME -> this
    }

    fun modeAt(time: LocalDateTime): ContextMode =
        SCHEDULED.firstOrNull { runs(it, time) } ?: ContextMode.DAYTIME

    /**
     * Why these hours can't be used, or null when they can: a mode with no time at all, or
     * two modes at the same time on a day they share ("Weekend evening would overlap Late night").
     */
    fun problem(): String? {
        SCHEDULED.firstOrNull { hours(it)!!.start == hours(it)!!.end }?.let { return "${it.label} needs a start and an end" }
        // Every quarter hour of a week from Monday: no two modes at once.
        val monday = LocalDateTime.of(2024, 1, 1, 0, 0)
        for (quarter in 0 until WEEK_QUARTERS) {
            val time = monday.plusMinutes(quarter * STEP_MINUTES)
            val running = SCHEDULED.filter { runs(it, time) }
            if (running.size > 1) return "${running[1].label} would overlap ${running[0].label}"
        }
        return null
    }

    private fun runs(mode: ContextMode, time: LocalDateTime): Boolean {
        val hours = hours(mode) ?: return false
        val clock = time.toLocalTime()
        if (clock !in hours) return false
        val days = DAYS.getValue(mode)
        // After midnight, an overnight mode is still the day it started on.
        val day = if (hours.overnight && clock < hours.end) time.dayOfWeek.minus(1) else time.dayOfWeek
        return day in days
    }

    companion object {
        val DEFAULT = ContextSchedule()

        /** Settings › Time & modes moves the hours in these steps. */
        const val STEP_MINUTES = 15L
        private const val WEEK_QUARTERS = 7 * 24 * 4

        /** Late night first: it ends an evening that runs into it. */
        private val SCHEDULED = listOf(
            ContextMode.LATE_NIGHT,
            ContextMode.WEEKEND_MORNING,
            ContextMode.WEEKEND_EVENING,
            ContextMode.WEEKNIGHT_EVENING,
        )

        private val DAYS = mapOf(
            ContextMode.WEEKNIGHT_EVENING to setOf(
                DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
            ),
            ContextMode.WEEKEND_EVENING to setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
            ContextMode.WEEKEND_MORNING to setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            ContextMode.LATE_NIGHT to DayOfWeek.entries.toSet(),
        )
    }
}
