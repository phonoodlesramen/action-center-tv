package com.phonoodlesramen.actioncenter.suggest

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.cache.ContinueEntity
import com.phonoodlesramen.actioncenter.plex.cache.EpisodeRow
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.plex.cache.PlayEntity
import com.phonoodlesramen.actioncenter.plex.cache.PlexDatabase
import com.phonoodlesramen.actioncenter.plex.cache.SuggestLibraryDao
import com.phonoodlesramen.actioncenter.plex.cache.toEntity
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

/** Made-up titles. Now is Monday 26 October 2026, 8:41 PM UTC. */
@RunWith(RobolectricTestRunner::class)
class SuggestionEngineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plex = Room.inMemoryDatabaseBuilder(context, PlexDatabase::class.java).allowMainThreadQueries().build()
    private val suggestions = Room.inMemoryDatabaseBuilder(context, SuggestionDatabase::class.java).allowMainThreadQueries().build()
    private val monday = LocalDateTime.of(2026, 10, 26, 20, 41)
    private var now = monday.toInstant(ZoneOffset.UTC).toEpochMilli()
    private val lines = mutableListOf<String>()

    @After
    fun tearDown() {
        plex.close()
        suggestions.close()
    }

    private fun daysAgo(days: Long) = monday.minusDays(days).toEpochSecond(ZoneOffset.UTC)

    private fun engine(library: SuggestLibraryDao = plex.suggestLibrary()) = SuggestionEngine(
        library, suggestions.dao(), clock = { now }, zone = { ZoneOffset.UTC }, compute = Dispatchers.Unconfined, log = lines::add,
    )

    private fun film(key: String, title: String, viewCount: Int = 0, genres: List<String> = listOf("Science Fiction")) =
        PlexItem(
            key, "movie", title, year = 2016, durationMs = 116 * 60_000L, viewCount = viewCount,
            lastViewedAt = if (viewCount > 0) daysAgo(20) else null, addedAt = daysAgo(90), audienceRating = 8.0, genres = genres,
            media = com.phonoodlesramen.actioncenter.plex.PlexMedia("/library/parts/$key/1/file.mkv"),
        )

    private fun episode(key: String, number: Int, viewCount: Int = 0) = PlexItem(
        key, "episode", "Episode $number", showTitle = "Harbour Lights", season = 1, episode = number, durationMs = 45 * 60_000L,
        viewCount = viewCount, lastViewedAt = if (viewCount > 0) daysAgo(2) else null, addedAt = daysAgo(400), showKey = "s1",
        media = com.phonoodlesramen.actioncenter.plex.PlexMedia("/library/parts/$key/1/file.mkv"),
    )

    private suspend fun library(firstWatched: Boolean = false) {
        plex.dao().upsertItems(
            listOf(
                film("m1", "Arrival", viewCount = 1, genres = listOf("Drama", "Science Fiction")),
                film("m2", "Contact"),
                film("m3", "Sicario", genres = listOf("Crime")),
                PlexItem("s1", "show", "Harbour Lights", year = 2019, genres = listOf("Drama")),
                episode("e1", 1, viewCount = if (firstWatched) 1 else 0),
                episode("e2", 2),
            ).map { it.toEntity("1", 0) },
        )
        plex.dao().insertPlays(listOf(PlayEntity("h1", "m1", "movie", "Arrival", daysAgo(20), null)))
        plex.dao().replaceContinueWatching(
            listOf(ContinueEntity(0, "e2", "episode", "Episode 2", "Harbour Lights", 1, 2, null, 45 * 60_000L, 0, null, null, null, null, daysAgo(2), "s1")),
        )
    }

    private suspend fun picks() = suggestions.dao().picks(Profiles.OWNER).first()

    @Test
    fun `a run writes every card's picks at once, with their reasons, and logs how long it took`() = runTest {
        library()

        engine().run()

        val bySurface = picks().groupBy { it.surface }
        assertTrue(bySurface.keys.toString(), bySurface.keys.containsAll(listOf(Surfaces.HERO, Surfaces.UP_NEXT)))
        assertEquals(listOf("e2"), bySurface.getValue(Surfaces.UP_NEXT).map { it.ratingKey })
        assertTrue("every pick has a reason [REQ]", picks().all { it.reason.isNotBlank() })
        assertEquals("2026-10-26", suggestions.dao().run(Profiles.OWNER)?.day)
        assertNull(suggestions.dao().run(Profiles.OWNER)?.error)
        assertTrue(lines.toString(), lines.single().startsWith("suggest: ran in "))
    }

    @Test
    fun `the finish pick's row records its episodes left, for the mode to decide whether it leads (M3)`() = runTest {
        library(firstWatched = true)

        engine().run()

        val (finish, others) = picks().partition { it.surface == Surfaces.HERO && it.rank == 0 }
        assertEquals(listOf("e2" to 1), finish.map { it.ratingKey to it.episodesLeft })
        assertTrue("no other row has any", others.all { it.episodesLeft == null })
    }

    @Test
    fun `a show's pick plays its next episode, or its first when you haven't started it`() = runTest {
        library()
        suspend fun read() = Library.read(plex.suggestLibrary())
        assertEquals("e1", SuggestionEngine.playable("s1", read()))
        assertEquals("a movie plays itself", "m2", SuggestionEngine.playable("m2", read()))

        plex.dao().upsertItems(listOf(episode("e1", 1, viewCount = 1).toEntity("1", 0)))
        assertEquals("e2", SuggestionEngine.playable("s1", read()))
    }

    @Test
    fun `it runs again on a new day, and not before`() = runTest {
        library()
        val engine = engine()

        assertTrue(engine.runIfNewDay())
        assertFalse(engine.runIfNewDay())
        now += 24 * 60 * 60_000L
        assertTrue(engine.runIfNewDay())
    }

    @Test
    fun `the home's start and resume asking together run it once`() = runTest {
        // Seen on the Shield: both saw "not run today" before either finished.
        library()
        val engine = engine()

        val ran = listOf(async { engine.runIfNewDay() }, async { engine.runIfNewDay() }).awaitAll()

        assertEquals(listOf(true, false), ran)
        assertEquals(1, lines.count { it.startsWith("suggest: ran in ") })
    }

    @Test
    fun `an empty cache writes nothing, and a failed run keeps the last picks`() = runTest {
        engine().run()
        assertTrue(picks().isEmpty())
        assertNull(suggestions.dao().run(Profiles.OWNER))

        library()
        engine().run()
        val before = picks()
        val broken = object : SuggestLibraryDao by plex.suggestLibrary() {
            override suspend fun titles(): List<ItemEntity> = error("cache broke")
            override suspend fun episodes(): List<EpisodeRow> = emptyList()
        }
        engine(broken).run()

        assertEquals(before, picks())
        assertEquals("IllegalStateException", suggestions.dao().run(Profiles.OWNER)?.error)
        assertTrue(lines.toString(), lines.last().startsWith("suggest: failed: IllegalStateException"))
    }

    @Test
    fun `feedback is saved, and the engine runs with it`() = runTest {
        library()
        val engine = engine()
        val feedback = EngineFeedback(FeedbackStore(suggestions.dao()) { now }, engine)
        engine.run()
        assertTrue(picks().any { it.ratingKey == "e2" && it.surface == Surfaces.UP_NEXT })

        val undo = feedback.record("s1", FeedbackKind.UP_NEXT_REMOVED)

        assertTrue("Up Next leaves the show out", picks().none { it.surface == Surfaces.UP_NEXT })
        feedback.undo(undo)
        assertTrue(picks().any { it.ratingKey == "e2" && it.surface == Surfaces.UP_NEXT })
    }
}
