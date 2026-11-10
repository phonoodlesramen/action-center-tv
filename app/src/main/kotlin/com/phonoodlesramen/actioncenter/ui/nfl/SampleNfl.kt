package com.phonoodlesramen.actioncenter.ui.nfl

import com.phonoodlesramen.actioncenter.home.ChannelButton
import com.phonoodlesramen.actioncenter.nfl.GameState
import com.phonoodlesramen.actioncenter.nfl.NflController
import com.phonoodlesramen.actioncenter.nfl.NflFrom
import com.phonoodlesramen.actioncenter.nfl.NflGame
import com.phonoodlesramen.actioncenter.nfl.NflGameDetail
import com.phonoodlesramen.actioncenter.nfl.NflLoad
import com.phonoodlesramen.actioncenter.nfl.NflSettings
import com.phonoodlesramen.actioncenter.nfl.NflStanding
import com.phonoodlesramen.actioncenter.nfl.NflStat
import com.phonoodlesramen.actioncenter.nfl.NflState
import com.phonoodlesramen.actioncenter.nfl.NflTeam
import com.phonoodlesramen.actioncenter.nfl.NflTeamSchedule
import com.phonoodlesramen.actioncenter.nfl.NflView
import com.phonoodlesramen.actioncenter.nfl.NflWeek
import com.phonoodlesramen.actioncenter.nfl.NflWeekRef
import com.phonoodlesramen.actioncenter.nfl.SeasonPhase
import com.phonoodlesramen.actioncenter.nfl.WatchApp
import com.phonoodlesramen.actioncenter.nfl.WatchOptions
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * A made-up NFL for previews and screenshot tests (Phase 13): Week 8 of 2026 on Sunday night 1
 * November, in the samples' zone (UTC, so the times read as the house's). The 32 teams are real;
 * every score, record and standing is made up, and no player is named. The Eagles are your team,
 * the house has Sunday Ticket, and Eagles at Commanders is on (NBC); Bears at Seahawks is Monday
 * night (ESPN).
 */
object SampleNfl {
    /** Sunday 1 November 2026, 9:12 PM: the Sunday night game is in the 3rd quarter. */
    val sundayNight: LocalDateTime = LocalDateTime.of(2026, 11, 1, 21, 12)
    val nowMillis: Long = sundayNight.millis()

    private fun LocalDateTime.millis() = toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0, year: Int = 2026) = LocalDateTime.of(year, month, day, hour, minute).millis()

    /** abbr, location, name, colour, second colour, conference, division. */
    val teams: List<NflTeam> = listOf(
        team("BUF", "Buffalo", "Bills", "00338D", "D50A0A", "AFC", "East"),
        team("MIA", "Miami", "Dolphins", "008E97", "FC4C02", "AFC", "East"),
        team("NE", "New England", "Patriots", "002A5C", "C60C30", "AFC", "East"),
        team("NYJ", "New York", "Jets", "115740", "FFFFFF", "AFC", "East"),
        team("BAL", "Baltimore", "Ravens", "29126F", "000000", "AFC", "North"),
        team("CIN", "Cincinnati", "Bengals", "FB4F14", "000000", "AFC", "North"),
        team("CLE", "Cleveland", "Browns", "472A08", "FF3C00", "AFC", "North"),
        team("PIT", "Pittsburgh", "Steelers", "000000", "FFB612", "AFC", "North"),
        team("HOU", "Houston", "Texans", "021018", "EB0028", "AFC", "South"),
        team("IND", "Indianapolis", "Colts", "003B75", "FFFFFF", "AFC", "South"),
        team("JAX", "Jacksonville", "Jaguars", "007487", "D7A22A", "AFC", "South"),
        team("TEN", "Tennessee", "Titans", "4495D2", "001532", "AFC", "South"),
        team("DEN", "Denver", "Broncos", "0A2343", "FC4C02", "AFC", "West"),
        team("KC", "Kansas City", "Chiefs", "E31837", "FFB612", "AFC", "West"),
        team("LV", "Las Vegas", "Raiders", "000000", "A5ACAF", "AFC", "West"),
        team("LAC", "Los Angeles", "Chargers", "0080C6", "FFC20E", "AFC", "West"),
        team("DAL", "Dallas", "Cowboys", "002A5C", "B0B7BC", "NFC", "East"),
        team("NYG", "New York", "Giants", "003C7F", "C9243F", "NFC", "East"),
        team("PHI", "Philadelphia", "Eagles", "06424D", "000000", "NFC", "East"),
        team("WSH", "Washington", "Commanders", "5A1414", "FFB612", "NFC", "East"),
        team("CHI", "Chicago", "Bears", "0B1C3A", "E64100", "NFC", "North"),
        team("DET", "Detroit", "Lions", "0076B6", "BBBBBB", "NFC", "North"),
        team("GB", "Green Bay", "Packers", "204E32", "FFB612", "NFC", "North"),
        team("MIN", "Minnesota", "Vikings", "4F2683", "FFC62F", "NFC", "North"),
        team("ATL", "Atlanta", "Falcons", "A71930", "000000", "NFC", "South"),
        team("CAR", "Carolina", "Panthers", "0085CA", "000000", "NFC", "South"),
        team("NO", "New Orleans", "Saints", "D3BC8D", "000000", "NFC", "South"),
        team("TB", "Tampa Bay", "Buccaneers", "BD1C36", "3E3A35", "NFC", "South"),
        team("ARI", "Arizona", "Cardinals", "A40227", "FFFFFF", "NFC", "West"),
        team("LAR", "Los Angeles", "Rams", "003594", "FFD100", "NFC", "West"),
        team("SF", "San Francisco", "49ers", "AA0000", "B3995D", "NFC", "West"),
        team("SEA", "Seattle", "Seahawks", "002A5C", "69BE28", "NFC", "West"),
    )

    private fun team(abbr: String, location: String, name: String, color: String, alt: String, conference: String, division: String) =
        NflTeam(abbr, name, location, "#$color", "#$alt", conference, division)

    /**
     * Made-up standings after Week 8: by division, leader first, with each conference's seeds
     * (1-4 the leaders, 5-7 the wild cards). abbr, wins, losses, ties, seed, streak, points for and against.
     */
    val standings: List<NflStanding> = listOf(
        // AFC East
        standing("BUF", 6, 1, 0, 1, 1, "W3", 212, 150),
        standing("NE", 5, 3, 0, 7, 2, "W1", 171, 160),
        standing("MIA", 2, 6, 0, 13, 3, "L2", 140, 190),
        standing("NYJ", 2, 6, 0, 14, 4, "L1", 150, 201),
        // AFC North
        standing("PIT", 6, 2, 0, 3, 1, "W4", 180, 141),
        standing("CIN", 5, 2, 0, 6, 2, "W2", 190, 160),
        standing("CLE", 5, 3, 0, 8, 3, "L1", 139, 150),
        standing("BAL", 4, 4, 0, 9, 4, "L2", 170, 172),
        // AFC South
        standing("JAX", 5, 2, 0, 4, 1, "W2", 182, 120),
        standing("HOU", 3, 4, 0, 11, 2, "W1", 160, 166),
        standing("IND", 2, 6, 0, 12, 3, "L3", 150, 199),
        standing("TEN", 1, 7, 0, 15, 4, "L2", 128, 200),
        // AFC West
        standing("KC", 6, 2, 0, 2, 1, "L1", 170, 120),
        standing("DEN", 6, 2, 0, 5, 2, "W3", 160, 140),
        standing("LV", 4, 4, 0, 10, 3, "W1", 188, 198),
        standing("LAC", 0, 8, 0, 16, 4, "L8", 101, 180),
        // NFC East
        standing("NYG", 4, 2, 1, 3, 1, "W1", 161, 158),
        standing("DAL", 3, 5, 0, 12, 2, "L1", 190, 192),
        standing("PHI", 2, 5, 0, 15, 3, "L5", 128, 172),
        standing("WSH", 1, 5, 1, 16, 4, "L1", 130, 170),
        // NFC North
        standing("MIN", 6, 2, 0, 2, 1, "L1", 140, 96),
        standing("DET", 6, 2, 0, 5, 2, "W3", 200, 170),
        standing("CHI", 5, 2, 0, 6, 3, "W2", 182, 140),
        standing("GB", 5, 3, 0, 7, 4, "W1", 176, 160),
        // NFC South
        standing("TB", 4, 4, 0, 4, 1, "W2", 172, 150),
        standing("CAR", 3, 5, 0, 10, 2, "L3", 172, 196),
        standing("ATL", 3, 5, 0, 11, 3, "L1", 168, 180),
        standing("NO", 2, 5, 0, 14, 4, "L1", 160, 194),
        // NFC West
        standing("SF", 6, 1, 0, 1, 1, "L1", 200, 138),
        standing("SEA", 4, 3, 0, 8, 2, "W1", 170, 130),
        standing("LAR", 4, 4, 0, 9, 3, "W2", 182, 160),
        standing("ARI", 3, 5, 0, 13, 4, "W1", 160, 210),
    ).let { list ->
        // Games behind the division's leader.
        list.map { s ->
            val leader = list.first { teams.first { t -> t.abbr == it.abbr }.divisionName == teams.first { t -> t.abbr == s.abbr }.divisionName }
            val behind = ((leader.wins - s.wins) + (s.losses - leader.losses)) / 2.0
            s.copy(gamesBehind = if (s.abbr == leader.abbr) "-" else if (behind % 1.0 == 0.0) behind.toInt().toString() else behind.toString())
        }
    }

    private fun standing(abbr: String, wins: Int, losses: Int, ties: Int, seed: Int, divisionRank: Int, streak: String, pf: Int, pa: Int): NflStanding {
        val games = wins + losses + ties
        val pct = (wins + ties / 2.0) / games
        return NflStanding(
            abbr = abbr, wins = wins, losses = losses, ties = ties,
            winPercent = if (pct >= 1.0) "1.000" else ".%03d".format(Math.round(pct * 1000)),
            seed = seed, divisionRank = divisionRank, streak = streak, gamesBehind = null,
            divisionRecord = "${(wins / 3).coerceAtMost(3)}-${(losses / 3).coerceAtMost(3)}",
            pointsFor = pf, pointsAgainst = pa, differential = pf - pa,
        )
    }

    private fun record(abbr: String) = standings.first { it.abbr == abbr }.record

    private fun final(id: String, away: String, home: String, kickoff: Long, awayScore: Int, homeScore: Int, network: String, overtime: Boolean = false, streaming: Boolean = false) = NflGame(
        id = id, seasonType = 2, week = 8, kickoffMillis = kickoff, away = away, home = home,
        awayScore = awayScore, homeScore = homeScore, awayRecord = record(away), homeRecord = record(home),
        state = GameState.FINAL, detail = if (overtime) "Final/OT" else "Final", period = if (overtime) 5 else 4, clock = "0:00",
        winner = if (awayScore > homeScore) away else home,
        tv = if (streaming) emptyList() else listOf(network), streaming = if (streaming) listOf(network) else emptyList(),
        venue = VENUES[home],
        awayLineScores = quarters(awayScore, overtime), homeLineScores = quarters(homeScore, overtime),
    )

    /** A made-up split of [score] by quarter. */
    private fun quarters(score: Int, overtime: Boolean): List<Int> {
        val q = listOf(score * 3 / 10, score * 2 / 10, score * 2 / 10)
        val rest = score - q.sum()
        return if (overtime) q + (rest - 3).coerceAtLeast(0) + (3.coerceAtMost(rest)) else q + rest
    }

    /** Eagles at Commanders, live in the 3rd: Washington has the ball in the red zone. */
    val live = NflGame(
        id = "401873035", seasonType = 2, week = 8, kickoffMillis = at(11, 1, 20, 20), away = "PHI", home = "WSH",
        awayScore = 14, homeScore = 17, awayRecord = "2-5", homeRecord = "1-5-1",
        state = GameState.LIVE, detail = "1:45 - 3rd", period = 3, clock = "1:45",
        possession = "WSH", downDistance = "2nd & Goal at PHI 7", redZone = true,
        lastPlay = "A. Quill up the middle to PHI 7 for 2 yards (B. Marsh).",
        tv = listOf("NBC"), venue = "Northwest Stadium",
        awayLineScores = listOf(7, 7, 0), homeLineScores = listOf(3, 7, 7),
    )

    /** Bears at Seahawks, Monday night on ESPN. */
    val monday = NflGame(
        id = "401873036", seasonType = 2, week = 8, kickoffMillis = at(11, 2, 20, 15), away = "CHI", home = "SEA",
        awayRecord = "5-2", homeRecord = "4-3", state = GameState.PRE, detail = "11/2 - 8:15 PM EST",
        tv = listOf("ESPN", "ABC"), venue = "Lumen Field",
    )

    private val VENUES = mapOf(
        "GB" to "Lambeau Field", "BUF" to "Highmark Stadium", "CIN" to "Paycor Stadium", "DAL" to "AT&T Stadium", "DET" to "Ford Field",
        "NYJ" to "MetLife Stadium", "PIT" to "Acrisure Stadium", "TB" to "Raymond James Stadium", "JAX" to "EverBank Stadium",
        "LAR" to "SoFi Stadium", "DEN" to "Empower Field at Mile High", "MIA" to "Hard Rock Stadium",
    )

    val games: List<NflGame> = listOf(
        final("401873023", "CAR", "GB", at(10, 29, 20, 15), 17, 24, "Prime Video", streaming = true),
        final("401873026", "BAL", "BUF", at(11, 1, 13), 13, 31, "CBS"),
        final("401873031", "TEN", "CIN", at(11, 1, 13), 30, 33, "CBS", overtime = true),
        final("401873024", "ARI", "DAL", at(11, 1, 13), 27, 24, "Fox", overtime = true),
        final("401873030", "MIN", "DET", at(11, 1, 13), 17, 20, "Fox"),
        final("401873029", "LV", "NYJ", at(11, 1, 13), 34, 28, "Fox"),
        final("401873027", "CLE", "PIT", at(11, 1, 13), 10, 24, "CBS"),
        final("401873025", "ATL", "TB", at(11, 1, 13), 3, 20, "Fox"),
        final("401873028", "IND", "JAX", at(11, 1, 13), 14, 38, "CBS"),
        final("401873032", "LAC", "LAR", at(11, 1, 16, 5), 12, 27, "Fox"),
        final("401873033", "KC", "DEN", at(11, 1, 16, 25), 21, 24, "CBS"),
        final("401873034", "NE", "MIA", at(11, 1, 16, 25), 27, 17, "CBS"),
        live,
        monday,
    )

    val week = NflWeek(2026, 2, 8, "Week 8", games, byes = listOf("NO", "NYG", "SF", "HOU"))

    /** The 2026 season's calendar: the preseason, 18 weeks from Wednesday 9 September, and the playoffs. */
    val calendar: List<NflWeekRef> = buildList {
        val day = 24 * 60 * 60_000L
        add(NflWeekRef(1, 1, "Hall of Fame Weekend", at(8, 5, 0), at(8, 12, 0) - 1))
        (1..3).forEach { add(NflWeekRef(1, it + 1, "Preseason Week $it", at(8, 12, 0) + (it - 1) * 7 * day, at(8, 12, 0) + it * 7 * day - 1)) }
        val weekOne = at(9, 9, 0)
        (1..18).forEach { add(NflWeekRef(2, it, "Week $it", weekOne + (it - 1) * 7 * day, weekOne + it * 7 * day - 1)) }
        val wildCard = at(1, 13, 0, year = 2027)
        listOf("Wild Card", "Divisional Round", "Conference Championship", "Super Bowl").forEachIndexed { index, label ->
            val start = wildCard + index * 7 * day
            add(NflWeekRef(3, index + 1, label, start, start + (if (index == 3) 14 else 7) * day - 1))
        }
    }

    val settings = NflSettings(yourTeams = listOf("PHI"), areaTeams = listOf("PHI"), sundayTicket = true, hideScores = false)

    fun state(settings: NflSettings = this.settings, week: NflWeek? = this.week, phase: SeasonPhase = SeasonPhase.REGULAR) = NflState(
        version = 57,
        updatedAtMillis = nowMillis - 20_000,
        phase = phase,
        seasonYear = 2026,
        teams = teams,
        week = week,
        calendar = calendar,
        standings = standings,
        standingsSeason = 2026,
        morningStandings = standings,
        settings = settings,
    )

    /** The copy as the home and the NFL screen draw it: from the guide station, checked 20 s ago. */
    fun view(settings: NflSettings = this.settings, shown: Set<String> = emptySet()) = NflView(
        state = state(settings),
        from = NflFrom.STATION,
        asOfMillis = nowMillis - 20_000,
        shown = shown,
        shownDay = LocalDate.of(2026, 11, 1),
        zone = ZoneOffset.UTC,
    )

    /** The Live row's networks (YouTube TV's row: NBC, CBS, FOX, ABC, ESPN) and the apps on the Shield. */
    val watch = WatchOptions(
        channels = listOf("NBC", "CBS", "FOX", "ABC", "ESPN").mapIndexed { index, name ->
            ChannelButton("youtube-tv-$index", name, "https://tv.youtube.com/watch/sample-$name", source = ChannelButton.Source.YOUTUBE_TV)
        },
        installed = listOf(WatchApp.YOUTUBE_TV, WatchApp.PRIME_VIDEO, WatchApp.NETFLIX, WatchApp.PARAMOUNT_PLUS, WatchApp.DISNEY_PLUS, WatchApp.YOUTUBE)
            .mapTo(HashSet()) { it.packageName },
    )

    /** The Eagles' 2026 season so far (made up): results to Week 7, Week 8 on, then the games to come; the bye in Week 10. */
    val eagles = NflTeamSchedule(
        "PHI", 2026,
        listOf(
            scheduled(1, "WSH", home = true, at(9, 13, 16, 25), "Fox", 24 to 21),
            scheduled(2, "TEN", home = false, at(9, 20, 13), "Fox", 27 to 20),
            scheduled(3, "CHI", home = false, at(9, 28, 20, 15), "ESPN", 10 to 24),
            scheduled(4, "LAR", home = true, at(10, 4, 13), "Fox", 20 to 23),
            scheduled(5, "JAX", home = false, at(10, 11, 9, 30), "NFL Net", 14 to 17),
            scheduled(6, "CAR", home = true, at(10, 18, 13), "CBS", 13 to 27),
            scheduled(7, "DAL", home = true, at(10, 26, 20, 15), "ESPN", 20 to 34),
            live,
            scheduled(9, "NYG", home = true, at(11, 8, 13), "Fox"),
            scheduled(11, "PIT", home = true, at(11, 22, 16, 25), "CBS"),
            scheduled(12, "DAL", home = false, at(11, 26, 16, 30), "Fox"),
            scheduled(13, "ARI", home = false, at(12, 6, 16, 5), "Fox"),
            scheduled(14, "IND", home = true, at(12, 13, 13), "Fox"),
            scheduled(15, "SEA", home = true, at(12, 19, 17), "Fox"),
            scheduled(16, "HOU", home = true, at(12, 24, 20, 15), "Prime Video"),
            scheduled(17, "SF", home = false, at(1, 3, 20, 20, year = 2027), "NBC"),
            scheduled(18, "NYG", home = false, at(1, 10, 13, year = 2027), null, known = false),
        ),
    )

    private fun scheduled(week: Int, opponent: String, home: Boolean, kickoff: Long, network: String?, score: Pair<Int, Int>? = null, known: Boolean = true): NflGame {
        val (away, homeTeam) = if (home) opponent to "PHI" else "PHI" to opponent
        val streaming = network == "Prime Video"
        return NflGame(
            id = "phi-$week", seasonType = 2, week = week, kickoffMillis = kickoff, kickoffKnown = known, away = away, home = homeTeam,
            awayScore = score?.let { if (home) it.second else it.first }, homeScore = score?.let { if (home) it.first else it.second },
            state = if (score != null) GameState.FINAL else GameState.PRE, detail = if (score != null) "Final" else "",
            winner = score?.let { (mine, theirs) -> if (mine > theirs) "PHI" else opponent },
            tv = if (network != null && !streaming) listOf(network) else emptyList(), streaming = if (streaming) listOf(network!!) else emptyList(),
        )
    }

    /** Eagles at Commanders' team stats (made up). */
    val liveDetail = NflGameDetail(
        live,
        awayStats = stats("12", "188", "130", "58", "1", "21:40"),
        homeStats = stats("14", "241", "152", "89", "0", "20:54"),
    )

    private fun stats(vararg values: String) =
        listOf("1st Downs", "Total Yards", "Passing", "Rushing", "Turnovers", "Possession").zip(values) { label, value -> NflStat(label, value) }

    /** The NFL screen's lookups, from the sample: the Eagles' season and the live game's stats; anything else is loading forever. */
    open class Controller(view: NflView = view()) : NflController.Fixed(view) {
        override suspend fun schedule(abbr: String, seasonYear: Int): NflLoad<NflTeamSchedule> =
            if (abbr == "PHI") NflLoad.Loaded(eagles) else NflLoad.Failed("ESPN has no schedule for $abbr then.")

        override suspend fun game(id: String): NflLoad<NflGameDetail> =
            if (id == live.id) NflLoad.Loaded(liveDetail) else NflLoad.Failed("ESPN has no game with that id.")
    }
}
