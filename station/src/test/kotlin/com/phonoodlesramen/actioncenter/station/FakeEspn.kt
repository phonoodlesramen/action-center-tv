package com.phonoodlesramen.actioncenter.station

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okio.Buffer

/**
 * ESPN's site API faked on one MockWebServer (never ESPN itself): hand-written, tiny answers in
 * ESPN's shapes (`:guide-core`'s fixtures are the real ones). A 2026 season whose calendar is laid
 * around [anchor] (week 9 started the day before), the [games] of the current week, four teams in
 * two divisions, and logos.
 */
internal class FakeEspn(anchor: Long) : AutoCloseable {
    /** A made-up game, in the scoreboard's terms: [state] is `pre`, `in` or `post`. */
    data class Game(
        val id: String,
        val away: String,
        val home: String,
        val kickoffMillis: Long,
        val state: String = "pre",
        val awayScore: Int = 0,
        val homeScore: Int = 0,
        val period: Int = 0,
        val tv: String = "CBS",
    )

    /** The season's dates, as ESPN's calendar gives them. */
    data class Season(val preseason: Long, val weekOne: Long, val week9: Long, val superBowl: Long, val end: Long) {
        companion object {
            const val DAY = 24 * 60 * 60_000L

            /** The regular season, week 9 from the day before [anchor]. */
            fun regular(anchor: Long) = Season(anchor - 90 * DAY, anchor - 60 * DAY, anchor - DAY, anchor + 90 * DAY, anchor + 100 * DAY)

            /** The preseason, Week 1 a month off. */
            fun preseason(anchor: Long) = Season(anchor - DAY, anchor + 30 * DAY, anchor + 90 * DAY, anchor + 180 * DAY, anchor + 190 * DAY)

            /** The off-season: the Super Bowl was weeks ago. */
            fun over(anchor: Long) = Season(anchor - 220 * DAY, anchor - 190 * DAY, anchor - 130 * DAY, anchor - 40 * DAY, anchor - 30 * DAY)
        }
    }

    private val server = MockWebServer()

    @Volatile
    var season: Season = Season.regular(anchor)

    /** The current week: its type and number. */
    @Volatile
    var current: Pair<Int, Int> = 2 to 9

    @Volatile
    var games: List<Game> = emptyList()

    /** Other weeks, by "year/type/week"; any other is empty, as ESPN answers one past the Super Bowl. */
    val weeks = ConcurrentHashMap<String, List<Game>>()

    @Volatile
    var buffaloWins = 6

    /** A game's summary says it's `pre`, `in` or `post`. */
    @Volatile
    var summaryState = "post"

    /** Every call answers 503. */
    @Volatile
    var failing = false

    /** While set, a standings call waits for it (10 s at most): ESPN still answering, mid-poll. */
    @Volatile
    var standingsGate: CountDownLatch? = null

    /** What was asked for, in order: "scoreboard", "week", "standings", "teams", "schedule", "summary", "logo". */
    val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The `season` each standings call named, null for the current one. */
    val standingsSeasons: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    /** The `w` each logo call asked for. */
    val logoSizes: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        server.start()
    }

    val url: HttpUrl get() = server.url("/")

    fun count(kind: String) = asked.count { it == kind }

    private fun answer(request: RecordedRequest): MockResponse {
        if (failing) return MockResponse.Builder().code(503).body("busy").build()
        val url = request.url
        val path = url.encodedPath
        val kind = when {
            path == "$SITE/scoreboard" -> if (url.queryParameter("dates") == null) "scoreboard" else "week"
            path == "/apis/v2/sports/football/nfl/standings" -> "standings"
            path == "$SITE/teams" -> "teams"
            path.startsWith("$SITE/teams/") && path.endsWith("/schedule") -> "schedule"
            path == "$SITE/summary" -> "summary"
            path == "/combiner/i" -> "logo"
            else -> return MockResponse.Builder().code(404).build()
        }
        asked += kind
        if (kind == "standings") standingsGate?.await(10, TimeUnit.SECONDS)
        return when (kind) {
            "scoreboard" -> json(scoreboard(current.first, current.second, games))
            "week" -> {
                val key = "${url.queryParameter("dates")}/${url.queryParameter("seasontype")}/${url.queryParameter("week")}"
                val found = weeks[key]
                val year = url.queryParameter("dates")!!.toInt()
                json(if (found == null) """{"events":[]}""" else scoreboard(url.queryParameter("seasontype")!!.toInt(), url.queryParameter("week")!!.toInt(), found, year))
            }
            "standings" -> {
                standingsSeasons += url.queryParameter("season")
                json(standings(url.queryParameter("season")?.toInt() ?: YEAR))
            }
            "teams" -> json(TEAMS)
            "schedule" -> json(
                """{"requestedSeason":{"year":$YEAR,"type":2},"team":{"abbreviation":"BUF"},
                "events":[${event(Game("7", "BUF", "MIA", season.weekOne, "post", 24, 20), 2, 1)}]}""",
            )
            "summary" -> json(summary(url.queryParameter("event")!!))
            else -> {
                logoSizes += url.queryParameter("w")
                MockResponse.Builder().code(200).addHeader("Content-Type", "image/png").body(Buffer().write(LOGO)).build()
            }
        }
    }

    private fun scoreboard(type: Int, week: Int, games: List<Game>, year: Int = YEAR): String {
        val s = season
        fun entry(label: String, value: Int, from: Long, to: Long) = """{"label":"$label","value":"$value","startDate":"${iso(from)}","endDate":"${iso(to)}"}"""
        val calendar = """[
            {"label":"Preseason","value":"1","entries":[${entry("Preseason Week 1", 1, s.preseason, s.weekOne)}]},
            {"label":"Regular Season","value":"2","entries":[${entry("Week 1", 1, s.weekOne, s.week9)},${entry("Week 9", 9, s.week9, s.superBowl)}]},
            {"label":"Postseason","value":"3","entries":[${entry("Super Bowl", 4, s.superBowl, s.end)}]},
            {"label":"Off Season","value":"4","entries":[]}]"""
        return """{"leagues":[{"season":{"year":$year},"calendar":$calendar}],"season":{"type":$type,"year":$year},
            "week":{"number":$week,"teamsOnBye":[{"abbreviation":"SF"}]},"events":[${games.joinToString(",") { event(it, type, week, year) }}]}"""
    }

    private fun event(game: Game, type: Int, week: Int, year: Int = YEAR): String {
        fun side(abbr: String, homeAway: String, score: Int, other: Int) =
            """{"homeAway":"$homeAway","winner":${game.state == "post" && score > other},"team":{"id":"${IDS.getValue(abbr)}","abbreviation":"$abbr"},"score":"$score"}"""
        return """{"id":"${game.id}","date":"${iso(game.kickoffMillis)}","season":{"year":$year,"type":$type},"week":{"number":$week},
            "competitions":[{"timeValid":true,"status":{"displayClock":"5:00","period":${game.period},"type":{"state":"${game.state}","shortDetail":"${game.state}"}},
            "competitors":[${side(game.home, "home", game.homeScore, game.awayScore)},${side(game.away, "away", game.awayScore, game.homeScore)}],
            "geoBroadcasts":[{"type":{"shortName":"TV"},"media":{"shortName":"${game.tv}"}}]}]}"""
    }

    private fun standings(year: Int): String {
        fun entry(abbr: String, wins: Int, losses: Int, seed: Int) =
            """{"team":{"abbreviation":"$abbr"},"stats":[{"name":"wins","value":$wins},{"name":"losses","value":$losses},
            {"name":"playoffSeed","value":$seed},{"name":"winPercent","displayValue":".500"}]}"""
        return """{"season":{"year":$year},"children":[
            {"name":"American Football Conference","abbreviation":"AFC","children":[{"name":"AFC East","standings":{"entries":[
                ${entry("BUF", buffaloWins, 1, 1)},${entry("MIA", 2, 5, 6)}]}}]},
            {"name":"National Football Conference","abbreviation":"NFC","children":[{"name":"NFC East","standings":{"entries":[
                ${entry("PHI", 5, 2, 2)},${entry("DAL", 3, 4, 7)}]}}]}]}"""
    }

    private fun summary(id: String) = """{"header":{"id":"$id","season":{"year":$YEAR,"type":2},"week":9,"competitions":[{"date":"${iso(season.week9)}",
        "status":{"type":{"state":"$summaryState"}},"competitors":[{"homeAway":"home","team":{"abbreviation":"BUF"},"score":"21"},
        {"homeAway":"away","team":{"abbreviation":"MIA"},"score":"17"}]}]},
        "boxscore":{"teams":[{"homeAway":"away","statistics":[{"label":"Total Yards","displayValue":"310"}]},
        {"homeAway":"home","statistics":[{"label":"Total Yards","displayValue":"402"}]}]}}"""

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    override fun close() = server.close()

    companion object {
        const val YEAR = 2026
        private const val SITE = "/apis/site/v2/sports/football/nfl"
        private val IDS = mapOf("BUF" to "2", "MIA" to "15", "PHI" to "21", "DAL" to "6")

        /** Not a real PNG: the station passes logos through as they come. */
        val LOGO = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

        private val TEAMS = """{"sports":[{"leagues":[{"teams":[
            {"team":{"abbreviation":"BUF","location":"Buffalo","name":"Bills","color":"00338d","alternateColor":"d50a0a"}},
            {"team":{"abbreviation":"DAL","location":"Dallas","name":"Cowboys","color":"002a5c","alternateColor":"b0b7bc"}},
            {"team":{"abbreviation":"MIA","location":"Miami","name":"Dolphins","color":"008e97","alternateColor":"fc4c02"}},
            {"team":{"abbreviation":"PHI","location":"Philadelphia","name":"Eagles","color":"06424d","alternateColor":"000000"}}]}]}]}"""

        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'").withZone(ZoneOffset.UTC)

        /** ESPN's times: minutes, no seconds. */
        fun iso(millis: Long): String = ISO.format(Instant.ofEpochMilli(millis))
    }
}
