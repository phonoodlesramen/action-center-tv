package com.phonoodlesramen.actioncenter.nfl

import com.phonoodlesramen.actioncenter.station.NflStation
import com.phonoodlesramen.actioncenter.station.StationException
import com.phonoodlesramen.actioncenter.ui.nfl.SampleNfl
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The NFL on this TV (Phase 13, F11): paired, the station's copy (a fake station); unpaired, made
 * from ESPN (a MockWebServer ESPN, never the real one); the settings either way; Show score and
 * Not today kept on this TV until tomorrow; logos fetched once.
 */
class NflSourceTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private var now = SampleNfl.nowMillis
    private val store = MemoryNfl()
    private val espnServer = FakeEspn(SampleNfl.nowMillis)
    private val espn = EspnClient(OkHttpClient(), espnServer.url, espnServer.url)

    @After
    fun tearDown() = espnServer.close()

    private fun source(station: NflStation, scope: CoroutineScope) =NflSource(store, station, espn, zone, scope, clock = { now })

    /** The view once [check] holds, waiting for the source's state to catch up with its store. */
    private suspend fun NflSource.eventually(check: (NflView) -> Boolean): NflView = withTimeout(5_000) {
        while (!check(view.value)) yield()
        view.value
    }

    @Test
    fun `paired, the station's state is copied with its ETag, and a 304 keeps it`() = runBlocking {
        val station = FakeNflStation(SampleNfl.state())
        val nfl = source(station, this)
        assertTrue(nfl.copyFromStation())
        val view = nfl.eventually { it.state != null }
        assertEquals(NflFrom.STATION, view.from)
        assertEquals(57, view.state!!.version)
        assertEquals("57", store.current.etag)

        val copiedAt = now
        now += 60_000
        assertTrue(nfl.copyFromStation())
        assertEquals("nfl etag=57", station.asked.last())
        // A 304 says the copy is current, without writing it out again.
        assertEquals(now, nfl.eventually { it.asOfMillis == now }.asOfMillis)
        assertEquals(copiedAt, store.current.checkedAtMillis)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `paired, keepUp follows nfl changes, so a score shows as soon as the station has it`() = runBlocking {
        val station = FakeNflStation(SampleNfl.state())
        val nfl = source(station, this)
        val following = launch { nfl.keepUp(flowOf(true)) }
        nfl.eventually { it.state?.version == 57L }
        val scored = SampleNfl.state().let { s -> s.copy(version = 58, week = s.week!!.copy(games = s.week!!.games.map { if (it.id == SampleNfl.live.id) it.copy(homeScore = 24) else it })) }
        station.state = scored
        station.changes.send(58)
        val view = nfl.eventually { it.state?.version == 58L }
        assertEquals(24, view.state!!.week!!.games.first { it.id == SampleNfl.live.id }.homeScore)
        assertTrue(station.asked.contains("changes after=57"))
        following.cancel()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a kid's home doesn't follow the station, so it doesn't keep its poller fast`() = runBlocking {
        val station = FakeNflStation(SampleNfl.state())
        val nfl = source(station, this)
        val following = launch { nfl.keepUp(flowOf(false)) }
        repeat(20) { yield() }
        assertEquals(emptyList<String>(), station.asked.toList())
        following.cancel()
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a 1_1 station has no NFL, and says it needs an update`() = runBlocking {
        val station = FakeNflStation(SampleNfl.state()).apply { outdated = true }
        val nfl = source(station, this)
        assertFalse(nfl.copyFromStation())
        assertTrue(nfl.eventually { it.outdated }.outdated)
        assertEquals(NflSource.OUTDATED, nfl.save(NflSettings(hideScores = true)))
        coroutineContext.cancelChildren()
    }

    @Test
    fun `paired, the settings are the house's, kept by the station and refused while it can't be reached`() = runBlocking {
        val station = FakeNflStation(SampleNfl.state())
        val nfl = source(station, this)
        nfl.copyFromStation()
        val next = SampleNfl.settings.copy(hideScores = true)
        assertNull(nfl.save(next))
        assertEquals(listOf(next), station.saved.toList())
        assertTrue(nfl.eventually { it.settings.hideScores }.settings.hideScores)
        // The copy follows with the station's new version.
        assertEquals(58, store.current.station!!.version)

        station.reachable = false
        assertEquals(StationException.CANT_REACH, nfl.save(next.copy(sundayTicket = false)))
        assertEquals(1, station.saved.size)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `Show score and Not today are this TV's, until tomorrow`() = runBlocking {
        val nfl = source(FakeNflStation(SampleNfl.state()), this)
        nfl.show(SampleNfl.live.id)
        nfl.putAwayToday()
        var view = nfl.eventually { it.notTodayDay != null && it.shown.isNotEmpty() }
        val today = LocalDate.of(2026, 11, 1)
        assertEquals(setOf(SampleNfl.live.id), view.shownOn(today))
        assertEquals(today, view.notTodayDay)

        // Tomorrow, another game shown starts the day's list over.
        now += 24 * 60 * 60_000L
        nfl.show("401873036")
        view = nfl.eventually { it.shownDay == today.plusDays(1) }
        assertEquals(setOf("401873036"), view.shownOn(today.plusDays(1)))
        assertEquals(emptySet<String>(), view.shownOn(today))
        coroutineContext.cancelChildren()
    }

    @Test
    fun `unpaired, the TV makes the NFL from ESPN itself, with its own settings`() = runBlocking {
        val station = FakeNflStation(null).apply { paired.value = false }
        espnServer.games = listOf(FakeEspn.Game("1", "MIA", "BUF", now - 60 * 60_000L, "in", 10, 14, period = 2))
        val nfl = source(station, this)
        nfl.roundOfEspn()
        val view = nfl.eventually { it.state != null }
        assertEquals(NflFrom.ESPN, view.from)
        assertEquals(SeasonPhase.REGULAR, view.state!!.phase)
        assertEquals(GameState.LIVE, view.state!!.week!!.games.single().state)
        assertEquals(listOf("BUF", "MIA", "PHI", "DAL"), view.state!!.standings.map { it.abbr })
        assertEquals(now, view.asOfMillis)
        assertEquals(listOf("scoreboard", "standings", "teams"), espnServer.asked.toList())

        assertNull(nfl.save(NflSettings(yourTeams = listOf("BUF"))))
        assertEquals(listOf("BUF"), nfl.eventually { it.settings.yourTeams.isNotEmpty() }.settings.yourTeams)
        assertTrue(station.saved.isEmpty())
        assertTrue(station.asked.isEmpty())
        coroutineContext.cancelChildren()
    }

    @Test
    fun `unpaired with the NFL off, nothing asks ESPN until Show the NFL is turned on`() = runBlocking {
        val station = FakeNflStation(null).apply { paired.value = false }
        espnServer.games = listOf(FakeEspn.Game("1", "MIA", "BUF", now - 60 * 60_000L, "in", 10, 14, period = 2))
        val nfl = source(station, this)
        launch { nfl.keepUp(flowOf(true)) }
        delay(300)
        assertTrue(espnServer.asked.isEmpty())
        assertFalse(nfl.view.value.showing)

        // Turned on in Settings › NFL: kept even before ESPN answers, then ESPN is asked.
        assertNull(nfl.save(NflSettings(show = true)))
        assertTrue(nfl.eventually { it.showing }.showing)
        withTimeout(5_000) { while (espnServer.count("scoreboard") == 0) yield() }
        assertTrue(nfl.eventually { it.state != null }.showing)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `paired, the TV still follows the station with the NFL off, so it hears another TV turn it on`() = runBlocking {
        val station = FakeNflStation(SampleNfl.state().let { it.copy(settings = it.settings.copy(show = false)) })
        val nfl = source(station, this)
        launch { nfl.keepUp(flowOf(true)) }
        assertFalse(nfl.eventually { it.state != null }.showing)
        station.state = SampleNfl.state().let { it.copy(version = 58, settings = it.settings.copy(show = true)) }
        station.changes.send(58)
        assertTrue(nfl.eventually { it.state?.version == 58L }.showing)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `paired, a house's switch turned on before the station has a copy reads as on`() = runBlocking {
        val station = FakeNflStation(null)
        val nfl = source(station, this)
        assertNull(nfl.save(NflSettings(show = true)))
        assertTrue(nfl.eventually { it.showing }.showing)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `unpaired, a failing ESPN keeps the copy and says why`() = runBlocking {
        val nfl = source(FakeNflStation(null).apply { paired.value = false }, this)
        espnServer.games = listOf(FakeEspn.Game("1", "MIA", "BUF", now))
        nfl.roundOfEspn()
        nfl.eventually { it.state != null }
        espnServer.failing = true
        now += 60_000
        nfl.roundOfEspn()
        val view = nfl.eventually { it.problem != null }
        assertEquals("ESPN answered 503.", view.problem)
        assertEquals(1, view.state!!.week!!.games.size)
        assertEquals(now, store.current.triedAtMillis)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `other weeks come from the station while paired, else from ESPN`() = runBlocking {
        val station = FakeNflStation(SampleNfl.state())
        val nfl = source(station, this)
        assertTrue(nfl.week(2, 7, 2026) is NflLoad.Loaded)
        assertEquals("week 2/7/2026", station.asked.last())

        station.paired.value = false
        val week = nfl.week(2, 7, 2026) as NflLoad.Loaded
        assertEquals("PHI", week.value.games.single().winner)
        espnServer.failing = true
        assertEquals(NflLoad.Failed("ESPN answered 503."), nfl.week(2, 7, 2026))
        coroutineContext.cancelChildren()
    }

    @Test
    fun `logos are fetched once, into the cache, from the station while paired, else ESPN's`() = runBlocking {
        val dir = Files.createTempDirectory("nfl-logos").toFile()
        val station = FakeNflStation(SampleNfl.state())
        val logos = NflLogos(dir, station, espn, clock = { now })
        val file = logos.file("PHI", LogoSize.SMALL)!!
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        logos.file("PHI", LogoSize.SMALL)
        assertEquals(listOf("logo PHI s"), station.asked.toList())

        station.paired.value = false
        assertArrayEquals(byteArrayOf(9, 9), logos.file("BUF", LogoSize.LARGE)!!.readBytes())
        assertEquals(1, espnServer.count("logo"))
        assertNull(logos.file("../x", LogoSize.SMALL))
        dir.deleteRecursively()
        Unit
    }
}
