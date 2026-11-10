package com.phonoodlesramen.actioncenter.guide

import com.phonoodlesramen.actioncenter.guide.Fixtures.movie
import com.phonoodlesramen.actioncenter.guide.Fixtures.series
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The starter lineup (G2) from a made-up library: what makes a channel, the order, and the 20-channel and 24-hour rules (Q5). */
class StarterLineupTest {
    private val harness = GuideHarness()

    @After
    fun tearDown() = harness.close()

    /**
     * Movies: 14 old science fiction films (25.7 h, also the Classics) and 13 comedies
     * (21.7 h, short of a day). TV: shows by views, some too short for a marathon.
     */
    private val library: List<ItemEntity> =
        (1..14).map { movie("sf$it", "Orbit $it", 110, listOf("Science Fiction"), year = 1979 + it) } +
            (1..13).map { movie("co$it", "Open Door $it", 100, listOf("Comedy"), year = 2015) } +
            series("ridge", "Ridge Line", listOf("Drama"), seasons = 4, perSeason = 10, minutes = 52, views = 30) +
            series("open", "Open House", listOf("Comedy"), seasons = 3, perSeason = 12, minutes = 22, views = 50) +
            series("night", "Night Shift", listOf("Drama", "Crime"), seasons = 3, perSeason = 10, minutes = 44, views = 5) +
            series("harbor", "Harbor Watch", listOf("Crime"), seasons = 3, perSeason = 13, minutes = 45, views = 10) +
            series("desk", "Desk Duty", listOf("Comedy"), seasons = 6, perSeason = 12, minutes = 21, views = 3) +
            series("quiet", "Quiet Hours", listOf("Documentary"), seasons = 2, perSeason = 10, minutes = 50, views = 1) +
            series("sky", "Sky Atlas", listOf("Documentary"), seasons = 1, perSeason = 30, minutes = 50)

    @Test
    fun `the lineup is Movies, genres largest first, TV Mix, Classics and three marathons`() = runTest {
        harness.synced(library)
        harness.engine.extend()

        val channels = harness.dao.channels()
        assertEquals(
            listOf(
                "Movies", "Drama TV", "Crime TV", "Documentary TV", "Comedy TV", "Science Fiction", "TV Mix", "Classics",
                "Ridge Line Marathon", "Harbor Watch Marathon", "Desk Duty Marathon",
            ),
            channels.map { it.name },
        )
        assertEquals((1..11).toList(), channels.map { it.number })
        assertEquals(
            listOf(
                "movies", "tv-genre-drama", "tv-genre-crime", "tv-genre-documentary", "tv-genre-comedy", "genre-science-fiction",
                "tv-mix", "classics", "marathon-ridge", "marathon-harbor", "marathon-desk",
            ),
            channels.map { it.id },
        )
        val rules = channels.associate { it.id to ChannelRule.fromJson(it.rule) }
        assertEquals(ChannelRule.Order.SHUFFLE, rules.getValue("genre-science-fiction").order)
        assertEquals(ChannelRule.Order.ROTATE_SHOWS, rules.getValue("tv-genre-drama").order)
        assertEquals(ChannelRule.Order.ROTATE_SHOWS, rules.getValue("tv-mix").order)
        assertEquals(ChannelRule.Order.IN_ORDER, rules.getValue("marathon-ridge").order)
        assertEquals(listOf("ridge"), rules.getValue("marathon-ridge").shows)
        assertEquals(25, rules.getValue("classics").minAgeYears)
        assertTrue("every channel has its own seed", channels.map { it.seed }.toSet().size == channels.size)
        assertTrue("half-hour starts", channels.all { it.alignmentMinutes == 30 })
        assertTrue(harness.logs.any { "starter lineup of 11 channels" in it })
    }

    @Test
    fun `a channel needs a day of distinct programming`() = runTest {
        harness.synced(library)
        harness.engine.extend()

        val names = harness.dao.channels().map { it.name }
        // 13 comedies are 21.7 hours; Open House (50 views) and Night Shift are under a day.
        assertTrue("Comedy" !in names)
        assertTrue("Open House Marathon" !in names)
        assertTrue("Night Shift Marathon" !in names)
        // Sky Atlas fills a day but nobody has watched it.
        assertTrue("Sky Atlas Marathon" !in names)
    }

    @Test
    fun `at most 20 channels, and genres give way to the others`() = runTest {
        val genres = ('A'..'Y').map { "Genre $it" }
        harness.synced(
            (1..16).map { movie("g$it", "Many Things $it", 120, genres, year = 1990) } +
                movie("plain", "Plain Title", 120, year = 2020) +
                series("ridge", "Ridge Line", emptyList(), seasons = 4, perSeason = 10, minutes = 52, views = 3),
        )
        harness.engine.extend()

        val names = harness.dao.channels().map { it.name }
        assertEquals(20, names.size)
        assertEquals(listOf("Movies") + ('A'..'P').map { "Genre $it" } + listOf("TV Mix", "Classics", "Ridge Line Marathon"), names)
    }

    @Test
    fun `a genre that is everything Movies plays isn't a channel of its own`() = runTest {
        harness.synced((1..15).map { movie("d$it", "Long Day $it", 120, listOf("Drama")) })
        harness.engine.extend()

        assertEquals(listOf("Movies"), harness.dao.channels().map { it.name })
    }

    @Test
    fun `channels of short episodes start on the quarter hour`() = runTest {
        harness.synced(series("toon", "Paper Rockets", listOf("Animation"), seasons = 10, perSeason = 15, minutes = 11, views = 4))
        harness.engine.extend()

        val channels = harness.dao.channels()
        assertEquals(listOf("TV Mix", "Paper Rockets Marathon"), channels.map { it.name })
        assertTrue(channels.all { it.alignmentMinutes == 15 })
    }

    @Test
    fun `the lineup waits for a full sync, and is made once`() = runTest {
        harness.plex.dao().upsertItems(library)
        harness.engine.extend()
        assertEquals("no full sync yet", 0, harness.dao.channelCount())

        harness.synced(emptyList())
        harness.engine.extend()
        val made = harness.dao.channels()
        assertEquals(11, made.size)

        // A bigger library later doesn't remake it.
        harness.now += 30 * GuideHarness.MINUTE
        harness.synced((1..13).map { movie("more$it", "Second Door $it", 100, listOf("Comedy"), year = 2016) })
        harness.engine.extend()
        assertEquals(made.map { it.id }, harness.dao.channels().map { it.id })
    }

    @Test
    fun `a library too small for a channel is asked again after the next sync`() = runTest {
        harness.synced((1..5).map { movie("s$it", "Small $it", 100) })
        harness.engine.extend()
        harness.engine.extend()
        assertEquals(0, harness.dao.channelCount())
        assertEquals(1, harness.logs.count { "nothing in the library fills a channel" in it })

        harness.now += 30 * GuideHarness.MINUTE
        harness.synced((6..15).map { movie("s$it", "Small $it", 100) })
        harness.engine.extend()
        assertEquals(listOf("Movies"), harness.dao.channels().map { it.name })
    }
}
