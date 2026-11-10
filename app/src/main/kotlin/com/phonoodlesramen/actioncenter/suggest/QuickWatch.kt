package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.cache.ContinueEntity
import com.phonoodlesramen.actioncenter.plex.cache.EpisodeRow
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Quick Watch (S6): what fits before your cutoff. The engine lists [candidates] after each
 * sync, best first; [fit] keeps the ones that fit when the card is drawn, so the minute
 * tick never runs the engine. Pure.
 */
object QuickWatch {
    /** Settings › Time & modes' cutoff until you change it: every night but Friday's and Saturday's. */
    val DEFAULT_CUTOFF: LocalTime = LocalTime.of(22, 30)

    /** Friday and Saturday nights' cutoff until you change it (M4). */
    val WEEKEND_CUTOFF: LocalTime = LocalTime.of(23, 30)

    /** This long before the cutoff, Quick Watch becomes the next 45 minutes, rounded up to the quarter hour. */
    const val SWITCH_BEFORE_MINUTES = 30L
    const val LATE_MINUTES = 45L
    const val ROUND_TO_MINUTES = 15

    /** The longest anything in Quick Watch runs, or has left. */
    const val MAX_MINUTES = 45L
    const val MAX_CANDIDATES = 12

    /** A show's next episode counts if you finished an episode of it this recently. */
    const val RECENT_SHOW_DAYS = 30L

    /** A cutoff before this (12:30 AM, say) belongs to the night before, not the morning. */
    private val NIGHT_ENDS: LocalTime = LocalTime.of(5, 0)
    private val WEEKEND_NIGHTS = setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
    private const val DAY_MILLIS = 24 * 60 * 60_000L
    private const val NEXT_EPISODE = "Next episode"

    /**
     * Tonight's setting (M4): [weekends] on Friday and Saturday nights, [weeknights] on every
     * other. A night belongs to the day it started: before 5 AM is still the night before, so
     * 1 AM on a Saturday is Friday's night.
     */
    fun settingFor(now: LocalDateTime, weeknights: LocalTime, weekends: LocalTime): LocalTime {
        val night = if (now.toLocalTime() < NIGHT_ENDS) now.toLocalDate().minusDays(1) else now.toLocalDate()
        return if (night.dayOfWeek in WEEKEND_NIGHTS) weekends else weeknights
    }

    /**
     * The cutoff at [now]: [setting] until 30 minutes before it, then now + 45 minutes. An
     * evening setting starts over at midnight, as the card always has; one after midnight
     * holds all evening, and its late stretch runs on to 5 AM.
     */
    fun cutoff(setting: LocalTime, now: LocalTime): LocalTime {
        val switch = setting.minusMinutes(SWITCH_BEFORE_MINUTES)
        val late = when {
            setting >= NIGHT_ENDS -> now >= switch
            // A cutoff after midnight: the switch may come before it, and the night runs on to morning.
            switch > setting -> now >= switch || now < NIGHT_ENDS
            else -> now >= switch && now < NIGHT_ENDS
        }
        if (!late) return setting
        val later = now.plusMinutes(LATE_MINUTES)
        return later.truncatedTo(ChronoUnit.HOURS)
            .plusMinutes(((later.minute + ROUND_TO_MINUTES - 1) / ROUND_TO_MINUTES * ROUND_TO_MINUTES).toLong())
    }

    /**
     * Up to 12, best first. Resumes and next episodes from Continue Watching come first, the
     * least time left first; then short titles you haven't seen (45 minutes or less), by
     * taste: films with a file, and the next episode of a show you finished one of in the
     * last 30 days. Hidden from Quick Watch or Not tonight is out everywhere, by movie or
     * show; Remove from Up Next also takes an item out of the first part.
     */
    fun candidates(input: RunInput): List<Picked> {
        val library = input.library
        val hides = input.hides
        fun hidden(keys: Set<String>, vararg sets: Set<String>) = sets.any { set -> keys.any { it in set } }

        val inContinue = library.continueWatching.flatMapTo(mutableSetOf()) { UpNextRank.keysOf(it, library) }
        val resumes = library.continueWatching
            .map { it to minutesLeft(it) }
            .filter { (item, left) ->
                left in 1..MAX_MINUTES &&
                    !hidden(UpNextRank.keysOf(item, library), hides.quickWatch, hides.upNext, hides.notTonight)
            }
            .sortedWith(compareBy<Pair<ContinueEntity, Long>> { it.second }.thenBy { it.first.position })
            .map { (item, left) -> Picked(item.ratingKey, resumeReason(item, left)) }

        val since = input.now - RECENT_SHOW_DAYS * DAY_MILLIS
        val films = library.titles.filter { title ->
            title.type == "movie" && title.partKey != null && title.viewCount == 0 && (title.viewOffsetMs ?: 0) <= 0 &&
                minutes(title.durationMs) in 1..MAX_MINUTES
        }.map { ShortTitle(it.ratingKey, it, episode = false) }
        val episodes = library.titles.filter { it.type == "show" }.mapNotNull { show ->
            nextEpisode(show.ratingKey, library)
                ?.takeIf { minutes(it.durationMs) in 1..MAX_MINUTES }
                ?.takeIf { UpNextRank.episodesFinishedSince(show.ratingKey, library, since).isNotEmpty() }
                ?.let { ShortTitle(it.ratingKey, show, episode = true) }
        }
        val unseen = (films + episodes)
            .filter { candidate -> candidate.keys.none { it in inContinue } && !hidden(candidate.keys, hides.quickWatch, hides.notTonight) }
            .map { it to input.affinity.of(it.title) }
            .sortedWith(
                compareByDescending<Pair<ShortTitle, Double>> { it.second }
                    .thenBy { Seeds.tieBreak(it.first.ratingKey, input.today) }
                    .thenBy { it.first.ratingKey },
            )
            .take((MAX_CANDIDATES - resumes.size).coerceAtLeast(0))
            .map { (candidate, _) ->
                Picked(candidate.ratingKey, if (candidate.episode) NEXT_EPISODE else input.reason(candidate.title) ?: "Short and unseen")
            }

        return (resumes + unseen).take(MAX_CANDIDATES)
    }

    /**
     * What fits now: each candidate with its minutes left, kept when it runs 1 minute to the
     * lesser of 45 and the minutes until [cutoff] (past midnight, as the card does). Keeps
     * the candidates' order.
     */
    fun fit(candidates: List<Pair<Picked, Long>>, now: LocalTime, cutoff: LocalTime, limit: Int = 2): List<Picked> {
        val untilCutoff = Duration.between(now, cutoff).toMinutes().let { if (it < 0) it + 24 * 60 else it }
        return candidates.filter { (_, minutes) -> minutes in 1..minOf(MAX_MINUTES, untilCutoff) }.take(limit).map { it.first }
    }

    /** A short title: a film, or a show's next episode ([title] is then the show). */
    private class ShortTitle(val ratingKey: String, val title: ItemEntity, val episode: Boolean) {
        val keys = setOf(ratingKey, title.ratingKey)
    }

    private fun resumeReason(item: ContinueEntity, left: Long) = when {
        (item.viewOffsetMs ?: 0) > 0 -> "${left}m left"
        item.type == "episode" -> NEXT_EPISODE
        else -> "In your Continue Watching"
    }

    private fun minutesLeft(item: ContinueEntity): Long = (((item.durationMs ?: 0) - (item.viewOffsetMs ?: 0)) / 60_000).coerceAtLeast(0)

    private fun minutes(durationMs: Long?): Long = (durationMs ?: 0) / 60_000

    /** The episode after the furthest one you've watched, if you haven't started it. Specials don't count. */
    private fun nextEpisode(showKey: String, library: Library): EpisodeRow? {
        val episodes = library.episodesOf(showKey)
            .filter { (it.season ?: 1) != 0 }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        val furthest = episodes.indexOfLast { it.viewCount > 0 }
        if (furthest < 0) return null
        return episodes.getOrNull(furthest + 1)?.takeIf { it.viewCount == 0 && (it.viewOffsetMs ?: 0) <= 0 }
    }
}
