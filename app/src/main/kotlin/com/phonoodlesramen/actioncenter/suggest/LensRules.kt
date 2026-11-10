package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.Season
import com.phonoodlesramen.actioncenter.plex.cache.EpisodeRow
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** S7-S9's numbers and words, in one place for tuning. */
object LensTuning {
    /** Titles per lens. */
    const val PICKS = 8

    /** Played in the last this many days, or in Up Next, a title is off every lens (Phase 14, L5). */
    const val PLAYED_REST_DAYS = 14L

    /** Throwback: the week around a year ago, the days around a release day, and which anniversaries count. */
    const val YEAR_AGO_DAYS = 7L
    const val ANNIVERSARY_DAYS = 3L
    const val ANNIVERSARY_EVERY_YEARS = 5
    const val NOT_REVISITED_YEARS = 3L

    /** Loved: 4 stars or more, or finished twice. */
    const val LOVED_RATING = SignalWeights.LOVED_RATING
    const val LOVED_FINISHES = 2

    /** Classic [U18], as P6 had it. */
    const val CLASSIC_YEARS = 25
    const val CLASSIC_RATING = 7.0
    const val CLASSIC_REWATCH_YEARS = 5L

    /** For the family: over by 8:30 PM until Phase 5's bedtime, and nothing overnight. */
    val FAMILY_ENDS_BY: LocalTime = LocalTime.of(20, 30)
    val FAMILY_DAY_STARTS: LocalTime = LocalTime.of(5, 0)
    const val FAMILY_RECENT_YEARS = 1L
    val FAMILY_GENRES = listOf("family", "animation")

    val ACTION_GENRES = listOf("action", "adventure")
    const val ACTION_REWATCH_YEARS = 2L

    /** Hidden gem: rated at or above this share of your library, and in it this long. */
    const val GEM_PERCENTILE = 0.85
    const val GEM_MIN_DAYS = 60L

    /** Finish the series: a collection of more films than this is a list ("Top 250"), not a series. */
    const val SERIES_MAX_FILMS = 15

    /** Comfort rewatch: films finished this often, your most-watched shows this year, rested and short. */
    const val COMFORT_FINISHES = 2
    const val COMFORT_SHOWS = 5
    const val COMFORT_SHOW_DAYS = 365L
    const val COMFORT_REST_DAYS = 14L
    const val COMFORT_MAX_MILLIS = 120 * 60_000L

    /** Seasonal (S8): a collection or label with one of these names, then these whole words in the title or summary. */
    val HALLOWEEN_TAGS = listOf("Halloween")
    val HALLOWEEN_WORDS = listOf(
        "halloween", "haunted", "haunting", "witch", "witches", "ghost", "ghosts", "trick-or-treat", "pumpkin", "vampire", "zombie",
    )
    val HOLIDAY_TAGS = listOf("Christmas", "Holiday", "Holidays", "Hanukkah")
    val HOLIDAY_WORDS = listOf("christmas", "xmas", "santa", "holiday", "hanukkah", "snowman", "reindeer", "elf")

    /** A seasonal favourite: finished in this many seasons running. */
    const val FAVOURITE_YEARS = 2
}

/**
 * The lens card's titles (S7-S9): up to [LensTuning.PICKS] a lens, each with its line [REQ].
 * Not tonight and Don't show this keep a title out (an episode by its show). A lens ranks by
 * its own order, then your taste, then the day's tie-break. A title can be in two lenses,
 * and the rotation skips Tonight's Pick (U20). Pure.
 */
object LensRules {
    /** Every lens's titles (yours, and a kid's since Phase 6), or only [lenses]'. */
    fun picks(input: RunInput, lenses: Collection<Lens> = Lens.entries): Map<Lens, List<Picked>> = Run(input).all(lenses)

    /**
     * Whether a family title started at [time] is over by 8:30 PM. From then until 5 AM
     * nothing fits; an unknown length fits. Read when the card is built: it moves with the minute.
     */
    fun familyFits(durationMs: Long?, time: LocalDateTime): Boolean {
        val clock = time.toLocalTime()
        if (clock >= LensTuning.FAMILY_ENDS_BY || clock < LensTuning.FAMILY_DAY_STARTS) return false
        val length = durationMs ?: return true
        return !time.plus(Duration.ofMillis(length)).isAfter(time.toLocalDate().atTime(LensTuning.FAMILY_ENDS_BY))
    }

    private class SeasonWords(val tags: List<String>, words: List<String>, val name: String, val month: Month, val months: String) {
        val pattern = wholeWords(words)
    }

    private val HALLOWEEN = SeasonWords(LensTuning.HALLOWEEN_TAGS, LensTuning.HALLOWEEN_WORDS, "Halloween", Month.OCTOBER, "Octobers")
    private val HOLIDAYS = SeasonWords(LensTuning.HOLIDAY_TAGS, LensTuning.HOLIDAY_WORDS, "holiday", Month.DECEMBER, "Decembers")
    private val FAMILY_PATTERN = wholeWords(LensTuning.FAMILY_GENRES)
    private val ACTION_PATTERN = wholeWords(LensTuning.ACTION_GENRES)
    private val NUMBERS = listOf("two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val EPISODE_ORDER = compareBy<EpisodeRow>({ it.season ?: 1 }, { it.episode ?: 0 })

    /** Two to nine in words, as the lines say them. */
    private fun times(n: Int) = if (n in 2..9) NUMBERS[n - 2] else n.toString()

    private fun rated(rating: Double) = "%.1f".format(Locale.US, rating)

    /** One title a lens could show: [title] is the movie or show, [ratingKey] what plays. Lower [tier] first, then higher [score]. */
    private class Candidate(val title: ItemEntity, val ratingKey: String, val reason: String, val tier: Int = 0, val score: Double = 0.0)

    private val BY_TIER = compareBy<Candidate> { it.tier }.thenByDescending { it.score }

    private class Run(private val input: RunInput) {
        private val library = input.library
        private val today = input.today
        private val affinities = HashMap<String, Double>()

        fun all(lenses: Collection<Lens>): Map<Lens, List<Picked>> = Lens.entries.filter { it in lenses }.associateWith { lens ->
            when (lens) {
                Lens.THROWBACK -> rank(lens, throwback())
                Lens.CLASSIC -> rank(lens, classic())
                Lens.FAMILY -> rank(lens, family())
                Lens.ACTION -> rank(lens, action(), compareByDescending<Candidate> { affinity(it.title) }.thenByDescending { it.score })
                Lens.HIDDEN_GEM -> rank(lens, hiddenGems())
                Lens.FINISH_SERIES -> rank(lens, nextFilms() + stoppedShows())
                Lens.SEASONAL -> rank(lens, seasonal())
                Lens.COMFORT -> rank(lens, comfort())
            }
        }

        /**
         * A lens's titles: not hidden, not played lately, in its order. What's resting (L5)
         * steps aside unless the lens has nothing else; then the longest-rested come back first.
         */
        private fun rank(lens: Lens, candidates: List<Candidate>, order: Comparator<Candidate> = BY_TIER): List<Picked> {
            val hidden = (input.hides.notTonight + input.hides.lens[lens.name].orEmpty()).mapTo(HashSet()) { library.showOf[it] ?: it }
            val ranked = candidates
                .filter { it.title.ratingKey !in hidden && it.ratingKey !in hidden && !justPlayed(it.title) }
                .sortedWith(order.thenByDescending { affinity(it.title) }.thenBy { Seeds.tieBreak(it.title.ratingKey, today) })
            val fresh = ranked.filter { input.hides.rested[it.title.ratingKey] == null }
            return fresh.ifEmpty { ranked.sortedBy { input.hides.rested[it.title.ratingKey] } }
                .distinctBy { it.title.ratingKey }
                .take(LensTuning.PICKS)
                .map { Picked(it.ratingKey, it.reason) }
        }

        /** Up Next's titles, by the movie or show. */
        private val inUpNext: Set<String> by lazy {
            library.continueWatching.flatMapTo(HashSet()) { listOfNotNull(it.showKey, it.ratingKey, library.showOf[it.ratingKey]) }
        }

        /** In Up Next, or played (finished or not) in the last 14 days (L5): fresh in mind, so off every lens. */
        private fun justPlayed(title: ItemEntity): Boolean {
            if (title.ratingKey in inUpNext) return true
            val last = lastViewedDay(title) ?: return false
            return ChronoUnit.DAYS.between(last, today) < LensTuning.PLAYED_REST_DAYS
        }

        /** Finished a year ago today, then that week; released this week in a round year; loved, then left. */
        private fun throwback(): List<Candidate> = buildList {
            val yearAgo = today.minusYears(1)
            for (play in library.plays) {
                val title = library.byKey[library.showOf[play.ratingKey] ?: play.ratingKey] ?: continue
                val days = abs(ChronoUnit.DAYS.between(yearAgo, input.dayOf(play.viewedAt)))
                when {
                    days == 0L -> add(Candidate(title, title.ratingKey, "One year ago today you watched", tier = 0))
                    days <= LensTuning.YEAR_AGO_DAYS -> add(Candidate(title, title.ratingKey, "One year ago this week you watched", tier = 1))
                }
            }
            for (title in library.titles) {
                if (watched(title)) {
                    anniversary(title)?.let { add(Candidate(title, title.ratingKey, "Released $it years ago this week", tier = 2)) }
                }
                val last = lastViewedDay(title)
                if (loved(title) && last != null && last <= today.minusYears(LensTuning.NOT_REVISITED_YEARS)) {
                    add(Candidate(title, title.ratingKey, "You loved it, then never went back", tier = 3))
                }
            }
        }

        /** How many years ago [title] came out, when that's a round number and its release day is within 3 days of today. */
        private fun anniversary(title: ItemEntity): Int? {
            val released = day(title.releaseDate) ?: return null
            return (today.year - 1..today.year + 1).firstNotNullOfOrNull { year ->
                val years = year - released.year
                years.takeIf {
                    it > 0 && it % LensTuning.ANNIVERSARY_EVERY_YEARS == 0 &&
                        abs(ChronoUnit.DAYS.between(released.withYear(year), today)) <= LensTuning.ANNIVERSARY_DAYS
                }
            }
        }

        private fun classic(): List<Candidate> = library.titles.mapNotNull { title ->
            val year = title.year ?: return@mapNotNull null
            val rating = rating(title) ?: return@mapNotNull null
            if (title.type != "movie" || year > today.year - LensTuning.CLASSIC_YEARS || rating < LensTuning.CLASSIC_RATING) return@mapNotNull null
            val age = today.year - year
            val reason = if (!watched(title)) {
                "$age years old and you've never seen it"
            } else {
                val last = lastViewedDay(title)?.takeIf { it <= today.minusYears(LensTuning.CLASSIC_REWATCH_YEARS) } ?: return@mapNotNull null
                "$age years old · last watched in ${last.year}"
            }
            Candidate(title, title.ratingKey, reason, score = rating)
        }

        /** Family and Animation first, and what nobody's watched this year before what they have. */
        private fun family(): List<Candidate> = library.titles.mapNotNull { title ->
            val band = Features.ratingBand(title.contentRating)
            val contentRating = title.contentRating?.trim()
            if (contentRating == null || (band != Features.KIDS && band != Features.FAMILY) || !playable(title)) return@mapNotNull null
            val reason = when {
                !watched(title) -> "Rated $contentRating · nobody's seen it yet"
                loved(title) -> "Rated $contentRating · everyone loved it last time"
                else -> "Rated $contentRating"
            }
            val recent = lastViewedDay(title)?.let { it > today.minusYears(LensTuning.FAMILY_RECENT_YEARS) } == true
            val tier = (if (hasGenre(title, FAMILY_PATTERN)) 0 else 2) + (if (recent) 1 else 0)
            Candidate(title, title.ratingKey, reason, tier = tier)
        }

        /** Ranked by taste, then rating; the line is the engine's reason when it has one. */
        private fun action(): List<Candidate> = library.titles.mapNotNull { title ->
            if (!playable(title) || !hasGenre(title, ACTION_PATTERN)) return@mapNotNull null
            val last = lastViewedDay(title)
            if (last != null && last > today.minusYears(LensTuning.ACTION_REWATCH_YEARS)) return@mapNotNull null
            val rating = rating(title)
            val reason = input.reason(title) ?: when {
                last != null -> "Last watched in ${last.year}"
                rating != null -> "Rated ${rated(rating)} · never played"
                else -> "Never played"
            }
            Candidate(title, title.ratingKey, reason, score = rating ?: 0.0)
        }

        private fun hiddenGems(): List<Candidate> {
            val threshold = percentile(library.titles.mapNotNull(::rating), LensTuning.GEM_PERCENTILE) ?: return emptyList()
            return library.titles.mapNotNull { title ->
                val rating = rating(title)?.takeIf { it >= threshold } ?: return@mapNotNull null
                val added = title.addedAt?.let(input::dayOf) ?: return@mapNotNull null
                if (!neverPlayed(title) || ChronoUnit.DAYS.between(added, today) < LensTuning.GEM_MIN_DAYS) return@mapNotNull null
                Candidate(title, title.ratingKey, "Rated ${rated(rating)} · in your library since ${added.year}, never played")
            }
        }

        /** In each collection, the first film by release you haven't finished that comes after one you have (S9). */
        private fun nextFilms(): List<Candidate> {
            val films = library.titles.filter { it.type == "movie" }
            val collections = films.flatMap { film -> names(film.collections).map { it to film } }.groupBy({ it.first }, { it.second })
            return collections.values.filter { it.size <= LensTuning.SERIES_MAX_FILMS }.mapNotNull { members ->
                var latest: ItemEntity? = null
                members.mapNotNull { film -> released(film)?.let { film to it } }
                    .sortedWith(compareBy({ it.second }, { it.first.ratingKey }))
                    .firstNotNullOfOrNull { (film, _) ->
                        if (watched(film)) {
                            latest = film
                            null
                        } else {
                            latest?.let { Candidate(film, film.ratingKey, "You watched ${it.title} · ${film.title} is next") }
                        }
                    }
            }
        }

        /** Shows you stopped (S2's stalled) that aren't in Up Next: the next episode on from the furthest you got (S9). */
        private fun stoppedShows(): List<Candidate> {
            val upNext = library.continueWatching.flatMapTo(HashSet()) { listOfNotNull(it.showKey, it.ratingKey, library.showOf[it.ratingKey]) }
            return input.signals.filter { it.kind == SignalKind.STALLED }.map { it.titleKey }.distinct().mapNotNull { key ->
                val show = library.byKey[key]?.takeIf { key !in upNext } ?: return@mapNotNull null
                val episodes = library.episodesOf(key).filter { (it.season ?: 1) != 0 }.sortedWith(EPISODE_ORDER)
                val furthest = episodes.indexOfLast { it.viewCount > 0 }
                val next = episodes.drop(furthest + 1).firstOrNull { it.viewCount == 0 }
                    ?: episodes.firstOrNull { it.viewCount == 0 }
                    ?: return@mapNotNull null
                Candidate(show, next.ratingKey, stopped(show.title, episodes, next, episodes.getOrNull(furthest)))
            }
        }

        private fun stopped(show: String, episodes: List<EpisodeRow>, next: EpisodeRow, furthest: EpisodeRow?): String {
            val season = next.season ?: 1
            val seen = episodes.count { (it.season ?: 1) == season && it.viewCount > 0 }
            return when {
                seen > 0 -> "You stopped $show $seen ${if (seen == 1) "episode" else "episodes"} into season $season"
                furthest != null -> "You stopped $show after season ${furthest.season ?: 1}"
                else -> "You stopped $show"
            }
        }

        /** In season only (S8): your collections and labels first, then keywords. */
        private fun seasonal(): List<Candidate> {
            val words = when (Season.at(today) ?: return emptyList()) {
                Season.HALLOWEEN -> HALLOWEEN
                Season.HOLIDAYS -> HOLIDAYS
            }
            return library.titles.mapNotNull { title ->
                val curated = (names(title.collections) + names(title.labels)).any { name -> words.tags.any { it.equals(name, ignoreCase = true) } }
                val matched = curated || words.pattern.containsMatchIn(title.title) || title.summary?.let(words.pattern::containsMatchIn) == true
                if (!matched) return@mapNotNull null
                val inSeason = library.playsOf(title.ratingKey).map { input.dayOf(it.viewedAt) }.filter { it.month == words.month }
                val running = longestRun(inSeason.map { it.year })
                val noun = if (title.type == "show") "show" else "film"
                val reason = when {
                    running >= LensTuning.FAVOURITE_YEARS -> "A ${words.name} favourite, watched ${times(running)} ${words.months} running"
                    watched(title) -> "A ${words.name} $noun you've seen"
                    else -> "A ${words.name} $noun you haven't seen"
                }
                Candidate(title, title.ratingKey, reason, tier = if (curated) 0 else 1)
            }
        }

        /** Films finished twice or more, and your 5 most-watched shows this year by an episode you've seen; rested and short. */
        private fun comfort(): List<Candidate> {
            val films = library.titles.filter { it.type == "movie" && finishes(it) >= LensTuning.COMFORT_FINISHES }
            val top = films.maxWithOrNull(compareBy({ finishes(it) }, { library.lastViewed(it.ratingKey) ?: 0L }))
            val filmPicks = films.filter { rested(it) && short(it.durationMs) }.map { film ->
                val line = "Watched ${times(finishes(film))} times"
                Candidate(film, film.ratingKey, if (film.ratingKey == top?.ratingKey) "$line · your most rewatched" else line)
            }
            val since = input.now / 1_000 - LensTuning.COMFORT_SHOW_DAYS * 86_400
            val finished = library.plays.filter { it.viewedAt >= since }.mapNotNull { library.showOf[it.ratingKey] }.groupingBy { it }.eachCount()
            val showPicks = finished.mapNotNull { (key, count) ->
                val show = library.byKey[key]?.takeIf(::rested) ?: return@mapNotNull null
                val episode = library.episodesOf(key)
                    .filter { it.viewCount > 0 && (it.season ?: 1) != 0 && short(it.durationMs) }
                    .maxWithOrNull(compareBy({ it.viewCount }, { it.lastViewedAt ?: 0L }))
                    ?: return@mapNotNull null
                Triple(show, episode, count)
            }
                .sortedWith(
                    compareByDescending<Triple<ItemEntity, EpisodeRow, Int>> { it.third }.thenBy { Seeds.tieBreak(it.first.ratingKey, today) },
                )
                .take(LensTuning.COMFORT_SHOWS)
                .map { (show, episode) -> Candidate(show, episode.ratingKey, "One of your most-watched shows") }
            return filmPicks + showPicks
        }

        private fun affinity(title: ItemEntity) = affinities.getOrPut(title.ratingKey) { input.affinity.of(title) }

        private fun rating(title: ItemEntity) = title.audienceRating ?: title.rating

        /** Movies with a file, and shows. */
        private fun playable(title: ItemEntity) = title.type == "show" || (title.type == "movie" && title.partKey != null)

        /** A film's plays or view count, whichever is more. */
        private fun finishes(film: ItemEntity) = maxOf(film.viewCount, library.playsOf(film.ratingKey).size)

        /** Finished: a film, or any of a show's episodes. */
        private fun watched(title: ItemEntity) = title.viewCount > 0 || library.playsOf(title.ratingKey).isNotEmpty() ||
            (title.type == "show" && ((title.watchedEpisodeCount ?: 0) > 0 || library.episodesOf(title.ratingKey).any { it.viewCount > 0 }))

        /** Never started: no finish and no position, nor on any of a show's episodes. */
        private fun neverPlayed(title: ItemEntity) = !watched(title) && (title.viewOffsetMs ?: 0) == 0L &&
            library.episodesOf(title.ratingKey).none { (it.viewOffsetMs ?: 0) > 0 }

        /** Rated 4 stars or more, or finished twice: a film twice, or two of a show's episodes each twice (as Signals' rewatch). */
        private fun loved(title: ItemEntity): Boolean {
            if ((title.userRating ?: 0.0) >= LensTuning.LOVED_RATING) return true
            return if (title.type == "show") {
                library.episodesOf(title.ratingKey).count { (it.season ?: 1) != 0 && it.viewCount >= 2 } >= LensTuning.LOVED_FINISHES
            } else {
                finishes(title) >= LensTuning.LOVED_FINISHES
            }
        }

        private fun lastViewedDay(title: ItemEntity): LocalDate? = library.lastViewed(title.ratingKey)?.let(input::dayOf)

        /** Not watched in the last 14 days. */
        private fun rested(title: ItemEntity) =
            lastViewedDay(title)?.let { ChronoUnit.DAYS.between(it, today) >= LensTuning.COMFORT_REST_DAYS } ?: true

        private fun short(durationMs: Long?) = durationMs == null || durationMs <= LensTuning.COMFORT_MAX_MILLIS

        /** A film's place in its series: its release date, else its year. */
        private fun released(film: ItemEntity): LocalDate? = day(film.releaseDate) ?: film.year?.let { LocalDate.of(it, 1, 1) }

        private fun hasGenre(title: ItemEntity, genres: Regex) = names(title.genres).any(genres::containsMatchIn)
    }

    /** Any of [words], whole and in any case. */
    private fun wholeWords(words: List<String>) = Regex(words.joinToString("|", "\\b(", ")\\b") { Regex.escape(it) }, RegexOption.IGNORE_CASE)

    private fun names(joined: String) = joined.split('|').map(String::trim).filter(String::isNotEmpty)

    private fun day(date: String?): LocalDate? = try {
        date?.let(LocalDate::parse)
    } catch (_: DateTimeParseException) {
        null
    }

    /** The [p] point of [values], between the two nearest ranks (as a spreadsheet's PERCENTILE). */
    private fun percentile(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val at = p * (sorted.size - 1)
        val below = sorted[floor(at).toInt()]
        return below + (sorted[ceil(at).toInt()] - below) * (at - floor(at))
    }

    /** The most years in a row in [years]. */
    private fun longestRun(years: List<Int>): Int {
        var best = 0
        var run = 0
        var previous: Int? = null
        for (year in years.distinct().sorted()) {
            run = if (previous != null && year == previous + 1) run + 1 else 1
            best = maxOf(best, run)
            previous = year
        }
        return best
    }
}
