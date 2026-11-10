package com.phonoodlesramen.actioncenter.suggest

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Keeping the lenses fresh (Phase 14, L5). A title that has been on the lens card on [days]
 * days since it was last played rests from every lens for 30 days
 * ([FeedbackRules.REST_MILLIS]), and the lens's next title takes its place. Today doesn't
 * count until it's over, so a title never leaves mid-evening. Pure.
 */
object LensFreshness {
    /** Days on the card are kept this long, well past any count. */
    const val KEEP_DAYS = 60L

    /** The rests to start at [now] for [profile]: none when [days] is null ("Once it's watched"). */
    fun rests(shown: List<LensShownEntity>, days: Int?, library: Library, profile: String, now: Long, zone: ZoneId): List<FeedbackEntity> {
        if (days == null) return emptyList()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return shown.groupBy { it.ratingKey }.mapNotNull { (key, rows) ->
            val played = library.lastViewed(key)?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
            val counted = rows.mapNotNull { day(it.day) }.filter { it < today && (played == null || it > played) }.distinct()
            if (counted.size < days) return@mapNotNull null
            FeedbackRules.record(previous = null, profile, key, FeedbackKind.LENS_RESTED, scope = "", now)
        }
    }

    private fun day(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()
}
