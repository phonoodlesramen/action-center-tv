package com.phonoodlesramen.actioncenter.apps

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/** M5's buckets: the hours of a week that starts on Monday, in local time. */
object UsageHours {
    const val PER_DAY = 24
    const val PER_WEEK = 7 * PER_DAY

    /** The Monday that starts [date]'s week, as an epoch day. */
    fun weekStart(date: LocalDate): Long = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()

    /** 0 is Monday 0:00-1:00; 167 is Sunday 23:00-24:00. */
    fun hourOfWeek(time: LocalDateTime): Int = (time.dayOfWeek.value - 1) * PER_DAY + time.hour

    /** Saturday and Sunday; Monday to Friday are weekdays. */
    fun isWeekend(hourOfWeek: Int): Boolean = hourOfWeek >= 5 * PER_DAY
}

/**
 * M5's order from [UsageCounts]. Pure. Every week counts by how recent it is: this week
 * 1.0, fading linearly to 0.25 for the week 7 back; older weeks don't count.
 * - [AppScores.now]: opens within an hour either side of now (three hours of the day,
 *   wrapping across midnight), on every day of the same kind as today (weekday or weekend,
 *   by each bucket's own day), then all-day opens to break ties.
 * - [AppScores.allDay]: opens at any hour of any day.
 */
object UsageScore {
    /** This week and the 7 before it: what `usage.db` keeps. */
    const val WEEKS = 8
    const val OLDEST_WEIGHT = 0.25

    fun at(counts: UsageCounts, time: LocalDateTime): AppScores {
        val allDay = allDay(counts, time)
        return AppScores(now = ranked(around(counts, time), allDay), allDay = allDay)
    }

    /** Weighted opens by package around [time] on its kind of day. */
    fun around(counts: UsageCounts, time: LocalDateTime): Map<String, Double> {
        val hours = hoursAround(time)
        return opens(counts, time) { hours[it] }
    }

    /** Weighted opens by package at any hour of any day. */
    fun allDay(counts: UsageCounts, time: LocalDateTime): Map<String, Double> = opens(counts, time) { true }

    private inline fun opens(counts: UsageCounts, time: LocalDateTime, inHours: (Int) -> Boolean): Map<String, Double> {
        val thisWeek = UsageHours.weekStart(time.toLocalDate())
        val opens = HashMap<String, Double>()
        counts.rows.forEach { row ->
            if (!inHours(row.hourOfWeek)) return@forEach
            val weight = weekWeight(thisWeek, row.weekStart) ?: return@forEach
            opens.merge(row.packageName, row.opens * weight, Double::plus)
        }
        return opens
    }

    /**
     * The hours of the week around [time]: its hour of the day and the ones either side
     * (so 0:30 takes in 23:00), on each day of its kind. Sunday 23:00 is a weekend hour, so
     * it never counts towards Monday 0:30, nor Monday 0:00 towards Sunday 23:30.
     */
    fun hoursAround(time: LocalDateTime): BooleanArray {
        val hour = UsageHours.hourOfWeek(time)
        val weekend = UsageHours.isWeekend(hour)
        val hours = BooleanArray(UsageHours.PER_WEEK)
        for (day in 0 until 7) {
            for (offset in -1..1) {
                val bucket = day * UsageHours.PER_DAY + Math.floorMod(time.hour + offset, UsageHours.PER_DAY)
                if (UsageHours.isWeekend(bucket) == weekend) hours[bucket] = true
            }
        }
        return hours
    }

    /** 1.0 for [thisWeek], fading linearly to [OLDEST_WEIGHT] 7 weeks back; null for older weeks, or a later one after a clock change. */
    fun weekWeight(thisWeek: Long, weekStart: Long): Double? {
        val back = Math.floorDiv(thisWeek - weekStart, 7L)
        if (back !in 0 until WEEKS) return null
        return 1.0 - (1.0 - OLDEST_WEIGHT) * back / (WEEKS - 1)
    }

    /** The Monday (epoch day) of the oldest week that counts: older rows are pruned. */
    fun oldestWeek(thisWeek: Long): Long = thisWeek - 7L * (WEEKS - 1)

    /**
     * One number per package that sorts like (around now, then all day), for
     * [LaunchpadOrder]'s single usage map. Ranks, not sums, so a busy all-day app never
     * outranks one you open at this time; apps level on both share a rank, so launches from
     * here and A-Z still break the tie.
     */
    private fun ranked(now: Map<String, Double>, allDay: Map<String, Double>): Map<String, Double> {
        val level = { packageName: String -> (now[packageName] ?: 0.0) to allDay.getValue(packageName) }
        val levels = allDay.keys.map(level).distinct().sortedWith(compareBy({ it.first }, { it.second }))
        val rank = levels.withIndex().associate { (i, it) -> it to (i + 1).toDouble() }
        return allDay.keys.associateWith { rank.getValue(level(it)) }
    }
}
