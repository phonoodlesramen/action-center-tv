package com.phonoodlesramen.actioncenter.guide

import com.phonoodlesramen.actioncenter.guide.Fixtures.movie
import com.phonoodlesramen.actioncenter.guide.Fixtures.series
import com.phonoodlesramen.actioncenter.guide.GuideHarness.Companion.HOUR
import com.phonoodlesramen.actioncenter.guide.GuideHarness.Companion.MINUTE
import com.phonoodlesramen.actioncenter.guide.GuideHarness.Companion.channel
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The lineup as a whole: what the card says when it has no channels to show (2.7), off
 * channels, remaking the starter lineup (G18) and a new Plex server (G19). Real time, so
 * runBlocking.
 */
class GuideLineupTest {
    /** Movies (with trailers) and a show worth a marathon: Movies, TV Mix and Ridge Line Marathon. */
    private val library: List<ItemEntity> =
        (1..15).map { movie("m${it.toString().padStart(2, '0')}", "Glowing Reef $it", 100, trailer = "t$it") } +
            series("ridge", "Ridge Line", listOf("Drama"), seasons = 4, perSeason = 10, minutes = 52, views = 8)

    /** Another server's library: Movies, TV Mix and Harbor Watch Marathon, none of the titles above. */
    private val otherLibrary: List<ItemEntity> =
        (1..15).map { movie("o${it.toString().padStart(2, '0')}", "Second Door $it", 110, listOf("Comedy")) } +
            series("harbor", "Harbor Watch", listOf("Crime"), seasons = 3, perSeason = 13, minutes = 45, views = 4)

    /** Too small for any channel. */
    private val small: List<ItemEntity> = (1..5).map { movie("s$it", "Small $it", 100) }

    /** A [LineupServer] whose writes wait at [gate], to catch the card while a lineup is being made. */
    private class GatedServer : LineupServer {
        @Volatile
        var gate = CompletableDeferred(Unit)

        @Volatile
        private var value: String? = null

        override suspend fun get(): String? = value

        override suspend fun set(clientIdentifier: String?) {
            gate.await()
            value = clientIdentifier
        }
    }

    private suspend fun GuideHarness.card(): GuideCardState = withTimeout(TIMEOUT) { engine.card.first() }

    private suspend fun GuideHarness.ready(predicate: (GuideCardState.Ready) -> Boolean = { true }): GuideCardState.Ready =
        withTimeout(TIMEOUT) { engine.card.filterIsInstance<GuideCardState.Ready>().first(predicate) }

    private suspend fun eventually(condition: suspend () -> Boolean) = withTimeout(TIMEOUT) {
        while (!condition()) delay(10)
    }

    private suspend fun rejected(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: EditRejected) {
            return e.message.orEmpty()
        }
        fail("expected the editor to turn it down")
        return ""
    }

    // What the card says.

    @Test
    fun `the card says it's building while the starter lineup is made`() = runBlocking {
        // Each extension waits to read the linked server, and the lineup's server waits to be saved.
        val linked = CompletableDeferred<Unit>()
        val server = GatedServer().apply { gate = CompletableDeferred() }
        GuideHarness(linkedServer = { linked.await().let { "server-a" } }, lineupServer = server).use { h ->
            h.synced(library)
            assertEquals("synced, and the starter lineup is next", GuideCardState.Building, h.card())
            assertEquals(0, h.dao.channelCount())

            val making = launch(Dispatchers.IO) { h.engine.extend() }
            linked.complete(Unit)
            eventually { h.dao.channelCount() > 0 }
            assertEquals("its channels are in, not yet scheduled", GuideCardState.Building, h.card())

            server.gate.complete(Unit)
            making.join()
            assertEquals(listOf("Movies", "TV Mix", "Ridge Line Marathon"), h.ready().channels.map { it.name })
        }
    }

    @Test
    fun `not set up before the library syncs, and nothing to fill when it makes no channels`() = runBlocking {
        GuideHarness().use { h ->
            assertEquals(GuideCardState.NotSetUp, h.card())

            h.synced(small)
            // The card itself tries the starter lineup.
            assertEquals(GuideCardState.NothingToFill, withTimeout(TIMEOUT) { h.engine.card.first { it != GuideCardState.Building } })
            assertEquals(0, h.dao.channelCount())
        }
    }

    @Test
    fun `off channels leave the card, keep their schedule growing, and are back at once`() = runBlocking {
        GuideHarness().use { h ->
            h.synced(library)
            h.engine.extend()

            h.editor.setEnabled("tv-mix", false)
            assertEquals(listOf("movies", "marathon-ridge"), h.ready { it.channels.size == 2 }.channels.map { it.id })

            h.now += 5 * HOUR
            h.engine.extend()
            assertTrue(h.dao.channel("tv-mix")!!.scheduledUntil!! >= h.now + 24 * HOUR)

            h.editor.setEnabled("tv-mix", true)
            val back = h.ready { it.channels.size == 3 }.channels.first { it.id == "tv-mix" }
            assertNotNull(back.slotAt(h.now))

            h.dao.channels().forEach { h.editor.setEnabled(it.id, false) }
            assertEquals("every channel off", GuideCardState.NoneOn, withTimeout(TIMEOUT) { h.engine.card.first { it !is GuideCardState.Ready } })
        }
    }

    // Remake the starter lineup (G18).

    @Test
    fun `remaking replaces every channel and slot with a new starter lineup, building meanwhile`() = runBlocking {
        val server = GatedServer()
        GuideHarness(linkedServer = { "server-a" }, lineupServer = server).use { h ->
            h.synced(library)
            h.engine.extend()
            val mine = h.editor.create(ChannelDraft("Mine", ChannelRule(maxMinutes = 120)))
            h.editor.rename("movies", "Films")
            h.editor.setEnabled("tv-mix", false)

            server.gate = CompletableDeferred()
            val remaking = launch(Dispatchers.IO) { h.editor.remakeStarterLineup() }
            eventually { h.dao.channel(mine) == null }
            assertEquals(GuideCardState.Building, h.card())

            server.gate.complete(Unit)
            remaking.join()
            val channels = h.dao.channels()
            assertEquals(listOf("movies", "tv-mix", "marathon-ridge"), channels.map { it.id })
            assertEquals(listOf("Movies", "TV Mix", "Ridge Line Marathon"), channels.map { it.name })
            assertTrue(channels.all { it.enabled })
            channels.forEach { assertTrue(it.scheduledUntil!! >= h.now + 24 * HOUR) }
            assertEquals(emptyList<SlotEntity>(), h.dao.slots(mine))
            assertEquals("server-a", server.get())
            assertEquals(3, h.ready { it.channels.size == 3 }.channels.size)
        }
    }

    @Test
    fun `remaking is turned down, and the lineup kept, when there's nothing to make it from`() = runBlocking {
        GuideHarness().use { h ->
            h.channels(channel("mine", ChannelRule()))
            assertEquals(
                "Plex hasn't synced your library yet, so there's nothing to make a lineup from.",
                rejected { h.editor.remakeStarterLineup() },
            )

            h.synced(small)
            assertEquals(
                "Nothing in your library fills a day of a channel yet, so the lineup stays as it is.",
                rejected { h.editor.remakeStarterLineup() },
            )
            assertEquals(listOf("mine"), h.dao.channels().map { it.id })
            assertTrue("the card isn't left building", h.card() != GuideCardState.Building)
        }
    }

    // A new Plex server (G19).

    @Test
    fun `a full sync from another server remakes the lineup, and re-linking the same one keeps it`() = runBlocking {
        var linked: String? = "server-a"
        GuideHarness(linkedServer = { linked }).use { h ->
            h.synced(library)
            h.engine.extend()
            assertEquals("server-a", h.lineupServer.get())
            val mine = h.editor.create(ChannelDraft("Mine", ChannelRule()))

            // The same server, synced again: everything stays.
            h.now += 30 * MINUTE
            h.synced(library)
            h.engine.extend()
            assertNotNull(h.dao.channel(mine))

            // Signing out empties the cache (PlexSync.clear); another server is linked. Before its
            // library has synced in full, nothing changes.
            h.plex.dao().clearItems()
            h.plex.dao().clearSyncState()
            linked = "server-b"
            h.engine.extend()
            assertNotNull(h.dao.channel(mine))
            assertEquals("server-a", h.lineupServer.get())

            // Its first full sync: a starter lineup from its library.
            h.now += 30 * MINUTE
            h.synced(otherLibrary)
            h.engine.extend()
            val channels = h.dao.channels()
            assertEquals(listOf("movies", "tv-mix", "marathon-harbor"), channels.map { it.id })
            assertNull(h.dao.channel(mine))
            assertEquals("server-b", h.lineupServer.get())
            assertTrue(h.logs.any { "another Plex server" in it })
            val theirs = otherLibrary.map { it.ratingKey }.toSet()
            channels.forEach { channel ->
                val programs = h.programs(channel.id)
                assertTrue(programs.isNotEmpty())
                assertTrue("only the new server's titles", programs.all { it.itemKey in theirs })
            }
        }
    }

    @Test
    fun `a lineup made before 2_7 remembers the server linked now, and stays`() = runBlocking {
        GuideHarness(linkedServer = { "server-a" }).use { h ->
            h.synced(library)
            h.channels(channel("mine", ChannelRule()))
            h.engine.extend()

            assertEquals("server-a", h.lineupServer.get())
            assertEquals(listOf("mine"), h.dao.channels().map { it.id })
        }
    }

    @Test
    fun `once made, a lineup emptied by hand stays empty until it's remade`() = runBlocking {
        GuideHarness(linkedServer = { "server-a" }).use { h ->
            h.synced(library)
            h.engine.extend()
            h.dao.channels().forEach { h.editor.delete(it.id) }

            h.now += 30 * MINUTE
            h.synced(library)
            h.engine.extend()
            assertEquals(0, h.dao.channelCount())
            assertEquals(GuideCardState.NoneOn, h.card())

            h.editor.remakeStarterLineup()
            assertEquals(3, h.dao.channelCount())
        }
    }

    private companion object {
        const val TIMEOUT = 20_000L
    }
}
