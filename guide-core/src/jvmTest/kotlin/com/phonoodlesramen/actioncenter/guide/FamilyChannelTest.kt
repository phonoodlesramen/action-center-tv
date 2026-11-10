package com.phonoodlesramen.actioncenter.guide

import com.phonoodlesramen.actioncenter.guide.Fixtures.movie
import com.phonoodlesramen.actioncenter.guide.Fixtures.series
import com.phonoodlesramen.actioncenter.guide.GuideHarness.Companion.MINUTE
import com.phonoodlesramen.actioncenter.guide.GuideHarness.Companion.channel
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
 * The Family channel (K8): what kids see of the guide. A new starter lineup makes it; a
 * lineup made before Phase 5 gets it once, numbered after the last, and never twice.
 */
class FamilyChannelTest {
    private val harness = GuideHarness()

    @After
    fun tearDown() = harness.close()

    /** 14 old science fiction films for grown-ups (25.7 h, also the Classics). */
    private val grownUp: List<ItemEntity> = (1..14).map { movie("sf$it", "Orbit $it", 110, listOf("Science Fiction"), year = 1979 + it) }

    /** 16 family films rated G or PG (25.3 h), tagged Family and Animation. */
    private val familyFilms: List<ItemEntity> = (1..16).map {
        movie("fam$it", "Paper Kites $it", 95, listOf("Family", "Animation"), rating = if (it % 2 == 0) "G" else "PG")
    }

    /** A TV-Y7 show of 30 short episodes (11 h); its episodes have no rating of their own, so they take the show's. */
    private val cartoon: List<ItemEntity> = series("tide", "Tide Pool Friends", listOf("Animation"), seasons = 2, perSeason = 15, minutes = 22)
        .map { if (it.type == "show") it.copy(contentRating = "TV-Y7") else it }

    private val library = grownUp + familyFilms + cartoon

    private suspend fun family(): List<ChannelEntity> = harness.dao.channels().filter { it.name == StarterLineup.FAMILY_NAME }

    @Test
    fun `a new starter lineup has a Family channel of G to TV-PG movies and episodes, shuffled`() = runTest {
        harness.synced(library)
        harness.engine.extend()

        val channels = harness.dao.channels()
        // The Family genre gives the name way: there's one channel called Family.
        assertEquals(listOf("Movies", "Science Fiction", "Animation", "Classics", "Family"), channels.map { it.name })
        val family = channels.last()
        assertEquals(StarterLineup.FAMILY_ID, family.id)
        assertEquals(5, family.number)
        val rule = ChannelRule.fromJson(family.rule)
        assertEquals(listOf("G", "PG", "TV-Y", "TV-Y7", "TV-G", "TV-PG"), rule.contentRatings)
        assertEquals(listOf(ChannelRule.MOVIE, ChannelRule.EPISODE), rule.types)
        assertEquals(ChannelRule.Order.SHUFFLE, rule.order)

        // It plays only the family titles: the films and the cartoon's episodes.
        val kidsKeys = (familyFilms + cartoon).map { it.ratingKey }.toSet()
        val programs = harness.programs(StarterLineup.FAMILY_ID)
        assertTrue(programs.isNotEmpty())
        assertTrue(programs.all { it.itemKey in kidsKeys })
        assertTrue("episodes too", programs.any { it.showKey == "tide" })
        assertTrue(harness.lineupServer.familyAdded())
    }

    @Test
    fun `kids see Family, and Animation, whose rule names no ratings but plays only G and PG films`() = runBlocking {
        harness.synced(library)
        harness.engine.extend()

        val card = withTimeout(TIMEOUT) { harness.engine.card.filterIsInstance<GuideCardState.Ready>().first() }
        // R2: a channel's ratings are what it can play, not what its rule names.
        val ratings = card.channels.associate { it.name to it.contentRatings }
        assertEquals(setOf("G", "PG", "TV-Y7"), ratings["Family"])
        assertEquals(setOf("G", "PG"), ratings["Animation"])
        assertEquals(setOf("G", "PG", "PG-13"), ratings["Movies"])
        assertEquals(setOf("PG-13"), ratings["Science Fiction"])
        assertTrue(card.channels.none { it.unrated })

        val kid = KidGuide.of(card, Fixtures.SEVEN_TO_NINE) as GuideCardState.Ready
        assertEquals(listOf("Animation", "Family"), kid.channels.map { it.name })
    }

    @Test
    fun `a lineup made before Phase 5 gets Family once, numbered after the last`() = runTest {
        harness.channels(
            channel("movies", ChannelRule(types = listOf(ChannelRule.MOVIE)), number = 1),
            channel("scifi", ChannelRule(types = listOf(ChannelRule.MOVIE), genres = listOf("Science Fiction")), number = 4),
        )
        harness.synced(library)
        harness.engine.extend()

        val channels = harness.dao.channels()
        assertEquals(listOf("movies", "scifi", StarterLineup.FAMILY_ID), channels.map { it.id })
        assertEquals(5, channels.last().number)
        assertTrue("it's scheduled at once", harness.programs(StarterLineup.FAMILY_ID).isNotEmpty())
        assertEquals(1, harness.logs.count { "Family channel added as 5" in it })

        // Later extensions and syncs never add a second.
        harness.engine.extend()
        harness.now += 30 * MINUTE
        harness.synced(emptyList())
        harness.engine.extend()
        assertEquals(1, family().size)
        assertEquals(1, harness.logs.count { "Family channel added" in it })
    }

    @Test
    fun `once added, a Family channel deleted by hand stays deleted`() = runTest {
        harness.channels(channel("movies", ChannelRule(types = listOf(ChannelRule.MOVIE))))
        harness.synced(library)
        harness.engine.extend()
        assertEquals(1, family().size)

        harness.editor.delete(StarterLineup.FAMILY_ID)
        harness.now += 30 * MINUTE
        harness.synced(emptyList())
        harness.engine.extend()
        assertEquals(emptyList<ChannelEntity>(), family())
    }

    @Test
    fun `a starter lineup's Family deleted by hand stays deleted too`() = runTest {
        harness.synced(library)
        harness.engine.extend()
        harness.editor.delete(StarterLineup.FAMILY_ID)

        harness.now += 30 * MINUTE
        harness.synced(emptyList())
        harness.engine.extend()
        assertEquals(emptyList<ChannelEntity>(), family())
    }

    @Test
    fun `a lineup that has its own Family channel isn't given another`() = runTest {
        val own = channel("our-family", StarterLineup.FAMILY_RULE, number = 2).copy(name = StarterLineup.FAMILY_NAME)
        harness.channels(channel("movies", ChannelRule(types = listOf(ChannelRule.MOVIE))), own)
        harness.synced(library)
        harness.engine.extend()

        assertEquals(listOf("movies", "our-family"), harness.dao.channels().map { it.id })
        assertTrue(harness.lineupServer.familyAdded())
    }

    @Test
    fun `without a day of family titles it waits for a sync that brings them`() = runTest {
        harness.channels(channel("movies", ChannelRule(types = listOf(ChannelRule.MOVIE))))
        harness.synced(grownUp + familyFilms.take(5))
        harness.engine.extend()
        harness.engine.extend()
        assertEquals(emptyList<ChannelEntity>(), family())
        assertFalse(harness.lineupServer.familyAdded())

        harness.now += 30 * MINUTE
        harness.synced(familyFilms.drop(5) + cartoon)
        harness.engine.extend()
        assertEquals(listOf(StarterLineup.FAMILY_ID), family().map { it.id })
    }

    @Test
    fun `an emptied lineup gets no Family of its own`() = runTest {
        harness.channels(channel("movies", ChannelRule(types = listOf(ChannelRule.MOVIE))))
        // Made for this server, then every channel deleted by hand (G19: it stays empty).
        harness.lineupServer.set("server-a")
        harness.synced(library)
        harness.editor.delete("movies")
        harness.engine.extend()
        assertEquals(0, harness.dao.channelCount())
        assertFalse(harness.lineupServer.familyAdded())
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
