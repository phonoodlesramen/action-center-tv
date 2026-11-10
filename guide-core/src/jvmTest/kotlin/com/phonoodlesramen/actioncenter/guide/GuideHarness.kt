package com.phonoodlesramen.actioncenter.guide

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.plex.cache.PlexDatabase
import com.phonoodlesramen.actioncenter.plex.cache.SyncStateEntity
import java.time.ZoneOffset
import java.util.Collections
import kotlinx.coroutines.Dispatchers

/**
 * An in-memory Plex cache and guide.db, and a [GuideEngine] and its [RoomGuideEditor] on a
 * clock the test moves (UTC, so the half hours are easy to read). Close it after each test.
 * A harness given another's Plex cache leaves it open.
 */
internal class GuideHarness(
    plex: PlexDatabase? = null,
    checkEveryMillis: Long = GuideEngine.CHECK_EVERY_MILLIS,
    maxWaitMillis: Long = GuideEngine.MAX_WAIT_MILLIS,
    /** The linked Plex server (G19); none by default. */
    linkedServer: suspend () -> String? = { null },
    val lineupServer: LineupServer = LineupServer.InMemory(),
) : AutoCloseable {
    private val ownsPlex = plex == null

    val plex: PlexDatabase = plex
        ?: Room.inMemoryDatabaseBuilder<PlexDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()

    val guide: GuideDatabase = Room.inMemoryDatabaseBuilder<GuideDatabase>()
        .setDriver(BundledSQLiteDriver())
        .build()

    @Volatile
    var now = WEEKNIGHT

    val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val engine = GuideEngine(
        guide, this.plex, clock = { now }, zone = { ZoneOffset.UTC }, io = Dispatchers.IO, checkEveryMillis = checkEveryMillis,
        maxWaitMillis = maxWaitMillis, log = { logs += it }, linkedServer = linkedServer, lineupServer = lineupServer,
    )

    val editor = RoomGuideEditor(guide, this.plex, engine, log = { logs += it })

    val dao = guide.dao()

    /** Caches [items] and marks a clean full sync now, as PlexSync would. */
    suspend fun synced(items: List<ItemEntity>) {
        plex.dao().upsertItems(items)
        plex.dao().saveSyncState(SyncStateEntity(lastAttemptAt = now, lastSuccessAt = now, lastFullAt = now, itemsUpdatedSince = now / 1000))
    }

    /** Adds channels by hand, so the starter lineup is skipped. */
    suspend fun channels(vararg channels: ChannelEntity) = dao.insertChannels(channels.toList())

    suspend fun programs(channelId: String) = dao.slots(channelId).filter { it.kind == SlotEntity.PROGRAM }

    /** Every program [channelId] has had, by start, though old slots are pruned as the clock moves on. */
    val seen = sortedMapOf<Long, SlotEntity>()

    /** Extends, then remembers [channelId]'s programs. */
    suspend fun extendAndRecord(channelId: String) {
        engine.extend()
        programs(channelId).forEach { seen[it.startMillis] = it }
    }

    override fun close() {
        guide.close()
        if (ownsPlex) plex.close()
    }

    companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE

        /** Monday 26 October 2026, 8:41 PM UTC, as the sample guide. */
        const val WEEKNIGHT = 1_793_047_260_000L

        fun channel(
            id: String,
            rule: ChannelRule,
            number: Int = 1,
            alignment: Int = 30,
            seed: Long = GuideSeeds.seedFor(id),
        ) = ChannelEntity(id, number, id, rule.toJson(), seed, alignment, ChannelCursor().toJson())
    }
}

/** A made-up library: every title here is invented. */
internal object Fixtures {
    const val MOVIES = "1"
    const val TV = "2"

    /**
     * The 7-9 bracket's ceiling (K3), as `AgeBrackets.DEFAULT` in `:app` has it: profiles
     * stay on the TVs. `KidGuideTest` there checks the Family channel against the real one.
     */
    val SEVEN_TO_NINE = setOf("G", "PG", "TV-Y", "TV-Y7", "TV-G", "TV-PG")

    fun movie(
        key: String,
        title: String,
        minutes: Int,
        genres: List<String> = emptyList(),
        year: Int = 2018,
        trailer: String? = null,
        rating: String? = "PG-13",
        views: Int = 0,
        file: Boolean = true,
        section: String = MOVIES,
    ) = ItemEntity(
        ratingKey = key, sectionKey = section, type = "movie", title = title, year = year, durationMs = minutes * 60_000L,
        viewCount = views, lastViewedAt = if (views > 0) 1_780_000_000 else null, addedAt = 1_780_000_000, updatedAt = 1_780_000_000,
        contentRating = rating, audienceRating = null, rating = null,
        thumb = "/library/metadata/$key/thumb", art = "/library/metadata/$key/art", genres = genres.joinToString("|"), syncedAt = 1,
        trailerKey = trailer, summary = "A made-up film about $title.", partKey = if (file) "/library/parts/$key/1/file.mkv" else null,
        container = "mkv",
    )

    fun show(key: String, title: String, genres: List<String> = emptyList(), year: Int = 2015, rating: String? = "TV-14") = ItemEntity(
        ratingKey = key, sectionKey = TV, type = "show", title = title, year = year, durationMs = null, viewCount = 0,
        lastViewedAt = null, addedAt = 1_780_000_000, updatedAt = 1_780_000_000, contentRating = rating, audienceRating = null,
        rating = null, thumb = "/library/metadata/$key/thumb", art = "/library/metadata/$key/art", genres = genres.joinToString("|"),
        syncedAt = 1, summary = "A made-up show.",
    )

    fun episode(
        show: ItemEntity,
        season: Int,
        number: Int,
        minutes: Int,
        views: Int = 0,
        file: Boolean = true,
        rating: String? = null,
    ): ItemEntity {
        val key = "${show.ratingKey}-s${season.toString().padStart(2, '0')}e${number.toString().padStart(2, '0')}"
        return ItemEntity(
            ratingKey = key, sectionKey = TV, type = "episode", title = "Episode $number", year = null, durationMs = minutes * 60_000L,
            viewCount = views, lastViewedAt = if (views > 0) 1_780_000_000L + views else null, addedAt = 1_780_000_000,
            updatedAt = 1_780_000_000, contentRating = rating, audienceRating = null, rating = null,
            thumb = "/library/metadata/$key/thumb", art = null, genres = "", syncedAt = 1,
            showKey = show.ratingKey, showTitle = show.title, season = season, episode = number,
            summary = "Season $season, episode $number of ${show.title}.", partKey = if (file) "/library/parts/$key/1/file.mkv" else null,
        )
    }

    /** A show and its episodes: [seasons] of [perSeason], each [minutes] long, with [views] spread over the first few. */
    fun series(
        key: String,
        title: String,
        genres: List<String>,
        seasons: Int,
        perSeason: Int,
        minutes: Int,
        views: Int = 0,
    ): List<ItemEntity> {
        val show = show(key, title, genres)
        val episodes = (1..seasons).flatMap { s -> (1..perSeason).map { e -> episode(show, s, e, minutes) } }
        // All of the show's views on its first episode: the totals are what count.
        return listOf(show) + episodes.mapIndexed { i, ep -> if (i == 0 && views > 0) ep.copy(viewCount = views, lastViewedAt = 1_780_000_000L) else ep }
    }
}
