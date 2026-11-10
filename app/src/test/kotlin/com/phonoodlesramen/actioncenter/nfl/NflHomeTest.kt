package com.phonoodlesramen.actioncenter.nfl

import com.phonoodlesramen.actioncenter.ui.nfl.SampleNfl
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The NFL on the home (F1, F2, F8, F9, F10): the game-day card's window and Not today, the row's
 * order and place, the off-season and a kid's home showing nothing, and Hide scores with Show score.
 * The sample's Week 8, in UTC as the house's zone.
 */
class NflHomeTest {
    private fun at(text: String) = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val view = SampleNfl.view()

    /** Monday night alone: Bears at Seahawks at 8:15 PM, everything else over. */
    private val mondayOnly = view.copy(state = view.state!!.copy(week = SampleNfl.week.copy(games = SampleNfl.week.games.filter { it.state == GameState.FINAL } + SampleNfl.monday)))

    @Test
    fun `the card is your team's game while it's on`() {
        assertEquals(SampleNfl.live.id, NflHome.card(view, SampleNfl.nowMillis, kid = false)?.id)
    }

    @Test
    fun `the card goes up 30 minutes before a kickoff and comes down when the day's last game ends`() {
        assertNull(NflHome.card(mondayOnly, at("2026-11-02T19:44"), kid = false))
        assertEquals(SampleNfl.monday.id, NflHome.card(mondayOnly, at("2026-11-02T19:45"), kid = false)?.id)
        val over = mondayOnly.copy(
            state = mondayOnly.state!!.copy(
                week = mondayOnly.state!!.week!!.copy(
                    games = mondayOnly.state!!.week!!.games.map { if (it.id == SampleNfl.monday.id) it.copy(state = GameState.FINAL, awayScore = 20, homeScore = 17, winner = "CHI") else it },
                ),
            ),
        )
        assertNull(NflHome.card(over, at("2026-11-02T23:30"), kid = false))
    }

    @Test
    fun `Not today puts Quick Watch back until tomorrow, and the row stays`() {
        val putAway = view.copy(notTodayDay = LocalDate.of(2026, 11, 1))
        assertNull(NflHome.card(putAway, SampleNfl.nowMillis, kid = false))
        assertNotNull(NflHome.row(putAway, SampleNfl.nowMillis, kid = false))
        // Monday, the card is back for Monday night.
        assertEquals(SampleNfl.monday.id, NflHome.card(putAway.copy(state = mondayOnly.state), at("2026-11-02T20:00"), kid = false)?.id)
    }

    @Test
    fun `neither while Show the NFL is off`() {
        val off = view.copy(state = view.state!!.copy(settings = view.state!!.settings.copy(show = false)))
        assertNull(NflHome.card(off, SampleNfl.nowMillis, kid = false))
        assertNull(NflHome.row(off, SampleNfl.nowMillis, kid = false))
    }

    @Test
    fun `neither on a kid's home`() {
        assertNull(NflHome.card(view, SampleNfl.nowMillis, kid = true))
        assertNull(NflHome.row(view, SampleNfl.nowMillis, kid = true))
    }

    @Test
    fun `nothing in the off-season, or before there's a copy`() {
        val off = view.copy(state = view.state!!.copy(phase = SeasonPhase.OFF, week = null))
        assertNull(NflHome.card(off, SampleNfl.nowMillis, kid = false))
        assertNull(NflHome.row(off, SampleNfl.nowMillis, kid = false))
        assertNull(NflHome.card(NflView.None, SampleNfl.nowMillis, kid = false))
        assertNull(NflHome.row(NflView.None, SampleNfl.nowMillis, kid = false))
    }

    @Test
    fun `the row has live games, the next kickoff, your division, then the finals, newest first`() {
        val row = NflHome.row(view, SampleNfl.nowMillis, kid = false)!!
        assertEquals("NFL · Week 8 · 1 live", row.label)
        assertEquals(listOf(SampleNfl.live.id, SampleNfl.monday.id), row.games.map { it.id })
        assertEquals("NFC East", row.division!!.name)
        assertEquals(listOf("NYG", "DAL", "PHI", "WSH"), row.division!!.teams.map { it.abbr })
        // Twelve finals, Thursday night's last.
        assertEquals(12, row.finals.size)
        assertEquals("401873023", row.finals.last().id)
        assertTrue(row.finals.zipWithNext().all { (a, b) -> a.kickoffMillis >= b.kickoffMillis })
    }

    @Test
    fun `the row is first on a game day from an hour before the first kickoff, and after Live otherwise`() {
        assertTrue(NflHome.row(view, SampleNfl.nowMillis, kid = false)!!.first)
        assertFalse(NflHome.row(mondayOnly, at("2026-11-02T10:00"), kid = false)!!.first)
        assertTrue(NflHome.row(mondayOnly, at("2026-11-02T19:15"), kid = false)!!.first)
    }

    @Test
    fun `no team set, no division card`() {
        assertNull(NflHome.row(SampleNfl.view(SampleNfl.settings.copy(yourTeams = emptyList())), SampleNfl.nowMillis, kid = false)!!.division)
    }

    @Test
    fun `Hide scores hides today's games until Show score, and the standings are this morning's meanwhile`() {
        val morning = SampleNfl.standings.map { if (it.abbr == "WSH") it.copy(wins = 0) else it }
        val hiding = SampleNfl.view(SampleNfl.settings.copy(hideScores = true)).let { it.copy(state = it.state!!.copy(morningStandings = morning)) }
        assertTrue(hiding.hidden(SampleNfl.live, SampleNfl.nowMillis))
        assertTrue(hiding.hidden(SampleNfl.week.games.first { it.id == "401873026" }, SampleNfl.nowMillis))
        // Thursday's final isn't today's; Monday's hasn't started.
        assertFalse(hiding.hidden(SampleNfl.week.games.first { it.id == "401873023" }, SampleNfl.nowMillis))
        assertFalse(hiding.hidden(SampleNfl.monday, SampleNfl.nowMillis))
        assertEquals(morning, hiding.standings(SampleNfl.nowMillis))

        val shown = hiding.copy(shown = setOf(SampleNfl.live.id), shownDay = LocalDate.of(2026, 11, 1))
        assertFalse(shown.hidden(SampleNfl.live, SampleNfl.nowMillis))
        // Shown until tomorrow: a live game past midnight is today's again, and hidden.
        assertTrue(shown.hidden(SampleNfl.live, at("2026-11-02T00:10")))
        assertEquals(SampleNfl.standings, SampleNfl.view().standings(SampleNfl.nowMillis))
    }

    @Test
    fun `an old copy says how old while a game is on`() {
        assertNull(NflText.staleScores(view, SampleNfl.nowMillis))
        val old = view.copy(asOfMillis = at("2026-11-01T21:05"))
        assertEquals("Scores from 9:05 PM", NflText.staleScores(old, SampleNfl.nowMillis))
        assertNull(NflText.staleScores(old.copy(state = mondayOnly.state), SampleNfl.nowMillis))
    }

    @Test
    fun `the words`() {
        assertEquals("1:45 3rd", NflText.clock(SampleNfl.live))
        assertEquals("Halftime", NflText.clock(SampleNfl.live.copy(detail = "Halftime")))
        assertEquals("Mon 8:15 PM", NflText.kickoff(SampleNfl.monday, ZoneOffset.UTC))
        assertEquals("Kickoff in 25 min", NflText.kickoffIn(SampleNfl.monday, at("2026-11-02T19:50"), ZoneOffset.UTC))
        assertEquals("Oct 29 – Nov 2", NflText.dates(SampleNfl.week, ZoneOffset.UTC))
        assertEquals("NFL NET", NflText.badge(WatchLinks.network("NFL Net")))
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st"), listOf(1, 2, 3, 4, 11, 12, 13, 21).map(NflText::ordinal))
    }
}
