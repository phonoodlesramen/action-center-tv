package com.phonoodlesramen.actioncenter.apps

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The launcher's own record of which apps you open, and when (M5), kept from Android's
 * usage stats (which only keep a few days of events).
 */
interface AppUsage {
    /** Everything recorded so far; the home re-orders the apps when it changes. */
    val counts: Flow<UsageCounts>

    /**
     * Reads the events since the last read, at most every 15 minutes (it's asked on every
     * resume and after every Plex sync). Never throws: without usage access it records nothing.
     */
    suspend fun refresh()

    /** No usage access, and in previews and tests: the launch counts order the apps. */
    companion object None : AppUsage {
        override val counts: Flow<UsageCounts> = flowOf(UsageCounts())

        override suspend fun refresh() {}
    }
}

/** [opens] of [packageName] in [hourOfWeek] (0 is Monday 0:00-1:00) of the week that began on [weekStart] (its Monday's epoch day). */
data class UsageRow(val packageName: String, val weekStart: Long, val hourOfWeek: Int, val opens: Int)

data class UsageCounts(val rows: List<UsageRow> = emptyList())

/**
 * How strongly each app (by package) leads, from [UsageScore]:
 * - [now] is the Apps row's order (M5): opens around this time on this kind of day, then
 *   all-day opens, as one number per package (higher first);
 * - [allDay] is opens at any time, for All apps' Most used.
 */
data class AppScores(val now: Map<String, Double> = emptyMap(), val allDay: Map<String, Double> = emptyMap())
