package com.phonoodlesramen.actioncenter.nfl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The NFL (Phase 13): what the guide station keeps
 * from ESPN's public scoreboard and standings, and what the TVs copy and draw. The same types go on
 * the wire (`GET /v1/nfl…`) and into a TV's copy, and an unpaired TV makes them itself.
 */

/** Where the season is. [OFF] runs from the day after the Super Bowl to the first preseason game (F8). */
@Serializable
enum class SeasonPhase {
    @SerialName("preseason") PRESEASON,
    @SerialName("regular") REGULAR,
    @SerialName("postseason") POSTSEASON,
    @SerialName("off") OFF,
}

/** ESPN's `status.type.state`: `pre`, `in` or `post`. */
@Serializable
enum class GameState {
    @SerialName("pre") PRE,
    @SerialName("in") LIVE,
    @SerialName("post") FINAL,
}

/** One of the 32 teams, by ESPN's abbreviation ("PHI", "WSH"). */
@Serializable
data class NflTeam(
    val abbr: String,
    /** "Eagles". */
    val name: String,
    /** "Philadelphia". */
    val location: String,
    /** Its colours as `#RRGGBB`. */
    val color: String,
    val altColor: String,
    /** "AFC" or "NFC". */
    val conference: String,
    /** "East", "North", "South" or "West". */
    val division: String,
) {
    val fullName get() = "$location $name"
    val divisionName get() = "$conference $division"
}

@Serializable
data class NflGame(
    /** ESPN's event id. */
    val id: String,
    /** 1 preseason, 2 regular season, 3 postseason. */
    val seasonType: Int,
    /**
     * The week within [seasonType], as ESPN numbers it. The postseason's numbers differ by season
     * (2025: 4 the Pro Bowl, 5 the Super Bowl; 2026: 4 the Super Bowl), so [NflState.calendar] names them.
     */
    val week: Int,
    val kickoffMillis: Long,
    /** False while the league hasn't set the time (ESPN's "TBD", weeks 16-18). */
    val kickoffKnown: Boolean = true,
    val away: String,
    val home: String,
    val awayScore: Int? = null,
    val homeScore: Int? = null,
    /** The records ESPN shows with the game, "2-5" or "1-4-1". */
    val awayRecord: String? = null,
    val homeRecord: String? = null,
    val state: GameState,
    /** ESPN's short status: "1:45 - 3rd", "Final/OT", "11/2 - 8:15 PM EST". */
    val detail: String = "",
    val period: Int? = null,
    /** The game clock, "1:45"; it moves only between plays. */
    val clock: String? = null,
    /** The winner's abbreviation once it's over; null for a tie or a game still to finish. */
    val winner: String? = null,
    /** Who has the ball, while it's on. */
    val possession: String? = null,
    /** "2nd & Goal at PHI 7", while it's on. */
    val downDistance: String? = null,
    val redZone: Boolean = false,
    val lastPlay: String? = null,
    /** TV networks as ESPN names them: "CBS", "Fox", "NBC", "ESPN", "ABC", "NFL Net". */
    val tv: List<String> = emptyList(),
    /** Streaming services: "Prime Video", "Netflix", "Peacock", "YouTube", "Paramount+", "NFL+". */
    val streaming: List<String> = emptyList(),
    val venue: String? = null,
    /** A postseason game's round, "AFC Wild Card Playoffs"; a neutral-site game's note. */
    val headline: String? = null,
    /** Points by quarter, overtime last. */
    val awayLineScores: List<Int> = emptyList(),
    val homeLineScores: List<Int> = emptyList(),
) {
    fun involves(abbr: String) = away == abbr || home == abbr
}

/**
 * A week in the season's calendar, as ESPN lists it: what ‹ Week and Week › step through. The
 * Pro Bowl isn't one.
 */
@Serializable
data class NflWeekRef(
    val seasonType: Int,
    val week: Int,
    /** "Hall of Fame Weekend", "Preseason Week 1", "Week 8", "Wild Card", "Super Bowl". */
    val label: String,
    val startMillis: Long,
    val endMillis: Long,
)

/** One week's games, as the scoreboard gives them. */
@Serializable
data class NflWeek(
    val seasonYear: Int,
    val seasonType: Int,
    val week: Int,
    /** "Week 8", "Preseason Week 2", "Wild Card". */
    val label: String,
    val games: List<NflGame>,
    /** Teams on a bye this week. */
    val byes: List<String> = emptyList(),
)

/** A team's line in the standings. */
@Serializable
data class NflStanding(
    val abbr: String,
    val wins: Int,
    val losses: Int,
    val ties: Int = 0,
    /** ".714", as ESPN shows it. */
    val winPercent: String,
    /** 1-16 within the conference: 1-4 the division leaders, 5-7 the wild cards. */
    val seed: Int? = null,
    /** 1-4 within the division. */
    val divisionRank: Int,
    /** "W3", "L1". */
    val streak: String? = null,
    /** Games behind the division's leader, "1.5"; "-" for the leader. */
    val gamesBehind: String? = null,
    val divisionRecord: String? = null,
    val pointsFor: Int = 0,
    val pointsAgainst: Int = 0,
    val differential: Int = 0,
    /** ESPN's clinch mark once there is one: "*", "z", "y", "x" or "e". */
    val clincher: String? = null,
) {
    val record get() = if (ties > 0) "$wins-$losses-$ties" else "$wins-$losses"
}

/** The house's NFL settings, kept by the station (B), or by an unpaired TV. */
@Serializable
data class NflSettings(
    /** Your teams (F5): their games lead the card and the row, and a star marks them. */
    val yourTeams: List<String> = emptyList(),
    /** Your area's teams (E): their Sunday afternoon games are on your CBS and FOX. */
    val areaTeams: List<String> = emptyList(),
    /** The house has NFL Sunday Ticket on YouTube TV (F6). */
    val sundayTicket: Boolean = false,
    /** Scores hidden until asked for, game by game (F10, C). */
    val hideScores: Boolean = false,
    /**
     * Settings › NFL › Show the NFL (O6): null until it's chosen. Read [showing], not this.
     * While it's off, nothing asks ESPN: not the station, not an unpaired TV.
     */
    val show: Boolean? = null,
) {
    /**
     * Whether the house has the NFL: [show] once it's chosen; before that, whether the house
     * already uses it (its teams, Sunday Ticket or Hide scores). A new house starts with it
     * off; one that set the NFL up before the switch keeps it on.
     */
    val showing: Boolean get() = show ?: (yourTeams.isNotEmpty() || areaTeams.isNotEmpty() || sundayTicket || hideScores)
}

/** `GET /v1/nfl`: everything the home and the NFL screen draw. */
@Serializable
data class NflState(
    /** Goes up whenever anything in it changes; `ETag` and `GET /v1/nfl/changes` follow it. */
    val version: Long,
    val updatedAtMillis: Long,
    val phase: SeasonPhase,
    val seasonYear: Int,
    val teams: List<NflTeam>,
    /** The current week; null in the off-season. */
    val week: NflWeek? = null,
    /** The season's weeks, preseason to the Super Bowl, in order. */
    val calendar: List<NflWeekRef> = emptyList(),
    /** In the preseason, last season's final standings (D). */
    val standings: List<NflStanding> = emptyList(),
    /** The season [standings] belong to. */
    val standingsSeason: Int = seasonYear,
    /** The standings as they were at 4 AM, drawn while today's scores are hidden (C). */
    val morningStandings: List<NflStanding> = emptyList(),
    val settings: NflSettings = NflSettings(),
    /** Why the last fetch failed, while it keeps failing; the copy stays. */
    val problem: String? = null,
)

/** `GET /v1/nfl/teams/{abbr}/schedule`: a team's season. */
@Serializable
data class NflTeamSchedule(val abbr: String, val seasonYear: Int, val games: List<NflGame>)

/** A team stat beside the other side's: "Total Yards", "257". */
@Serializable
data class NflStat(val label: String, val value: String)

/** `GET /v1/nfl/games/{id}`: the game with both sides' team stats, from ESPN's summary. */
@Serializable
data class NflGameDetail(val game: NflGame, val awayStats: List<NflStat> = emptyList(), val homeStats: List<NflStat> = emptyList())
