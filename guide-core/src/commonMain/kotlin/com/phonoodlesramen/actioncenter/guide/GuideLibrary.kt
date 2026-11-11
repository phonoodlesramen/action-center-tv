package com.phonoodlesramen.actioncenter.guide

import com.phonoodlesramen.actioncenter.plex.cache.GuideGenreTotal
import com.phonoodlesramen.actioncenter.plex.cache.GuideLibraryDao
import com.phonoodlesramen.actioncenter.plex.cache.GuideNameTotal
import com.phonoodlesramen.actioncenter.plex.cache.GuidePoolEntry
import com.phonoodlesramen.actioncenter.plex.cache.GuidePoolStats
import com.phonoodlesramen.actioncenter.plex.cache.GuideShowViews
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.plex.cache.SyncStateEntity
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The guide's reads of the Plex cache. Rules run as queries, so a channel never loads the
 * whole library: a shuffle loads its titles' keys and lengths, rotating shows ask for one
 * show's next episode at a time, and a title's details are read only when it's placed.
 * [year] is this year, for a rule's rolling age.
 */
internal class GuideLibrary(private val dao: GuideLibraryDao) {
    suspend fun syncState(): SyncStateEntity? = dao.syncState()

    suspend fun stats(rule: ChannelRule, year: Int): GuidePoolStats = dao.poolStats(
        RuleSql(rule, year).query("COUNT(*) AS count, COALESCE(SUM(i.durationMs), 0) AS totalMs, COALESCE(AVG(i.durationMs), 0) AS averageMs"),
    ) ?: GuidePoolStats(0, 0, 0.0)

    /** Every title the rule matches, by ratingKey. */
    suspend fun pool(rule: ChannelRule, year: Int): List<GuidePoolEntry> =
        dao.pool(RuleSql(rule, year).query(RuleSql.ENTRY_COLUMNS, tail = "ORDER BY i.ratingKey"))

    /** The rule's films with a trailer, by ratingKey: the lead-ins' other trailers (G21). */
    suspend fun trailerFilms(rule: ChannelRule, year: Int): List<String> =
        dao.pool(RuleSql(rule, year).query(RuleSql.ENTRY_COLUMNS, "i.type = 'movie' AND i.trailerKey IS NOT NULL", tail = "ORDER BY i.ratingKey"))
            .map { it.ratingKey }

    /**
     * What every title [rule] can play is rated (R2), in one `SELECT DISTINCT`: an episode
     * without a rating of its own has its show's, and a blank one counts as none.
     */
    suspend fun poolRatings(rule: ChannelRule, year: Int): PoolRatings {
        val rows = dao.poolRatings(RuleSql(rule, year).query("DISTINCT ${RuleSql.RATING} AS rating"))
        return PoolRatings(
            ratings = rows.mapNotNullTo(HashSet()) { row -> row.rating?.takeIf { it.isNotBlank() } },
            unrated = rows.any { it.rating.isNullOrBlank() },
        )
    }

    /** A rule's [poolRatings] and the sync state they were read after. */
    private data class Remembered(val sync: SyncStateEntity?, val ratings: PoolRatings)

    /** [channelRatings]' answers, by the rule's saved JSON and the year. */
    private val remembered = ConcurrentHashMap<Pair<String, Int>, Remembered>()

    /**
     * [poolRatings] for a channel's saved rule, [ruleJson], remembered until the library
     * syncs again: [sync] is the cache's sync state now, and any change to it (a sync
     * starting, ending or failing, or signing out) reads each rule afresh. The lineup is
     * read often; each rule is queried once per sync. A rule that won't read plays nothing.
     */
    suspend fun channelRatings(ruleJson: String, year: Int, sync: SyncStateEntity?): PoolRatings {
        remembered.values.removeIf { it.sync != sync }
        val key = ruleJson to year
        remembered[key]?.takeIf { it.sync == sync }?.let { return it.ratings }
        val rule = try {
            ChannelRule.fromJson(ruleJson)
        } catch (_: IllegalArgumentException) {
            return PoolRatings.NOTHING
        }
        // Each answer keeps its sync, so a read that spans a sync never stands for the next one.
        return poolRatings(rule, year).also { remembered[key] = Remembered(sync, it) }
    }

    /** [ratingKey], if the rule still matches it and it still has a file. */
    suspend fun entry(rule: ChannelRule, year: Int, ratingKey: String): GuidePoolEntry? =
        dao.pool(RuleSql(rule, year).query(RuleSql.ENTRY_COLUMNS, "i.ratingKey = ?", listOf(ratingKey))).firstOrNull()

    /** The shows the rule's episodes come from. */
    suspend fun shows(rule: ChannelRule, year: Int): List<String> = dao.poolShows(
        RuleSql(rule, year).query("DISTINCT i.showKey AS showKey", "i.showKey IS NOT NULL", tail = "ORDER BY i.showKey"),
    ).map { it.showKey }

    /** [showKey]'s first episode the rule matches after [after], in order; its very first when [after] is null. */
    suspend fun episodeAfter(rule: ChannelRule, year: Int, showKey: String, after: EpisodePosition?): GuidePoolEntry? {
        val season = "COALESCE(i.season, $UNNUMBERED)"
        val episode = "COALESCE(i.episode, $UNNUMBERED)"
        val sql = RuleSql(rule, year)
        val query = if (after == null) {
            sql.query(RuleSql.ENTRY_COLUMNS, "i.showKey = ?", listOf(showKey), "${RuleSql.EPISODE_ORDER} LIMIT 1")
        } else {
            val s = (after.season ?: UNNUMBERED).toLong()
            val e = (after.episode ?: UNNUMBERED).toLong()
            sql.query(
                RuleSql.ENTRY_COLUMNS,
                "i.showKey = ? AND ($season > ? OR ($season = ? AND ($episode > ? OR ($episode = ? AND i.ratingKey > ?))))",
                listOf(showKey, s, s, e, e, after.key),
                "${RuleSql.EPISODE_ORDER} LIMIT 1",
            )
        }
        return dao.pool(query).firstOrNull()
    }

    suspend fun item(ratingKey: String): ItemEntity? = dao.item(ratingKey)

    /** For each key still in the cache, whether it has a file. Missing keys are gone from the library. */
    suspend fun playable(keys: Collection<String>): Map<String, Boolean> =
        keys.chunked(MAX_KEYS).flatMap { dao.keyStates(it) }.associate { it.ratingKey to it.playable }

    suspend fun movieGenreTotals(): List<GuideGenreTotal> = dao.movieGenreTotals()

    suspend fun tvGenreTotals(): List<GuideGenreTotal> = dao.tvGenreTotals()

    suspend fun mostWatchedShows(limit: Int): List<GuideShowViews> = dao.mostWatchedShows(limit)

    /**
     * What a rule can pick from (2.7). The cache keeps a section's key but not its name, so
     * a section is called by what it holds: "Movies · 1,204 titles", "TV · 85 shows".
     * Collections, studios and labels are those of movies and shows with something the
     * guide can schedule; studios and labels come the most titles first (a show is one).
     */
    suspend fun choices(): RuleChoices {
        fun genres(rows: List<GuideGenreTotal>) =
            StarterLineup.genreTotals(rows).entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).map { it.key }
        return RuleChoices(
            sections = dao.sectionTotals().map { section ->
                val title = when {
                    section.movies > 0 && section.shows == 0 -> "Movies · ${count(section.movies, "title")}"
                    section.shows > 0 && section.movies == 0 -> "TV · ${count(section.shows, "show")}"
                    else -> "Library ${section.sectionKey}"
                }
                Choice(section.sectionKey, title)
            },
            movieGenres = genres(dao.movieGenreTotals()),
            tvGenres = genres(dao.tvGenreTotals()),
            shows = dao.showsWithEpisodes().map { Choice(it.ratingKey, it.title) },
            collections = dao.collectionTags().asSequence()
                .flatMap { it.split('|') }.map { it.trim() }.filter { it.isNotEmpty() }
                .distinct().sortedBy { it.lowercase() }.toList(),
            decades = dao.decades(),
            contentRatings = dao.contentRatings(),
            studios = mostTitlesFirst(dao.studioTotals(), joined = false),
            labels = mostTitlesFirst(dao.labelTotals(), joined = true),
            icons = ChannelGlyphs.ALL,
        )
    }

    /** The names in [rows] (each a "|"-joined list when [joined]), the most titles first, then A to Z. */
    private fun mostTitlesFirst(rows: List<GuideNameTotal>, joined: Boolean): List<String> {
        val totals = HashMap<String, Int>()
        for (row in rows) {
            val names = if (joined) row.name.split('|') else listOf(row.name)
            names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { totals.merge(it, row.titles, Int::plus) }
        }
        return totals.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() }.thenBy { it.key })
            .map { it.key }
    }

    private fun count(n: Int, one: String) = "%,d %s".format(Locale.US, n, if (n == 1) one else "${one}s")

    companion object {
        /** An unnumbered season or episode sorts last, as in [RuleSql.EPISODE_ORDER]. */
        const val UNNUMBERED = 99999

        /** SQLite on Android 11 allows 999 arguments in a query. */
        private const val MAX_KEYS = 500
    }
}

/**
 * What a channel can play is rated (R2): each content rating among its titles, and whether
 * any title has none (K4: kids never see those). [NOTHING] when it can play nothing.
 */
internal data class PoolRatings(val ratings: Set<String>, val unrated: Boolean) {
    /** True when there's no title to play at all. */
    val nothing: Boolean get() = ratings.isEmpty() && !unrated

    companion object {
        val NOTHING = PoolRatings(emptySet(), unrated = false)
    }
}
