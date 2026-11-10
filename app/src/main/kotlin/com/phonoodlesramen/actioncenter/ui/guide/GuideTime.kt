package com.phonoodlesramen.actioncenter.ui.guide

import com.phonoodlesramen.actioncenter.guide.GuideSlot
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * How the full guide writes times, in the home's style: "9:30" like the Quick Watch cutoff,
 * "1h 58m" like a runtime, "41m left" like Up Next. AM/PM only where the hour alone could
 * mislead.
 */
internal object GuideTime {
    private val Clock = DateTimeFormatter.ofPattern("h:mm")
    private val ClockAmPm = DateTimeFormatter.ofPattern("h:mm a")
    private val AmPm = DateTimeFormatter.ofPattern("a")
    private val Day = DateTimeFormatter.ofPattern("EEE d MMM")
    private const val MINUTE = 60_000L
    private const val HALF_DAY = 12 * 60 * MINUTE

    fun clock(millis: Long, zone: ZoneId): String = at(millis, zone).format(Clock)

    fun clockAmPm(millis: Long, zone: ZoneId): String = at(millis, zone).format(ClockAmPm)

    /** "8:30 – 10:41 PM", or "11:30 PM – 1:25 AM" across noon or midnight. */
    fun range(slot: GuideSlot, zone: ZoneId): String {
        val start = at(slot.startMillis, zone)
        val end = at(slot.endMillis, zone)
        return if (start.format(AmPm) == end.format(AmPm) && start.toLocalDate() == end.toLocalDate()) {
            "${start.format(Clock)} – ${end.format(ClockAmPm)}"
        } else {
            "${start.format(ClockAmPm)} – ${end.format(ClockAmPm)}"
        }
    }

    /** A slot's length: "1h 58m", "52m", "2h". */
    fun length(slot: GuideSlot): String = minutes(((slot.endMillis - slot.startMillis) / MINUTE).toInt())

    /** "41m left", "1h 17m left". */
    fun left(slot: GuideSlot, nowMillis: Long): String = "${minutes(slot.minutesLeftAt(nowMillis))} left"

    private fun minutes(total: Int): String = when {
        total < 60 -> "${total}m"
        total % 60 == 0 -> "${total / 60}h"
        else -> "${total / 60}h ${total % 60}m"
    }

    /** "Starts at 9:30" later today; AM/PM once it's 12 hours or more away; "tomorrow" past midnight. */
    fun startsAt(startMillis: Long, nowMillis: Long, zone: ZoneId): String {
        val start = at(startMillis, zone)
        val days = ChronoUnit.DAYS.between(at(nowMillis, zone).toLocalDate(), start.toLocalDate())
        return when {
            days == 0L && startMillis - nowMillis < HALF_DAY -> "Starts at ${start.format(Clock)}"
            days == 0L -> "Starts at ${start.format(ClockAmPm)}"
            days == 1L -> "Starts tomorrow at ${start.format(ClockAmPm)}"
            else -> "Starts ${start.format(Day)} at ${start.format(ClockAmPm)}"
        }
    }

    /** "Starts in 2h 19m", rounded up to the minute. */
    fun startsIn(startMillis: Long, nowMillis: Long): String =
        "Starts in ${minutes(((startMillis - nowMillis + MINUTE - 1) / MINUTE).toInt().coerceAtLeast(1))}"

    fun endedAt(endMillis: Long, zone: ZoneId): String = "Ended at ${clock(endMillis, zone)}"

    /** The window's day over the channel column: "Today", "Tomorrow", "Yesterday" or the date. */
    fun day(windowStart: Long, nowMillis: Long, zone: ZoneId): String {
        val day = at(windowStart, zone).toLocalDate()
        return when (ChronoUnit.DAYS.between(at(nowMillis, zone).toLocalDate(), day)) {
            0L -> "Today"
            1L -> "Tomorrow"
            -1L -> "Yesterday"
            else -> day.format(Day)
        }
    }

    /** A ruler mark: "8:30 PM" first, then "9:00", "9:30", with AM/PM again where it changes. */
    fun mark(millis: Long, previousMillis: Long?, zone: ZoneId): String {
        val time = at(millis, zone)
        val changed = previousMillis == null || at(previousMillis, zone).format(AmPm) != time.format(AmPm)
        return time.format(if (changed) ClockAmPm else Clock)
    }

    fun minuteOfDay(millis: Long, zone: ZoneId): Int = at(millis, zone).let { it.hour * 60 + it.minute }

    private fun at(millis: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(zone)
}
