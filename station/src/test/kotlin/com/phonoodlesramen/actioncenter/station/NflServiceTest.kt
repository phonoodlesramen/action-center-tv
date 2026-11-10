package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.nfl.EspnClient
import com.phonoodlesramen.actioncenter.nfl.GameState
import com.phonoodlesramen.actioncenter.nfl.NflSettings
import com.phonoodlesramen.actioncenter.nfl.SeasonPhase
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The station's NFL on a fake clock, against a fake ESPN: the poller's pace, the copy, the caches and the logos. */
class NflServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** Sunday 8 Nov 2026, 2 PM Eastern. */
    private var now = at("2026-11-08T14:00-05:00")
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var espn: FakeEspn
    private lateinit var versions: Versions
    private lateinit var nfl: NflService

    @Before
    fun setUp() {
        espn = FakeEspn(now)
        versions = Versions(temp.root.toPath().resolve("nfl-version"))
        nfl = service()
    }

    @After
    fun tearDown() = espn.close()

    private fun service() = NflService(
        EspnClient(OkHttpClient(), espn.url, espn.url), temp.root.toPath(), temp.root.toPath().resolve("nfl-logos"), versions, { now }, NY,
        { logs += it },
    )

    private fun poll() = runBlocking { nfl.poll() }

    /** A 1 PM game in the 2nd quarter. */
    private val live = FakeEspn.Game("1", "MIA", "BUF", at("2026-11-08T13:00-05:00"), "in", 10, 14, period = 2)

    @Test
    fun `the poller is fast only while a game is on and a TV asked in the last 3 minutes`() {
        espn.games = listOf(live)
        poll()
        assertEquals(NflService.GAME_DAY_MILLIS, nfl.nextPollMillis(now))
        nfl.asked()
        assertEquals(NflService.LIVE_MILLIS, nfl.nextPollMillis(now))
        now += NflService.WATCHING_MILLIS - 1
        assertEquals(NflService.LIVE_MILLIS, nfl.nextPollMillis(now))
        now += 1
        assertEquals(NflService.GAME_DAY_MILLIS, nfl.nextPollMillis(now))
    }

    @Test
    fun `a game past its kickoff counts as on before ESPN says so`() {
        espn.games = listOf(FakeEspn.Game("2", "DAL", "PHI", at("2026-11-08T16:25-05:00")))
        poll()
        nfl.asked()
        assertEquals(NflService.GAME_DAY_MILLIS, nfl.nextPollMillis(now))
        now = at("2026-11-08T16:26-05:00")
        nfl.asked()
        assertEquals(NflService.LIVE_MILLIS, nfl.nextPollMillis(now))
    }

    @Test
    fun `gently otherwise, 15 minutes on a game day, hourly on other days, and daily at 4 AM in the off-season`() {
        espn.games = listOf(FakeEspn.Game("3", "DAL", "PHI", at("2026-11-08T20:20-05:00")))
        poll()
        nfl.asked()
        assertEquals(NflService.GAME_DAY_MILLIS, nfl.nextPollMillis(now))

        espn.games = listOf(
            FakeEspn.Game("4", "MIA", "BUF", at("2026-11-05T20:15-05:00"), "post", 17, 20, period = 4),
            FakeEspn.Game("5", "DAL", "PHI", at("2026-11-09T20:15-05:00")),
        )
        poll()
        nfl.asked()
        assertEquals(NflService.QUIET_MILLIS, nfl.nextPollMillis(now))

        espn.season = FakeEspn.Season.over(now)
        poll()
        assertEquals(SeasonPhase.OFF, nfl.state()!!.phase)
        assertNull(nfl.state()!!.week)
        assertEquals(at("2026-11-09T04:00-05:00") - now, nfl.nextPollMillis(now))
    }

    @Test
    fun `while the house's NFL is off the poller asks ESPN nothing, and turning it on polls at once`() = runBlocking {
        espn.games = listOf(live)
        val poller = launch(Dispatchers.Default) { nfl.run() }
        // A TV asking can't make it poll while it's off.
        nfl.asked()
        delay(300)
        assertEquals(0, espn.count("scoreboard"))
        assertFalse(nfl.polling())

        nfl.putSettings(NflSettings(show = true))
        eventually { espn.count("scoreboard") == 1 }
        assertTrue(nfl.polling())
        poller.cancel()
    }

    @Test
    fun `a house that set the NFL up before the switch keeps polling`() {
        nfl.putSettings(NflSettings(sundayTicket = true))
        assertTrue(nfl.polling())
        nfl.putSettings(NflSettings(sundayTicket = true, show = false))
        assertFalse(nfl.polling())
    }

    @Test
    fun `a TV asking during a game wakes the poller at once`() = runBlocking {
        espn.games = listOf(live)
        nfl.putSettings(NflSettings(show = true))
        val poller = launch(Dispatchers.Default) { nfl.run() }
        eventually { nfl.state() != null }
        now += NflService.LIVE_MILLIS
        nfl.asked()
        eventually { espn.count("scoreboard") == 2 }
        poller.cancel()
    }

    @Test
    fun `a TV asking while ESPN is still answering doesn't put the next poll back`() = runBlocking {
        espn.games = listOf(live)
        val answering = CountDownLatch(1)
        espn.standingsGate = answering
        nfl.putSettings(NflSettings(show = true))
        val poller = launch(Dispatchers.Default) { nfl.run() }
        eventually { espn.count("standings") == 1 }
        // The first poll hasn't seen the game yet, so this can't wake it: the pace has to.
        now += NflService.LIVE_MILLIS
        nfl.asked()
        answering.countDown()
        eventually { espn.count("scoreboard") == 2 }
        poller.cancel()
    }

    @Test
    fun `a failing ESPN keeps the copy, says why, and is tried again within 5 minutes`() {
        espn.games = listOf(live)
        poll()
        val good = nfl.state()!!

        espn.failing = true
        now += 60_000L
        poll()
        val kept = nfl.state()!!
        assertEquals(good.week, kept.week)
        assertEquals(good.standings, kept.standings)
        assertEquals(good.updatedAtMillis, kept.updatedAtMillis)
        assertEquals("ESPN answered 503.", kept.problem)
        assertTrue(kept.version > good.version)
        assertEquals(NflService.RETRY_MILLIS, nfl.nextPollMillis(now))

        // The same failure again is no change.
        poll()
        assertEquals(kept.version, nfl.state()!!.version)

        espn.failing = false
        poll()
        assertNull(nfl.state()!!.problem)
        assertTrue(nfl.state()!!.version > kept.version)
        assertEquals(listOf("nfl: ESPN answered 503.", "nfl: ESPN answers again"), logs)
    }

    @Test
    fun `the version moves when something changed, not when only the time did`() {
        espn.games = listOf(live)
        poll()
        val first = nfl.state()!!
        now += 30_000L
        poll()
        val same = nfl.state()!!
        assertEquals(first.version, same.version)
        assertEquals(now, same.updatedAtMillis)

        espn.games = listOf(live.copy(homeScore = 21))
        poll()
        val scored = nfl.state()!!
        assertTrue(scored.version > first.version)
        assertEquals(21, scored.week!!.games.single().homeScore)
    }

    @Test
    fun `standings come with the first poll, after a game ends, and at 4 AM, which is the morning's`() {
        espn.games = listOf(live)
        poll()
        assertEquals(1 to 1, espn.count("standings") to espn.count("teams"))
        val first = nfl.state()!!
        assertEquals(listOf("BUF", "MIA", "PHI", "DAL"), first.standings.map { it.abbr })
        assertEquals(first.standings, first.morningStandings)
        assertEquals(listOf("BUF", "DAL", "MIA", "PHI"), first.teams.map { it.abbr })
        assertEquals("AFC" to "East", first.teams.first().let { it.conference to it.division })

        poll()
        assertEquals(1, espn.count("standings"))

        espn.buffaloWins = 7
        espn.games = listOf(live.copy(state = "post", homeScore = 28, period = 4))
        poll()
        assertEquals(2, espn.count("standings"))
        val after = nfl.state()!!
        assertEquals(7, after.standings.first().wins)
        // While today's scores are hidden, the morning's still say 6.
        assertEquals(6, after.morningStandings.first().wins)

        now = at("2026-11-09T03:59-05:00")
        poll()
        assertEquals(2, espn.count("standings"))
        now = at("2026-11-09T04:00-05:00")
        poll()
        assertEquals(3, espn.count("standings"))
        assertEquals(7, nfl.state()!!.morningStandings.first().wins)
        assertEquals(1, espn.count("teams"))
    }

    @Test
    fun `in the preseason the standings are last season's (D)`() {
        espn.season = FakeEspn.Season.preseason(now)
        espn.current = 1 to 1
        poll()
        val state = nfl.state()!!
        assertEquals(SeasonPhase.PRESEASON, state.phase)
        assertEquals(2026 to 2025, state.seasonYear to state.standingsSeason)
        assertEquals(listOf<String?>("2025"), espn.standingsSeasons)
        assertEquals("Preseason Week 1", state.week!!.label)
    }

    @Test
    fun `the copy survives a restart, so a TV gets it before ESPN is asked again`() {
        espn.games = listOf(live)
        poll()
        val before = nfl.state()!!
        versions = Versions(temp.root.toPath().resolve("nfl-version"))
        nfl = service()
        val after = nfl.state()!!
        assertEquals(before.week, after.week)
        assertEquals(before.standings, after.standings)
        assertEquals(before.calendar, after.calendar)
        assertTrue(after.version > before.version)
        assertEquals(1, espn.count("scoreboard"))
    }

    @Test
    fun `other weeks are cached, a finished one for good and one with games to come for 15 minutes`() = runBlocking {
        espn.games = listOf(live)
        nfl.poll()
        espn.weeks["2026/2/7"] = listOf(FakeEspn.Game("7", "PHI", "DAL", at("2026-10-25T13:00-04:00"), "post", 21, 17, period = 4))
        espn.weeks["2026/2/10"] = listOf(FakeEspn.Game("10", "BUF", "PHI", at("2026-11-15T13:00-05:00")))

        assertEquals("PHI", nfl.week(2, 7, null).games.single().winner)
        now += 30 * FakeEspn.Season.DAY
        nfl.week(2, 7, null)
        assertEquals(1, espn.count("week"))

        nfl.week(2, 10, null)
        now += NflService.WEEK_MILLIS - 1
        nfl.week(2, 10, null)
        assertEquals(2, espn.count("week"))
        now += 1
        nfl.week(2, 10, null)
        assertEquals(3, espn.count("week"))

        // This week is the copy's; nothing to fetch.
        assertEquals(GameState.LIVE, nfl.week(2, 9, null).games.single().state)
        assertEquals(3, espn.count("week"))

        assertEquals(404, refused { nfl.week(3, 5, null) })
        assertEquals(404, refused { nfl.week(4, 1, null) })
        espn.weeks["2025/3/1"] = listOf(FakeEspn.Game("11", "BUF", "MIA", at("2026-01-11T13:00-05:00"), "post", 27, 24, period = 4))
        assertEquals(2025, nfl.week(3, 1, 2025).seasonYear)
    }

    @Test
    fun `a team's schedule is cached for 15 minutes`() = runBlocking {
        nfl.poll()
        assertEquals("BUF", nfl.schedule("buf", null, null).abbr)
        nfl.schedule("BUF", null, null)
        assertEquals(1, espn.count("schedule"))
        now += NflService.SCHEDULE_MILLIS
        nfl.schedule("BUF", null, null)
        assertEquals(2, espn.count("schedule"))
        assertEquals(404, refused { nfl.schedule("XYZ", null, null) })
    }

    @Test
    fun `a game's details are fetched every 30 s while it's on, and kept for good once final`() = runBlocking {
        nfl.poll()
        espn.summaryState = "in"
        assertEquals(listOf("310"), nfl.game("1").awayStats.map { it.value })
        now += NflService.GAME_LIVE_MILLIS - 1
        nfl.game("1")
        assertEquals(1, espn.count("summary"))
        now += 1
        espn.summaryState = "post"
        assertEquals(GameState.FINAL, nfl.game("1").game.state)
        now += 30 * FakeEspn.Season.DAY
        nfl.game("1")
        assertEquals(2, espn.count("summary"))

        // ESPN failing: the copy if there is one, else a sentence.
        espn.failing = true
        assertEquals(GameState.FINAL, nfl.game("1").game.state)
        assertEquals(502, refused { nfl.game("2") })
        assertEquals(404, refused { nfl.game("not-an-id") })
    }

    @Test
    fun `logos are fetched once per team and size, kept in nfl-logos, and fetched again after a month`() = runBlocking {
        nfl.poll()
        assertArrayEquals(FakeEspn.LOGO, nfl.logo("buf", "s"))
        nfl.logo("BUF", "s")
        assertEquals(1, espn.count("logo"))
        nfl.logo("BUF", "l")
        assertEquals(listOf<String?>("64", "192"), espn.logoSizes)

        val file = temp.root.toPath().resolve("nfl-logos/BUF-s.png")
        assertArrayEquals(FakeEspn.LOGO, Files.readAllBytes(file))
        Files.setLastModifiedTime(file, FileTime.fromMillis(now - NflService.LOGO_MILLIS))
        nfl.logo("BUF", "s")
        assertEquals(3, espn.count("logo"))

        // A month on with ESPN failing: the one it has.
        Files.setLastModifiedTime(file, FileTime.fromMillis(now - NflService.LOGO_MILLIS))
        espn.failing = true
        assertArrayEquals(FakeEspn.LOGO, nfl.logo("BUF", "s"))
        assertEquals(502, refused { nfl.logo("PHI", "s") })
        assertEquals(400, refused { nfl.logo("BUF", "m") })
        assertEquals(404, refused { nfl.logo("XYZ", "s") })
        assertEquals(404, refused { nfl.logo("../x", "s") })
    }

    @Test
    fun `the house's settings move the version when they change, refuse an unknown team, and are kept`() {
        poll()
        val before = nfl.state()!!.version
        val version = nfl.putSettings(NflSettings(yourTeams = listOf("phi"), areaTeams = listOf("PHI", "DAL"), sundayTicket = true, hideScores = true))
        assertTrue(version > before)
        assertEquals(NflSettings(listOf("PHI"), listOf("PHI", "DAL"), sundayTicket = true, hideScores = true), nfl.settings())
        assertEquals(version, nfl.state()!!.version)
        assertTrue(nfl.state()!!.settings.hideScores)
        assertEquals(version, nfl.putSettings(nfl.settings()))

        try {
            nfl.putSettings(NflSettings(yourTeams = listOf("XYZ")))
            fail("took XYZ")
        } catch (e: ApiException) {
            assertEquals(400 to "\"XYZ\" isn't an NFL team.", e.status to e.message)
        }

        nfl = service()
        assertEquals(listOf("PHI"), nfl.settings().yourTeams)
    }

    @Test
    fun `nothing to answer until ESPN has answered once`() {
        assertNull(nfl.state())
        espn.failing = true
        poll()
        assertNull(nfl.state())
        assertEquals("ESPN answered 503.", nfl.problem)
        assertEquals(NflService.RETRY_MILLIS, nfl.nextPollMillis(now))
        assertFalse(Files.exists(temp.root.toPath().resolve("nfl-logos")))
    }

    private suspend fun refused(block: suspend () -> Unit): Int = try {
        block()
        fail("Expected an ApiException")
        0
    } catch (e: ApiException) {
        e.status
    }

    private fun eventually(within: Long = 10_000L, check: () -> Boolean) {
        val until = System.currentTimeMillis() + within
        while (!check()) {
            if (System.currentTimeMillis() > until) fail("still not so after $within ms")
            Thread.sleep(20)
        }
    }

    private fun at(text: String) = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    private companion object {
        val NY: ZoneId = ZoneId.of("America/New_York")
    }
}
