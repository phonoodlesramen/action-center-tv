package com.phonoodlesramen.actioncenter.nfl

import com.phonoodlesramen.actioncenter.station.NflReply
import com.phonoodlesramen.actioncenter.station.NflStation
import com.phonoodlesramen.actioncenter.station.StationException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okio.Buffer

/** The guide station's NFL calls, faked: its [state], what it was asked, and `nfl/changes` answered from [changes]. */
class FakeNflStation(var state: NflState?) : NflStation {
    override val paired = MutableStateFlow(true)

    /** A 1.1 station: 404 to everything NFL. */
    @Volatile
    var outdated = false

    @Volatile
    var reachable = true

    /** Each version sent here answers one held `nfl/changes`. */
    val changes = Channel<Long?>(Channel.UNLIMITED)

    val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val saved: MutableList<NflSettings> = Collections.synchronizedList(mutableListOf())

    private fun <T> answer(call: String, value: () -> T): NflReply<T> {
        asked += call
        return when {
            !reachable -> NflReply.Failed(StationException.CANT_REACH, reached = false)
            outdated -> NflReply.Outdated
            else -> NflReply.Answered(value())
        }
    }

    override suspend fun nfl(etag: String?): NflReply<Pair<NflState, String?>?> = answer("nfl etag=$etag") {
        val current = state ?: return@answer null
        if (etag == current.version.toString()) null else current to current.version.toString()
    }

    override suspend fun nflChanges(after: Long): NflReply<Long?> {
        asked += "changes after=$after"
        if (!reachable) return NflReply.Failed(StationException.CANT_REACH, reached = false)
        if (outdated) return NflReply.Outdated
        return NflReply.Answered(changes.receive())
    }

    override suspend fun nflWeek(seasonType: Int, week: Int, seasonYear: Int?) = answer("week $seasonType/$week/$seasonYear") {
        NflWeek(seasonYear ?: 2026, seasonType, week, "Week $week", emptyList())
    }

    override suspend fun nflSchedule(abbr: String, seasonYear: Int?) = answer("schedule $abbr") { NflTeamSchedule(abbr, seasonYear ?: 2026, emptyList()) }

    override suspend fun nflGame(id: String) = answer("game $id") { NflGameDetail(state!!.week!!.games.first { it.id == id }) }

    override suspend fun nflLogo(abbr: String, size: String) = answer("logo $abbr $size") { byteArrayOf(1, 2, 3) }

    override suspend fun saveNflSettings(settings: NflSettings) = answer("put settings") {
        saved += settings
        // A station with no copy yet (the NFL was off, ESPN never asked) still keeps the settings.
        val current = state ?: return@answer 1L
        val next = current.copy(settings = settings, version = current.version + 1)
        state = next
        next.version
    }
}

/**
 * ESPN's site API faked on a MockWebServer (never ESPN itself), in ESPN's shapes, as the station's
 * tests fake it: a 2026 season in week 9, [games] this week, four teams in two divisions, logos.
 */
class FakeEspn(private val anchor: Long) : AutoCloseable {
    data class Game(val id: String, val away: String, val home: String, val kickoffMillis: Long, val state: String = "pre", val awayScore: Int = 0, val homeScore: Int = 0, val period: Int = 0)

    private val server = MockWebServer()

    @Volatile
    var games: List<Game> = emptyList()

    @Volatile
    var failing = false

    val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

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
            path == "/combiner/i" -> "logo"
            else -> return MockResponse.Builder().code(404).build()
        }
        asked += kind
        return when (kind) {
            "scoreboard" -> json(scoreboard(9, games))
            "week" -> json(scoreboard(url.queryParameter("week")!!.toInt(), listOf(Game("7", "PHI", "DAL", anchor - 7 * DAY, "post", 21, 17, 4))))
            "standings" -> json(STANDINGS)
            "teams" -> json(TEAMS)
            else -> MockResponse.Builder().code(200).addHeader("Content-Type", "image/png").body(Buffer().write(byteArrayOf(9, 9))).build()
        }
    }

    private fun scoreboard(week: Int, games: List<Game>): String {
        fun entry(label: String, value: Int, from: Long, to: Long) = """{"label":"$label","value":"$value","startDate":"${iso(from)}","endDate":"${iso(to)}"}"""
        val calendar = """[
            {"label":"Preseason","value":"1","entries":[${entry("Preseason Week 1", 1, anchor - 90 * DAY, anchor - 60 * DAY)}]},
            {"label":"Regular Season","value":"2","entries":[${entry("Week 1", 1, anchor - 60 * DAY, anchor - DAY)},${entry("Week 9", 9, anchor - DAY, anchor + 90 * DAY)}]},
            {"label":"Postseason","value":"3","entries":[${entry("Super Bowl", 4, anchor + 90 * DAY, anchor + 100 * DAY)}]},
            {"label":"Off Season","value":"4","entries":[]}]"""
        return """{"leagues":[{"season":{"year":2026},"calendar":$calendar}],"season":{"type":2,"year":2026},
            "week":{"number":$week,"teamsOnBye":[]},"events":[${games.joinToString(",") { event(it, week) }}]}"""
    }

    private fun event(game: Game, week: Int): String {
        fun side(abbr: String, homeAway: String, score: Int, other: Int) =
            """{"homeAway":"$homeAway","winner":${game.state == "post" && score > other},"team":{"id":"${IDS.getValue(abbr)}","abbreviation":"$abbr"},"score":"$score"}"""
        return """{"id":"${game.id}","date":"${iso(game.kickoffMillis)}","season":{"year":2026,"type":2},"week":{"number":$week},
            "competitions":[{"timeValid":true,"status":{"displayClock":"5:00","period":${game.period},"type":{"state":"${game.state}","shortDetail":"${game.state}"}},
            "competitors":[${side(game.home, "home", game.homeScore, game.awayScore)},${side(game.away, "away", game.awayScore, game.homeScore)}],
            "geoBroadcasts":[{"type":{"shortName":"TV"},"media":{"shortName":"CBS"}}]}]}"""
    }

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    override fun close() = server.close()

    companion object {
        const val DAY = 24 * 60 * 60_000L
        private const val SITE = "/apis/site/v2/sports/football/nfl"
        private val IDS = mapOf("BUF" to "2", "MIA" to "15", "PHI" to "21", "DAL" to "6")
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'").withZone(ZoneOffset.UTC)

        fun iso(millis: Long): String = ISO.format(Instant.ofEpochMilli(millis))

        private fun standing(abbr: String, wins: Int, losses: Int, seed: Int) =
            """{"team":{"abbreviation":"$abbr"},"stats":[{"name":"wins","value":$wins},{"name":"losses","value":$losses},
            {"name":"playoffSeed","value":$seed},{"name":"winPercent","displayValue":".500"}]}"""

        private val STANDINGS = """{"season":{"year":2026},"children":[
            {"name":"American Football Conference","abbreviation":"AFC","children":[{"name":"AFC East","standings":{"entries":[
                ${standing("BUF", 6, 1, 1)},${standing("MIA", 2, 5, 6)}]}}]},
            {"name":"National Football Conference","abbreviation":"NFC","children":[{"name":"NFC East","standings":{"entries":[
                ${standing("PHI", 5, 2, 2)},${standing("DAL", 3, 4, 7)}]}}]}]}"""

        private val TEAMS = """{"sports":[{"leagues":[{"teams":[
            {"team":{"abbreviation":"BUF","location":"Buffalo","name":"Bills","color":"00338d","alternateColor":"d50a0a"}},
            {"team":{"abbreviation":"DAL","location":"Dallas","name":"Cowboys","color":"002a5c","alternateColor":"b0b7bc"}},
            {"team":{"abbreviation":"MIA","location":"Miami","name":"Dolphins","color":"008e97","alternateColor":"fc4c02"}},
            {"team":{"abbreviation":"PHI","location":"Philadelphia","name":"Eagles","color":"06424d","alternateColor":"000000"}}]}]}]}"""
    }
}
