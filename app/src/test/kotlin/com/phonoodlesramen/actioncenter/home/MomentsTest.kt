package com.phonoodlesramen.actioncenter.home

import com.phonoodlesramen.actioncenter.context.ContextMode
import com.phonoodlesramen.actioncenter.home.TonightStrip.Slot
import com.phonoodlesramen.actioncenter.nfl.GameState
import com.phonoodlesramen.actioncenter.ui.home.aroundTheLeague
import com.phonoodlesramen.actioncenter.ui.home.eachGetsTheirOwn
import com.phonoodlesramen.actioncenter.ui.home.untilCutoff
import com.phonoodlesramen.actioncenter.ui.nfl.SampleNfl
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Moments (H3): which moment leads, what it puts first, and what Keep the usual layout holds. */
class MomentsTest {
    @Test
    fun `each context mode has its moment, and weekend evenings and daytime keep the usual home`() {
        assertEquals(Moment.WEEKNIGHT, Moments.at(ContextMode.WEEKNIGHT_EVENING, gameOn = false))
        assertEquals(Moment.WEEKEND_MORNING, Moments.at(ContextMode.WEEKEND_MORNING, gameOn = false))
        assertEquals(Moment.LATE_NIGHT, Moments.at(ContextMode.LATE_NIGHT, gameOn = false))
        assertNull(Moments.at(ContextMode.WEEKEND_EVENING, gameOn = false))
        assertNull(Moments.at(ContextMode.DAYTIME, gameOn = false))
    }

    @Test
    fun `game day wins over the time of day while the game-day card is up`() {
        ContextMode.entries.forEach { assertEquals(Moment.GAME_DAY, Moments.at(it, gameOn = true)) }
    }

    @Test
    fun `Marquee's strip leads with what the moment puts first`() {
        assertEquals(TonightStrip.DEFAULT_ORDER, Moments.stripOrder(null))
        assertEquals(listOf(Slot.PICK, Slot.QUICK_WATCH), Moments.stripOrder(Moment.WEEKNIGHT).take(2))
        assertEquals(Slot.LENS, Moments.stripOrder(Moment.WEEKEND_MORNING).first())
        assertEquals(Slot.GAME, Moments.stripOrder(Moment.GAME_DAY).first())
        assertEquals(listOf(Slot.LENS, Slot.QUICK_WATCH), Moments.stripOrder(Moment.LATE_NIGHT).take(2))
        Moment.entries.forEach { assertEquals("six places, none twice", 6, Moments.stripOrder(it).distinct().size) }
    }

    @Test
    fun `Keep the usual layout holds for the evening, past midnight too`() {
        val evening = LocalDateTime.of(2026, 11, 9, 23, 30)
        val pastMidnight = LocalDateTime.of(2026, 11, 10, 0, 30)

        assertEquals(LocalDate.of(2026, 11, 9), Moments.evening(pastMidnight))
        assertEquals(Moments.held(ContextMode.LATE_NIGHT, evening), Moments.held(ContextMode.LATE_NIGHT, pastMidnight))
        assertTrue(Moments.held(ContextMode.LATE_NIGHT, evening) != Moments.held(ContextMode.LATE_NIGHT, evening.plusDays(1)))
    }

    @Test
    fun `around the league is today's other games, live first, then the latest finals`() {
        val view = SampleNfl.view()
        val games = aroundTheLeague(view, featured = SampleNfl.live, nowMillis = SampleNfl.nowMillis)

        assertEquals(3, games.size)
        assertTrue(games.none { it.id == SampleNfl.live.id })
        assertTrue(games.all { it.state == GameState.FINAL })
        // The late games (4:05 and 4:25 PM) before the early ones.
        assertTrue(games.first().kickoffMillis >= games.last().kickoffMillis)
        assertEquals(games.map { it.kickoffMillis }.sortedDescending(), games.map { it.kickoffMillis })
    }

    @Test
    fun `the cards' small lines`() {
        assertEquals("Maya gets their own home.", eachGetsTheirOwn(listOf("Maya")))
        assertEquals("Maya and Leo each get their own home.", eachGetsTheirOwn(listOf("Maya", "Leo")))
        assertEquals("1h 49m until 10:30", untilCutoff(109, "10:30"))
        assertEquals("25 min until 10:30", untilCutoff(25, "10:30"))
        assertNull(untilCutoff(0, "10:30"))
    }
}
