package com.phonoodlesramen.actioncenter.suggest

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import com.phonoodlesramen.actioncenter.content.Lens
import kotlinx.coroutines.flow.Flow

/** Whose feedback and picks. Phase 5 adds one profile per kid; until then everything is the owner's. */
object Profiles {
    const val OWNER = "owner"
}

/** What you told a card (S1, S10). */
enum class FeedbackKind {
    /** Tonight's Pick › Not tonight. */
    NOT_TONIGHT,

    /** Up Next › Remove from Up Next: until you play it again, and it counts as abandoned. */
    UP_NEXT_REMOVED,

    /** Quick Watch › Hide from Quick Watch. */
    QUICK_WATCH_HIDDEN,

    /** The lens card › Don't show this: from that lens only (the row's scope). */
    LENS_HIDDEN,

    /** Passed over on the lens card (Phase 14, L5): on it for days without a play, it rests from every lens. */
    LENS_RESTED,
}

/**
 * One piece of feedback on a title: a movie, or a show (an episode's feedback is its
 * show's). Times are millis.
 */
@Entity(tableName = "feedback", primaryKeys = ["profile", "ratingKey", "kind", "scope"])
data class FeedbackEntity(
    val profile: String,
    val ratingKey: String,
    val kind: FeedbackKind,
    /** The lens's name for [FeedbackKind.LENS_HIDDEN]; empty otherwise. */
    val scope: String = "",
    val atMillis: Long,
    /** How many in a row within [FeedbackRules.SECOND_STRIKE_WITHIN_MILLIS]: Not tonight's second strike. */
    val count: Int = 1,
    /** The title is hidden until then. */
    val untilMillis: Long,
)

/** Which card a pick is for. */
object Surfaces {
    const val HERO = "hero"
    const val QUICK_WATCH = "quick"
    const val UP_NEXT = "upnext"

    fun lens(lens: Lens) = "lens:${lens.name}"

    /** More like this (S10): titles like the hero's pick [ratingKey]. */
    fun moreLike(ratingKey: String) = "more:$ratingKey"
}

/** One title the engine picked for a card, in order, with its reason [REQ]. */
@Entity(tableName = "picks", primaryKeys = ["profile", "surface", "rank"])
data class PickEntity(
    val profile: String,
    /** [Surfaces]. */
    val surface: String,
    val rank: Int,
    val ratingKey: String,
    /** "Because you liked Heat": the most specific evidence. */
    val reason: String,
    /** The next pieces of evidence, "|"-joined, for Why this pick?. */
    val evidence: String = "",
    val computedAt: Long,
    /** A "finish it" pick's episodes left in its season (S5): the mode decides whether it leads (M3). */
    val episodesLeft: Int? = null,
)

/**
 * An AI blurb (M7-M9): one line for a pick, written for the [reason] it had then. It shows
 * in place of the reason only while the pick still has that reason, so it never outlives
 * its evidence.
 */
@Entity(tableName = "blurbs", primaryKeys = ["profile", "ratingKey"])
data class BlurbEntity(
    val profile: String,
    val ratingKey: String,
    val reason: String,
    val text: String,
    val writtenAt: Long,
)

/**
 * A day a title (a movie, or a show) was on the lens card for a few seconds with the home on
 * screen (Phase 14, L5), for whoever was watching. "YYYY-MM-DD", local. The engine counts the
 * days since it was last played and rests it ([LensFreshness]).
 */
@Entity(tableName = "lens_shown", primaryKeys = ["profile", "ratingKey", "day"])
data class LensShownEntity(
    val profile: String,
    val ratingKey: String,
    val day: String,
)

/** The last run per profile: when, for which day (a new day runs again), how long it took, and why it failed. */
@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey val profile: String,
    val ranAt: Long,
    /** The local day it ran for, "YYYY-MM-DD". */
    val day: String,
    val tookMillis: Long,
    val error: String? = null,
)

@Dao
interface SuggestionDao {
    @Query("SELECT * FROM feedback WHERE profile = :profile")
    suspend fun feedback(profile: String): List<FeedbackEntity>

    @Query("SELECT * FROM feedback WHERE profile = :profile")
    fun feedbackFlow(profile: String): Flow<List<FeedbackEntity>>

    @Query("SELECT * FROM feedback WHERE profile = :profile AND ratingKey = :ratingKey AND kind = :kind AND scope = :scope")
    suspend fun feedback(profile: String, ratingKey: String, kind: FeedbackKind, scope: String): FeedbackEntity?

    @Upsert
    suspend fun saveFeedback(row: FeedbackEntity)

    @Query("DELETE FROM feedback WHERE profile = :profile AND ratingKey = :ratingKey AND kind = :kind AND scope = :scope")
    suspend fun deleteFeedback(profile: String, ratingKey: String, kind: FeedbackKind, scope: String)

    /** Feedback that hides nothing and has faded from the signals. */
    @Query("DELETE FROM feedback WHERE untilMillis < :before AND atMillis < :before")
    suspend fun pruneFeedback(before: Long)

    @Query("SELECT * FROM picks WHERE profile = :profile ORDER BY surface, rank")
    fun picks(profile: String): Flow<List<PickEntity>>

    @Query("DELETE FROM picks WHERE profile = :profile")
    suspend fun clearPicks(profile: String)

    @Insert
    suspend fun insertPicks(rows: List<PickEntity>)

    /** A run's picks replace the last run's all at once, so the home never sees half of each. */
    @Transaction
    suspend fun replacePicks(profile: String, rows: List<PickEntity>) {
        clearPicks(profile)
        insertPicks(rows)
    }

    @Query("DELETE FROM picks WHERE profile NOT IN (:profiles)")
    suspend fun clearPicksOtherThan(profiles: List<String>)

    @Query("DELETE FROM runs WHERE profile NOT IN (:profiles)")
    suspend fun clearRunsOtherThan(profiles: List<String>)

    /** Only [profiles]' picks and runs stay (Phase 5): a removed kid's go. Their feedback stays until it's pruned. */
    @Transaction
    suspend fun keepPicksOf(profiles: List<String>) {
        clearPicksOtherThan(profiles)
        clearRunsOtherThan(profiles)
    }

    @Query("SELECT * FROM runs WHERE profile = :profile")
    suspend fun run(profile: String): RunEntity?

    @Upsert
    suspend fun saveRun(run: RunEntity)

    @Query("SELECT * FROM blurbs WHERE profile = :profile")
    fun blurbs(profile: String): Flow<List<BlurbEntity>>

    @Upsert
    suspend fun saveBlurbs(rows: List<BlurbEntity>)

    /** Blurbs older than [before]: a week's are kept, in case a pick comes back. */
    @Query("DELETE FROM blurbs WHERE writtenAt < :before")
    suspend fun pruneBlurbs(before: Long)

    /** A day on the lens card (L5): once a day a title, so a second time that day does nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun saveShown(row: LensShownEntity)

    @Query("SELECT * FROM lens_shown WHERE profile = :profile")
    suspend fun shown(profile: String): List<LensShownEntity>

    @Query("DELETE FROM lens_shown WHERE profile = :profile AND ratingKey IN (:ratingKeys)")
    suspend fun clearShown(profile: String, ratingKeys: List<String>)

    /** Days on the lens card before [before] ("YYYY-MM-DD"): long past any count. */
    @Query("DELETE FROM lens_shown WHERE day < :before")
    suspend fun pruneShown(before: String)

    /** [rests] start, and the days that earned them go, at once (L5). */
    @Transaction
    suspend fun rest(profile: String, rests: List<FeedbackEntity>) {
        rests.forEach { saveFeedback(it) }
        rests.map { it.ratingKey }.chunked(SHOWN_CHUNK).forEach { clearShown(profile, it) }
    }
}

/** Keys a statement, under SQLite's 999 variables. */
private const val SHOWN_CHUNK = 500

/**
 * Feedback, picks and runs (Phase 3), in their own file like `guide.db`: a Plex cache
 * rebuild or a sign-out never wipes your feedback. The schema is exported, so a later
 * version migrates instead of starting over. Version 2 (Phase 4) adds a finish pick's
 * episodes left and the AI blurbs; version 3 (Phase 14) the lens card's days on screen.
 */
@Database(
    entities = [FeedbackEntity::class, PickEntity::class, RunEntity::class, BlurbEntity::class, LensShownEntity::class],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class SuggestionDatabase : RoomDatabase() {
    abstract fun dao(): SuggestionDao

    companion object {
        const val NAME = "suggestions.db"
    }
}
