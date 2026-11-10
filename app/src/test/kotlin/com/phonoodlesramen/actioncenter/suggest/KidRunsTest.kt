package com.phonoodlesramen.actioncenter.suggest

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.PlexMedia
import com.phonoodlesramen.actioncenter.plex.cache.ContinueEntity
import com.phonoodlesramen.actioncenter.plex.cache.PlayEntity
import com.phonoodlesramen.actioncenter.plex.cache.PlexDatabase
import com.phonoodlesramen.actioncenter.plex.cache.toEntity
import com.phonoodlesramen.actioncenter.profiles.AgeBrackets
import com.phonoodlesramen.actioncenter.profiles.KidPlayEntity
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A kid's run (Phase 5, K2-K4). Made-up titles and names. Now is Monday 26 October 2026, 8:41 PM UTC. */
@RunWith(RobolectricTestRunner::class)
class KidRunsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plex = Room.inMemoryDatabaseBuilder(context, PlexDatabase::class.java).allowMainThreadQueries().build()
    private val suggestions = Room.inMemoryDatabaseBuilder(context, SuggestionDatabase::class.java).allowMainThreadQueries().build()
    private val monday = LocalDateTime.of(2026, 10, 26, 20, 41)
    private val now = monday.toInstant(ZoneOffset.UTC).toEpochMilli()
    private val lines = mutableListOf<String>()
    private val sevenToNine = AgeBrackets.DEFAULT[0].ratings
    private var kids = emptyList<KidView>()

    @After
    fun tearDown() {
        plex.close()
        suggestions.close()
    }

    private fun daysAgo(days: Long) = monday.minusDays(days).toEpochSecond(ZoneOffset.UTC)

    private fun engine() = SuggestionEngine(
        plex.suggestLibrary(), suggestions.dao(), clock = { now }, zone = { ZoneOffset.UTC }, compute = Dispatchers.Unconfined,
        log = lines::add, kids = KidSource { kids },
    )

    private fun film(
        key: String,
        title: String,
        rating: String?,
        genres: List<String> = listOf("Family"),
        viewCount: Int = 0,
        year: Int = 2016,
        score: Double = 7.5,
        collections: List<String> = emptyList(),
    ) = PlexItem(
        key, "movie", title, year = year, durationMs = 90 * 60_000L, viewCount = viewCount, lastViewedAt = if (viewCount > 0) daysAgo(40) else null,
        addedAt = daysAgo(90), contentRating = rating, audienceRating = score, genres = genres, media = PlexMedia("/library/parts/$key/1/file.mkv"),
        collections = collections,
    )

    private fun show(key: String, title: String, rating: String?) = PlexItem(key, "show", title, year = 2019, contentRating = rating, genres = listOf("Animation"))

    private fun episode(key: String, show: String, number: Int, rating: String? = null) = PlexItem(
        key, "episode", "Episode $number", season = 1, episode = number, durationMs = 22 * 60_000L, addedAt = daysAgo(400), showKey = show,
        contentRating = rating, media = PlexMedia("/library/parts/$key/1/file.mkv"),
    )

    private fun cw(position: Int, key: String, type: String, rating: String?, show: String? = null) = ContinueEntity(
        position, key, type, "Title $key", null, 1, 2, null, 22 * 60_000L, 300_000, rating, null, null, null, daysAgo(3), show,
    )

    private fun play(key: String, at: Long, show: String? = null) = KidPlayEntity("maya", key, if (show == null) "movie" else "episode", at, show, 31_415_926)

    /** Maya, 9: two plays of Puddle Jumpers: The Movie, Cloud Kingdom a year ago today, and one episode of the show three days ago. */
    private val maya = KidView(
        "maya", sevenToNine,
        plays = listOf(
            play("k2", daysAgo(60)), play("k2", daysAgo(30)),
            play("k1", monday.minusYears(1).toEpochSecond(ZoneOffset.UTC)),
            play("e1", daysAgo(3), show = "s1"),
        ),
        continueWatching = listOf(cw(0, "e2", "episode", null, show = "s1"), cw(1, "a1", "movie", "R")),
    )

    /** What a 7-9 may see: everything else is above the ceiling, unrated, or an episode rated above it. */
    private val allowed = setOf("k1", "k2", "k3", "s1", "e1", "e2", "e3", "s3")

    private suspend fun library() {
        plex.dao().upsertItems(
            listOf(
                film("k1", "Cloud Kingdom", "G", genres = listOf("Family", "Animation")),
                film("k2", "Puddle Jumpers: The Movie", "PG"),
                film("k3", "Paper Boats", "G"),
                film("a1", "Night Shift", "R", genres = listOf("Crime"), viewCount = 2),
                film("a2", "Space Cadets", "PG-13", genres = listOf("Action", "Family")),
                film("a3", "Mystery Tape", null),
                show("s1", "Puddle Jumpers", "TV-Y"),
                episode("e1", "s1", 1), episode("e2", "s1", 2), episode("e3", "s1", 3, rating = "TV-Y"),
                show("s2", "Dark Harbour", "TV-MA"),
                episode("x1", "s2", 1), episode("x2", "s2", 2),
                show("s3", "Ocean Friends", "TV-G"),
                episode("o1", "s3", 1, rating = "TV-14"),
            ).map { it.toEntity("1", 0) },
        )
        // Yours: Night Shift twice, and Dark Harbour.
        plex.dao().insertPlays(
            listOf(
                PlayEntity("h1", "a1", "movie", "Night Shift", daysAgo(400), null),
                PlayEntity("h2", "a1", "movie", "Night Shift", daysAgo(40), null),
                PlayEntity("h3", "x1", "episode", "Episode 1", daysAgo(2), null),
            ),
        )
        plex.dao().replaceContinueWatching(listOf(cw(0, "x2", "episode", "TV-MA", show = "s2")))
    }

    private suspend fun picks(profile: String) = suggestions.dao().picks(profile).first()

    /**
     * Beside [library]: a title for each other lens within a 7-9's ratings (an old film, an
     * action film, the next Puddle Jumpers film, a Halloween one), and an R-rated classic and
     * Halloween film that would fill yours.
     */
    private suspend fun everyLens() {
        plex.dao().upsertItems(
            listOf(
                film("k2", "Puddle Jumpers: The Movie", "PG", collections = listOf("Puddle Jumpers")),
                film("k4", "Puddle Jumpers 2", "PG", year = 2018, collections = listOf("Puddle Jumpers")),
                film("k5", "Old Kite", "G", year = 1990, score = 8.0),
                film("k6", "Sky Racers", "PG", genres = listOf("Action", "Adventure")),
                film("k7", "Pumpkin Patrol", "G"),
                film("a4", "Gangland", "R", genres = listOf("Crime"), year = 1990, score = 9.0),
                film("a5", "Haunted Hollow", "R", genres = listOf("Horror")),
            ).map { it.toEntity("1", 0) },
        )
    }

    @Test
    fun `a kid's run never picks a title above their ceiling or unrated, and fills every lens`() = runTest {
        library()
        everyLens()
        kids = listOf(maya)

        engine().runKids()

        val theirs = picks("maya")
        assertTrue(theirs.isNotEmpty())
        val safe = allowed + setOf("k4", "k5", "k6", "k7")
        assertTrue(theirs.toString(), theirs.all { it.ratingKey in safe })
        val lenses = theirs.groupBy { it.surface }
        assertEquals("every lens, as yours", Lens.entries.map(Surfaces::lens).toSet(), lenses.keys.filter { it.startsWith("lens:") }.toSet())
        assertEquals(listOf("k5"), lenses.getValue(Surfaces.lens(Lens.CLASSIC)).map { it.ratingKey })
        assertEquals(listOf("k6"), lenses.getValue(Surfaces.lens(Lens.ACTION)).map { it.ratingKey })
        assertEquals(
            listOf("k4" to "You watched Puddle Jumpers: The Movie · Puddle Jumpers 2 is next"),
            lenses.getValue(Surfaces.lens(Lens.FINISH_SERIES)).map { it.ratingKey to it.reason },
        )
        assertEquals(listOf("k7"), lenses.getValue(Surfaces.lens(Lens.SEASONAL)).map { it.ratingKey })
        assertEquals("2026-10-26", suggestions.dao().run("maya")?.day)
        assertTrue(lines.toString(), lines.single().startsWith("suggest: a kid's run in "))
        assertFalse("the log never says who", lines.single().contains("maya"))
    }

    @Test
    fun `their plays drive their picks, with their rewatch, their year ago, their show to finish and their Up Next`() = runTest {
        library()
        kids = listOf(maya)

        engine().runKids()

        val theirs = picks("maya").groupBy { it.surface }
        val comfort = theirs.getValue(Surfaces.lens(Lens.COMFORT))
        assertEquals("their two plays, not your Night Shift", listOf("k2"), comfort.map { it.ratingKey })
        assertTrue(comfort.single().reason, comfort.single().reason.startsWith("Watched two times"))
        assertEquals(listOf("k1" to "One year ago today you watched"), theirs.getValue(Surfaces.lens(Lens.THROWBACK)).map { it.ratingKey to it.reason })
        val finish = theirs.getValue(Surfaces.HERO).first()
        assertEquals("e2", finish.ratingKey)
        assertTrue(finish.reason, finish.reason.contains("2 episodes from the end of Puddle Jumpers"))
        assertEquals("their Continue Watching, the R film left out", listOf("e2"), theirs.getValue(Surfaces.UP_NEXT).map { it.ratingKey })
    }

    @Test
    fun `your picks are untouched by a kid's run`() = runTest {
        library()
        kids = listOf(maya)
        val engine = engine()
        engine.run()
        val yours = picks(Profiles.OWNER)
        assertTrue(yours.any { it.ratingKey == "x2" })

        engine.runKids()

        assertEquals(yours, picks(Profiles.OWNER))
    }

    @Test
    fun `an unlinked kid gets picks within their ceiling and no Up Next, and a removed kid's picks go`() = runTest {
        library()
        kids = listOf(maya, KidView("leo", sevenToNine))
        val engine = engine()
        engine.runKids()

        val leo = picks("leo")
        assertTrue(leo.isNotEmpty())
        assertTrue(leo.toString(), leo.all { it.ratingKey in allowed })
        assertTrue(leo.none { it.surface == Surfaces.UP_NEXT })

        kids = listOf(maya)
        engine.runKids()
        assertEquals(emptyList<PickEntity>(), picks("leo"))
        assertNull(suggestions.dao().run("leo"))
        assertTrue(picks("maya").isNotEmpty())
    }

    @Test
    fun `a kid's feedback is theirs, so their picks change and yours don't`() = runTest {
        library()
        kids = listOf(maya)
        val engine = engine()
        engine.run()
        engine.runKids()
        val yours = picks(Profiles.OWNER)
        assertTrue(picks("maya").any { it.surface == Surfaces.HERO && it.ratingKey == "k3" })

        EngineFeedback(FeedbackStore(suggestions.dao(), clock = { now }), engine, profile = { "maya" }).record("k3", FeedbackKind.NOT_TONIGHT)

        assertEquals(listOf("k3"), suggestions.dao().feedback("maya").map { it.ratingKey })
        assertEquals(emptyList<FeedbackEntity>(), suggestions.dao().feedback(Profiles.OWNER))
        assertTrue(picks("maya").none { it.surface == Surfaces.HERO && it.ratingKey == "k3" })
        assertEquals(yours, picks(Profiles.OWNER))
    }

    @Test
    fun `your stars, positions and finishes don't count for a kid, theirs do`() {
        val yours = film("k1", "Cloud Kingdom", "G", viewCount = 3).copy(userRating = 10.0, lastRatedAt = daysAgo(5), viewOffsetMs = 600_000)
            .toEntity("1", 0)
        val shared = Library(listOf(yours), emptyList(), listOf(PlayEntity("h1", "k1", "movie", "Cloud Kingdom", daysAgo(5), null)), emptyList())

        val theirs = KidLibrary.of(shared, KidView("maya", sevenToNine, continueWatching = listOf(cw(0, "k1", "movie", "G")))).titles.single()

        assertEquals(0, theirs.viewCount)
        assertNull(theirs.userRating)
        assertEquals(300_000L, theirs.viewOffsetMs)
        assertEquals(daysAgo(3), theirs.lastViewedAt)
        assertTrue(KidLibrary.of(shared, KidView("maya", sevenToNine)).plays.isEmpty())
    }
}
