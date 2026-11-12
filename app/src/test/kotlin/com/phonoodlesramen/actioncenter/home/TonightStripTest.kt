package com.phonoodlesramen.actioncenter.home

import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.LensDeck
import com.phonoodlesramen.actioncenter.content.LensPick
import com.phonoodlesramen.actioncenter.content.LensSlot
import com.phonoodlesramen.actioncenter.content.QuickItem
import com.phonoodlesramen.actioncenter.content.SampleContentSource
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.player.BackTo
import com.phonoodlesramen.actioncenter.player.SavedSpot
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Marquee's "For tonight" strip (spec §4, Marquee): its order, its fill, and its live card. */
class TonightStripTest {
    private val content = SampleContentSource.content()
    private val pick = content.picks.first()
    private val heat = LensCardState(Lens.THROWBACK, content.lenses.getValue(Lens.THROWBACK).first(), season = null)
    private val coherence = LensCardState(Lens.HIDDEN_GEM, content.lenses.getValue(Lens.HIDDEN_GEM).first(), season = null)
    private val now = SampleGuide.WEEKNIGHT
    private val guide = SampleGuide.ready(now)

    @Test
    fun `six cards in the mockup's order - the pick, the lens, Up Next, Quick Watch, live, the next lens`() {
        val live = TonightStrip.live(guide, favorites = emptyList(), nowMillis = now)
        val strip = TonightStrip.build(pick, heat, content, live = live, moreLens = coherence)

        assertEquals(
            listOf("pick", "lens", "up:slow", "quick:bear", "live", "lens2"),
            strip.map { it.key },
        )
        assertEquals(listOf("Pick", "Throwback", "Up next", "Quick Watch", "Live", "Hidden gem"), strip.map { it.badge.text })
        assertEquals(BadgeTone.WARM, strip[1].badge.tone)
        assertEquals("Throwback lens", strip[1].caption)
        assertEquals("Fits before 10:30", strip[3].caption)
    }

    @Test
    fun `without a lineup or a second lens, the rest of Up Next and Quick Watch fill the strip, taking turns`() {
        val strip = TonightStrip.build(pick, heat, content)

        assertEquals(listOf("pick", "lens", "up:slow", "quick:bear", "up:shogun", "quick:abbott"), strip.map { it.key })
    }

    @Test
    fun `a title never shows twice`() {
        // The Bear is both in Up Next and in Quick Watch: it shows once, where it comes first.
        val doubled = content.copy(quickWatch = listOf(QuickItem("slow", "Slow Horses", "S4E2 · 41m")) + content.quickWatch)

        val strip = TonightStrip.build(pick, heat, doubled)

        assertEquals(1, strip.count { it.artId == "slow" })
        assertEquals(strip.map { it.artId }.distinct(), strip.map { it.artId })
    }

    @Test
    fun `the live card is your first favorite channel with a program on`() {
        val live = TonightStrip.live(guide, favorites = listOf("comedy", "tv-mix"), nowMillis = now) as StripCard.Live

        assertEquals("comedy", live.channel.id)
        assertTrue(live.favorite)
        assertEquals("On your favorite channel", live.reason)
        assertEquals("Live on 5 Comedy", live.caption)
    }

    @Test
    fun `with no favorites it's the lineup's first channel, and nothing without a lineup`() {
        val live = TonightStrip.live(guide, favorites = emptyList(), nowMillis = now) as StripCard.Live

        assertEquals("movies", live.channel.id)
        assertNull(TonightStrip.live(GuideCardState.NotSetUp, emptyList(), now))
        assertNull(TonightStrip.live(GuideCardState.Building, listOf("comedy"), now))
    }

    @Test
    fun `while the guide player's spot holds, the live card is Back to that channel (P4)`() {
        val channel = guide.channels.first { it.id == "scifi" }
        val slot = channel.slotAt(now)!!
        val spot = SavedSpot(channel.id, slot.startMillis, slot.itemKey!!, positionMs = 60_000, savedAtMillis = now - 600_000)

        val paused = TonightStrip.live(guide, favorites = listOf("comedy"), nowMillis = now, backTo = BackTo(channel, slot, spot))

        assertTrue(paused is StripCard.Paused)
        assertEquals(StripCard.KEY_LIVE, paused!!.key)
        assertEquals("Back to 2 Sci-Fi", paused.eyebrow)
    }

    @Test
    fun `the next lens is the rotation's first title from another lens that isn't the pick`() {
        val jaws = LensPick("jaws", "Jaws", "1975 · 2h 4m · PG", "51 years old and you've never seen it", seen = false)
        val deck = LensDeck {
            listOf(LensSlot(Lens.THROWBACK, heat.pick), LensSlot(Lens.CLASSIC, LensPick("severance", "Severance", "", "", false)), LensSlot(Lens.CLASSIC, jaws))
        }

        val next = TonightStrip.moreLens(deck, shown = heat, pickId = "severance", season = null)

        assertEquals(Lens.CLASSIC to "jaws", next?.lens to next?.pick?.id)
    }

    @Test
    fun `no pick and no content is an empty strip`() {
        assertEquals(emptyList<StripCard>(), TonightStrip.build(pick = null, lens = null, content = null))
    }
}
