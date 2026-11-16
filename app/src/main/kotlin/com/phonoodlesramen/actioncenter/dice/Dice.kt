package com.phonoodlesramen.actioncenter.dice

import com.phonoodlesramen.actioncenter.suggest.Catalog
import com.phonoodlesramen.actioncenter.suggest.Library
import com.phonoodlesramen.actioncenter.suggest.SignalWeights
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

/**
 * A title the die can land on (the die, spec §4): a movie or a show, as Play starts it.
 * [ratingKey] is the movie or show; [playKey] is what Play hands off, the movie or the
 * show's next episode ([episode] names it). [lastSeenYear] is when you last watched it,
 * over a year ago, or null when you never have (or Plex doesn't say when).
 */
data class DieTitle(
    val ratingKey: String,
    val playKey: String,
    val type: String,
    val title: String,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val contentRating: String? = null,
    /** Plex's audience rating, else the critics', 0-10. */
    val rating: Double? = null,
    /** A movie's length, or the length of the episode Play starts. */
    val durationMs: Long? = null,
    val art: String? = null,
    val trailerKey: String? = null,
    /** "S1 · E1": the episode Play starts, for a show. */
    val episode: String? = null,
    /** You've seen it, a show started: never in the last year, or it wouldn't be on the table. */
    val seen: Boolean = false,
    val lastSeenYear: Int? = null,
    /** A show you'd started: Play picks up at [episode] rather than starting it. */
    val picksUp: Boolean = false,
) {
    val decade: Int? get() = year?.let { it / 10 * 10 }
}

/** The tray's first dial: what kind of title. */
enum class DieKind(val label: String) {
    ANY("Anything"),
    MOVIES("Movies"),
    SHOWS("Shows"),
}

/** The tray's three dials (spec §4, the die). A null [genre] or [decade] is any. */
data class DiceFilters(val kind: DieKind = DieKind.ANY, val genre: String? = null, val decade: Int? = null) {
    fun admits(title: DieTitle): Boolean =
        when (kind) {
            DieKind.ANY -> true
            DieKind.MOVIES -> title.type == MOVIE
            DieKind.SHOWS -> title.type == SHOW
        } &&
            (genre == null || title.genres.any { it.equals(genre, ignoreCase = true) }) &&
            (decade == null || title.decade == decade)

    /** Whether every dial is on Any. */
    val open: Boolean get() = kind == DieKind.ANY && genre == null && decade == null
}

/** One stop on a dial: its [value] (null is Any), its [label], and how many titles it would leave with the other two dials as they are. */
data class DialStop<T>(val value: T, val label: String, val count: Int)

/**
 * Everything on the table for whoever's watching. A dial's stops keep their order whatever the
 * other dials say (genres by how many titles each has here, decades newest first), so the
 * remote finds them where it left them; only their counts move.
 */
class DiceDeck(val titles: List<DieTitle>) {
    private val genreOrder: List<String> = titles.asSequence().flatMap { it.genres.asSequence() }
        .groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }

    private val decadeOrder: List<Int> = titles.mapNotNull { it.decade }.distinct().sortedDescending()

    val isEmpty: Boolean get() = titles.isEmpty()

    fun matching(filters: DiceFilters): List<DieTitle> = titles.filter(filters::admits)

    fun kinds(filters: DiceFilters): List<DialStop<DieKind>> =
        DieKind.entries.map { DialStop(it, it.label, count(filters.copy(kind = it))) }

    /** Any, then up to [Dice.MAX_GENRES] genres, the biggest first; the chosen one is always there. */
    fun genres(filters: DiceFilters): List<DialStop<String?>> {
        val shown = genreOrder.take(Dice.MAX_GENRES).let { top ->
            val chosen = filters.genre
            if (chosen == null || top.any { it.equals(chosen, ignoreCase = true) }) top else top + chosen
        }
        return listOf(DialStop<String?>(null, ANY_GENRE, count(filters.copy(genre = null)))) +
            shown.map { DialStop(it, it, count(filters.copy(genre = it))) }
    }

    /** Any, then each decade in the library, newest first. */
    fun decades(filters: DiceFilters): List<DialStop<Int?>> {
        val shown = (decadeOrder + listOfNotNull(filters.decade)).distinct().sortedDescending()
        return listOf(DialStop<Int?>(null, ANY_DECADE, count(filters.copy(decade = null)))) +
            shown.map { DialStop(it, "${it}s", count(filters.copy(decade = it))) }
    }

    private fun count(filters: DiceFilters) = titles.count(filters::admits)

    companion object {
        val Empty = DiceDeck(emptyList())
        const val ANY_GENRE = "Any genre"
        const val ANY_DECADE = "Any decade"
    }
}

/**
 * One throw: [title], landed on [face], the [throws]th with these dials, from a [pool] of
 * titles. [startedOver]: every title that fits had come up, so the table started over.
 */
data class DieRoll(val title: DieTitle, val face: Int, val throws: Int, val pool: Int, val startedOver: Boolean = false) {
    /** The eyebrow over the title (spec §4, the die): a little more to say each time you roll again. */
    val line: String
        get() = when {
            pool == 1 -> "The only one that fits"
            startedOver -> "That's all of them. Round two"
            throws <= 1 -> "The die has spoken"
            throws == 2 -> "Rolled again"
            throws == 3 -> "Third time's the charm"
            throws == 4 -> "Still deciding?"
            throws <= 6 -> "The die is getting dizzy"
            else -> "Roll $throws. The die believes in you"
        }
}

/**
 * The die (spec §4): your library on a table, and a throw that lands on one title. Pure.
 *
 * - **What's on the table:** films with a file and shows with an episode; not what you've
 *   watched in the last year (or started), nothing in Continue Watching (that's Up Next's),
 *   nothing Not tonight hides, nothing you rated 2 stars or fewer. A kid's table is their
 *   own library view (their ceiling, their plays).
 * - **The throw is loaded, a little:** a title you've never seen is [UNSEEN_WEIGHT] times as
 *   likely as one you saw more than a year ago.
 * - **No repeats:** what's come up tonight doesn't come up again until everything that fits
 *   has, and then the table starts over.
 */
object Dice {
    const val UNSEEN_WEIGHT = 3
    const val SEEN_WEIGHT = 1

    /** Watched or played this recently, a title stays off the table. */
    const val REST_DAYS = 365L

    /** The genre dial's stops at most, besides Any. */
    const val MAX_GENRES = 24

    private const val DAY_SECONDS = 86_400L

    fun deck(library: Library, notTonight: Set<String>, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): DiceDeck {
        val catalog = Catalog.of(library)
        val since = nowMillis / 1_000 - REST_DAYS * DAY_SECONDS
        val inProgress = library.continueWatching.flatMapTo(HashSet()) { listOfNotNull(it.ratingKey, it.showKey) }
        val titles = library.titles.mapNotNull { title ->
            val key = title.ratingKey
            if (key in notTonight || key in inProgress) return@mapNotNull null
            if ((title.userRating ?: Double.MAX_VALUE) <= SignalWeights.DISLIKED_RATING) return@mapNotNull null
            val last = library.lastViewed(key)
            if (last != null && last >= since) return@mapNotNull null
            val seen = catalog.watched(title)
            val lastSeenYear = last?.let { Instant.ofEpochSecond(it).atZone(zone).year }
            val genres = title.genres.split('|').map(String::trim).filter(String::isNotEmpty)
            val rating = title.audienceRating ?: title.rating
            when (title.type) {
                MOVIE -> {
                    if (title.partKey == null || (title.viewOffsetMs ?: 0) > 0) return@mapNotNull null
                    DieTitle(
                        key, key, MOVIE, title.title, title.year, genres, title.contentRating, rating, title.durationMs,
                        title.art ?: title.thumb, title.trailerKey, seen = seen, lastSeenYear = lastSeenYear,
                    )
                }
                SHOW -> {
                    val next = catalog.nextEpisode(key) ?: return@mapNotNull null
                    if (next.ratingKey in notTonight || (next.viewOffsetMs ?: 0) > 0) return@mapNotNull null
                    DieTitle(
                        key, next.ratingKey, SHOW, title.title, title.year, genres, title.contentRating, rating, next.durationMs,
                        title.art ?: title.thumb, episode = episodeLabel(next.season, next.episode), seen = seen, lastSeenYear = lastSeenYear,
                        picksUp = catalog.episodes(key).firstOrNull()?.ratingKey != next.ratingKey,
                    )
                }
                else -> null
            }
        }
        return DiceDeck(titles.sortedBy { it.ratingKey })
    }

    /**
     * A throw at what fits [filters]: one title, weighted, from those that haven't come up
     * ([rolled]); all of them again once every one has. [throws] counts this one. Null when
     * nothing fits.
     */
    fun roll(deck: DiceDeck, filters: DiceFilters, rolled: Set<String>, throws: Int, random: Random): DieRoll? {
        val pool = deck.matching(filters)
        if (pool.isEmpty()) return null
        val fresh = pool.filter { it.ratingKey !in rolled }
        val table = fresh.ifEmpty { pool }
        var at = random.nextInt(table.sumOf(::weight))
        val title = table.first { at -= weight(it); at < 0 }
        return DieRoll(title, face = random.nextInt(1, 7), throws = throws, pool = pool.size, startedOver = fresh.isEmpty())
    }

    fun weight(title: DieTitle): Int = if (title.seen) SEEN_WEIGHT else UNSEEN_WEIGHT

    private fun episodeLabel(season: Int?, episode: Int?): String? = when {
        season != null && episode != null -> "S$season · E$episode"
        episode != null -> "Episode $episode"
        else -> null
    }
}

/** The result card's lines under the title (spec §4, the die). Pure. */
object DieLines {
    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /** "1995 · R · 2h 50m · Crime, Drama", or for a show "2019 · TV-14 · 48m episodes · Comedy". */
    fun meta(title: DieTitle): String = listOfNotNull(
        title.year?.toString(),
        title.contentRating?.takeIf { it.isNotBlank() },
        length(title.durationMs)?.let { if (title.type == SHOW) "$it episodes" else it },
        title.genres.take(2).joinToString(", ").takeIf { it.isNotEmpty() },
    ).joinToString(" · ")

    /**
     * Why it's a fair landing, and when it'd be over: "New to you · over by 10:42 PM", "Last
     * seen in 2021", "Picks up at S2 · E4", "Starts at S1 · E1".
     */
    fun detail(title: DieTitle, now: LocalDateTime): String {
        val seen = when {
            title.type == SHOW && title.picksUp -> "Picks up at ${title.episode ?: "the next episode"}"
            title.type == SHOW && title.seen -> listOfNotNull("From the top", title.episode).joinToString(" · ")
            title.type == SHOW -> listOfNotNull("New to you", title.episode?.let { "starts at $it" }).joinToString(" · ")
            title.lastSeenYear != null -> "Last seen in ${title.lastSeenYear}"
            title.seen -> "Seen it before"
            else -> "New to you"
        }
        val over = title.durationMs?.takeIf { it > 0 }?.let { "over by ${overBy(now, it)}" }
        return listOfNotNull(seen, over).joinToString(" · ")
    }

    /** [now] plus [durationMs], up to the next minute: "10:42 PM". */
    fun overBy(now: LocalDateTime, durationMs: Long): String {
        val end = now.plusSeconds((durationMs + 59_999) / 60_000 * 60)
        return end.format(CLOCK)
    }

    /** "48m", "1h 41m", "2h". */
    fun length(durationMs: Long?): String? {
        val minutes = (durationMs ?: return null) / 60_000
        if (minutes <= 0) return null
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> "${rest}m"
            rest == 0L -> "${hours}h"
            else -> "${hours}h ${rest}m"
        }
    }
}

private const val MOVIE = "movie"
private const val SHOW = "show"
