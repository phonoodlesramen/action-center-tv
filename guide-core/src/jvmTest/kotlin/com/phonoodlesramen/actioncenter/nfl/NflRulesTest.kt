package com.phonoodlesramen.actioncenter.nfl

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The NFL's rules on ESPN's real weeks (the fixtures) and on made-up games, in Eastern time, as the Shield shows it. */
class NflRulesTest {
    private val live = EspnParser.scoreboard(fixture("scoreboard-2026-week8-live.json"))
    private val week8 = live.week!!

    /** Week 8 once the Sunday night game is over. */
    private val week8Done = week8.copy(games = week8.games.map { if (it.id == SNF) it.copy(state = GameState.FINAL, winner = "WSH") else it })

    @Test
    fun `the phase follows ESPN's calendar, and the off-season runs from the day after the Super Bowl to the first preseason game`() {
        val calendar = live.calendar
        assertEquals(SeasonPhase.REGULAR, NflRules.phase(calendar, week8, at("2026-11-01T22:26-05:00"), NY))
        assertEquals(SeasonPhase.OFF, NflRules.phase(calendar, null, at("2026-08-05T23:59-04:00"), NY))
        assertEquals(SeasonPhase.PRESEASON, NflRules.phase(calendar, null, at("2026-08-06T00:00-04:00"), NY))
        assertEquals(SeasonPhase.PRESEASON, NflRules.phase(calendar, null, at("2026-09-05T12:00-04:00"), NY))
        assertEquals(SeasonPhase.REGULAR, NflRules.phase(calendar, null, at("2026-09-10T20:00-04:00"), NY))
        assertEquals(SeasonPhase.POSTSEASON, NflRules.phase(calendar, null, at("2027-01-16T16:30-05:00"), NY))

        // With the Super Bowl's week, the day after its kickoff.
        val superBowl = EspnParser.scoreboard(fixture("scoreboard-2026-post4.json"))
        assertEquals(SeasonPhase.POSTSEASON, NflRules.phase(superBowl.calendar, superBowl.week, at("2027-02-14T23:59-05:00"), NY))
        assertEquals(SeasonPhase.OFF, NflRules.phase(superBowl.calendar, superBowl.week, at("2027-02-15T00:00-05:00"), NY))
        // Without it, the calendar's end.
        assertEquals(SeasonPhase.POSTSEASON, NflRules.phase(calendar, null, at("2027-02-16T02:58-05:00"), NY))
        assertEquals(SeasonPhase.OFF, NflRules.phase(calendar, null, at("2027-02-16T03:00-05:00"), NY))

        // 2025's Super Bowl was week 5, after the Pro Bowl.
        val lx = EspnParser.scoreboard(fixture("scoreboard-2025-post5.json"))
        assertEquals(SeasonPhase.POSTSEASON, NflRules.phase(lx.calendar, lx.week, at("2026-02-08T22:00-05:00"), NY))
        assertEquals(SeasonPhase.OFF, NflRules.phase(lx.calendar, lx.week, at("2026-02-09T00:00-05:00"), NY))
        assertEquals(SeasonPhase.OFF, NflRules.phase(emptyList(), null, at("2026-11-01T12:00-05:00"), NY))
    }

    @Test
    fun `the standings are last season's until Week 1 (D)`() {
        val calendar = live.calendar
        assertEquals(2025, NflRules.standingsSeason(calendar, 2026, at("2026-08-20T12:00-04:00")))
        assertEquals(2026, NflRules.standingsSeason(calendar, 2026, at("2026-09-06T03:00-04:00")))
        assertEquals(2026, NflRules.standingsSeason(calendar, 2026, at("2027-03-01T12:00-05:00")))
        assertEquals(2025, NflRules.standingsSeason(emptyList(), 2026, at("2026-11-01T12:00-05:00")))
    }

    @Test
    fun `the card's game is your team's while it's on, else the closest live one, else the next kickoff (F2)`() {
        val now = at("2026-11-01T22:26-05:00")
        assertEquals(SNF, NflRules.featured(week8, listOf("PHI"), now)?.id)
        assertEquals(SNF, NflRules.featured(week8, emptyList(), now)?.id)
        assertEquals(MNF, NflRules.featured(week8Done, listOf("PHI"), at("2026-11-01T23:45-05:00"))?.id)
        assertNull(NflRules.featured(week8Done.copy(games = week8Done.games.filter { it.state == GameState.FINAL }), emptyList(), now))

        val sunday = at("2026-11-08T15:00-05:00")
        val second = game("a", "BUF", "MIA", "2026-11-08T13:00-05:00", GameState.LIVE, 17, 14, period = 2)
        val fourth = game("b", "NE", "NYJ", "2026-11-08T13:00-05:00", GameState.LIVE, 20, 23, period = 4)
        val rout = game("c", "PHI", "DAL", "2026-11-08T13:00-05:00", GameState.LIVE, 31, 3, period = 4)
        val week = week(second, fourth, rout)
        assertEquals("b", NflRules.featured(week, emptyList(), sunday)?.id)
        assertEquals("c", NflRules.featured(week, listOf("PHI"), sunday)?.id)

        // Yours, a minute past kickoff, before ESPN says it's started.
        val yours = game("d", "KC", "DEN", "2026-11-08T14:59-05:00")
        assertEquals("d", NflRules.featured(week(second, fourth, yours), listOf("DEN"), sunday)?.id)

        // Nothing on: the next kickoff, yours first among those at the same time.
        val early = game("e", "BUF", "MIA", "2026-11-08T13:00-05:00")
        val mine = game("f", "NE", "NYJ", "2026-11-08T13:00-05:00")
        val later = game("g", "LV", "KC", "2026-11-08T16:25-05:00")
        val unset = game("h", "TB", "ATL", "2026-11-08T00:00-05:00", known = false)
        assertEquals("e", NflRules.featured(week(later, unset, early, mine), emptyList(), at("2026-11-08T11:00-05:00"))?.id)
        assertEquals("f", NflRules.featured(week(later, unset, early, mine), listOf("NYJ"), at("2026-11-08T11:00-05:00"))?.id)
    }

    @Test
    fun `the card is up from 30 minutes before a kickoff until every game that day is final (F1)`() {
        assertFalse(NflRules.cardUp(week8, at("2026-11-01T12:29-05:00"), NY))
        assertTrue(NflRules.cardUp(week8, at("2026-11-01T12:30-05:00"), NY))
        assertTrue(NflRules.cardUp(week8, at("2026-11-01T22:26-05:00"), NY))
        // Thursday's only game is final.
        assertFalse(NflRules.cardUp(week8, at("2026-10-29T21:00-04:00"), NY))

        assertFalse(NflRules.cardUp(week8Done, at("2026-11-01T23:45-05:00"), NY))
        assertFalse(NflRules.cardUp(week8Done, at("2026-11-02T19:44-05:00"), NY))
        assertTrue(NflRules.cardUp(week8Done, at("2026-11-02T19:45-05:00"), NY))
        // A game ESPN never calls final is given up 6 hours after its kickoff.
        assertTrue(NflRules.cardUp(week8Done, at("2026-11-03T02:14-05:00"), NY))
        assertFalse(NflRules.cardUp(week8Done, at("2026-11-03T02:15-05:00"), NY))
        assertFalse(NflRules.cardUp(null, at("2026-11-01T22:26-05:00"), NY))
    }

    @Test
    fun `the row goes first on a game day from an hour before the first kickoff to the day's end (F1)`() {
        assertFalse(NflRules.rowFirst(week8, at("2026-11-01T11:59-05:00"), NY))
        assertTrue(NflRules.rowFirst(week8, at("2026-11-01T12:00-05:00"), NY))
        assertTrue(NflRules.rowFirst(week8Done, at("2026-11-01T23:59-05:00"), NY))
        assertFalse(NflRules.rowFirst(week8, at("2026-11-02T00:00-05:00"), NY))
        assertFalse(NflRules.rowFirst(week8, at("2026-11-02T19:14-05:00"), NY))
        assertTrue(NflRules.rowFirst(week8, at("2026-11-02T19:15-05:00"), NY))
        assertFalse(NflRules.rowFirst(week8, at("2026-11-03T12:00-05:00"), NY))
    }

    @Test
    fun `the row puts live games first, yours then the closest, then the next kickoffs, then finals newest first`() {
        val order = NflRules.rowOrder(week8, emptyList())
        assertEquals(listOf(SNF, MNF), order.take(2).map { it.id })
        assertEquals("401873023", order.last().id)
        val finals = order.drop(2)
        assertTrue(finals.all { it.state == GameState.FINAL })
        assertEquals(finals.sortedByDescending { it.kickoffMillis }, finals)

        val close = game("a", "NE", "NYJ", "2026-11-08T13:00-05:00", GameState.LIVE, 20, 23, period = 4)
        val rout = game("b", "PHI", "DAL", "2026-11-08T13:00-05:00", GameState.LIVE, 31, 3, period = 4)
        val later = game("c", "LV", "KC", "2026-11-08T16:25-05:00")
        val soon = game("d", "BUF", "MIA", "2026-11-08T16:05-05:00")
        assertEquals(listOf("a", "b", "d", "c"), NflRules.rowOrder(week(later, rout, soon, close), emptyList()).map { it.id })
        assertEquals(listOf("b", "a", "d", "c"), NflRules.rowOrder(week(later, rout, soon, close), listOf("DAL")).map { it.id })
    }

    @Test
    fun `the Games tab's slots on ESPN's week 8`() {
        val slots = NflRules.slots(week8, NY)
        assertEquals(
            listOf(
                "Thursday night · Oct 29" to 1, "Sunday · Nov 1 · 1:00" to 8, "Sunday afternoon · 4:05 and 4:25" to 3,
                "Sunday night" to 1, "Monday night" to 1,
            ),
            slots.map { it.label to it.games.size },
        )

        // A live game leads its slot.
        val tampa = week8.copy(games = week8.games.map { if (it.id == "401873025") it.copy(state = GameState.LIVE) else it })
        assertEquals("401873025", NflRules.slots(tampa, NY)[1].games.first().id)
    }

    @Test
    fun `slots at Christmas, with flex games whose time isn't set`() {
        val week16 = EspnParser.scoreboard(fixture("scoreboard-2026-week16.json")).week!!
        assertEquals(
            listOf(
                "Thursday night · Dec 24", "Friday · Dec 25 · 1:00", "Friday · Dec 25 · 4:30", "Friday night · Dec 25",
                "Sunday · Dec 27 · 1:00", "Sunday afternoon · 4:05 and 4:25", "Sunday night", "Monday night", "Times not set yet",
            ),
            NflRules.slots(week16, NY).map { it.label },
        )
        assertEquals(4, NflRules.slots(week16, NY).last().games.size)
    }

    @Test
    fun `London's morning, a Saturday's own times, and the playoffs by round`() {
        val london = game("a", "PHI", "JAX", "2026-10-11T09:30-04:00", tv = listOf("NFL Net"))
        val one = game("b", "BUF", "MIA", "2026-10-11T13:00-04:00")
        assertEquals(listOf("Sunday morning · 9:30", "Sunday · Oct 11 · 1:00"), NflRules.slots(week(london, one), NY).map { it.label })

        val saturday = week(game("c", "DEN", "KC", "2026-12-19T16:30-05:00"), game("d", "GB", "CHI", "2026-12-19T13:00-05:00"))
        assertEquals(listOf("Saturday · Dec 19 · 1:00", "Saturday · Dec 19 · 4:30"), NflRules.slots(saturday, NY).map { it.label })

        val wildCard = EspnParser.scoreboard(fixture("scoreboard-2025-post1.json")).week!!
        val rounds = NflRules.slots(wildCard, NY)
        assertEquals(listOf("NFC Wild Card Playoffs", "AFC Wild Card Playoffs"), rounds.map { it.label })
        assertEquals(listOf("401772979", "401772981", "401772980"), rounds.first().games.map { it.id })
    }

    @Test
    fun `the bracket has 2025's seeds and every round of its playoffs matched in`() {
        val standings = EspnParser.standings(fixture("standings-2025.json"))
        val teams = EspnParser.teams(fixture("teams.json"), standings.divisions)
        val games = listOf("scoreboard-2025-post1.json", "scoreboard-2025-post2.json", "scoreboard-2025-post3.json", "scoreboard-2025-post5.json")
            .flatMap { EspnParser.scoreboard(fixture(it)).week!!.games }
        val bracket = NflRules.bracket(standings.standings, teams, games)

        val afc = bracket.afc
        assertEquals(listOf("DEN", "NE", "JAX", "PIT", "HOU", "BUF", "LAC"), afc.seeds.map { it.abbr })
        assertEquals("DEN", afc.bye)
        assertEquals(listOf("NE" to "LAC", "JAX" to "BUF", "PIT" to "HOU"), afc.wildCard.map { it.high to it.low })
        assertEquals(listOf("401772978", "401772977", "401772976"), afc.wildCard.map { it.game?.id })
        assertEquals(listOf("NE", "BUF", "HOU"), afc.wildCard.map { it.winner })
        assertEquals(listOf("DEN" to "BUF", "NE" to "HOU"), afc.divisional.map { it.high to it.low })
        assertEquals(listOf("401772982", "401772983"), afc.divisional.map { it.game?.id })
        assertEquals("DEN" to "NE", afc.championship?.let { it.high to it.low })
        assertEquals("NE", afc.champion)
        assertEquals(listOf("IND", "BAL", "MIA"), afc.hunt.map { it.abbr })

        val nfc = bracket.nfc
        assertEquals(listOf("SEA", "CHI", "PHI", "CAR", "LAR", "SF", "GB"), nfc.seeds.map { it.abbr })
        assertEquals(listOf("CHI" to "GB", "PHI" to "SF", "CAR" to "LAR"), nfc.wildCard.map { it.high to it.low })
        assertEquals(listOf("SEA" to "SF", "CHI" to "LAR"), nfc.divisional.map { it.high to it.low })
        assertEquals("SEA" to "LAR", nfc.championship?.let { it.high to it.low })
        assertEquals("SEA", nfc.champion)

        val superBowl = bracket.superBowl!!
        assertEquals("NE" to "SEA", superBowl.high to superBowl.low)
        assertEquals("401772988", superBowl.game?.id)
        assertEquals("SEA", superBowl.winner)
    }

    @Test
    fun `if the season ended today, the pairings come from today's seeds, later rounds once the one before is decided`() {
        val standings = EspnParser.standings(fixture("standings-2026.json"))
        val teams = EspnParser.teams(fixture("teams.json"), standings.divisions)
        val today = NflRules.bracket(standings.standings, teams, emptyList())

        assertEquals("BUF", today.afc.bye)
        assertEquals(listOf("DEN" to "NE", "PIT" to "CIN", "JAX" to "KC"), today.afc.wildCard.map { it.high to it.low })
        assertTrue(today.afc.wildCard.all { it.game == null })
        assertEquals(emptyList<NflMatchup>(), today.afc.divisional)
        assertNull(today.afc.championship)
        assertEquals(listOf("CLE", "LV", "BAL"), today.afc.hunt.map { it.abbr })
        assertEquals("SF", today.nfc.bye)
        assertEquals(listOf("MIN" to "GB", "NYG" to "CHI", "CAR" to "DET"), today.nfc.wildCard.map { it.high to it.low })
        assertNull(today.superBowl)

        // 2025 after the wild-card round only: the divisional pairings, their games not in yet.
        val last = EspnParser.standings(fixture("standings-2025.json"))
        val wildCard = EspnParser.scoreboard(fixture("scoreboard-2025-post1.json")).week!!.games
        val after = NflRules.bracket(last.standings, EspnParser.teams(fixture("teams.json"), last.divisions), wildCard)
        assertEquals(listOf("DEN" to "BUF", "NE" to "HOU"), after.afc.divisional.map { it.high to it.low })
        assertTrue(after.afc.divisional.all { it.game == null })
        assertNull(after.afc.championship)
    }

    @Test
    fun `Sunday Ticket carries Sunday afternoon's CBS and FOX games outside the area (E)`() {
        val week16 = EspnParser.scoreboard(fixture("scoreboard-2026-week16.json")).week!!
        fun ticket(id: String, settings: NflSettings = TICKET) = NflRules.sundayTicketGame(week16.games.single { it.id == id }, settings, NY)

        assertTrue(ticket("401873151"))  // NE at NYJ, CBS, 1:00
        assertTrue(ticket("401873150"))  // LAC at MIA, FOX
        assertTrue(ticket("401873153"))  // SF at KC, CBS, 4:25
        assertFalse(ticket("401873154")) // Sunday night, NBC
        assertFalse(ticket("401873155")) // Monday night
        assertFalse(ticket("401873141")) // Christmas, Netflix
        assertFalse(ticket("401873146")) // a flex game, no time yet
        assertFalse(ticket("401873151", TICKET.copy(areaTeams = listOf("NYJ"))))
        assertFalse(ticket("401873151", TICKET.copy(sundayTicket = false)))
        assertFalse(ticket("401873151", TICKET.copy(areaTeams = emptyList())))

        assertTrue(NflRules.sundayTicketGame(game("a", "BUF", "MIA", "2026-12-27T12:00-05:00"), TICKET, NY))
        assertFalse(NflRules.sundayTicketGame(game("a", "BUF", "MIA", "2026-12-27T18:00-05:00"), TICKET, NY))
        assertFalse(NflRules.sundayTicketGame(game("a", "BUF", "MIA", "2026-08-16T13:00-04:00", seasonType = 1), TICKET, NY))
    }

    @Test
    fun `hide scores hides today's games until one is shown (C)`() {
        val hide = NflSettings(hideScores = true)
        val sunday = LocalDate.of(2026, 11, 1)
        fun hidden(id: String, settings: NflSettings = hide, shown: Set<String> = emptySet(), today: LocalDate = sunday) =
            NflRules.scoreHidden(week8.games.single { it.id == id }, settings, shown, today, NY)

        assertTrue(hidden(SNF))
        assertTrue(hidden("401873026"))                        // a final today
        assertFalse(hidden("401873023"))                       // Thursday's
        assertFalse(hidden(MNF))                               // not started: no score to hide
        assertFalse(hidden("401873026", shown = setOf("401873026")))
        assertFalse(hidden(SNF, settings = NflSettings()))
        // After midnight the game still on stays hidden; yesterday's finals don't.
        assertTrue(hidden(SNF, today = sunday.plusDays(1)))
        assertFalse(hidden("401873026", today = sunday.plusDays(1)))

        assertTrue(NflRules.anyScoreHidden(week8, hide, emptySet(), sunday, NY))
        assertFalse(NflRules.anyScoreHidden(week8, hide, week8.games.map { it.id }.toSet(), sunday, NY))
        assertFalse(NflRules.anyScoreHidden(week8, NflSettings(), emptySet(), sunday, NY))
    }

    private fun game(
        id: String,
        away: String,
        home: String,
        kickoff: String,
        state: GameState = GameState.PRE,
        awayScore: Int? = null,
        homeScore: Int? = null,
        period: Int? = null,
        tv: List<String> = listOf("CBS"),
        seasonType: Int = 2,
        known: Boolean = true,
    ) = NflGame(
        id = id, seasonType = seasonType, week = 9, kickoffMillis = at(kickoff), kickoffKnown = known, away = away, home = home,
        awayScore = awayScore, homeScore = homeScore, state = state, period = period, tv = tv,
    )

    private fun week(vararg games: NflGame) = NflWeek(2026, games.first().seasonType, 9, "Week 9", games.toList())

    private fun fixture(name: String) = javaClass.getResource("/nfl/$name")!!.readText()

    private fun at(text: String) = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    private companion object {
        val NY: ZoneId = ZoneId.of("America/New_York")
        const val SNF = "401873035"
        const val MNF = "401873036"
        val TICKET = NflSettings(areaTeams = listOf("PHI"), sundayTicket = true)
    }
}
