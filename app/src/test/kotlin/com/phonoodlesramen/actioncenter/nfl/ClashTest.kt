package com.phonoodlesramen.actioncenter.nfl

import com.phonoodlesramen.actioncenter.ui.nfl.SampleNfl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Two cities (ui-spec "Two cities"): how they meet, and whose goes dark. */
class ClashTest {
    private val live = SampleNfl.live

    @Test
    fun `before kickoff the cities fade into each other, and once it's on they clash`() {
        assertEquals(Clash.Seam.FADE, Clash.seam(SampleNfl.monday))
        assertEquals(Clash.Seam.ZIGZAG, Clash.seam(live))
        assertEquals(Clash.Seam.ZIGZAG, Clash.seam(live.copy(state = GameState.FINAL)))
    }

    @Test
    fun `once it's over the loser's city goes dark, whichever side it's on`() {
        assertEquals("WSH", Clash.dimmed(live.copy(state = GameState.FINAL, homeScore = 10, winner = "PHI"), hidden = false))
        assertEquals("PHI", Clash.dimmed(live.copy(state = GameState.FINAL, winner = "WSH"), hidden = false))
    }

    @Test
    fun `a game still on, a tie and a hidden score leave both lit`() {
        assertNull(Clash.dimmed(live, hidden = false))
        assertNull(Clash.dimmed(live.copy(state = GameState.FINAL, homeScore = 14, winner = null), hidden = false))
        assertNull(Clash.dimmed(live.copy(state = GameState.FINAL, winner = "WSH"), hidden = true))
    }
}
