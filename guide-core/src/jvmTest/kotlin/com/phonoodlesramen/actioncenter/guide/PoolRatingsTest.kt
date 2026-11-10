package com.phonoodlesramen.actioncenter.guide

import androidx.room.RoomRawQuery
import com.phonoodlesramen.actioncenter.guide.Fixtures.episode
import com.phonoodlesramen.actioncenter.guide.Fixtures.movie
import com.phonoodlesramen.actioncenter.guide.Fixtures.series
import com.phonoodlesramen.actioncenter.guide.Fixtures.show
import com.phonoodlesramen.actioncenter.guide.GuideHarness.Companion.MINUTE
import com.phonoodlesramen.actioncenter.guide.GuideHarness.Companion.channel
import com.phonoodlesramen.actioncenter.plex.cache.GuideLibraryDao
import com.phonoodlesramen.actioncenter.plex.cache.GuideRatingRow
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2: a channel's ratings are those of every title its rule can play (an episode without
 * one has its show's), with whether any is unrated; remembered per rule until the library
 * syncs again; and kids see a channel by them, plus what's still to play in its schedule.
 */
class PoolRatingsTest {
    private val harness = GuideHarness()

    @After
    fun tearDown() = harness.close()

    /** A TV-Y7 cartoon whose 30 episodes have no rating of their own (11 h). */
    private val cartoon: List<ItemEntity> = series("tide", "Tide Pool Friends", listOf("Animation"), seasons = 2, perSeason = 15, minutes = 22)
        .map { if (it.type == "show") it.copy(contentRating = "TV-Y7") else it }

    /** A TV-14 drama, one of whose episodes is rated TV-MA on its own. */
    private val ridge: List<ItemEntity> = run {
        val show = show("ridge", "Ridge Line", listOf("Drama"), rating = "TV-14")
        listOf(show, episode(show, 1, 1, 52), episode(show, 1, 2, 52, rating = "TV-MA"), episode(show, 1, 3, 52))
    }

    /** A show with no rating, whose episodes have none either. */
    private val mystery: List<ItemEntity> = run {
        val show = show("fog", "Fog Bank", listOf("Mystery"), rating = null)
        listOf(show, episode(show, 1, 1, 44), episode(show, 1, 2, 44))
    }

    /** G and PG family films, an unrated one, and one rated blank. */
    private val films = listOf(
        movie("kites", "Paper Kites", 88, listOf("Family"), rating = "G"),
        movie("lantern", "The Lantern Fox", 94, listOf("Family"), rating = "PG"),
        movie("home-movie", "Home Movie", 61, listOf("Family"), rating = null),
        movie("blank", "Blank Card", 75, listOf("Drama"), rating = ""),
    )

    private val year = 2026

    private suspend fun ratings(rule: ChannelRule) = harness.engine.library.poolRatings(rule, year)

    @Test
    fun `an episode without a rating has its show's, and one with its own keeps it`() = runTest {
        harness.synced(cartoon + ridge)
        val tide = ratings(ChannelRule(types = listOf(ChannelRule.EPISODE), shows = listOf("tide"), order = ChannelRule.Order.IN_ORDER))
        assertEquals(PoolRatings(setOf("TV-Y7"), unrated = false), tide)
        val drama = ratings(ChannelRule(types = listOf(ChannelRule.EPISODE), shows = listOf("ridge"), order = ChannelRule.Order.IN_ORDER))
        assertEquals(PoolRatings(setOf("TV-14", "TV-MA"), unrated = false), drama)
    }

    @Test
    fun `a title with no rating, or a blank one, marks the pool unrated`() = runTest {
        harness.synced(films + mystery)
        assertEquals(PoolRatings(setOf("G", "PG"), unrated = true), ratings(ChannelRule(genres = listOf("Family"))))
        assertEquals(PoolRatings(emptySet(), unrated = true), ratings(ChannelRule(genres = listOf("Drama"))))
        // An episode of an unrated show has nothing to take.
        val fog = ratings(ChannelRule(types = listOf(ChannelRule.EPISODE), shows = listOf("fog"), order = ChannelRule.Order.IN_ORDER))
        assertEquals(PoolRatings(emptySet(), unrated = true), fog)
        assertFalse(fog.nothing)
    }

    @Test
    fun `a rule that names ratings plays only those, and never an unrated title`() = runTest {
        harness.synced(films + cartoon + ridge)
        val family = ratings(StarterLineup.FAMILY_RULE)
        assertEquals(PoolRatings(setOf("G", "PG", "TV-Y7"), unrated = false), family)
        assertTrue(KidGuide.safe(family.ratings, family.unrated, Fixtures.SEVEN_TO_NINE))
    }

    @Test
    fun `a rule that matches nothing plays nothing`() = runTest {
        harness.synced(films)
        val none = ratings(ChannelRule(genres = listOf("Western")))
        assertEquals(PoolRatings.NOTHING, none)
        assertTrue(none.nothing)
    }

    /** The cache's guide queries, counting the ratings ones. */
    private class Counting(private val real: GuideLibraryDao) : GuideLibraryDao by real {
        var asked = 0

        override suspend fun poolRatings(query: RoomRawQuery): List<GuideRatingRow> = real.poolRatings(query).also { asked++ }
    }

    @Test
    fun `a rule's ratings are read once per sync, and again after the next one`() = runTest {
        val dao = Counting(harness.plex.guideLibrary())
        val library = GuideLibrary(dao)
        val family = ChannelRule(genres = listOf("Family")).toJson()
        val tide = ChannelRule(types = listOf(ChannelRule.EPISODE), shows = listOf("tide"), order = ChannelRule.Order.IN_ORDER).toJson()
        harness.synced(films.take(2) + cartoon)
        val first = library.syncState()

        repeat(3) { assertEquals(PoolRatings(setOf("G", "PG"), unrated = false), library.channelRatings(family, year, first)) }
        assertEquals(PoolRatings(setOf("TV-Y7"), unrated = false), library.channelRatings(tide, year, first))
        assertEquals(2, dao.asked)

        // A PG-13 family film arrives mid-sync: until the sync is done, the answer stands.
        val night = movie("night", "Night Kites", 102, listOf("Family"), rating = "PG-13")
        harness.plex.dao().upsertItems(listOf(night))
        assertEquals(PoolRatings(setOf("G", "PG"), unrated = false), library.channelRatings(family, year, first))
        assertEquals(2, dao.asked)

        harness.now += 30 * MINUTE
        harness.synced(listOf(night))
        val second = library.syncState()
        assertEquals(PoolRatings(setOf("G", "PG", "PG-13"), unrated = false), library.channelRatings(family, year, second))
        assertEquals(PoolRatings(setOf("TV-Y7"), unrated = false), library.channelRatings(tide, year, second))
        assertEquals(4, dao.asked)

        // Signing out empties the sync state: read again.
        library.channelRatings(family, year, sync = null)
        assertEquals(5, dao.asked)
        // A rule that won't read plays nothing, and asks nothing.
        assertEquals(PoolRatings.NOTHING, library.channelRatings("{not a rule", year, second))
        assertEquals(5, dao.asked)
    }

    private suspend fun card(): GuideCardState.Ready =
        withTimeout(TIMEOUT) { harness.engine.card.filterIsInstance<GuideCardState.Ready>().first() }

    @Test
    fun `kids see a channel by what it plays, and see it change once the library syncs`() = runBlocking {
        val nine = Fixtures.SEVEN_TO_NINE
        harness.channels(
            // A marathon of a TV-Y7 cartoon: its rule names no ratings.
            channel("tide", ChannelRule(types = listOf(ChannelRule.EPISODE), shows = listOf("tide"), order = ChannelRule.Order.IN_ORDER), 1),
            channel("family-films", ChannelRule(types = listOf(ChannelRule.MOVIE), genres = listOf("Family")), 2),
            channel("drama", ChannelRule(types = listOf(ChannelRule.EPISODE), shows = listOf("ridge"), order = ChannelRule.Order.IN_ORDER), 3),
        )
        harness.synced(cartoon + ridge + films.take(2))
        harness.engine.extend()

        val before = card()
        assertEquals(mapOf("tide" to setOf("TV-Y7"), "family-films" to setOf("G", "PG"), "drama" to setOf("TV-14", "TV-MA")), before.channels.associate { it.id to it.contentRatings })
        assertEquals(listOf("tide", "family-films"), (KidGuide.of(before, nine) as GuideCardState.Ready).channels.map { it.id })

        // An unrated family film joins the library: Family films can play it from the next sync (K4).
        harness.now += 30 * MINUTE
        harness.synced(listOf(films[2]))
        val after = card()
        assertTrue(after.channels.single { it.id == "family-films" }.unrated)
        assertEquals(listOf("tide"), (KidGuide.of(after, nine) as GuideCardState.Ready).channels.map { it.id })
    }

    @Test
    fun `after a Ratings change, a channel is kid-safe once the program on now is over (G16)`() = runBlocking {
        // Animation films rated G, PG and PG-13, a day of each.
        val animation = listOf("G", "PG", "PG-13").flatMap { rating ->
            (1..14).map { movie("${rating.lowercase()}-$it", "$rating Film $it", 110, listOf("Animation"), rating = rating) }
        }
        harness.channels(channel("animation", ChannelRule(types = listOf(ChannelRule.MOVIE), genres = listOf("Animation"))))
        // The one channel: no Family appended to this lineup.
        harness.lineupServer.setFamilyAdded(true)
        harness.synced(animation)
        harness.engine.extend()
        val onNow = harness.dao.slotAt("animation", harness.now)!!
        assertEquals(SlotEntity.PROGRAM, onNow.kind)
        val rating = onNow.contentRating!!

        // The two ratings the film on now isn't, set as the channel's for everyone.
        val others = listOf("G", "PG", "PG-13") - rating
        harness.editor.update("animation", ChannelDraft("animation", ChannelRule(types = listOf(ChannelRule.MOVIE), genres = listOf("Animation"), contentRatings = others)))
        val during = card().channels.single()
        assertEquals(setOf("G", "PG", "PG-13"), during.contentRatings)
        assertEquals(GuideCardState.NoneForKids, KidGuide.of(card(), others.toSet()))

        // Once it's over (and the lineup is read again), only the new ratings are still to play.
        harness.now = onNow.endMillis + MINUTE
        harness.synced(emptyList())
        assertEquals(others.toSet(), card().channels.single().contentRatings)
        assertEquals(listOf("animation"), (KidGuide.of(card(), others.toSet()) as GuideCardState.Ready).channels.map { it.id })
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
