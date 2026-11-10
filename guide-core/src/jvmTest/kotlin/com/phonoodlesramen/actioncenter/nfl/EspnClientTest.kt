package com.phonoodlesramen.actioncenter.nfl

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The calls ESPN gets, against MockWebServer (never ESPN itself), and its failures as the sentences the TVs show. */
class EspnClientTest {
    private val server = MockWebServer()
    private lateinit var espn: EspnClient

    @Before
    fun setUp() {
        server.start()
        espn = EspnClient(OkHttpClient(), base = server.url("/"), cdn = server.url("/"))
    }

    @After
    fun tearDown() = server.close()

    private fun respond(fixture: String) = server.enqueue(
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(javaClass.getResource("/nfl/$fixture")!!.readText()).build(),
    )

    private fun asked() = server.takeRequest(1, TimeUnit.SECONDS)!!.url

    @Test
    fun `the current week is the scoreboard without a date, and another week names its season, type and week`() = runTest {
        respond("scoreboard-2026-week8-live.json")
        assertEquals(8, espn.scoreboard().week!!.week)
        val current = asked()
        assertEquals("/apis/site/v2/sports/football/nfl/scoreboard", current.encodedPath)
        assertNull(current.query)

        respond("scoreboard-2025-post1.json")
        assertEquals("Wild Card", espn.scoreboard(2025, 3, 1).week!!.label)
        val other = asked()
        assertEquals(listOf("2025", "3", "1"), listOf("dates", "seasontype", "week").map { other.queryParameter(it) })
    }

    @Test
    fun `standings by division, seeds first, and another season's when asked`() = runTest {
        respond("standings-2025.json")
        assertEquals(2025, espn.standings(season = 2025).seasonYear)
        val url = asked()
        assertEquals("/apis/v2/sports/football/nfl/standings", url.encodedPath)
        assertEquals(listOf("3", "playoffseed:asc", "2025"), listOf("level", "sort", "season").map { url.queryParameter(it) })

        respond("standings-2026.json")
        espn.standings()
        assertNull(asked().queryParameter("season"))
    }

    @Test
    fun `teams, a team's schedule and a game's summary`() = runTest {
        respond("teams.json")
        assertEquals(32, espn.teams(emptyMap()).size)
        assertEquals("/apis/site/v2/sports/football/nfl/teams", asked().encodedPath)

        respond("schedule-buf-2026.json")
        assertEquals("BUF", espn.schedule("BUF", 2026, 2).abbr)
        val schedule = asked()
        assertEquals("/apis/site/v2/sports/football/nfl/teams/buf/schedule", schedule.encodedPath)
        assertEquals("2026" to "2", schedule.queryParameter("season") to schedule.queryParameter("seasontype"))

        respond("summary-ari-dal.json")
        assertEquals("ARI", espn.summary("401873024").game.winner)
        val summary = asked()
        assertEquals("/apis/site/v2/sports/football/nfl/summary", summary.encodedPath)
        assertEquals("401873024", summary.queryParameter("event"))
    }

    @Test
    fun `a logo is the dark art, resized`() = runTest {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/png").body(Buffer().write(png)).build())

        assertArrayEquals(png, espn.logo("NYJ", 64))
        val url = asked()
        assertEquals("/combiner/i", url.encodedPath)
        assertEquals("/i/teamlogos/nfl/500-dark/nyj.png", url.queryParameter("img"))
        assertEquals("64" to "64", url.queryParameter("w") to url.queryParameter("h"))

        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html>").build())
        assertEquals("ESPN sent no logo for NYJ.", failure { espn.logo("NYJ", 64) }.message)
    }

    @Test
    fun `failures are sentences, for an error status, an answer that won't read, and no answer`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).body("busy").build())
        val busy = failure { espn.scoreboard() }
        assertEquals("ESPN answered 503.", busy.message)
        assertEquals(503, busy.code)

        server.enqueue(MockResponse.Builder().code(200).body("<html>not json</html>").build())
        assertEquals("ESPN's answer has changed, and can't be read.", failure { espn.standings() }.message)

        server.enqueue(MockResponse.Builder().code(200).body("""{"header":{}}""").build())
        assertEquals("ESPN's answer has changed, and can't be read.", failure { espn.summary("1") }.message)

        val closed = MockWebServer().apply { start() }
        val gone = EspnClient(OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).build(), base = closed.url("/"))
        closed.close()
        assertEquals("Can't reach ESPN.", failure { gone.scoreboard() }.message)
    }

    private suspend fun failure(block: suspend () -> Unit): EspnException = try {
        block()
        fail("Expected an EspnException")
        throw AssertionError()
    } catch (e: EspnException) {
        e
    }
}
