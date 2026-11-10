package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.cache.EpisodeRow
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** S5's numbers, in one place for tuning. */
object PickRules {
    const val PICKS = 6

    /** The one series you haven't started sits third. */
    const val SERIES_AT = 2

    /**
     * "Finish it": a show you watched in the last 30 days, 4 or fewer episodes from a season's
     * end. When the cards are drawn, it leads from 4 out on weeknight evenings and from 3 at
     * other times (M3, `ModeRules`).
     */
    const val FINISH_WITHIN = 4
    const val FINISH_WATCHED_DAYS = 30L
}

/**
 * A title's score for the hero (plan §3): your taste, a nudge of up to ±0.1 for its rating
 * (unrated counts as 5), and a little more when it came to Plex in the last 30 days.
 */
object Scoring {
    const val RATING_NUDGE = 0.2
    const val UNRATED = 5.0
    const val NEW_BONUS = 0.15
    const val NEW_DAYS = 30L

    /** What a pick loses for each pick above it with the same top genre, so the list varies. */
    const val SAME_TOP_GENRE = 0.1

    fun score(title: ItemEntity, affinity: Affinity, input: RunInput): Double {
        val rating = title.audienceRating ?: title.rating ?: UNRATED
        val new = Reasons.addedDays(title, input)?.let { it <= NEW_DAYS } == true
        return affinity.of(title) + RATING_NUDGE * (rating / 10 - 0.5) + if (new) NEW_BONUS else 0.0
    }

    /** A title's first genre: the one the variety rule compares. */
    fun topGenre(title: ItemEntity): String? = title.genres.split('|').map(String::trim).firstOrNull(String::isNotEmpty)

    /** The day's tie-break for a title. */
    fun seed(ratingKey: String, day: LocalDate): Int = Seeds.tieBreak(ratingKey, day)
}

/**
 * Tonight's Pick (S5): six picks. A "finish it" episode first when a show you watched this
 * month is close to a season's end, then your best-scored films you haven't seen, with one
 * series you haven't started third. Nothing Not tonight hides, and nothing you've watched
 * or started. The same library gives the same list all day, and a new one tomorrow; the
 * context mode reorders it when the cards are drawn (M3, `ModeRules`). Pure.
 */
object TonightsPick {
    private const val DAY_SECONDS = 86_400L

    fun pick(input: RunInput, taste: Taste): List<Picked> {
        val library = input.library
        val catalog = Catalog.of(library)
        val hidden = input.hides.notTonight
        fun scored(title: ItemEntity) =
            Scored(title, Scoring.score(title, taste, input), Scoring.seed(title.ratingKey, input.today), Scoring.topGenre(title))

        val films = library.titles
            .filter { it.type == "movie" && it.ratingKey !in hidden && it.partKey != null && !catalog.started(it) && !disliked(it) }
            .mapTo(mutableListOf(), ::scored)
        val firstEpisodes = library.titles
            .filter { it.type == "show" && it.ratingKey !in hidden && !catalog.started(it) && !disliked(it) }
            .mapNotNull { show -> catalog.episodes(show.ratingKey).firstOrNull()?.takeIf { it.ratingKey !in hidden }?.let { show to it } }
        var series = firstEpisodes.map { (show, _) -> scored(show) }

        val picks = mutableListOf<Picked>()
        val above = mutableListOf<String?>()
        finishing(input, catalog)?.let {
            picks += it.picked(input)
            above += Scoring.topGenre(it.show)
        }
        while (picks.size < PickRules.PICKS) {
            val show = if (picks.size == PickRules.SERIES_AT || films.isEmpty()) best(series, above) else null
            if (show != null) {
                val episode = firstEpisodes.first { it.first === show.title }.second
                picks += Reasons.of(show.title, taste, input).copy(ratingKey = episode.ratingKey)
                above += show.genre
                series = emptyList()
                continue
            }
            val film = best(films, above) ?: break
            films -= film
            picks += Reasons.of(film.title, taste, input)
            above += film.genre
        }
        return picks
    }

    private class Scored(val title: ItemEntity, val score: Double, val seed: Int, val genre: String?)

    /** The best of [pool], each pick above with its top genre taking [Scoring.SAME_TOP_GENRE] off. */
    private fun best(pool: List<Scored>, above: List<String?>): Scored? = pool.minWithOrNull(
        compareByDescending<Scored> { scored -> scored.score - Scoring.SAME_TOP_GENRE * above.count { it != null && it == scored.genre } }
            .thenBy { it.seed }
            .thenBy { it.title.ratingKey },
    )

    /** Rated 2 stars or fewer: you've said no. */
    private fun disliked(title: ItemEntity) = (title.userRating ?: Double.MAX_VALUE) <= SignalWeights.DISLIKED_RATING

    private class Finishing(val show: ItemEntity, val season: Int, val next: EpisodeRow, val left: Int, val lastSeason: Boolean, val lastWatched: Long) {
        fun picked(input: RunInput): Picked {
            val episodes = if (left == 1) "1 episode" else "$left episodes"
            val end = if (lastSeason) show.title else "${show.title}'s season $season"
            val days = ChronoUnit.DAYS.between(input.dayOf(lastWatched), input.today).coerceAtLeast(0)
            val evidence = listOfNotNull("Last watched ${Reasons.daysAgo(days)}", Reasons.rating(show)?.let { "Rated ${Reasons.oneDecimal(it)}" })
            return Picked(next.ratingKey, "You're $episodes from the end of $end", evidence, episodesLeft = left)
        }
    }

    /**
     * A show with a finished episode in the last 30 days and 1-4 episodes left in the season
     * you're watching (the one your latest finished episode is in; an older season with gaps
     * doesn't count): the next one after the furthest you've watched. The closest to the end
     * wins, then the one you watched most recently.
     */
    private fun finishing(input: RunInput, catalog: Catalog): Finishing? {
        val hidden = input.hides.notTonight
        val since = input.now / 1_000 - PickRules.FINISH_WATCHED_DAYS * DAY_SECONDS
        val found = input.library.titles.filter { it.type == "show" && it.ratingKey !in hidden }.mapNotNull { show ->
            val episodes = catalog.episodes(show.ratingKey)
            val (current, latest) = episodes.mapNotNull { episode -> catalog.finishedAt(episode)?.let { episode to it } }
                .maxByOrNull { it.second } ?: return@mapNotNull null
            if (latest < since) return@mapNotNull null
            val season = current.season ?: 1
            val rows = episodes.filter { (it.season ?: 1) == season }
            val furthest = rows.indexOfLast(catalog::watched)
            val left = rows.size - furthest - 1
            val next = rows.getOrNull(furthest + 1)
            if (furthest < 0 || left > PickRules.FINISH_WITHIN || next == null || next.ratingKey in hidden) return@mapNotNull null
            Finishing(show, season, next, left, season == episodes.maxOf { it.season ?: 1 }, latest)
        }
        return found.minWithOrNull(
            compareBy<Finishing> { it.left }
                .thenByDescending { it.lastWatched }
                .thenBy { Scoring.seed(it.next.ratingKey, input.today) }
                .thenBy { it.next.ratingKey },
        )
    }
}
