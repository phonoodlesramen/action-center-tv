package com.phonoodlesramen.actioncenter.guide

import com.phonoodlesramen.actioncenter.plex.cache.Classics
import com.phonoodlesramen.actioncenter.plex.cache.GuideGenreTotal
import com.phonoodlesramen.actioncenter.plex.cache.GuidePoolStats

/**
 * The lineup made from the library once it has synced (G2), in this order: Movies; the
 * genres with enough to fill a day, largest first; TV Mix; Classics (25+ years old, U18);
 * Family (K8); then marathons of the three most-watched shows. A channel only exists if its
 * titles run at least 24 hours without repeating, and there are at most 20 (Q5): genres
 * give way, so the others always fit.
 *
 * A genre makes a movie channel ("Comedy", shuffled) and a TV channel ("Comedy TV", its
 * shows in turn), each when it fills a day and isn't simply everything Movies or TV Mix
 * already plays. A Family genre's movie channel gives the name way to the Family channel.
 *
 * **Family** (K8) is what kids see of the guide: movies and episodes rated G to TV-PG
 * ([FAMILY_RATINGS], the 7-9 bracket's ceiling, K3), shuffled. Its rule names its ratings,
 * so it plays only those and it's kid-safe ([KidGuide]). The rest of the lineup names none,
 * and a kid sees one of those only when everything it plays is within their ratings (R2),
 * as an Animation channel of G and PG films is. The rule language has
 * filters but no "first", so Family and Animation titles aren't put ahead: a genre filter
 * would leave out every other kids' title. A lineup made before Phase 5 gets it once
 * ([family], GuideEngine).
 */
class StarterLineup internal constructor(private val library: GuideLibrary) {
    private class Candidate(val id: String, val name: String, val rule: ChannelRule, val stats: GuidePoolStats) {
        fun entity(id: String, number: Int) = ChannelEntity(
            id = id,
            number = number,
            name = name,
            rule = rule.toJson(),
            seed = GuideSeeds.seedFor(id),
            alignmentMinutes = if (stats.averageMs <= SHORT_PROGRAM_MILLIS) SHORT_ALIGNMENT else ALIGNMENT,
            cursor = ChannelCursor().toJson(),
        )
    }

    private class Genre(val name: String, val tv: Boolean, val totalMs: Long)

    private suspend fun candidate(id: String, name: String, rule: ChannelRule, year: Int): Candidate? {
        val stats = library.stats(rule, year)
        return if (stats.totalMs >= DAY_MILLIS) Candidate(id, name, rule, stats) else null
    }

    /**
     * The Family channel on its own (K8), for a lineup made before it existed: numbered
     * [number], with [id] (unique in that lineup). Null when the library has less than a day
     * of it.
     */
    suspend fun family(year: Int, number: Int, id: String = FAMILY_ID): ChannelEntity? =
        candidate(FAMILY_ID, FAMILY_NAME, FAMILY_RULE, year)?.entity(id, number)

    /** The channels in order, numbered from 1; empty when nothing fills a day. */
    suspend fun make(year: Int): List<ChannelEntity> {
        suspend fun candidate(id: String, name: String, rule: ChannelRule): Candidate? = candidate(id, name, rule, year)

        val movies = candidate("movies", "Movies", ChannelRule(types = listOf(ChannelRule.MOVIE)))
        val tvMix = candidate("tv-mix", "TV Mix", ChannelRule(types = listOf(ChannelRule.EPISODE), order = ChannelRule.Order.ROTATE_SHOWS))
        val classics = candidate(
            "classics", "Classics", ChannelRule(types = listOf(ChannelRule.MOVIE), minAgeYears = Classics.YEARS),
        )
        val marathons = mutableListOf<Candidate>()
        for (show in library.mostWatchedShows(MARATHON_CANDIDATES)) {
            if (marathons.size == MARATHONS) break
            val title = library.item(show.showKey)?.title ?: continue
            val rule = ChannelRule(types = listOf(ChannelRule.EPISODE), shows = listOf(show.showKey), order = ChannelRule.Order.IN_ORDER)
            candidate("marathon-${show.showKey}", "$title Marathon", rule)?.let { marathons += it }
        }
        val family = candidate(FAMILY_ID, FAMILY_NAME, FAMILY_RULE)

        val others = listOfNotNull(movies, tvMix, classics, family) + marathons
        val genres = mutableListOf<Candidate>()
        for (genre in genres()) {
            if (others.size + genres.size >= MAX_CHANNELS) break
            // One channel called Family: the one kids can see.
            if (family != null && !genre.tv && genre.name.equals(FAMILY_NAME, ignoreCase = true)) continue
            val parent = if (genre.tv) tvMix else movies
            val rule = if (genre.tv) {
                ChannelRule(types = listOf(ChannelRule.EPISODE), genres = listOf(genre.name), order = ChannelRule.Order.ROTATE_SHOWS)
            } else {
                ChannelRule(types = listOf(ChannelRule.MOVIE), genres = listOf(genre.name))
            }
            val channel = candidate(
                "${if (genre.tv) "tv-genre" else "genre"}-${slug(genre.name)}",
                if (genre.tv) "${genre.name} TV" else genre.name,
                rule,
            ) ?: continue
            if (parent != null && channel.stats.count >= parent.stats.count) continue
            genres += channel
        }

        val ordered = listOfNotNull(movies) + genres + listOfNotNull(tvMix, classics, family) + marathons
        val ids = mutableSetOf<String>()
        return ordered.mapIndexed { i, c ->
            // Two genres can share a slug ("Sci-Fi", "Sci Fi"): ids stay unique.
            var id = c.id
            var n = 2
            while (!ids.add(id)) id = "${c.id}-${n++}"
            c.entity(id, number = i + 1)
        }
    }

    /** Movie and TV genres that might fill a day, largest first. The channel's own rule decides. */
    private suspend fun genres(): List<Genre> {
        fun totals(rows: List<GuideGenreTotal>, tv: Boolean) = genreTotals(rows).map { (name, total) -> Genre(name, tv, total) }
        return (totals(library.movieGenreTotals(), tv = false) + totals(library.tvGenreTotals(), tv = true))
            .filter { it.totalMs >= DAY_MILLIS }
            .sortedWith(compareByDescending<Genre> { it.totalMs }.thenBy { it.name }.thenBy { it.tv })
    }

    private fun slug(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "g${name.hashCode().toUInt()}" }

    companion object {
        const val MAX_CHANNELS = 20
        const val MARATHONS = 3
        const val DAY_MILLIS = 24 * 60 * 60_000L

        /** Programs start on the half hour; channels of short episodes (15 minutes or less) on the quarter. */
        const val ALIGNMENT = 30
        const val SHORT_ALIGNMENT = 15
        private const val SHORT_PROGRAM_MILLIS = 15 * 60_000L

        /** Most-watched shows looked at for the three marathons, in case some don't fill a day. */
        private const val MARATHON_CANDIDATES = 20

        const val FAMILY_ID = "family"
        const val FAMILY_NAME = "Family"

        /** The Family channel's ratings (K8): G to TV-PG, the 7-9 bracket's ceiling (K3). */
        val FAMILY_RATINGS = listOf("G", "PG", "TV-Y", "TV-Y7", "TV-G", "TV-PG")

        /** Movies and episodes within [FAMILY_RATINGS] (an episode without a rating has its show's), shuffled. */
        val FAMILY_RULE = ChannelRule(types = listOf(ChannelRule.MOVIE, ChannelRule.EPISODE), contentRatings = FAMILY_RATINGS)

        /** Whether [channel] is a Family channel: called Family, with Family's ratings. A rule that won't read isn't. */
        fun isFamily(channel: ChannelEntity): Boolean = channel.name == FAMILY_NAME && channel.contentRatings() == FAMILY_RATINGS.toSet()

        /**
         * Each genre's total length from the cache's genre combinations ("|"-joined): a title
         * counts once for each of its genres. The starter lineup and the editor's choices.
         */
        fun genreTotals(rows: List<GuideGenreTotal>): Map<String, Long> {
            val byGenre = linkedMapOf<String, Long>()
            for (row in rows) {
                row.genres.orEmpty().split('|').map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach {
                    byGenre[it] = (byGenre[it] ?: 0L) + row.totalMs
                }
            }
            return byGenre
        }
    }
}
