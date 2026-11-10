package com.phonoodlesramen.actioncenter.nfl

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ESPN's own answers, trimmed (`resources/nfl`, from `scratchpad/trim_espn.py`): the week-8
 * scoreboard at 10:26 PM Eastern on Sunday 1 Nov 2026 with Philadelphia at Washington in the 3rd,
 * week 16's Christmas and flex games, 2025's playoffs, 2026's Super Bowl still TBD, standings,
 * teams, Buffalo's schedule and a summary. Team names are ESPN's; the one last play is made up.
 */
class EspnParserTest {
    @Test
    fun `a live game has the score, quarter, clock, who has the ball, the down and the last play`() {
        val week = EspnParser.scoreboard(fixture("scoreboard-2026-week8-live.json")).week!!
        val game = week.games.single { it.id == "401873035" }

        assertEquals(GameState.LIVE, game.state)
        assertEquals("PHI" to "WSH", game.away to game.home)
        assertEquals(10 to 13, game.awayScore to game.homeScore)
        assertEquals("2-5" to "1-4-1", game.awayRecord to game.homeRecord)
        assertEquals("6:20 - 3rd", game.detail)
        assertEquals(3, game.period)
        assertEquals("6:20", game.clock)
        // ESPN gives the team id ("21"); the competitors say whose it is.
        assertEquals("PHI", game.possession)
        assertEquals("1st & 10 at PHI 38", game.downDistance)
        assertFalse(game.redZone)
        assertEquals("T.Madeup punts 44 yards to PHI 22, Center-K.Notreal. R.Invented to PHI 38 for 16 yards (D.Fakename).", game.lastPlay)
        assertEquals(listOf(10, 0, 0) to listOf(3, 10, 0), game.awayLineScores to game.homeLineScores)
        assertEquals(listOf("NBC") to emptyList<String>(), game.tv to game.streaming)
        assertEquals("Northwest Stadium", game.venue)
        assertEquals(at("2026-11-01T20:20-05:00"), game.kickoffMillis)
        assertTrue(game.kickoffKnown)
        assertNull(game.winner)
        assertNull(game.headline)
    }

    @Test
    fun `the red zone`() {
        val text = fixture("scoreboard-2026-week8-live.json").replace("\"isRedZone\":false", "\"isRedZone\":true")
        assertTrue(EspnParser.scoreboard(text).week!!.games.single { it.id == "401873035" }.redZone)
    }

    @Test
    fun `a final has the winner and overtime's line score, and no clock or ball`() {
        val games = EspnParser.scoreboard(fixture("scoreboard-2026-week8-live.json")).week!!.games
        val overtime = games.single { it.id == "401873031" }

        assertEquals(GameState.FINAL, overtime.state)
        assertEquals("Final/OT", overtime.detail)
        assertEquals("CIN", overtime.winner)
        assertEquals(36 to 43, overtime.awayScore to overtime.homeScore)
        assertEquals(5, overtime.period)
        assertEquals(listOf(13, 0, 8, 15, 0) to listOf(0, 14, 9, 13, 7), overtime.awayLineScores to overtime.homeLineScores)
        assertNull(overtime.clock)
        assertNull(overtime.possession)

        val thursday = games.single { it.id == "401873023" }
        assertEquals("GB", thursday.winner)
        assertEquals(emptyList<String>() to listOf("Prime Video"), thursday.tv to thursday.streaming)
    }

    @Test
    fun `a game to come has no score or period, and its kickoff`() {
        val game = EspnParser.scoreboard(fixture("scoreboard-2026-week8-live.json")).week!!.games.single { it.id == "401873036" }

        assertEquals(GameState.PRE, game.state)
        assertEquals("CHI" to "SEA", game.away to game.home)
        assertNull(game.awayScore)
        assertNull(game.homeScore)
        assertNull(game.period)
        assertNull(game.clock)
        assertEquals("11/2 - 8:15 PM EST", game.detail)
        assertEquals(listOf("ESPN"), game.tv)
        assertEquals(at("2026-11-02T20:15-05:00"), game.kickoffMillis)
    }

    @Test
    fun `the week has its label from the calendar, every game, and the teams on a bye`() {
        val board = EspnParser.scoreboard(fixture("scoreboard-2026-week8-live.json"))
        val week = board.week!!

        assertEquals(2026, board.seasonYear)
        assertEquals(Triple(2026, 2, 8), Triple(week.seasonYear, week.seasonType, week.week))
        assertEquals("Week 8", week.label)
        assertEquals(14, week.games.size)
        assertEquals(listOf("NO", "NYG", "SF", "HOU"), week.byes)
    }

    @Test
    fun `the calendar runs from the preseason to the Super Bowl, with ESPN's labels and no off-season`() {
        val calendar = EspnParser.scoreboard(fixture("scoreboard-2026-week8-live.json")).calendar

        assertEquals(4 + 18 + 4, calendar.size)
        assertEquals(NflWeekRef(1, 1, "Hall of Fame Weekend", at("2026-08-06T07:00Z"), at("2026-08-13T06:59Z")), calendar.first())
        assertEquals("Preseason Week 1", calendar[1].label)
        assertEquals(NflWeekRef(3, 4, "Super Bowl", at("2027-02-03T08:00Z"), at("2027-02-16T07:59Z")), calendar.last())
        assertEquals(calendar.sortedBy { it.startMillis }, calendar)
    }

    @Test
    fun `2025's calendar had a Pro Bowl week, which isn't a week to step to`() {
        val board = EspnParser.scoreboard(fixture("scoreboard-2025-post1.json"))
        val playoffs = board.calendar.filter { it.seasonType == 3 }

        assertEquals(listOf("Wild Card" to 1, "Divisional Round" to 2, "Conference Championship" to 3, "Super Bowl" to 5), playoffs.map { it.label to it.week })
        assertEquals("Wild Card", board.week!!.label)
    }

    @Test
    fun `kickoffs the league hasn't set are timeValid false`() {
        val games = EspnParser.scoreboard(fixture("scoreboard-2026-week16.json")).week!!.games
        val flex = games.filterNot { it.kickoffKnown }

        assertEquals(listOf("401873146", "401873145", "401873147", "401873144"), flex.map { it.id })
        assertTrue(flex.all { it.detail == "TBD" && it.headline == "Flex Game: 12/26 or 12/27" && it.tv.isEmpty() })
        assertEquals(listOf("Netflix"), games.single { it.id == "401873141" }.streaming)
    }

    @Test
    fun `a postseason game has its round in the headline, and TV and streaming apart`() {
        val week = EspnParser.scoreboard(fixture("scoreboard-2025-post1.json")).week!!
        val game = week.games.single { it.id == "401772978" }

        assertEquals(Triple(2025, 3, 1), Triple(week.seasonYear, week.seasonType, week.week))
        assertEquals("AFC Wild Card Playoffs", game.headline)
        assertEquals("NE", game.winner)
        assertEquals(listOf("NBC") to listOf("Peacock"), game.tv to game.streaming)
        assertEquals(listOf("ESPN", "ABC"), week.games.single { it.id == "401772976" }.tv)
    }

    @Test
    fun `2026's Super Bowl is postseason week 4, teams still to be decided, and week 5 has nothing`() {
        val week = EspnParser.scoreboard(fixture("scoreboard-2026-post4.json")).week!!
        val game = week.games.single()

        assertEquals(4, week.week)
        assertEquals("Super Bowl", week.label)
        assertEquals("Super Bowl LXI", game.headline)
        assertEquals("TBD" to "TBD", game.away to game.home)
        assertEquals(listOf("ESPN", "ABC") to listOf("Disney+"), game.tv to game.streaming)

        val empty = EspnParser.scoreboard(fixture("scoreboard-2026-post5-empty.json"))
        assertNull(empty.week)
        assertEquals(2026, empty.seasonYear)
    }

    @Test
    fun `standings give each line by division, ranked by seed, with the clinch mark when there is one`() {
        val standings = EspnParser.standings(fixture("standings-2026.json"))

        assertEquals(2026, standings.seasonYear)
        assertEquals(32, standings.standings.size)
        val buffalo = standings.standings.first()
        assertEquals(
            NflStanding(
                abbr = "BUF", wins = 6, losses = 1, ties = 0, winPercent = ".857", seed = 1, divisionRank = 1, streak = "W3",
                gamesBehind = "-", divisionRecord = "0-1", pointsFor = 233, pointsAgainst = 181, differential = 52, clincher = "x",
            ),
            buffalo,
        )
        assertEquals("6-1", buffalo.record)
        assertEquals(listOf("BUF", "NE", "NYJ", "MIA"), standings.standings.take(4).map { it.abbr })
        assertEquals(listOf(1, 2, 3, 4), standings.standings.take(4).map { it.divisionRank })
        assertNull(standings.standings.single { it.abbr == "NE" }.clincher)
        assertEquals(1, standings.standings.count { it.clincher != null })
        assertEquals(NflDivision("AFC", "East"), standings.divisions["BUF"])
        assertEquals(NflDivision("NFC", "West"), standings.divisions["SF"])
        assertEquals("1-4-1", standings.standings.single { it.abbr == "WSH" }.let { "${it.wins}-${it.losses}-${it.ties}" })
    }

    @Test
    fun `last season's final standings keep ESPN's clinch marks, and the division leader first though ESPN listed it last`() {
        val standings = EspnParser.standings(fixture("standings-2025.json"))
        val west = standings.standings.filter { standings.divisions[it.abbr] == NflDivision("AFC", "West") }

        assertEquals(2025, standings.seasonYear)
        assertEquals(listOf("DEN", "LAC", "KC", "LV"), west.map { it.abbr })
        assertEquals(listOf("*", "y", "e", "e"), west.map { it.clincher })
        assertEquals(1 to 1, west.first().seed to west.first().divisionRank)
        assertEquals("14-3", standings.standings.single { it.abbr == "NE" }.record)
    }

    @Test
    fun `teams have names, colours as #RRGGBB, and each one's division from the standings`() {
        val divisions = EspnParser.standings(fixture("standings-2026.json")).divisions
        val teams = EspnParser.teams(fixture("teams.json"), divisions)

        assertEquals(32, teams.size)
        assertEquals(NflTeam("PHI", "Eagles", "Philadelphia", "#06424D", "#000000", "NFC", "East"), teams.single { it.abbr == "PHI" })
        assertEquals("Washington Commanders" to "NFC East", teams.single { it.abbr == "WSH" }.let { it.fullName to it.divisionName })
        assertTrue(teams.all { it.color.matches(Regex("#[0-9A-F]{6}")) && it.conference.isNotEmpty() })
    }

    @Test
    fun `a team's schedule has scores as objects, results and games to come`() {
        val schedule = EspnParser.schedule(fixture("schedule-buf-2026.json"))

        assertEquals("BUF" to 2026, schedule.abbr to schedule.seasonYear)
        assertEquals(17, schedule.games.size)
        val opener = schedule.games.first()
        assertEquals(Triple("BUF", "HOU", 1), Triple(opener.away, opener.home, opener.week))
        assertEquals(36 to 31, opener.awayScore to opener.homeScore)
        assertEquals("BUF", opener.winner)
        assertEquals(listOf("CBS"), opener.tv)
        val next = schedule.games.single { it.week == 9 }
        assertEquals(GameState.PRE, next.state)
        assertEquals(listOf("ESPN", "ABC"), next.tv)
        assertNull(next.awayScore)
        assertFalse(schedule.games.last().kickoffKnown)
        assertTrue(schedule.games.all { it.seasonType == 2 })
    }

    @Test
    fun `a summary has the line score and each side's team stats, by ESPN's labels`() {
        val detail = EspnParser.summary(fixture("summary-ari-dal.json"))
        val game = detail.game

        assertEquals("401873024", game.id)
        assertEquals("ARI" to "DAL", game.away to game.home)
        assertEquals(33 to 30, game.awayScore to game.homeScore)
        assertEquals("ARI", game.winner)
        assertEquals("Final/OT", game.detail)
        assertEquals(8, game.week)
        // Only displayValues in a summary's line score.
        assertEquals(listOf(0, 3, 7, 20, 3) to listOf(7, 7, 3, 13, 0), game.awayLineScores to game.homeLineScores)
        assertEquals("AT&T Stadium", game.venue)
        assertEquals(listOf("Fox"), game.tv)
        assertEquals(
            listOf("1st Downs" to "24", "Total Yards" to "467", "Passing" to "265", "Rushing" to "202", "Turnovers" to "2", "Possession" to "33:25"),
            detail.awayStats.map { it.label to it.value },
        )
        assertEquals(listOf("19", "261", "168", "93", "0", "33:42"), detail.homeStats.map { it.value })
    }

    @Test
    fun `unknown fields and missing ones read as defaults`() {
        val board = EspnParser.scoreboard("""{"events":[],"surprise":{"new":true}}""")
        assertNull(board.week)
        assertEquals(emptyList<NflWeekRef>(), board.calendar)
        val game = EspnParser.scoreboard(
            """{"season":{"type":2,"year":2026},"week":{"number":9},"events":[{"id":"1","date":"2026-11-08T18:00Z",
            "competitions":[{"competitors":[{"homeAway":"home","team":{"abbreviation":"BUF"}},{"homeAway":"away","team":{"abbreviation":"MIA"}}],
            "status":{"type":{"state":"pre"}},"odds":[{"details":"BUF -3"}]}]}]}""",
        ).week!!.games.single()
        assertEquals("MIA" to "BUF", game.away to game.home)
        assertEquals("Week 9", EspnParser.scoreboard("""{"season":{"type":2,"year":2026},"week":{"number":9},"events":[]}""").week!!.label)
        assertTrue(game.kickoffKnown)
    }

    private fun fixture(name: String) = javaClass.getResource("/nfl/$name")!!.readText()

    private fun at(text: String) = OffsetDateTime.parse(text).toInstant().toEpochMilli()
}
