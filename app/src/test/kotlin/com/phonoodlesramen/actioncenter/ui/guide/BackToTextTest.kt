package com.phonoodlesramen.actioncenter.ui.guide

import com.phonoodlesramen.actioncenter.player.SavedSpot
import com.phonoodlesramen.actioncenter.player.SpotRules
import com.phonoodlesramen.actioncenter.ui.home.FocusRequest
import com.phonoodlesramen.actioncenter.ui.home.HomeFocus
import com.phonoodlesramen.actioncenter.ui.home.backToCardFocus
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Back-to card's words (P4), and where Back from a resumed player lands. */
class BackToTextTest {
    private val minute = 60_000L
    private val now = SampleGuide.WEEKNIGHT

    @Test
    fun `it says where in the program, and when it was left`() {
        val spot = SavedSpot("scifi", 0, "k", 72 * minute + 30_000, now - 9 * minute)
        assertEquals("Paused 1:12:30 in · 9 min ago", BackToText.line(spot, now))
        assertEquals("Paused 6:30 in · just now", BackToText.line(spot.copy(positionMs = 6 * minute + 30_000, savedAtMillis = now - 59_000), now))
    }

    @Test
    fun `how long ago reads in minutes, then hours`() {
        assertEquals("just now", BackToText.ago(0))
        assertEquals("just now", BackToText.ago(-minute))
        assertEquals("1 min ago", BackToText.ago(minute))
        assertEquals("59 min ago", BackToText.ago(60 * minute - 1))
        assertEquals("1 hr ago", BackToText.ago(60 * minute))
        assertEquals("3 hr 59 min ago", BackToText.ago(SpotRules.KEEP_MILLIS - 1))
    }

    @Test
    fun `its title is the channel's`() {
        val lineup = SampleGuide.ready(now)
        val program = lineup.channels.single { it.id == "tv-mix" }.slotAt(now)!!
        val backTo = SpotRules.backTo(SavedSpot("tv-mix", program.startMillis, program.itemKey!!, minute, now), lineup, now)!!
        assertEquals("Back to TV Mix", BackToText.title(backTo))
    }

    @Test
    fun `Back from a resumed player lands on the Guide card, or a kid's Live card for the channel`() {
        assertEquals(FocusRequest(HomeFocus.GUIDE), backToCardFocus(kid = false, channelId = "scifi"))
        assertEquals(FocusRequest(HomeFocus.LIVE, channelId = "cartoons"), backToCardFocus(kid = true, channelId = "cartoons"))
    }
}
