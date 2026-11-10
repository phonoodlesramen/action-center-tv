package com.phonoodlesramen.actioncenter.plex.cache

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.PlexPlay
import kotlinx.coroutines.flow.Flow

/** A movie, show or episode from a Movies or TV library (P7). Times are epoch seconds. */
@Entity(tableName = "items", indices = [Index("sectionKey"), Index("type", "addedAt"), Index("showKey")])
data class ItemEntity(
    @PrimaryKey val ratingKey: String,
    val sectionKey: String,
    val type: String,
    val title: String,
    val year: Int?,
    val durationMs: Long?,
    val viewCount: Int,
    val lastViewedAt: Long?,
    val addedAt: Long?,
    val updatedAt: Long?,
    val contentRating: String?,
    val audienceRating: Double?,
    val rating: Double?,
    val thumb: String?,
    val art: String?,
    /** "|"-joined, for Phase 3's genre affinity. */
    val genres: String,
    /** When this row was last seen in a sync (millis): a full sync drops rows it didn't see. */
    val syncedAt: Long,
    /** A movie's trailer extra (its ratingKey), so Trailer needs no request. Null when it has none. */
    val trailerKey: String? = null,
    // Phase 2.6 (the Plex Guide): episodes, their shows, descriptions and each title's file.
    /** An episode's show: its ratingKey and title, season and episode numbers. */
    val showKey: String? = null,
    val showTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** Plex's description, for the guide's info panel. */
    val summary: String? = null,
    /** The first file's part path (`/library/parts/…`), never a token. Null: nothing to play. */
    val partKey: String? = null,
    val container: String? = null,
    val videoCodec: String? = null,
    val videoProfile: String? = null,
    val videoResolution: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val sizeBytes: Long? = null,
    // Phase 3 (suggestions): what the engine scores on. A position or rating changes with the
    // daily full sync: watching doesn't move `updatedAt`, so an incremental sync misses it.
    /** How far into an unfinished film or episode you are (millis). Null: not started, or finished. */
    val viewOffsetMs: Long? = null,
    /** Your star rating, 0-10, and when you gave it (epoch seconds). */
    val userRating: Double? = null,
    val lastRatedAt: Long? = null,
    /** "YYYY-MM-DD" (Plex's `originallyAvailableAt`): Throwback's release anniversaries. */
    val releaseDate: String? = null,
    /**
     * "|"-joined. A movie's or show's (and [genres]) come from its details once [detailsAt]
     * is set (S11): the listing gives at most 2 genres and 3 cast, and no labels.
     */
    val collections: String = "",
    val labels: String = "",
    val directors: String = "",
    /** The cast, top-billed first: at most [PlexSync.CAST_KEPT] from the details. */
    val actors: String = "",
    /** A show's episodes, and how many of them you've watched. */
    val episodeCount: Int? = null,
    val watchedEpisodeCount: Int? = null,
    /** When the tags were last filled from the title's details (millis); null: the listing's. */
    val detailsAt: Long? = null,
    /**
     * A movie's or show's studio, "" when it has none (Phase 9: a guide channel can pick
     * by it). The listing's, or its details' when the listing has none. An episode's rule
     * reads its show's.
     */
    @ColumnInfo(defaultValue = "")
    val studio: String = "",
    /**
     * The title's logo (Phase 12): its `clearLogo` path, null when it has none. The
     * listing's, or its details' when the listing has none. An episode's card uses its show's.
     */
    val logo: String? = null,
)

/** A movie's or show's tags from its details (S11), and when they were filled (millis). */
data class FilledTags(
    val ratingKey: String,
    val updatedAt: Long?,
    val genres: String,
    val collections: String,
    val labels: String,
    val directors: String,
    val actors: String,
    val detailsAt: Long?,
    /** "" when the details have none: the listing's stays. */
    val studio: String = "",
    /** Null when the details have none: the listing's stays (Phase 12). */
    val logo: String? = null,
)

/** Plex's Continue Watching, in Plex's order (D15). */
@Entity(tableName = "continue_watching")
data class ContinueEntity(
    @PrimaryKey val position: Int,
    val ratingKey: String,
    val type: String,
    val title: String,
    val showTitle: String?,
    val season: Int?,
    val episode: Int?,
    val year: Int?,
    val durationMs: Long?,
    val viewOffsetMs: Long?,
    val contentRating: String?,
    val thumb: String?,
    val art: String?,
    val showArt: String?,
    /** When you last watched any of it (epoch seconds), and an episode's show: Up Next's ranking (Phase 3). */
    val lastViewedAt: Long? = null,
    val showKey: String? = null,
)

/** One play from the signed-in account's history (P5). */
@Entity(tableName = "plays", indices = [Index("viewedAt"), Index("ratingKey")])
data class PlayEntity(
    @PrimaryKey val historyKey: String,
    val ratingKey: String,
    val type: String,
    val title: String,
    val viewedAt: Long,
    val thumb: String?,
)

/** The last sync, for staleness, the top bar's status and incremental syncs. Times are millis. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastFullAt: Long? = null,
    /** Epoch seconds: the next incremental sync asks for items updated after this. */
    val itemsUpdatedSince: Long? = null,
    /** Why the last sync failed; null when it worked. */
    val error: String? = null,
)

@Dao
interface PlexDao {
    @Upsert
    suspend fun upsertItems(items: List<ItemEntity>)

    @Query("DELETE FROM items WHERE sectionKey = :section AND syncedAt < :run")
    suspend fun deleteUnseen(section: String, run: Long)

    @Query("DELETE FROM items WHERE sectionKey NOT IN (:sections)")
    suspend fun deleteSectionsOtherThan(sections: List<String>)

    @Query("DELETE FROM continue_watching")
    suspend fun clearContinueWatching()

    @Insert
    suspend fun insertContinueWatching(items: List<ContinueEntity>)

    @Transaction
    suspend fun replaceContinueWatching(items: List<ContinueEntity>) {
        clearContinueWatching()
        insertContinueWatching(items)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlays(plays: List<PlayEntity>)

    /** A play the incremental sync heard about: Plex doesn't bump `updatedAt` for it. */
    @Query("UPDATE items SET viewCount = MAX(viewCount, 1), lastViewedAt = MAX(COALESCE(lastViewedAt, 0), :viewedAt) WHERE ratingKey = :ratingKey")
    suspend fun markPlayed(ratingKey: String, viewedAt: Long)

    @Query("DELETE FROM plays WHERE viewedAt < :before")
    suspend fun prunePlays(before: Long)

    @Query("SELECT MAX(viewedAt) FROM plays")
    suspend fun latestPlay(): Long?

    @Query("SELECT * FROM sync_state WHERE id = 0")
    suspend fun syncState(): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE id = 0")
    fun syncStateFlow(): Flow<SyncStateEntity?>

    @Upsert
    suspend fun saveSyncState(state: SyncStateEntity)

    @Query("DELETE FROM items")
    suspend fun clearItems()

    @Query("DELETE FROM plays")
    suspend fun clearPlays()

    @Query("DELETE FROM sync_state")
    suspend fun clearSyncState()

    // Reads for the home screen. The cards read only these (U15: the cache is the truth).

    @Query("SELECT * FROM continue_watching ORDER BY position")
    suspend fun continueWatching(): List<ContinueEntity>

    @Query("SELECT * FROM items WHERE type = 'movie' AND viewCount = 0 ORDER BY addedAt DESC LIMIT :limit")
    suspend fun newestUnwatchedMovies(limit: Int): List<ItemEntity>

    @Query(
        "SELECT * FROM items WHERE type = 'movie' AND year <= :maxYear " +
            "AND COALESCE(audienceRating, rating, 0) >= :minRating " +
            "AND (viewCount = 0 OR lastViewedAt < :notSince) " +
            "ORDER BY COALESCE(audienceRating, rating) DESC LIMIT :limit",
    )
    suspend fun classics(maxYear: Int, minRating: Double, notSince: Long, limit: Int): List<ItemEntity>

    @Query(
        "SELECT items.* FROM plays JOIN items ON items.ratingKey = plays.ratingKey " +
            "WHERE plays.viewedAt BETWEEN :from AND :to AND items.type = 'movie' " +
            "GROUP BY items.ratingKey ORDER BY MAX(plays.viewedAt) DESC LIMIT :limit",
    )
    suspend fun watchedBetween(from: Long, to: Long, limit: Int): List<ItemEntity>

    @Query("SELECT COUNT(*) FROM items")
    suspend fun itemCount(): Int

    /** Poster wall (L1): poster paths of random movies and shows. */
    @Query("SELECT thumb FROM items WHERE type IN ('movie', 'show') AND thumb IS NOT NULL ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomPosters(limit: Int): List<String>

    /** One cached title: the guide's player finds its file here. */
    @Query("SELECT * FROM items WHERE ratingKey = :ratingKey")
    suspend fun item(ratingKey: String): ItemEntity?

    /** A show's cached episodes: the next one takes Up Next's place of one you finished in its credits ([Finished]). */
    @Query("SELECT * FROM items WHERE type = 'episode' AND showKey = :showKey")
    suspend fun episodesOf(showKey: String): List<ItemEntity>

    /** Cached titles by key (at most 500 keys): what the engine's picks name. */
    @Query("SELECT * FROM items WHERE ratingKey IN (:keys)")
    suspend fun items(keys: List<String>): List<ItemEntity>

    /**
     * Search's fallback while Plex can't be reached (Phase 15, Q1): movies and shows whose
     * title holds [words] (a `LIKE` pattern, its `%`, `_` and `\` escaped with `\`), shortest
     * title first, so "Heat" comes before "The Heat of the Night".
     */
    @Query("SELECT * FROM items WHERE type IN ('movie', 'show') AND title LIKE '%' || :words || '%' ESCAPE '\\' ORDER BY LENGTH(title), title LIMIT :limit")
    suspend fun titlesLike(words: String, limit: Int): List<ItemEntity>

    /**
     * Playable titles matching a file description, largest first: the Playback check's
     * candidates. A null filter matches anything; [videoProfile] matches by substring.
     */
    @Query(
        "SELECT * FROM items WHERE partKey IS NOT NULL AND type IN ('movie', 'episode') " +
            "AND (:container IS NULL OR container = :container) " +
            "AND (:videoCodec IS NULL OR videoCodec = :videoCodec) " +
            "AND (:videoProfile IS NULL OR videoProfile LIKE '%' || :videoProfile || '%') " +
            "AND (:audioCodec IS NULL OR audioCodec = :audioCodec) " +
            "ORDER BY sizeBytes DESC LIMIT :limit",
    )
    suspend fun playableLike(
        container: String? = null,
        videoCodec: String? = null,
        videoProfile: String? = null,
        audioCodec: String? = null,
        limit: Int = 1,
    ): List<ItemEntity>

    /** Playable titles whose main audio is one of [codecs]: the Playback check's DTS count (G15). */
    @Query("SELECT COUNT(*) FROM items WHERE partKey IS NOT NULL AND type IN ('movie', 'episode') AND audioCodec IN (:codecs)")
    suspend fun playableWithAudio(codecs: List<String>): Int

    /** How many cached titles have a file to play, and how many are episodes: the sync's log. */
    @Query("SELECT COUNT(*) FROM items WHERE partKey IS NOT NULL")
    suspend fun playableCount(): Int

    // Full tags from the details (S11).

    /** The filled tags of those of [keys] that have them (at most 500 keys): the listing's upsert keeps them. */
    @Query(
        "SELECT ratingKey, updatedAt, genres, collections, labels, directors, actors, detailsAt, studio, logo FROM items " +
            "WHERE ratingKey IN (:keys) AND detailsAt IS NOT NULL",
    )
    suspend fun filledTags(keys: List<String>): List<FilledTags>

    /** Movies and shows whose tags need their details: never filled first, then filled before [before]. */
    @Query(
        "SELECT ratingKey FROM items WHERE type IN ('movie', 'show') AND (detailsAt IS NULL OR detailsAt < :before) " +
            "ORDER BY detailsAt IS NOT NULL, detailsAt, ratingKey LIMIT :limit",
    )
    suspend fun needingDetails(before: Long, limit: Int): List<String>

    /**
     * Writes a title's tags from its details, and nothing else: its file's details stay as
     * they are. A [studio] of "" keeps the listing's, and so does a null [logo].
     */
    @Query(
        "UPDATE items SET genres = :genres, collections = :collections, labels = :labels, directors = :directors, " +
            "actors = :actors, detailsAt = :detailsAt, studio = CASE WHEN :studio = '' THEN studio ELSE :studio END, " +
            "logo = COALESCE(:logo, logo) WHERE ratingKey = :ratingKey",
    )
    suspend fun fillTags(
        ratingKey: String,
        genres: String,
        collections: String,
        labels: String,
        directors: String,
        actors: String,
        detailsAt: Long?,
        studio: String,
        logo: String?,
    )

    @Transaction
    suspend fun fillAll(rows: List<FilledTags>) = rows.forEach {
        fillTags(it.ratingKey, it.genres, it.collections, it.labels, it.directors, it.actors, it.detailsAt, it.studio, it.logo)
    }

    // The library check (Phase 3, LibraryCheck): what the cache holds for scoring. A
    // "|"-joined column's names are its "|"s plus one, or none when it's empty.

    @Query(
        "SELECT COUNT(*) AS items, " +
            "COALESCE(SUM(viewOffsetMs > 0), 0) AS withPosition, " +
            "COALESCE(SUM(userRating IS NOT NULL), 0) AS rated, " +
            "COALESCE(SUM(releaseDate IS NOT NULL), 0) AS withReleaseDate, " +
            "COALESCE(SUM(collections != ''), 0) AS inCollections, " +
            "COALESCE(SUM(labels != ''), 0) AS labelled, " +
            "COALESCE(SUM(type IN ('movie', 'show') AND studio != ''), 0) AS withStudio, " +
            "COALESCE(SUM(type IN ('movie', 'show') AND logo IS NOT NULL), 0) AS withLogo, " +
            "COALESCE(SUM(directors != ''), 0) AS withDirectors, " +
            "COALESCE(SUM(actors != ''), 0) AS withCast, " +
            "COALESCE(SUM(type = 'show'), 0) AS shows, " +
            "COALESCE(SUM(type = 'show' AND episodeCount IS NOT NULL), 0) AS showsWithCounts, " +
            "COALESCE(SUM(type IN ('movie', 'show')), 0) AS moviesAndShows, " +
            "COALESCE(SUM(detailsAt IS NOT NULL), 0) AS filled, " +
            "COALESCE(MAX(CASE genres WHEN '' THEN 0 ELSE LENGTH(genres) - LENGTH(REPLACE(genres, '|', '')) + 1 END), 0) AS mostGenres, " +
            "COALESCE(MAX(CASE actors WHEN '' THEN 0 ELSE LENGTH(actors) - LENGTH(REPLACE(actors, '|', '')) + 1 END), 0) AS mostCast, " +
            "COALESCE(MAX(CASE directors WHEN '' THEN 0 ELSE LENGTH(directors) - LENGTH(REPLACE(directors, '|', '')) + 1 END), 0) AS mostDirectors " +
            "FROM items",
    )
    suspend fun libraryFields(): LibraryFields

    /**
     * Whether "last viewed" moves on a partial play: unfinished titles that have one, and
     * rewatches in progress whose last view is later than their last finish in history.
     */
    @Query(
        "SELECT COALESCE(SUM(viewCount = 0), 0) AS unfinished, " +
            "COALESCE(SUM(viewCount = 0 AND lastViewedAt IS NOT NULL), 0) AS unfinishedWithLastViewed, " +
            "COALESCE(SUM(viewCount > 0), 0) AS rewatching, " +
            "COALESCE(SUM(viewCount > 0 AND lastViewedAt > " +
            "(SELECT COALESCE(MAX(plays.viewedAt), 0) FROM plays WHERE plays.ratingKey = items.ratingKey)), 0) AS rewatchingViewedSinceFinish " +
            "FROM items WHERE viewOffsetMs > 0",
    )
    suspend fun positionTimes(): PositionTimes

    @Query("SELECT COUNT(*) AS items, COALESCE(SUM(lastViewedAt IS NOT NULL), 0) AS withLastViewed FROM continue_watching")
    suspend fun continueWatchingTimes(): ContinueWatchingTimes

    /** Every labelled title's "|"-joined labels: the library check names the commonest (Seasonal reads labels, S8). */
    @Query("SELECT labels FROM items WHERE labels != ''")
    suspend fun labelLists(): List<String>

    /** The titles of [type] listing the most cast: if the listing cuts lists short, these show it. */
    @Query(
        "SELECT * FROM items WHERE type = :type AND actors != '' " +
            "ORDER BY LENGTH(actors) - LENGTH(REPLACE(actors, '|', '')) DESC, ratingKey LIMIT :limit",
    )
    suspend fun mostListedCast(type: String, limit: Int): List<ItemEntity>
}

/** How many cached titles carry each field Phase 3 scores on, and the most names any title lists. */
data class LibraryFields(
    val items: Int,
    val withPosition: Int,
    val rated: Int,
    val withReleaseDate: Int,
    val inCollections: Int,
    val labelled: Int,
    /** Movies and shows with a studio (Phase 9): an episode's rule reads its show's. */
    val withStudio: Int,
    /** Movies and shows with a logo (Phase 12): an episode's card uses its show's. */
    val withLogo: Int,
    val withDirectors: Int,
    val withCast: Int,
    val shows: Int,
    val showsWithCounts: Int,
    val moviesAndShows: Int,
    val filled: Int,
    val mostGenres: Int,
    val mostCast: Int,
    val mostDirectors: Int,
)

/** Titles with a position: unfinished ones, and rewatches in progress. */
data class PositionTimes(
    val unfinished: Int,
    val unfinishedWithLastViewed: Int,
    val rewatching: Int,
    val rewatchingViewedSinceFinish: Int,
)

data class ContinueWatchingTimes(val items: Int, val withLastViewed: Int)

/**
 * Version 2 added `items.trailerKey`; version 3 added episodes, their shows, summaries and
 * each title's file (Phase 2.6); version 4 added positions, ratings, release dates,
 * collections, labels, directors, cast and episode counts, and Continue Watching's last
 * viewed and show (Phase 3); version 5 added `items.detailsAt`, for tags from each title's
 * details (S11). Up to 5, a new version re-synced from scratch.
 *
 * Version 6 adds `items.studio` (Phase 9) and migrates, so the TVs' and the station's
 * caches keep their rows: [FullSyncNext] makes the next sync a full one, which fills each
 * title's studio. Version 7 adds `items.logo` (Phase 12) the same way. Any other version
 * change still empties the cache (the builders' destructive fallback).
 */
@Database(
    entities = [ItemEntity::class, ContinueEntity::class, PlayEntity::class, SyncStateEntity::class],
    version = 7,
    autoMigrations = [
        AutoMigration(from = 5, to = 6, spec = PlexDatabase.FullSyncNext::class),
        AutoMigration(from = 6, to = 7, spec = PlexDatabase.FullSyncNext::class),
    ],
)
@ConstructedBy(PlexDatabaseConstructor::class)
abstract class PlexDatabase : RoomDatabase() {
    abstract fun dao(): PlexDao

    /** The Plex Guide's own queries on the library (Phase 2.6, package E). */
    abstract fun guideLibrary(): GuideLibraryDao

    /** The suggestion engine's reads (Phase 3). */
    abstract fun suggestLibrary(): SuggestLibraryDao

    /**
     * After 5 to 6 adds `items.studio` (empty), or 6 to 7 adds `items.logo` (null): forgets
     * when the last full sync was, so the next sync reads every title again and fills it in.
     * The incremental mark and the rest of the sync state stay.
     */
    class FullSyncNext : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
            connection.execSQL("UPDATE sync_state SET lastFullAt = NULL")
        }
    }

    companion object {
        const val NAME = "plex-cache.db"
        val TABLES = arrayOf("items", "continue_watching", "plays", "sync_state")
    }
}

/** Room's KSP writes the `actual` for each target, so neither needs reflection (Phase 8). */
@Suppress("KotlinNoActualForExpect")
expect object PlexDatabaseConstructor : RoomDatabaseConstructor<PlexDatabase> {
    override fun initialize(): PlexDatabase
}

fun PlexItem.toEntity(sectionKey: String, syncedAt: Long) = ItemEntity(
    ratingKey, sectionKey, type, title, year, durationMs, viewCount, lastViewedAt, addedAt, updatedAt, contentRating,
    audienceRating, rating, thumb, art, genres.joinToString("|"), syncedAt, trailerKey,
    showKey = showKey,
    showTitle = showTitle,
    season = season,
    episode = episode,
    summary = summary,
    partKey = media?.partKey,
    container = media?.container,
    videoCodec = media?.videoCodec,
    videoProfile = media?.videoProfile,
    videoResolution = media?.videoResolution,
    audioCodec = media?.audioCodec,
    audioChannels = media?.audioChannels,
    sizeBytes = media?.sizeBytes,
    viewOffsetMs = viewOffsetMs?.takeIf { it > 0 },
    userRating = userRating,
    lastRatedAt = lastRatedAt,
    releaseDate = releaseDate,
    collections = collections.joinToString("|"),
    labels = labels.joinToString("|"),
    directors = directors.joinToString("|"),
    actors = actors.joinToString("|"),
    episodeCount = episodeCount,
    watchedEpisodeCount = watchedEpisodeCount,
    studio = studio.orEmpty(),
    logo = logo,
)

/** A title's details as the tags to fill (S11): cast kept to the [PlexSync.CAST_KEPT] top-billed. */
fun PlexItem.toFilledTags(detailsAt: Long) = FilledTags(
    ratingKey, updatedAt,
    genres = genres.joinToString("|"),
    collections = collections.joinToString("|"),
    labels = labels.joinToString("|"),
    directors = directors.joinToString("|"),
    actors = actors.take(PlexSync.CAST_KEPT).joinToString("|"),
    detailsAt = detailsAt,
    studio = studio.orEmpty(),
    logo = logo,
)

fun PlexItem.toContinue(position: Int) = ContinueEntity(
    position, ratingKey, type, title, showTitle, season, episode, year, durationMs, viewOffsetMs, contentRating, thumb, art, showArt,
    lastViewedAt = lastViewedAt,
    showKey = showKey,
)

fun PlexPlay.toEntity() = PlayEntity(historyKey, ratingKey, type, title, viewedAt, thumb)
