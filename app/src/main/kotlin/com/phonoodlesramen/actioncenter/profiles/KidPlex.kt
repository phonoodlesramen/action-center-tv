package com.phonoodlesramen.actioncenter.profiles

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.PlexPlay
import com.phonoodlesramen.actioncenter.plex.cache.ContinueEntity
import com.phonoodlesramen.actioncenter.plex.cache.KidCards
import com.phonoodlesramen.actioncenter.suggest.KidSource
import com.phonoodlesramen.actioncenter.suggest.KidView
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/**
 * A kid's Plex Continue Watching (K2), read with their own token after each sync: their
 * Up Next. Like the cache's `continue_watching`, in Plex's order. Times are epoch seconds.
 */
@Entity(tableName = "kid_continue", primaryKeys = ["profileId", "rank"])
data class KidContinueEntity(
    val profileId: String,
    val rank: Int,
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
    val lastViewedAt: Long?,
    val showKey: String?,
)

/** One of a kid's plays from the server's history (K2), kept 400 days like yours. */
@Entity(tableName = "kid_plays", primaryKeys = ["profileId", "ratingKey", "viewedAt"])
data class KidPlayEntity(
    val profileId: String,
    val ratingKey: String,
    val type: String,
    /** Epoch seconds. */
    val viewedAt: Long,
    /** An episode's show. */
    val showKey: String?,
    /** The server account it was logged under: a kid linked to another Plex user starts over. */
    val accountId: Long,
)

/** A sync's new plays for one kid: [rows], logged under [accountId]; the kid's plays before [before] (epoch seconds) or under another account go. */
data class KidPlays(val accountId: Long, val rows: List<KidPlayEntity>, val before: Long)

/** Each kid's Plex data in `profiles.db` (Phase 5, K2): [KidSync] writes it, the engine and the cards read it. */
@Dao
interface KidPlexDao {
    @Query("SELECT * FROM kid_continue WHERE profileId = :kid ORDER BY rank")
    fun continueWatching(kid: String): Flow<List<KidContinueEntity>>

    @Query("SELECT * FROM kid_continue WHERE profileId = :kid ORDER BY rank")
    suspend fun continueWatchingNow(kid: String): List<KidContinueEntity>

    @Query("SELECT * FROM kid_plays WHERE profileId = :kid ORDER BY viewedAt DESC")
    suspend fun plays(kid: String): List<KidPlayEntity>

    /** [plays] as they change: their cards' "seen" (Phase 6, R1). */
    @Query("SELECT * FROM kid_plays WHERE profileId = :kid ORDER BY viewedAt DESC")
    fun playsFlow(kid: String): Flow<List<KidPlayEntity>>

    /** The kid's newest play under [account]: the next sync asks only for later ones. */
    @Query("SELECT MAX(viewedAt) FROM kid_plays WHERE profileId = :kid AND accountId = :account")
    suspend fun latestPlay(kid: String, account: Long): Long?

    @Query("DELETE FROM kid_continue WHERE profileId = :kid")
    suspend fun clearContinue(kid: String)

    @Insert
    suspend fun insertContinue(rows: List<KidContinueEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlays(rows: List<KidPlayEntity>)

    @Query("DELETE FROM kid_plays WHERE profileId = :kid AND (accountId != :account OR viewedAt < :before)")
    suspend fun prunePlays(kid: String, account: Long, before: Long)

    @Query("DELETE FROM kid_plays WHERE profileId = :kid")
    suspend fun clearPlays(kid: String)

    @Query("DELETE FROM kid_continue WHERE profileId NOT IN (:kids)")
    suspend fun clearContinueOtherThan(kids: List<String>)

    @Query("DELETE FROM kid_plays WHERE profileId NOT IN (:kids)")
    suspend fun clearPlaysOtherThan(kids: List<String>)

    /**
     * One kid's sync at once, so the cards never see half of it: their Continue Watching
     * replaced, their new plays added and the old ones pruned. A null part failed and
     * keeps what it had.
     */
    @Transaction
    suspend fun save(kid: String, continueWatching: List<KidContinueEntity>?, plays: KidPlays?) {
        if (continueWatching != null) {
            clearContinue(kid)
            insertContinue(continueWatching)
        }
        if (plays != null) {
            prunePlays(kid, plays.accountId, plays.before)
            insertPlays(plays.rows)
        }
    }

    /** A kid who isn't linked (any more): their Plex data goes. */
    @Transaction
    suspend fun clear(kid: String) {
        clearContinue(kid)
        clearPlays(kid)
    }

    /** Only [kids]' Plex data stays: a removed kid's goes with them. */
    @Transaction
    suspend fun keepOnly(kids: List<String>) {
        clearContinueOtherThan(kids)
        clearPlaysOtherThan(kids)
    }
}

fun PlexItem.toKidContinue(profileId: String, rank: Int) = KidContinueEntity(
    profileId, rank, ratingKey, type, title, showTitle, season, episode, year, durationMs, viewOffsetMs, contentRating, thumb, art, showArt,
    lastViewedAt, showKey,
)

fun PlexPlay.toKidPlay(profileId: String, accountId: Long) = KidPlayEntity(profileId, ratingKey, type, viewedAt, showKey, accountId)

/** As the cards and the engine read Continue Watching. */
fun KidContinueEntity.toContinue() = ContinueEntity(
    rank, ratingKey, type, title, showTitle, season, episode, year, durationMs, viewOffsetMs, contentRating, thumb, art, showArt,
    lastViewedAt = lastViewedAt,
    showKey = showKey,
)

/** A kid's ratings on [today]: their override, else their age's bracket (K3). */
fun KidEntity.ceiling(brackets: List<AgeBracket>, today: LocalDate): Set<String> =
    AgeBrackets.ceiling(LocalDate.ofEpochDay(birthday), ceilingOverride?.let { RoomProfileStore.split(it) }, brackets, today)

/**
 * The cards' own part for [profileId], a kid (Phase 5): their ceiling today, their Continue
 * Watching and their plays (Phase 6: what they've seen), none while they aren't linked to
 * a Plex Home user. A kid who has just been removed gets nothing at all, never yours, until
 * the profile falls back to you.
 */
fun KidPlexDao.cards(profileId: String, kids: Flow<List<KidEntity>>, brackets: Flow<List<AgeBracket>>, days: Flow<LocalDate>): Flow<KidCards> =
    combine(kids, brackets, days.distinctUntilChanged(), continueWatching(profileId), playsFlow(profileId)) { all, table, day, rows, plays ->
        val kid = all.firstOrNull { it.id == profileId } ?: return@combine KidCards(ceiling = emptySet(), continueWatching = emptyList())
        val linked = kid.plexUserId != null
        KidCards.of(kid.ceiling(table, day), if (linked) rows.map { it.toContinue() } else emptyList(), if (linked) plays else emptyList())
    }

/** The kids' part of the engine's runs (Phase 5), from `profiles.db`: a kid who isn't linked has no plays and no Continue Watching. */
class StoredKidSource(private val store: ProfileStore, private val dao: KidPlexDao) : KidSource {
    override suspend fun kids(today: LocalDate): List<KidView> {
        val brackets = store.brackets.first()
        return store.kids.first().map { kid ->
            val linked = kid.plexUserId != null
            KidView(
                id = kid.id,
                ceiling = kid.ceiling(brackets, today),
                plays = if (linked) dao.plays(kid.id) else emptyList(),
                continueWatching = if (linked) dao.continueWatchingNow(kid.id).map { it.toContinue() } else emptyList(),
            )
        }
    }
}
