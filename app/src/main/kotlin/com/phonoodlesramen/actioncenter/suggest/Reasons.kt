package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.cache.EpisodeRow
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.WeakHashMap

/** The counts and windows behind the reason lines. Tunable. */
object ReasonRules {
    /** "Because you liked Heat" needs this many features in common. */
    const val SHARED_FEATURES = 2

    /** "Because you finished three crime films this month" needs at least three. */
    const val GENRE_FINISHES = 3
    const val YEAR_DAYS = 365L

    /** "Added to Plex 3 days ago" up to 30 days; "New this week" up to 7. */
    const val ADDED_DAYS = 30L
    const val NEW_THIS_WEEK_DAYS = 7L
}

/**
 * A title's reason line (plan §3): the most specific evidence from what you finished,
 * collection first, then director, a title you liked, cast and a genre you keep finishing.
 * A reason never names a title you abandoned, stopped or disliked. Pure.
 */
object Reasons {
    private const val DAY_MILLIS = 24 * 60 * 60_000L
    private val SHARED_KINDS = setOf(FeatureKind.GENRE, FeatureKind.COLLECTION, FeatureKind.DIRECTOR, FeatureKind.ACTOR, FeatureKind.DECADE)
    private val NUMBERS = listOf("three", "four", "five", "six", "seven", "eight", "nine")

    /** Loved first, then the latest finish. */
    private val CITE_ORDER = compareByDescending<Finish> { it.loved }.thenByDescending { it.atMillis }.thenBy { it.titleKey }

    /**
     * A movie's or show's pick: its taste reason, else a fallback ("Added to Plex 3 days
     * ago", "Rated 8.4 · never played", "New to you"), and up to two facts for Why this pick?
     */
    fun of(title: ItemEntity, taste: Taste, input: RunInput): Picked {
        val reason = line(title, taste, input) ?: fallback(title, input)
        val days = addedDays(title, input)
        val evidence = buildList {
            rating(title)?.let { if (!reason.startsWith("Rated")) add("Rated ${oneDecimal(it)}") }
            if (days != null && !reason.startsWith("Added")) {
                if (days <= ReasonRules.NEW_THIS_WEEK_DAYS) add("New this week") else if (days <= ReasonRules.ADDED_DAYS) add("New this month")
            }
        }
        return Picked(title.ratingKey, reason, evidence)
    }

    /** The taste reason alone ("Because you liked Heat"), or null when only a fallback applies. */
    fun line(title: ItemEntity, taste: Taste, input: RunInput): String? {
        val catalog = Catalog.of(input.library)
        val features = catalog.features(title)
        fun finishedWith(feature: Feature) = taste.evidence(feature).mapNotNull { taste.finished[it] }.filter { it.titleKey != title.ratingKey }
        fun name(finish: Finish) = input.library.byKey[finish.titleKey]?.title
        val collections = features.filter { it.kind == FeatureKind.COLLECTION }
        val unseen = !catalog.watched(title)

        collections.firstOrNull { collection ->
            unseen && catalog.previousIn(collection.value, title.ratingKey)?.let { it.ratingKey in taste.finished } == true
        }?.let { return "Next in the ${it.value} collection" }
        collections.firstOrNull { finishedWith(it).isNotEmpty() }?.let { return "From the ${it.value} collection" }

        features.filter { it.kind == FeatureKind.DIRECTOR }.firstNotNullOfOrNull { director ->
            finishedWith(director).sortedWith(CITE_ORDER).firstNotNullOfOrNull(::name)?.let { "Because you watched ${director.value}'s $it" }
        }?.let { return it }

        val shared = LinkedHashMap<String, Int>()
        features.filter { it.kind in SHARED_KINDS }.forEach { feature ->
            finishedWith(feature).forEach { shared[it.titleKey] = (shared[it.titleKey] ?: 0) + 1 }
        }
        shared.filterValues { it >= ReasonRules.SHARED_FEATURES }.keys.mapNotNull { taste.finished[it] }
            .sortedWith(compareByDescending<Finish> { it.loved }.thenByDescending { shared[it.titleKey] }.then(CITE_ORDER))
            .firstNotNullOfOrNull { finish -> name(finish)?.let { if (finish.loved) "Because you liked $it" else "Because you finished $it" } }
            ?.let { return it }

        features.filter { it.kind == FeatureKind.ACTOR }.firstNotNullOfOrNull { actor ->
            finishedWith(actor).sortedWith(CITE_ORDER).firstNotNullOfOrNull(::name)?.let { "Starring ${actor.value}, from $it" }
        }?.let { return it }

        return genreLine(title, features, taste, input)
    }

    /** "Because you finished three crime films this month", or this year when the month has fewer. */
    private fun genreLine(title: ItemEntity, features: Set<Feature>, taste: Taste, input: RunInput): String? {
        val genres = features.filter { it.kind == FeatureKind.GENRE }
        if (genres.isEmpty()) return null
        val monthStart = input.today.withDayOfMonth(1).atStartOfDay(input.zone).toInstant().toEpochMilli()
        val windows = listOf(monthStart to "this month", input.now - ReasonRules.YEAR_DAYS * DAY_MILLIS to "this year")
        for ((since, words) in windows) {
            val (genre, count) = genres.map { genre ->
                genre to taste.evidence(genre).mapNotNull { taste.finished[it] }.count {
                    it.titleKey != title.ratingKey && it.atMillis in since..input.now && input.library.byKey[it.titleKey]?.type == title.type
                }
            }.maxBy { it.second }
            if (count >= ReasonRules.GENRE_FINISHES) {
                val kind = if (title.type == "show") "shows" else "films"
                return "Because you finished ${spelled(count)} ${genre.value.lowercase(Locale.ROOT)} $kind $words"
            }
        }
        return null
    }

    private fun fallback(title: ItemEntity, input: RunInput): String {
        val days = addedDays(title, input)
        if (days != null && days <= ReasonRules.ADDED_DAYS) {
            return when (days) {
                0L -> "Added to Plex today"
                1L -> "Added to Plex yesterday"
                else -> "Added to Plex $days days ago"
            }
        }
        val rating = rating(title)
        val played = Catalog.of(input.library).watched(title)
        return when {
            rating != null && !played -> "Rated ${oneDecimal(rating)} · never played"
            rating != null -> "Rated ${oneDecimal(rating)}"
            !played -> "New to you"
            else -> "From your library"
        }
    }

    /** Days since the title came to Plex: 0 today (or a clock that's behind), null when unknown. */
    internal fun addedDays(title: ItemEntity, input: RunInput): Long? =
        title.addedAt?.let { ChronoUnit.DAYS.between(input.dayOf(it), input.today).coerceAtLeast(0) }

    internal fun rating(title: ItemEntity): Double? = title.audienceRating ?: title.rating

    internal fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

    /** Three to nine as words, the rest as digits. */
    internal fun spelled(n: Int): String = if (n in 3..9) NUMBERS[n - 3] else n.toString()

    /** "today", "yesterday", "3 days ago". */
    internal fun daysAgo(days: Long): String = when (days) {
        0L -> "today"
        1L -> "yesterday"
        else -> "$days days ago"
    }
}

/**
 * The library worked out once per read, for reasons, Tonight's Pick and More like this:
 * each title's features, each collection in release order, what you've played, and each
 * show's episodes in order (specials left out). Kept while its [Library] is in use.
 */
internal class Catalog private constructor(library: Library) {
    private val byKey = library.byKey
    private val features: Map<String, Set<Feature>> = library.titles.associate { it.ratingKey to Features.of(it) }
    private val lastPlay: Map<String, Long> = library.plays.groupBy { it.ratingKey }.mapValues { (_, plays) -> plays.maxOf { it.viewedAt } }
    private val playedShows: Set<String> = library.plays.mapNotNullTo(HashSet()) { library.showOf[it.ratingKey] }
    private val episodes: Map<String, List<EpisodeRow>> = library.episodes.filter { (it.season ?: 1) != 0 }
        .groupBy { it.showKey }.mapValues { (_, rows) -> rows.sortedWith(EPISODE_ORDER) }

    /** Each collection's titles in release order: release date, else year. */
    val collections: Map<String, List<ItemEntity>> = LinkedHashMap<String, MutableList<ItemEntity>>().apply {
        library.titles.forEach { title ->
            features.getValue(title.ratingKey).filter { it.kind == FeatureKind.COLLECTION }.forEach { getOrPut(it.value) { mutableListOf() } += title }
        }
    }.mapValues { (_, members) -> members.sortedWith(RELEASE_ORDER) }

    fun features(title: ItemEntity): Set<Feature> =
        if (byKey[title.ratingKey] === title) features.getValue(title.ratingKey) else Features.of(title)

    /** The title just before [titleKey] in [collection]'s release order, if any. */
    fun previousIn(collection: String, titleKey: String): ItemEntity? {
        val members = collections[collection] ?: return null
        val at = members.indexOfFirst { it.ratingKey == titleKey }
        return if (at > 0) members[at - 1] else null
    }

    /** A show's episodes in order, specials left out. */
    fun episodes(showKey: String): List<EpisodeRow> = episodes[showKey].orEmpty()

    fun watched(episode: EpisodeRow): Boolean = episode.viewCount > 0 || episode.ratingKey in lastPlay

    /** When you last finished an episode (epoch seconds), from its last view or history. */
    fun finishedAt(episode: EpisodeRow): Long? =
        listOfNotNull(episode.lastViewedAt?.takeIf { episode.viewCount > 0 }, lastPlay[episode.ratingKey]).maxOrNull()

    /** A film you've finished, or a show with an episode you have. */
    fun watched(title: ItemEntity): Boolean = when (title.type) {
        "show" -> (title.watchedEpisodeCount ?: 0) > 0 || title.ratingKey in playedShows || episodes(title.ratingKey).any(::watched)
        else -> title.viewCount > 0 || title.ratingKey in lastPlay
    }

    /** Watched, or partway into it. */
    fun started(title: ItemEntity): Boolean = watched(title) || when (title.type) {
        "show" -> episodes(title.ratingKey).any { (it.viewOffsetMs ?: 0) > 0 }
        else -> (title.viewOffsetMs ?: 0) > 0
    }

    /** What playing a show starts: the episode after the furthest you've watched, else its first. */
    fun nextEpisode(showKey: String): EpisodeRow? {
        val rows = episodes(showKey)
        return rows.getOrNull(rows.indexOfLast(::watched) + 1) ?: rows.firstOrNull()
    }

    companion object {
        private val EPISODE_ORDER = compareBy<EpisodeRow>({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE }, { it.ratingKey })
        private val RELEASE_ORDER = compareBy<ItemEntity>({ it.releaseDate ?: it.year?.toString() ?: "~" }, { it.title }, { it.ratingKey })

        /** Weak keys: a library's catalog goes when the library does. */
        private val cache = WeakHashMap<Library, Catalog>()

        fun of(library: Library): Catalog = synchronized(cache) { cache.getOrPut(library) { Catalog(library) } }
    }
}
