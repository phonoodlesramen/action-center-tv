package com.phonoodlesramen.actioncenter.plex.cache

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery

/**
 * The Plex Guide's queries on the cached library (Phase 2.6): what its channel rules
 * pick from. Its own interface, so the guide engine can add queries without touching
 * [PlexDao]. The guide's channels and schedule live in their own database, not here.
 *
 * A title the guide can schedule is a movie or an episode with a file (`partKey`) and at
 * least a minute long; the rule queries (`guide/GuideRules.kt`) use the same test.
 */
@Dao
interface GuideLibraryDao {
    /** Movies and episodes with a file the player can open. */
    @Query("SELECT COUNT(*) FROM items WHERE partKey IS NOT NULL AND type IN ('movie', 'episode')")
    suspend fun playableTitles(): Int

    /** The last sync: the starter lineup waits for a full one. */
    @Query("SELECT * FROM sync_state WHERE id = 0")
    suspend fun syncState(): SyncStateEntity?

    /** One cached title: a program's details when it's scheduled. */
    @Query("SELECT * FROM items WHERE ratingKey = :ratingKey")
    suspend fun item(ratingKey: String): ItemEntity?

    /** Which of [keys] are still cached, and whether each has a file: scheduled slots show off air otherwise. At most 500 keys. */
    @Query("SELECT ratingKey, partKey IS NOT NULL AS playable FROM items WHERE ratingKey IN (:keys)")
    suspend fun keyStates(keys: List<String>): List<GuideKeyState>

    /** Schedulable movies' length by genre combination ("|"-joined): the starter lineup splits them into genres. */
    @Query(
        "SELECT genres, COUNT(*) AS count, SUM(durationMs) AS totalMs FROM items " +
            "WHERE type = 'movie' AND partKey IS NOT NULL AND durationMs >= 60000 GROUP BY genres",
    )
    suspend fun movieGenreTotals(): List<GuideGenreTotal>

    /** Schedulable episodes' length by their show's genre combination, specials left out. */
    @Query(
        "SELECT s.genres AS genres, COUNT(*) AS count, SUM(i.durationMs) AS totalMs " +
            "FROM items AS i JOIN items AS s ON s.ratingKey = i.showKey " +
            "WHERE i.type = 'episode' AND i.partKey IS NOT NULL AND i.durationMs >= 60000 AND COALESCE(i.season, 1) != 0 " +
            "GROUP BY s.genres",
    )
    suspend fun tvGenreTotals(): List<GuideGenreTotal>

    /** Shows by how often their episodes were watched, most first: the starter lineup's marathons. */
    @Query(
        "SELECT i.showKey AS showKey, SUM(i.viewCount) AS views, MAX(COALESCE(i.lastViewedAt, 0)) AS lastViewedAt " +
            "FROM items AS i WHERE i.type = 'episode' AND i.showKey IS NOT NULL " +
            "GROUP BY i.showKey HAVING SUM(i.viewCount) > 0 " +
            "ORDER BY views DESC, lastViewedAt DESC, i.showKey LIMIT :limit",
    )
    suspend fun mostWatchedShows(limit: Int): List<GuideShowViews>

    /** A channel rule's titles (`SELECT` [GuidePoolEntry]'s columns), built by the guide's rule queries. */
    @RawQuery
    suspend fun pool(query: RoomRawQuery): List<GuidePoolEntry>

    /** How many titles a channel rule matches and how long they run: the 24-hour rule (Q5) and the alignment. */
    @RawQuery
    suspend fun poolStats(query: RoomRawQuery): GuidePoolStats?

    /** The shows a channel rule's episodes come from. */
    @RawQuery
    suspend fun poolShows(query: RoomRawQuery): List<GuideShowRow>

    /** Each content rating a channel rule's titles have, once (R2); a null row when one has none. */
    @RawQuery
    suspend fun poolRatings(query: RoomRawQuery): List<GuideRatingRow>

    // Phase 2.7: what a rule can pick from, for Settings › Plex Guide and the phone editor.

    /** Each library section and what's in it: the cache keeps a section's key, not its name. */
    @Query(
        "SELECT sectionKey, SUM(type = 'movie') AS movies, SUM(type = 'show') AS shows FROM items " +
            "GROUP BY sectionKey ORDER BY CAST(sectionKey AS INTEGER), sectionKey",
    )
    suspend fun sectionTotals(): List<GuideSectionTotal>

    /** Shows with an episode the guide can schedule, by title. */
    @Query(
        "SELECT s.ratingKey AS ratingKey, s.title AS title FROM items AS s WHERE s.type = 'show' AND EXISTS (" +
            "SELECT 1 FROM items AS i WHERE i.showKey = s.ratingKey AND i.type = 'episode' " +
            "AND i.partKey IS NOT NULL AND i.durationMs >= 60000) " +
            "ORDER BY s.title COLLATE NOCASE, s.ratingKey",
    )
    suspend fun showsWithEpisodes(): List<GuideTitledKey>

    /** The decades the guide's titles come from, by their first year (an episode has its show's year). */
    @Query(
        "SELECT DISTINCT (COALESCE(i.year, s.year) / 10) * 10 AS decade FROM items AS i " +
            "LEFT JOIN items AS s ON s.ratingKey = i.showKey " +
            "WHERE i.type IN ('movie', 'episode') AND i.partKey IS NOT NULL AND i.durationMs >= 60000 " +
            "AND COALESCE(i.year, s.year) IS NOT NULL ORDER BY decade",
    )
    suspend fun decades(): List<Int>

    /** The content ratings of the guide's titles (an episode without one has its show's). */
    @Query(
        "SELECT DISTINCT COALESCE(i.contentRating, s.contentRating) AS rating FROM items AS i " +
            "LEFT JOIN items AS s ON s.ratingKey = i.showKey " +
            "WHERE i.type IN ('movie', 'episode') AND i.partKey IS NOT NULL AND i.durationMs >= 60000 " +
            "AND COALESCE(i.contentRating, s.contentRating) IS NOT NULL ORDER BY rating",
    )
    suspend fun contentRatings(): List<String>

    /**
     * The "|"-joined collections of movies and shows the guide can schedule (a show's with
     * at least one schedulable episode): the phone editor's collection choices (G18).
     */
    @Query("SELECT DISTINCT t.collections FROM items AS t WHERE t.collections != '' AND $GUIDE_TITLE")
    suspend fun collectionTags(): List<String>

    /** The studios of movies and shows the guide can schedule, each with how many (Phase 9). */
    @Query("SELECT t.studio AS name, COUNT(*) AS titles FROM items AS t WHERE t.studio != '' AND $GUIDE_TITLE GROUP BY t.studio")
    suspend fun studioTotals(): List<GuideNameTotal>

    /** The "|"-joined labels of movies and shows the guide can schedule, each list with how many have it (Phase 9). */
    @Query("SELECT t.labels AS name, COUNT(*) AS titles FROM items AS t WHERE t.labels != '' AND $GUIDE_TITLE GROUP BY t.labels")
    suspend fun labelTotals(): List<GuideNameTotal>
}

/**
 * A movie (`t`) the guide can schedule, or a show with at least one episode it can: whose
 * collections, studios and labels the phone editor offers.
 */
private const val GUIDE_TITLE = "(" +
    "(t.type = 'movie' AND t.partKey IS NOT NULL AND t.durationMs >= 60000) OR " +
    "(t.type = 'show' AND EXISTS (SELECT 1 FROM items AS e WHERE e.showKey = t.ratingKey " +
    "AND e.type = 'episode' AND e.partKey IS NOT NULL AND e.durationMs >= 60000)))"

/** A name, or a "|"-joined list of them, and how many titles have it. */
data class GuideNameTotal(val name: String, val titles: Int)

/** A title a rule matched: what the scheduler orders and places. Its display fields are read only when it's placed. */
data class GuidePoolEntry(
    val ratingKey: String,
    val type: String,
    val durationMs: Long,
    val showKey: String?,
    val season: Int?,
    val episode: Int?,
)

/** A rule's titles: how many, their total and average length. */
data class GuidePoolStats(val count: Int, val totalMs: Long, val averageMs: Double)

data class GuideShowRow(val showKey: String)

/** A rating a rule's titles have (an episode without one has its show's); null for a title with none. */
data class GuideRatingRow(val rating: String?)

data class GuideKeyState(val ratingKey: String, val playable: Boolean)

/** One genre combination's schedulable titles. */
data class GuideGenreTotal(val genres: String?, val count: Int, val totalMs: Long)

data class GuideShowViews(val showKey: String, val views: Int, val lastViewedAt: Long)

/** A library section's movies and shows. */
data class GuideSectionTotal(val sectionKey: String, val movies: Int, val shows: Int)

data class GuideTitledKey(val ratingKey: String, val title: String)
