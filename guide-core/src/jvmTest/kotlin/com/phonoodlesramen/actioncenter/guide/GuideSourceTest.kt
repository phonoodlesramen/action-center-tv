package com.phonoodlesramen.actioncenter.guide

import com.phonoodlesramen.actioncenter.guide.Fixtures.movie
import com.phonoodlesramen.actioncenter.guide.Fixtures.series
import com.phonoodlesramen.actioncenter.guide.GuideHarness.Companion.HOUR
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home's [GuideSource]: the lineup from guide.db, extended while it's read. Real time, so runBlocking. */
class GuideSourceTest {
    private val harness = GuideHarness()

    @After
    fun tearDown() = harness.close()

    /** Movies (with trailers) and one show worth a marathon. */
    private val library: List<ItemEntity> =
        (1..15).map { movie("m${it.toString().padStart(2, '0')}", "Glowing Reef $it", 100, trailer = "t$it") } +
            series("ridge", "Ridge Line", listOf("Drama"), seasons = 4, perSeason = 10, minutes = 52, views = 8)

    private suspend fun ready(predicate: (GuideCardState.Ready) -> Boolean = { true }): GuideCardState.Ready =
        withTimeout(TIMEOUT) { harness.engine.card.filterIsInstance<GuideCardState.Ready>().first(predicate) }

    private fun GuideCardState.Ready.slot(itemKey: String) = channels.flatMap { it.slots }.first { it.itemKey == itemKey }

    @Test
    fun `not set up until the library has synced`() = runBlocking {
        assertEquals(GuideCardState.NotSetUp, withTimeout(TIMEOUT) { harness.engine.card.first() })
        assertEquals(0, harness.dao.channelCount())
    }

    @Test
    fun `reading the lineup makes it, in number order, with something on now`() = runBlocking {
        harness.synced(library)

        // Nothing extends it but the card itself.
        val lineup = ready { it.channels.size == 3 }
        assertEquals(listOf("Movies", "TV Mix", "Ridge Line Marathon"), lineup.channels.map { it.name })
        assertEquals(listOf(1, 2, 3), lineup.channels.map { it.number })
        assertEquals(harness.now, lineup.updatedAtMillis)
        for (channel in lineup.channels) {
            assertNotNull("${channel.name} has something on now", channel.slotAt(harness.now))
            assertTrue(channel.slots.last().endMillis >= harness.now + 24 * HOUR)
        }
        // A marathon's logo is its show's poster (I1); the others draw their glyphs.
        assertEquals(listOf(null, null, "/library/metadata/ridge/thumb"), lineup.channels.map { it.logo })
        assertEquals(listOf("movie", "tv_gen"), lineup.channels.take(2).map { it.glyph })
    }

    @Test
    fun `slots map as the sample guide's do`() = runBlocking {
        harness.synced(library)
        harness.engine.extend()
        val lineup = ready { it.channels.size == 3 }

        val film = lineup.channels[0].slots.first { !it.filler }
        val key = film.itemKey!!
        assertEquals(library.first { it.ratingKey == key }.title, film.title)
        assertNull(film.episode)
        assertNull(film.showKey)
        assertEquals("/library/metadata/$key/art", film.art)
        assertEquals("PG-13", film.contentRating)
        assertTrue(film.summary!!.startsWith("A made-up film"))
        assertNull(film.offAir)

        val episode = lineup.channels[2].slots.first()
        assertEquals("Ridge Line", episode.title)
        assertEquals("S1 E1 · Episode 1", episode.episode)
        assertEquals("ridge", episode.showKey)
        assertEquals("ridge-s01e01", episode.itemKey)
        assertEquals("/library/metadata/ridge-s01e01/thumb", episode.art)
        assertEquals("an episode without a rating has its show's", "TV-14", episode.contentRating)
        assertEquals("Season 1, episode 1 of Ridge Line.", episode.summary)

        val filler = lineup.channels.flatMap { it.slots }.filter { it.filler }
        assertTrue("no Up next cards (G21)", filler.none { it.itemKey == null })
        assertTrue(filler.any { it.itemKey?.startsWith("t") == true && it.title.startsWith("Trailer: Glowing Reef") })
        assertTrue(filler.all { it.offAir == null })
    }

    @Test
    fun `slots reach back three hours, for the full guide`() = runBlocking {
        harness.synced(library)
        harness.engine.extend()
        harness.now += 5 * HOUR
        harness.engine.extend()

        val lineup = ready { it.channels.size == 3 }
        for (channel in lineup.channels) {
            assertTrue(channel.slots.first().endMillis > harness.now - 3 * HOUR)
            assertTrue(channel.slots.first().startMillis <= harness.now - 2 * HOUR)
        }
    }

    @Test
    fun `a scheduled title that leaves the library stays, off air`() = runBlocking {
        harness.synced(library)
        harness.engine.extend()
        val states = Channel<GuideCardState.Ready>(Channel.UNLIMITED)
        val collecting = CoroutineScope(coroutineContext).launch {
            harness.engine.card.filterIsInstance<GuideCardState.Ready>().collect { states.send(it) }
        }
        try {
            var lineup = withTimeout(TIMEOUT) { states.receive() }
            while (lineup.channels.size < 3) lineup = withTimeout(TIMEOUT) { states.receive() }
            val gone = lineup.channels[0].slots.first { !it.filler }.itemKey!!
            val noFile = lineup.channels[2].slots.first { !it.filler }.itemKey!!
            assertNull(lineup.slot(gone).offAir)

            // Deleted from the library (a full sync drops what it didn't see), and a file gone.
            val dao = harness.plex.dao()
            dao.upsertItems(listOf(library.first { it.ratingKey == gone }.copy(syncedAt = 0)))
            dao.deleteUnseen(Fixtures.MOVIES, 1)
            dao.upsertItems(listOf(library.first { it.ratingKey == noFile }.copy(partKey = null)))

            while (lineup.slot(gone).offAir == null || lineup.slot(noFile).offAir == null) lineup = withTimeout(TIMEOUT) { states.receive() }
            assertEquals(GuideEngine.GONE, lineup.slot(gone).offAir)
            assertEquals(library.first { it.ratingKey == gone }.title, lineup.slot(gone).title)
            assertEquals(GuideEngine.NO_FILE, lineup.slot(noFile).offAir)

            // Signing out empties the cache: the schedule stays, all of it off air.
            dao.clearItems()
            while (lineup.channels.flatMap { it.slots }.any { !it.filler && it.offAir == null }) lineup = withTimeout(TIMEOUT) { states.receive() }
            assertEquals(3, lineup.channels.size)
        } finally {
            collecting.cancel()
        }
    }

    @Test
    fun `while the lineup is read, the next 24 hours stay covered`() = runBlocking {
        GuideHarness(checkEveryMillis = 20, maxWaitMillis = 100).use { fast ->
            fast.synced(library)
            val states = Channel<GuideCardState.Ready>(Channel.UNLIMITED)
            val collecting = CoroutineScope(coroutineContext).launch {
                fast.engine.card.filterIsInstance<GuideCardState.Ready>().collect { states.send(it) }
            }
            try {
                var lineup = withTimeout(TIMEOUT) { states.receive() }
                while (lineup.channels.size < 3) lineup = withTimeout(TIMEOUT) { states.receive() }
                val scheduled = fast.dao.slots("movies")

                fast.now += 3 * HOUR
                while (lineup.channels.any { it.slots.last().endMillis < fast.now + 24 * HOUR }) {
                    lineup = withTimeout(TIMEOUT) { states.receive() }
                }
                assertEquals("nothing rewritten", scheduled, fast.dao.slots("movies").take(scheduled.size))
            } finally {
                collecting.cancel()
            }
        }
    }

    @Test
    fun `extending never throws`() = runBlocking {
        harness.synced(library)
        harness.guide.close()

        harness.engine.extend()
        assertTrue(harness.logs.any { it.startsWith("guide: not extended") })
    }

    private companion object {
        const val TIMEOUT = 20_000L
    }
}
