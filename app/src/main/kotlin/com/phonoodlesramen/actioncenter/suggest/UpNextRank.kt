package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.cache.ContinueEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/** S3's weights and windows, in one place for tuning. */
object UpNextWeights {
    const val RECENCY = 0.4
    const val MOMENTUM = 0.3
    const val HABIT = 0.2
    const val PROGRESS = 0.1

    /** Recency halves for every week since you last watched. */
    const val RECENCY_HALF_LIFE_DAYS = 7.0

    /** Episodes of the show you finished in the last two weeks; five is full momentum. */
    const val MOMENTUM_DAYS = 14L
    const val MOMENTUM_FULL = 5

    /** A next episode (no position) counts as this far in. */
    const val NEXT_EPISODE_PROGRESS = 0.3

    /** Untouched this long sinks below everything fresh. */
    const val STALE_DAYS = 30L

    /** The reasons: 2+ episodes for momentum, half of 3+ plays for a habit. */
    const val MOMENTUM_REASON = 2
    const val HABIT_REASON_SHARE = 0.5
    const val HABIT_REASON_PLAYS = 3
}

/**
 * Up Next (S3, S4): Plex's Continue Watching, ordered by how likely you are to carry on.
 * Each item scores on recency, momentum (episodes you've just finished), habit (you watch
 * it at this time, on this kind of day) and how far in you are. Anything untouched for 30
 * days sinks below the rest; Plex's order breaks ties. Pure.
 */
object UpNextRank {
    private const val DAY_MILLIS = 24 * 60 * 60_000L
    private const val WEEK_DAYS = 7L
    private val WEEKEND_NIGHTS = setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)

    /** Times of day for habits. Late runs from 10:30 PM past midnight to 5 AM. */
    enum class Band(val from: LocalTime, val phrase: String) {
        MORNING(LocalTime.of(5, 0), "in the morning"),
        AFTERNOON(LocalTime.NOON, "in the afternoon"),
        EVENING(LocalTime.of(18, 0), "in the evening"),
        LATE(LocalTime.of(22, 30), "late at night"),
    }

    fun rank(input: RunInput): List<Picked> {
        val library = input.library
        return library.continueWatching
            .filter { item -> keysOf(item, library).none { it in input.hides.upNext } }
            .map { score(it, input) }
            .sortedWith(compareBy<Scored> { it.stale }.thenByDescending { it.score }.thenBy { it.item.position })
            .map { Picked(it.item.ratingKey, it.reason) }
    }

    /** What a Continue Watching item counts as: a movie, or an episode's show. */
    internal fun titleKey(item: ContinueEntity, library: Library): String =
        if (item.type == "episode") item.showKey ?: library.showOf[item.ratingKey] ?: item.ratingKey else item.ratingKey

    /** The keys feedback can hide an item by: its own, and an episode's show. */
    internal fun keysOf(item: ContinueEntity, library: Library): Set<String> = setOf(item.ratingKey, titleKey(item, library))

    /** The show's episodes you finished since [sinceMillis]: plays in history, plus watched episodes, each once. */
    internal fun episodesFinishedSince(showKey: String, library: Library, sinceMillis: Long): Set<String> =
        library.playsOf(showKey).filter { it.viewedAt * 1_000 >= sinceMillis }.mapTo(mutableSetOf()) { it.ratingKey } +
            library.episodesOf(showKey).filter { it.viewCount > 0 && (it.lastViewedAt ?: 0) * 1_000 >= sinceMillis }.map { it.ratingKey }

    internal fun band(time: LocalDateTime): Band = Band.entries.lastOrNull { time.toLocalTime() >= it.from } ?: Band.LATE

    /** Friday and Saturday nights. The hours after midnight belong to the night before. */
    internal fun weekendNight(time: LocalDateTime): Boolean {
        val night = if (time.toLocalTime() < Band.MORNING.from) time.minusDays(1) else time
        return night.dayOfWeek in WEEKEND_NIGHTS
    }

    private class Scored(val item: ContinueEntity, val score: Double, val stale: Boolean, val reason: String)

    private fun score(item: ContinueEntity, input: RunInput): Scored {
        val library = input.library
        val key = titleKey(item, library)
        val lastViewed = item.lastViewedAt ?: library.lastViewed(key)
        val ageMillis = lastViewed?.let { (input.now - it * 1_000).coerceAtLeast(0) }
        val recency = ageMillis?.let { 0.5.pow(it / (UpNextWeights.RECENCY_HALF_LIFE_DAYS * DAY_MILLIS)) } ?: 0.0

        fun finishedInLast(days: Long) =
            if (item.type == "episode") episodesFinishedSince(key, library, input.now - days * DAY_MILLIS).size else 0
        val fortnight = finishedInLast(UpNextWeights.MOMENTUM_DAYS)
        val momentum = min(fortnight, UpNextWeights.MOMENTUM_FULL).toDouble() / UpNextWeights.MOMENTUM_FULL

        val plays = library.playsOf(key)
        val band = band(input.time)
        val weekend = weekendNight(input.time)
        val alike = plays.count { play ->
            val at = LocalDateTime.ofInstant(Instant.ofEpochSecond(play.viewedAt), input.zone)
            band(at) == band && weekendNight(at) == weekend
        }
        val habit = if (plays.isEmpty()) 0.0 else alike.toDouble() / plays.size

        val score = UpNextWeights.RECENCY * recency + UpNextWeights.MOMENTUM * momentum +
            UpNextWeights.HABIT * habit + UpNextWeights.PROGRESS * progress(item)
        val days = lastViewed?.let { ChronoUnit.DAYS.between(input.dayOf(it), input.today) }
        val week = if (fortnight >= UpNextWeights.MOMENTUM_REASON) finishedInLast(WEEK_DAYS) else 0
        val reason = when {
            week >= UpNextWeights.MOMENTUM_REASON -> "$week episodes this week"
            fortnight >= UpNextWeights.MOMENTUM_REASON -> "$fortnight episodes in two weeks"
            plays.size >= UpNextWeights.HABIT_REASON_PLAYS && habit >= UpNextWeights.HABIT_REASON_SHARE -> "You usually watch this ${band.phrase}"
            days == null || days >= UpNextWeights.STALE_DAYS -> "In your Continue Watching"
            days <= 0 -> "Watched today"
            days == 1L -> "Watched yesterday"
            else -> "Watched $days days ago"
        }
        val stale = ageMillis != null && ageMillis >= UpNextWeights.STALE_DAYS * DAY_MILLIS
        return Scored(item, score, stale, reason)
    }

    /** Half-way scores best: barely started and nearly done both score low. */
    private fun progress(item: ContinueEntity): Double {
        val offset = item.viewOffsetMs ?: 0
        val duration = item.durationMs ?: 0
        if (offset <= 0 || duration <= 0) return UpNextWeights.NEXT_EPISODE_PROGRESS
        val fraction = (offset.toDouble() / duration).coerceIn(0.0, 1.0)
        return 1 - abs(fraction - 0.5) * 2
    }
}
