package com.phonoodlesramen.actioncenter.plex.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.profiles.AgeBrackets
import com.phonoodlesramen.actioncenter.profiles.KidContinueEntity
import com.phonoodlesramen.actioncenter.profiles.KidEntity
import com.phonoodlesramen.actioncenter.profiles.KidPlayEntity
import com.phonoodlesramen.actioncenter.profiles.KidPlays
import com.phonoodlesramen.actioncenter.profiles.ProfileDatabase
import com.phonoodlesramen.actioncenter.profiles.RoomProfileStore
import com.phonoodlesramen.actioncenter.profiles.cards
import com.phonoodlesramen.actioncenter.suggest.PickEntity
import com.phonoodlesramen.actioncenter.suggest.Surfaces
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A kid's cards (Phase 5, K2-K4): their Up Next and (Phase 6) what they've seen, never yours. Made-up titles and names. */
@RunWith(RobolectricTestRunner::class)
class KidCardsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plex = Room.inMemoryDatabaseBuilder(context, PlexDatabase::class.java).allowMainThreadQueries().build()
    private val profiles = Room.inMemoryDatabaseBuilder(context, ProfileDatabase::class.java).allowMainThreadQueries().build()
    private val store = RoomProfileStore(profiles.dao())
    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 10, 26)
    private val days = flowOf(day)

    /** Monday, 4:10 PM: For the family still fits. */
    private val now = LocalDateTime.of(2026, 10, 26, 16, 10)

    @After
    fun tearDown() {
        plex.close()
        profiles.close()
    }

    private fun kid(id: String, name: String, plexUserId: Long?) =
        KidEntity(id, name, color = 0xFFFF8A65, birthday = LocalDate.of(2017, 3, 2).toEpochDay(), plexUserId = plexUserId)

    private fun theirs(profileId: String, rank: Int, key: String, rating: String?, show: String?) = KidContinueEntity(
        profileId, rank, key, if (show == null) "movie" else "episode", "Title $key", show?.let { "Show $it" }, 1, rank + 2, null,
        22 * 60_000L, 300_000, rating, "/thumb/$key", null, null, null, show,
    )

    private fun pick(profile: String, surface: String, rank: Int, key: String) = PickEntity(profile, surface, rank, key, "A reason", "", 0)

    private suspend fun library() {
        plex.dao().upsertItems(
            listOf(
                PlexItem("k1", "movie", "Cloud Kingdom", year = 2016, durationMs = 80 * 60_000L, contentRating = "G"),
                PlexItem("a1", "movie", "Night Shift", year = 2016, durationMs = 110 * 60_000L, contentRating = "R"),
                PlexItem("s1", "show", "Puddle Jumpers", contentRating = "TV-Y"),
                PlexItem("e2", "episode", "Rainy Day", season = 1, episode = 2, showKey = "s1"),
                PlexItem("s2", "show", "Dark Harbour", contentRating = "TV-MA"),
                PlexItem("x2", "episode", "Fog", season = 1, episode = 2, showKey = "s2"),
            ).map { it.toEntity("1", 0) },
        )
        // Your Up Next: Dark Harbour.
        plex.dao().replaceContinueWatching(listOf(ContinueEntity(0, "x2", "episode", "Fog", "Dark Harbour", 1, 2, null, 50 * 60_000L, 0, null, null, null, null, null, "s2")))
    }

    private suspend fun snapshot(profileId: String, picks: List<PickEntity> = emptyList()) =
        plex.snapshots(days, flowOf(picks), zone, kid = profiles.kidPlex().cards(profileId, store.kids, store.brackets, days)).first()

    @Test
    fun `a kid's Up Next is theirs, within their ceiling`() = runTest {
        library()
        store.saveKid(kid("maya", "Maya", plexUserId = 31_415_926))
        profiles.kidPlex().save(
            "maya",
            listOf(
                theirs("maya", 0, "e2", rating = null, show = "s1"),
                theirs("maya", 1, "x2", rating = null, show = "s2"),
                theirs("maya", 2, "a1", rating = "R", show = null),
                theirs("maya", 3, "u1", rating = null, show = null),
            ),
            null,
        )

        val snapshot = snapshot("maya")

        assertEquals("an unrated episode takes its show's; above the ceiling and unrated go", listOf("e2"), snapshot.continueWatching.map { it.ratingKey })
        assertEquals(listOf("e2"), PlexCards.build(snapshot, now, zone)!!.upNext.map { it.id })
        assertEquals("your plays aren't their Throwback", emptyList<ItemEntity>(), snapshot.throwbacks)
        assertTrue(snapshot.newest.all { AgeBrackets.allows(AgeBrackets.DEFAULT[0].ratings, it.contentRating) })
    }

    @Test
    fun `with their picks, Up Next is ranked from theirs, and a pick their ceiling no longer allows drops out`() = runTest {
        library()
        store.saveKid(kid("maya", "Maya", plexUserId = 31_415_926))
        profiles.kidPlex().save("maya", listOf(theirs("maya", 0, "e2", rating = null, show = "s1")), null)
        val picks = listOf(
            pick("maya", Surfaces.HERO, 0, "k1"),
            pick("maya", Surfaces.HERO, 1, "a1"),
            pick("maya", Surfaces.UP_NEXT, 0, "e2"),
            pick("maya", Surfaces.UP_NEXT, 1, "x2"),
        )

        val content = PlexCards.build(snapshot("maya", picks), now, zone)!!

        assertEquals(listOf("k1"), content.picks.map { it.id })
        assertEquals(listOf("e2"), content.upNext.map { it.id })
    }

    @Test
    fun `an unlinked kid's Up Next is empty, never yours, and so is a removed kid's`() = runTest {
        library()
        store.saveKid(kid("leo", "Leo", plexUserId = null))
        // Rows from before they were unlinked don't show.
        profiles.kidPlex().save("leo", listOf(theirs("leo", 0, "e2", rating = null, show = "s1")), null)

        val leo = snapshot("leo")
        assertEquals(emptyList<ContinueEntity>(), leo.continueWatching)
        assertEquals(emptyList<String>(), PlexCards.build(leo, now, zone)!!.upNext.map { it.id })

        val gone = snapshot("gone", listOf(pick("gone", Surfaces.HERO, 0, "k1")))
        assertEquals(emptyList<ContinueEntity>(), gone.continueWatching)
        assertEquals("nothing is allowed", emptyList<PickEntity>(), gone.picks)
    }

    @Test
    fun `signed out, the cache is empty and so is a kid's Up Next`() = runTest {
        store.saveKid(kid("maya", "Maya", plexUserId = 31_415_926))
        profiles.kidPlex().save("maya", listOf(theirs("maya", 0, "e2", rating = "TV-Y", show = "s1")), null)

        val snapshot = snapshot("maya")

        assertEquals(emptyList<ContinueEntity>(), snapshot.continueWatching)
        assertEquals(null, PlexCards.build(snapshot, now, zone))
    }

    private fun seconds(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day).atStartOfDay(zone).toEpochSecond()

    /**
     * Beside [library]: you've seen Cloud Kingdom three times (and starred it), Rainy Day once
     * and Lighthouse Keeper, a 1980 classic, in 2015. Maya has seen Paper Boats twice and
     * Rainy Day once, and is partway into Cloud Kingdom.
     */
    private suspend fun seenByEach() {
        plex.dao().upsertItems(
            listOf(
                PlexItem(
                    "k1", "movie", "Cloud Kingdom", year = 2016, durationMs = 80 * 60_000L, contentRating = "G",
                    viewCount = 3, lastViewedAt = seconds(2026, 9, 1), userRating = 10.0, lastRatedAt = seconds(2026, 9, 1),
                ),
                PlexItem("k3", "movie", "Paper Boats", year = 2019, durationMs = 70 * 60_000L, contentRating = "G"),
                PlexItem("e2", "episode", "Rainy Day", season = 1, episode = 2, showKey = "s1", viewCount = 1, lastViewedAt = seconds(2026, 10, 1)),
                PlexItem(
                    "c1", "movie", "Lighthouse Keeper", year = 1980, durationMs = 95 * 60_000L, contentRating = "G", audienceRating = 8.0,
                    viewCount = 1, lastViewedAt = seconds(2015, 6, 1),
                ),
            ).map { it.toEntity("1", 0) },
        )
        store.saveKid(kid("maya", "Maya", plexUserId = 31_415_926))
        profiles.kidPlex().save(
            "maya",
            listOf(theirs("maya", 0, "k1", rating = "G", show = null).copy(lastViewedAt = seconds(2026, 10, 25))),
            KidPlays(
                31_415_926,
                listOf(
                    KidPlayEntity("maya", "k3", "movie", seconds(2026, 8, 2), null, 31_415_926),
                    KidPlayEntity("maya", "k3", "movie", seconds(2026, 10, 20), null, 31_415_926),
                    KidPlayEntity("maya", "e2", "episode", seconds(2026, 10, 18), "s1", 31_415_926),
                ),
                before = 0,
            ),
        )
    }

    @Test
    fun `what a kid has seen is theirs, so Watch again is for their plays, never yours`() = runTest {
        library()
        seenByEach()
        val picks = listOf(
            pick("maya", Surfaces.lens(Lens.FAMILY), 0, "k1"),
            pick("maya", Surfaces.lens(Lens.FAMILY), 1, "k3"),
            pick("maya", Surfaces.lens(Lens.COMFORT), 0, "e2"),
        )

        val snapshot = snapshot("maya", picks)
        val content = PlexCards.build(snapshot, now, zone)!!

        assertEquals(
            "you've seen Cloud Kingdom, she hasn't; she's seen Paper Boats",
            listOf("k1" to false, "k3" to true),
            content.lenses.getValue(Lens.FAMILY).map { it.id to it.seen },
        )
        assertEquals(listOf("e2" to true), content.lenses.getValue(Lens.COMFORT).map { it.id to it.seen })
        val cloud = snapshot.titles.getValue("k1")
        assertEquals(0, cloud.viewCount)
        assertNull("your stars aren't hers", cloud.userRating)
        assertEquals("her position, and when she was last in it", 300_000L to seconds(2026, 10, 25), cloud.viewOffsetMs to cloud.lastViewedAt)
        assertEquals(2 to seconds(2026, 10, 20), snapshot.titles.getValue("k3").let { it.viewCount to it.lastViewedAt })
        val show = snapshot.titles.getValue("s1")
        assertEquals("the show counts her episodes", Triple(1, 1, seconds(2026, 10, 18)), Triple(show.viewCount, show.watchedEpisodeCount, show.lastViewedAt))
    }

    @Test
    fun `before their first run, the interim cards say what they've seen, not what you have`() = runTest {
        library()
        seenByEach()

        val maya = PlexCards.build(snapshot("maya"), now, zone)!!
        val yours = PlexCards.build(plex.snapshots(days, flowOf(emptyList()), zone).first(), now, zone)!!

        val classic = maya.lenses.getValue(Lens.CLASSIC).single { it.id == "c1" }
        assertFalse(classic.seen)
        assertEquals("46 years old and you've never seen it", classic.line)
        assertEquals("46 years old · last watched in 2015", yours.lenses.getValue(Lens.CLASSIC).single { it.id == "c1" }.line)
        assertTrue("she's seen Paper Boats, so it isn't new to her", maya.picks.none { it.id == "k3" })
        assertTrue(yours.picks.any { it.id == "k3" })
    }

    @Test
    fun `an unlinked kid has seen nothing, whatever their old plays or yours`() = runTest {
        library()
        seenByEach()
        store.saveKid(kid("maya", "Maya", plexUserId = null))

        val content = PlexCards.build(snapshot("maya", listOf(pick("maya", Surfaces.lens(Lens.FAMILY), 0, "k1"))), now, zone)!!

        assertEquals(listOf("k1" to false), content.lenses.getValue(Lens.FAMILY).map { it.id to it.seen })
    }

    @Test
    fun `your snapshot is as it was`() = runTest {
        library()

        val yours = plex.snapshots(days, flowOf(emptyList()), zone).first()

        assertEquals(listOf("x2"), yours.continueWatching.map { it.ratingKey })
    }
}
