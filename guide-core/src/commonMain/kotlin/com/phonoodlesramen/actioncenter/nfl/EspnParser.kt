package com.phonoodlesramen.actioncenter.nfl

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** A scoreboard: its week (null when ESPN has no games for it) and the season's calendar. */
data class EspnScoreboard(val seasonYear: Int, val week: NflWeek?, val calendar: List<NflWeekRef>)

/** A team's conference and division, "AFC" and "East": the standings say, the teams list doesn't. */
data class NflDivision(val conference: String, val division: String)

/** A season's standings, and each team's [NflDivision]. */
data class EspnStandings(val seasonYear: Int, val standings: List<NflStanding>, val divisions: Map<String, NflDivision>)

/**
 * ESPN's public site API (read 1-2 Nov 2026) to the model (`Nfl.kt`). Lenient: unknown fields
 * are ignored and nearly every field is optional, so one ESPN drops leaves a default rather than
 * a failure. Betting odds are never read; the launcher is ad-free.
 *
 * The same things come in different shapes from different calls:
 * - a score is a string on the scoreboard and in a summary, `{value, displayValue}` in a team's schedule;
 * - line scores are floats (`value`) on the scoreboard, `displayValue` strings only in a summary;
 * - who has the ball is a team id in the scoreboard's `situation`, a flag on the competitor in a summary;
 * - the week is `{number}` on an event, a bare number in a summary's header;
 * - networks are `geoBroadcasts` on the scoreboard, `broadcasts` (the same shape) elsewhere.
 */
object EspnParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** The team stats a game's details keep, by ESPN's labels, in this order. */
    val STAT_LABELS = listOf("1st Downs", "Total Yards", "Passing", "Rushing", "Turnovers", "Possession")

    /** `…/scoreboard`, for the current week or `?dates=<year>&seasontype=<type>&week=<n>`. */
    fun scoreboard(text: String): EspnScoreboard {
        val board = json.decodeFromString<ScoreboardJson>(text)
        val league = board.leagues.firstOrNull()
        val calendar = calendar(league?.calendar.orEmpty())
        val first = board.events.firstOrNull()
        val year = board.season?.year ?: league?.season?.year ?: first?.season?.year ?: 0
        val type = board.season?.type ?: first?.season?.type
        val number = board.week?.number ?: first?.week?.let(::weekNumber)
        val week = if (type == null || number == null) {
            null
        } else {
            NflWeek(
                seasonYear = year,
                seasonType = type,
                week = number,
                label = calendar.firstOrNull { it.seasonType == type && it.week == number }?.label ?: label(type, number),
                games = board.events.mapNotNull { game(it, type, number) },
                byes = board.week?.teamsOnBye.orEmpty().mapNotNull { it.abbreviation },
            )
        }
        return EspnScoreboard(year, week, calendar)
    }

    /**
     * `…/standings?level=3&sort=playoffseed:asc`: conferences, then divisions. Each team's
     * [NflStanding.divisionRank] is its place by seed within its division: ESPN's own order put
     * 2025's `*` clinchers (Denver and Seattle, both 1st seeds) last in their divisions.
     */
    fun standings(text: String): EspnStandings {
        val root = json.decodeFromString<StandingsJson>(text)
        val standings = mutableListOf<NflStanding>()
        val divisions = mutableMapOf<String, NflDivision>()
        for (conference in root.children) {
            val conferenceName = conference.abbreviation ?: conference.name.orEmpty()
            // level=3 nests divisions; level=2 puts the conference's standings on it directly.
            val groups = conference.children.ifEmpty { listOf(conference) }
            for (group in groups) {
                val division = if (group === conference) "" else group.name.orEmpty().removePrefix("$conferenceName ").trim()
                val entries = group.standings?.entries.orEmpty().mapIndexedNotNull { index, entry ->
                    val abbr = entry.team?.abbreviation ?: return@mapIndexedNotNull null
                    Triple(abbr, index, entry.stats.associateBy { it.name })
                }
                val ranked = entries.sortedWith(compareBy({ seed(it.third) ?: Int.MAX_VALUE }, { it.second }))
                ranked.forEachIndexed { rank, (abbr, _, stats) ->
                    divisions[abbr] = NflDivision(conferenceName, division)
                    standings += NflStanding(
                        abbr = abbr,
                        wins = stats.int("wins"),
                        losses = stats.int("losses"),
                        ties = stats.int("ties"),
                        winPercent = stats.text("winPercent") ?: ".000",
                        seed = seed(stats),
                        divisionRank = rank + 1,
                        streak = stats.text("streak"),
                        gamesBehind = stats.text("gamesBehind"),
                        divisionRecord = stats.text("divisionRecord"),
                        pointsFor = stats.int("pointsFor"),
                        pointsAgainst = stats.int("pointsAgainst"),
                        differential = stats.int("differential"),
                        clincher = stats.text("clincher"),
                    )
                }
            }
        }
        val year = root.season?.year ?: root.children.firstNotNullOfOrNull { it.children.firstOrNull()?.standings?.season } ?: 0
        return EspnStandings(year, standings, divisions)
    }

    /** `…/teams`: the 32, with their colours, and each one's division from [divisions] (the standings). */
    fun teams(text: String, divisions: Map<String, NflDivision>): List<NflTeam> {
        val root = json.decodeFromString<TeamsJson>(text)
        return root.sports.firstOrNull()?.leagues?.firstOrNull()?.teams.orEmpty().mapNotNull { wrapper ->
            val team = wrapper.team ?: return@mapNotNull null
            val abbr = team.abbreviation ?: return@mapNotNull null
            val division = divisions[abbr]
            NflTeam(
                abbr = abbr,
                name = team.name ?: team.nickname ?: abbr,
                location = team.location.orEmpty(),
                color = color(team.color, DEFAULT_COLOR),
                altColor = color(team.alternateColor, DEFAULT_ALT_COLOR),
                conference = division?.conference.orEmpty(),
                division = division?.division.orEmpty(),
            )
        }
    }

    /** `…/teams/{abbr}/schedule?season=&seasontype=`: a team's games, results and those to come. */
    fun schedule(text: String): NflTeamSchedule {
        val root = json.decodeFromString<ScheduleJson>(text)
        val type = root.requestedSeason?.type ?: root.season?.type ?: REGULAR
        val games = root.events.mapNotNull { event ->
            game(event, event.seasonType?.type ?: type, event.week?.let(::weekNumber) ?: 0)
        }
        return NflTeamSchedule(
            abbr = root.team?.abbreviation.orEmpty(),
            seasonYear = root.requestedSeason?.year ?: root.season?.year ?: 0,
            games = games,
        )
    }

    /** `…/summary?event=`: the game, and both sides' [STAT_LABELS] from the box score. */
    fun summary(text: String): NflGameDetail {
        val root = json.decodeFromString<SummaryJson>(text)
        val header = root.header ?: throw IllegalArgumentException("A summary without its header")
        val event = EventJson(
            id = header.id ?: throw IllegalArgumentException("A summary without its game's id"),
            timeValid = header.timeValid,
            competitions = header.competitions,
        )
        val game = game(
            event, header.season?.type ?: REGULAR, header.week?.let(::weekNumber) ?: 0,
            situation = root.situation, venue = root.gameInfo?.venue?.fullName,
        ) ?: throw IllegalArgumentException("A summary without its game")
        fun stats(side: BoxTeamJson?) = STAT_LABELS.mapNotNull { label ->
            side?.statistics?.firstOrNull { it.label == label }?.displayValue?.let { NflStat(label, it) }
        }
        val teams = root.boxscore?.teams.orEmpty()
        val away = teams.firstOrNull { it.homeAway == "away" } ?: teams.firstOrNull { it.team?.abbreviation == game.away }
        val home = teams.firstOrNull { it.homeAway == "home" } ?: teams.firstOrNull { it.team?.abbreviation == game.home }
        return NflGameDetail(game, awayStats = stats(away), homeStats = stats(home))
    }

    private fun game(
        event: EventJson,
        seasonType: Int,
        week: Int,
        situation: SituationJson? = null,
        venue: String? = null,
    ): NflGame? {
        val competition = event.competitions.firstOrNull() ?: return null
        val kickoff = millis(event.date ?: competition.date) ?: return null
        val status = competition.status ?: event.status
        val state = when (status?.type?.state) {
            "in" -> GameState.LIVE
            "post" -> GameState.FINAL
            else -> GameState.PRE
        }
        val sides = competition.competitors
        val away = sides.firstOrNull { it.homeAway == "away" } ?: sides.getOrNull(1)
        val home = sides.firstOrNull { it.homeAway == "home" } ?: sides.getOrNull(0)
        val live = state == GameState.LIVE
        val play = competition.situation ?: situation
        val byId = sides.associateBy({ it.team?.id ?: it.id }, { it.abbr })
        val possession = play?.possession?.let { byId[it] } ?: sides.firstOrNull { it.possession.isTrue() }?.abbr
        val (tv, streaming) = networks(competition)
        return NflGame(
            id = event.id,
            seasonType = event.season?.type ?: event.seasonType?.type ?: seasonType,
            week = event.week?.let(::weekNumber) ?: week,
            kickoffMillis = kickoff,
            kickoffKnown = competition.timeValid ?: event.timeValid ?: true,
            away = away?.abbr ?: TBD,
            home = home?.abbr ?: TBD,
            awayScore = away?.takeIf { state != GameState.PRE }?.let { score(it.score) },
            homeScore = home?.takeIf { state != GameState.PRE }?.let { score(it.score) },
            awayRecord = away?.record(),
            homeRecord = home?.record(),
            state = state,
            detail = status?.type?.shortDetail ?: status?.type?.detail.orEmpty(),
            period = status?.period?.takeIf { it > 0 && state != GameState.PRE },
            clock = status?.displayClock?.takeIf { live },
            winner = if (state == GameState.FINAL) sides.firstOrNull { it.winner == true }?.abbr else null,
            possession = possession?.takeIf { live },
            downDistance = play?.downDistanceText?.takeIf { live && it.isNotBlank() },
            redZone = live && play?.isRedZone == true,
            lastPlay = play?.lastPlay?.text?.takeIf { it.isNotBlank() },
            tv = tv,
            streaming = streaming,
            venue = competition.venue?.fullName ?: venue,
            headline = competition.notes.firstNotNullOfOrNull { note -> note.headline?.takeIf { it.isNotBlank() } },
            awayLineScores = away?.linescores.orEmpty().map { it.points() },
            homeLineScores = home?.linescores.orEmpty().map { it.points() },
        )
    }

    /** TV and streaming, by `type.shortName` ("TV", "Streaming"); a bare `names` list is TV. */
    private fun networks(competition: CompetitionJson): Pair<List<String>, List<String>> {
        val tv = mutableListOf<String>()
        val streaming = mutableListOf<String>()
        val typed = competition.geoBroadcasts.ifEmpty { competition.broadcasts.filter { it.media != null } }
        for (broadcast in typed) {
            val name = broadcast.media?.shortName?.takeIf { it.isNotBlank() } ?: continue
            when (broadcast.type?.shortName) {
                "Streaming" -> streaming += name
                else -> tv += name
            }
        }
        if (typed.isEmpty()) competition.broadcasts.flatMap { it.names }.filter { it.isNotBlank() }.forEach { tv += it }
        return tv.distinct() to streaming.distinct()
    }

    /** The season's weeks, preseason to the Super Bowl: not the off-season, not the Pro Bowl. */
    private fun calendar(periods: List<CalendarPeriodJson>): List<NflWeekRef> = periods.flatMap { period ->
        val type = period.value.toIntOrNull()?.takeIf { it in PRESEASON..POSTSEASON } ?: return@flatMap emptyList()
        period.entries.mapNotNull { entry ->
            val week = entry.value.toIntOrNull() ?: return@mapNotNull null
            if (entry.label.contains("Pro Bowl", ignoreCase = true)) return@mapNotNull null
            NflWeekRef(type, week, entry.label, millis(entry.startDate) ?: return@mapNotNull null, millis(entry.endDate) ?: return@mapNotNull null)
        }
    }.sortedBy { it.startMillis }

    private fun label(type: Int, week: Int) = when (type) {
        PRESEASON -> "Preseason Week $week"
        POSTSEASON -> "Playoffs Week $week"
        else -> "Week $week"
    }

    /** ESPN's times leave the seconds out ("2026-11-02T01:20Z"), which `Instant.parse` refuses. */
    internal fun millis(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            try {
                Instant.parse(text).toEpochMilli()
            } catch (e: DateTimeParseException) {
                null
            }
        }
    }

    private fun color(hex: String?, default: String): String =
        hex?.trim()?.removePrefix("#")?.takeIf { it.length == 6 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
            ?.let { "#" + it.uppercase() } ?: default

    private fun weekNumber(element: JsonElement): Int? = when (element) {
        is JsonPrimitive -> element.intOrNull
        is JsonObject -> (element["number"] as? JsonPrimitive)?.intOrNull
        else -> null
    }

    private fun score(element: JsonElement?): Int? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.toDoubleOrNull()?.toInt()
        is JsonObject -> (element["value"] as? JsonPrimitive)?.doubleOrNull?.toInt()
            ?: (element["displayValue"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()
        else -> null
    }

    private fun JsonElement?.isTrue() = (this as? JsonPrimitive)?.booleanOrNull == true

    private fun seed(stats: Map<String?, StatJson>): Int? = stats["playoffSeed"]?.value?.toInt()?.takeIf { it > 0 }

    private fun Map<String?, StatJson>.int(name: String): Int = this[name]?.let { it.value?.toInt() ?: it.displayValue?.toDoubleOrNull()?.toInt() } ?: 0

    private fun Map<String?, StatJson>.text(name: String): String? = this[name]?.displayValue?.takeIf { it.isNotBlank() }

    private const val PRESEASON = 1
    private const val REGULAR = 2
    private const val POSTSEASON = 3
    private const val TBD = "TBD"
    private const val DEFAULT_COLOR = "#333333"
    private const val DEFAULT_ALT_COLOR = "#999999"

    @Serializable
    private class ScoreboardJson(
        val leagues: List<LeagueJson> = emptyList(),
        val season: SeasonJson? = null,
        val week: WeekJson? = null,
        val events: List<EventJson> = emptyList(),
    )

    @Serializable
    private class LeagueJson(val season: LeagueSeasonJson? = null, val calendar: List<CalendarPeriodJson> = emptyList())

    /** The league's season: its `type` is an object, and on a request with `dates=` it's not the week's (2025's playoffs say "Regular Season"). */
    @Serializable
    private class LeagueSeasonJson(val year: Int? = null)

    @Serializable
    private class CalendarPeriodJson(val label: String = "", val value: String = "", val entries: List<CalendarEntryJson> = emptyList())

    @Serializable
    private class CalendarEntryJson(val label: String = "", val value: String = "", val startDate: String? = null, val endDate: String? = null)

    @Serializable
    private class SeasonJson(val year: Int? = null, val type: Int? = null)

    @Serializable
    private class SeasonTypeJson(val type: Int? = null)

    @Serializable
    private class WeekJson(val number: Int? = null, val teamsOnBye: List<TeamJson> = emptyList())

    @Serializable
    private class EventJson(
        val id: String,
        val date: String? = null,
        val season: SeasonJson? = null,
        val seasonType: SeasonTypeJson? = null,
        val week: JsonElement? = null,
        val timeValid: Boolean? = null,
        val status: StatusJson? = null,
        val competitions: List<CompetitionJson> = emptyList(),
    )

    @Serializable
    private class CompetitionJson(
        val date: String? = null,
        val timeValid: Boolean? = null,
        val venue: VenueJson? = null,
        val notes: List<NoteJson> = emptyList(),
        val status: StatusJson? = null,
        val broadcasts: List<BroadcastJson> = emptyList(),
        val geoBroadcasts: List<BroadcastJson> = emptyList(),
        val competitors: List<CompetitorJson> = emptyList(),
        val situation: SituationJson? = null,
    )

    @Serializable
    private class VenueJson(val fullName: String? = null)

    @Serializable
    private class NoteJson(val headline: String? = null)

    @Serializable
    private class StatusJson(val displayClock: String? = null, val period: Int? = null, val type: StatusTypeJson? = null)

    @Serializable
    private class StatusTypeJson(val state: String? = null, val detail: String? = null, val shortDetail: String? = null)

    @Serializable
    private class BroadcastJson(val type: BroadcastTypeJson? = null, val media: MediaJson? = null, val names: List<String> = emptyList())

    @Serializable
    private class BroadcastTypeJson(val shortName: String? = null)

    @Serializable
    private class MediaJson(val shortName: String? = null)

    @Serializable
    private class CompetitorJson(
        val id: String? = null,
        val homeAway: String? = null,
        val winner: Boolean? = null,
        val score: JsonElement? = null,
        val linescores: List<LineScoreJson> = emptyList(),
        val team: TeamJson? = null,
        val records: List<RecordJson> = emptyList(),
        val record: List<RecordJson> = emptyList(),
        /** A summary's: true on the side with the ball. */
        val possession: JsonElement? = null,
    ) {
        val abbr: String get() = team?.abbreviation ?: TBD

        /** The season's record, "2-5": `records` on the scoreboard, `record` in a schedule or summary. */
        fun record(): String? = (records + record).firstOrNull { it.type == "total" || it.name == "overall" }
            ?.let { it.summary ?: it.displayValue }?.takeIf { it.isNotBlank() }
    }

    @Serializable
    private class LineScoreJson(val value: Double? = null, val displayValue: String? = null) {
        fun points(): Int = value?.toInt() ?: displayValue?.toDoubleOrNull()?.toInt() ?: 0
    }

    @Serializable
    private class RecordJson(val type: String? = null, val name: String? = null, val summary: String? = null, val displayValue: String? = null)

    @Serializable
    private class TeamJson(
        val id: String? = null,
        val abbreviation: String? = null,
        val location: String? = null,
        val name: String? = null,
        val nickname: String? = null,
        val color: String? = null,
        val alternateColor: String? = null,
    )

    /** While it's on: who has the ball (a team id), the down and distance, the red zone, the last play. */
    @Serializable
    private class SituationJson(
        val possession: String? = null,
        val downDistanceText: String? = null,
        val isRedZone: Boolean = false,
        val lastPlay: PlayJson? = null,
    )

    @Serializable
    private class PlayJson(val text: String? = null)

    @Serializable
    private class StandingsJson(val season: SeasonJson? = null, val children: List<GroupJson> = emptyList())

    @Serializable
    private class GroupJson(
        val name: String? = null,
        val abbreviation: String? = null,
        val children: List<GroupJson> = emptyList(),
        val standings: GroupStandingsJson? = null,
    )

    @Serializable
    private class GroupStandingsJson(val season: Int? = null, val entries: List<EntryJson> = emptyList())

    @Serializable
    private class EntryJson(val team: TeamJson? = null, val stats: List<StatJson> = emptyList())

    @Serializable
    private class StatJson(val name: String? = null, val value: Double? = null, val displayValue: String? = null)

    @Serializable
    private class TeamsJson(val sports: List<SportJson> = emptyList())

    @Serializable
    private class SportJson(val leagues: List<TeamsLeagueJson> = emptyList())

    @Serializable
    private class TeamsLeagueJson(val teams: List<TeamWrapperJson> = emptyList())

    @Serializable
    private class TeamWrapperJson(val team: TeamJson? = null)

    @Serializable
    private class ScheduleJson(
        val season: SeasonTypedJson? = null,
        val requestedSeason: SeasonTypedJson? = null,
        val team: TeamJson? = null,
        val events: List<EventJson> = emptyList(),
    )

    @Serializable
    private class SeasonTypedJson(val year: Int? = null, val type: Int? = null)

    @Serializable
    private class SummaryJson(
        val header: HeaderJson? = null,
        val boxscore: BoxscoreJson? = null,
        val gameInfo: GameInfoJson? = null,
        val situation: SituationJson? = null,
    )

    @Serializable
    private class HeaderJson(
        val id: String? = null,
        val season: SeasonJson? = null,
        val week: JsonElement? = null,
        val timeValid: Boolean? = null,
        val competitions: List<CompetitionJson> = emptyList(),
    )

    @Serializable
    private class BoxscoreJson(val teams: List<BoxTeamJson> = emptyList())

    @Serializable
    private class BoxTeamJson(val homeAway: String? = null, val team: TeamJson? = null, val statistics: List<BoxStatJson> = emptyList())

    @Serializable
    private class BoxStatJson(val label: String? = null, val displayValue: String? = null)

    @Serializable
    private class GameInfoJson(val venue: VenueJson? = null)
}
