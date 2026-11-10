package com.phonoodlesramen.actioncenter.plex.cache

import androidx.room.Dao
import androidx.room.Query

/**
 * The suggestion engine's reads of the Plex cache (Phase 3): one pass of everything a run
 * needs. Its own interface, like [GuideLibraryDao], so the engine's queries never touch
 * [PlexDao]. Feedback and picks live in their own database (`suggest/SuggestionDatabase`).
 */
@Dao
interface SuggestLibraryDao {
    /** Movies and shows: what's scored, and what signals land on (an episode's land on its show). */
    @Query("SELECT * FROM items WHERE type IN ('movie', 'show')")
    suspend fun titles(): List<ItemEntity>

    /** Every episode with a show, slim: a show's finishes, stops and next episodes. */
    @Query(
        "SELECT ratingKey, showKey, season, episode, durationMs, viewCount, lastViewedAt, viewOffsetMs, addedAt, contentRating " +
            "FROM items WHERE type = 'episode' AND showKey IS NOT NULL",
    )
    suspend fun episodes(): List<EpisodeRow>

    @Query("SELECT * FROM plays")
    suspend fun plays(): List<PlayEntity>

    @Query("SELECT * FROM continue_watching ORDER BY position")
    suspend fun continueWatching(): List<ContinueEntity>
}

/** An episode, as far as suggestions care. Times are epoch seconds. */
data class EpisodeRow(
    val ratingKey: String,
    val showKey: String,
    val season: Int?,
    val episode: Int?,
    val durationMs: Long?,
    val viewCount: Int,
    val lastViewedAt: Long?,
    val viewOffsetMs: Long?,
    val addedAt: Long?,
    /** Its own rating: a kid's run leaves out what their ceiling doesn't allow, an unrated episode taking its show's (K4). */
    val contentRating: String? = null,
)
