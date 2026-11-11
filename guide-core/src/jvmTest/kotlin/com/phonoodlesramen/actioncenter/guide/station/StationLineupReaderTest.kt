package com.phonoodlesramen.actioncenter.guide.station

import com.phonoodlesramen.actioncenter.guide.ChannelRule
import com.phonoodlesramen.actioncenter.guide.Fixtures.movie
import com.phonoodlesramen.actioncenter.guide.Fixtures.series
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideEngine
import com.phonoodlesramen.actioncenter.guide.GuideHarness
import com.phonoodlesramen.actioncenter.guide.GuideHarness.Companion.channel
import com.phonoodlesramen.actioncenter.guide.LineupServer
import com.phonoodlesramen.actioncenter.guide.SlotEntity
import java.time.ZoneOffset
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The station reads `guide.db` as the TVs' card does, off channels and all (Phase 8). Real time, so runBlocking. */
class StationLineupReaderTest {
    // The Family channel was added long ago, so extending adds nothing of its own.
    private val harness = GuideHarness(lineupServer = LineupServer.InMemory(family = true))
    private val reader = StationLineupReader(harness.guide, harness.plex)

    /** Made-up films, one of them rated R. */
    private val films = (1..15).map { movie("m$it", "Glowing Reef $it", 100, rating = if (it == 3) "R" else "PG", trailer = "t$it") }

    @After
    fun tearDown() = harness.close()

    @Test
    fun `every channel, off ones too, with the slots and ratings the TVs' card shows`() = runBlocking {
        harness.synced(films)
        harness.channels(channel("reef", ChannelRule()), channel("off", ChannelRule(), number = 2).copy(enabled = false))
        harness.engine.extend()
        val card = withTimeout(TIMEOUT) { harness.engine.card.filterIsInstance<GuideCardState.Ready>().first() }

        val lineup = reader.read(harness.now - GuideEngine.SHOW_PAST_MILLIS, harness.now, ZoneOffset.UTC)
        assertEquals(listOf("reef", "off"), lineup.channels.map { it.id })
        assertEquals(listOf(true, false), lineup.channels.map { it.enabled })
        assertTrue(lineup.channels[1].slots.isNotEmpty())

        val reef = lineup.channels.first()
        val shown = card.channels.single()
        assertEquals(shown.slots, reef.slots.map { it.toGuideSlot() })
        assertEquals(shown.contentRatings, reef.contentRatings.toSet())
        assertEquals(setOf("PG", "R"), reef.contentRatings.toSet())
        assertEquals(shown.unrated, reef.unrated)
        assertEquals(ChannelRule(), reef.rule)
        assertEquals(card.updatedAtMillis, lineup.extendedAtMillis)
    }

    @Test
    fun `a one-show channel's logo is its show's poster, which every TV draws over its icon`() = runBlocking {
        harness.synced(films + series("ridge", "Ridge Line", listOf("Western"), seasons = 2, perSeason = 10, minutes = 52))
        val marathon = ChannelRule(types = listOf(ChannelRule.EPISODE), shows = listOf("ridge"), order = ChannelRule.Order.IN_ORDER)
        harness.channels(channel("reef", ChannelRule()), channel("marathon-ridge", marathon, number = 2))
        harness.engine.extend()

        val lineup = reader.read(harness.now - GuideEngine.SHOW_PAST_MILLIS, harness.now, ZoneOffset.UTC).channels.associateBy { it.id }
        assertEquals("/library/metadata/ridge/thumb", lineup.getValue("marathon-ridge").logo)
        assertNull(lineup.getValue("reef").logo)
    }

    @Test
    fun `the slot at a start, off air once its title loses its file`() = runBlocking {
        harness.synced(films)
        harness.channels(channel("reef", ChannelRule()))
        harness.engine.extend()
        val program = harness.programs("reef").first()
        assertEquals(SlotEntity.PROGRAM, program.kind)

        assertNull(reader.slot("reef", program.startMillis)!!.offAir)
        assertNull(reader.slot("reef", program.startMillis + 1))
        assertNull(reader.slot("elsewhere", program.startMillis))

        harness.plex.dao().upsertItems(listOf(films.first { it.ratingKey == program.itemKey }.copy(partKey = null)))
        assertEquals(GuideEngine.NO_FILE, reader.slot("reef", program.startMillis)!!.offAir)
    }

    private companion object {
        const val TIMEOUT = 20_000L
    }
}
