package com.phonoodlesramen.actioncenter.ui.guide

import com.phonoodlesramen.actioncenter.player.SavedSpot
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Back-to card when the spot was saved on another TV (Phase 8, N6): it names the room. */
class BackToRoomTest {
    private val minute = 60_000L
    private val now = SampleGuide.WEEKNIGHT

    @Test
    fun `a spot from another room names it instead of the place in the program`() {
        val spot = SavedSpot("scifi", 0, "k", 72 * minute, now - 9 * minute, room = "Living room")
        assertEquals("Paused in the living room · 9 min ago", BackToText.line(spot, now))
        // This TV's own spot is as before.
        assertEquals("Paused 1:12:00 in · 9 min ago", BackToText.line(spot.copy(room = null), now))
    }

    @Test
    fun `rooms read as a sentence says them`() {
        assertEquals("the living room", BackToText.inRoom("Living room"))
        assertEquals("the kitchen", BackToText.inRoom("Kitchen"))
        assertEquals("the bedroom", BackToText.inRoom(" Bedroom "))
        assertEquals("the TV room", BackToText.inRoom("TV room"))
        assertEquals("Maya's room", BackToText.inRoom("Maya's room"))
        assertEquals("the den", BackToText.inRoom("The den"))
    }
}
