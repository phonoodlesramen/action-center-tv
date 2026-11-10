package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.guide.ChannelDraft
import com.phonoodlesramen.actioncenter.guide.ChannelRule
import com.phonoodlesramen.actioncenter.guide.RuleChoices
import com.phonoodlesramen.actioncenter.guide.station.ApiError
import com.phonoodlesramen.actioncenter.guide.station.Changed
import com.phonoodlesramen.actioncenter.guide.station.ChannelPatch
import com.phonoodlesramen.actioncenter.guide.station.ChannelPlayed
import com.phonoodlesramen.actioncenter.guide.station.Created
import com.phonoodlesramen.actioncenter.guide.station.Favorites
import com.phonoodlesramen.actioncenter.guide.station.PairRequest
import com.phonoodlesramen.actioncenter.guide.station.PairStarted
import com.phonoodlesramen.actioncenter.guide.station.PairState
import com.phonoodlesramen.actioncenter.guide.station.PairStatus
import com.phonoodlesramen.actioncenter.guide.station.PlexLinkStarted
import com.phonoodlesramen.actioncenter.guide.station.PlexLinkState
import com.phonoodlesramen.actioncenter.guide.station.PlexLinkStatus
import com.phonoodlesramen.actioncenter.guide.station.Preview
import com.phonoodlesramen.actioncenter.guide.station.SpotAnswer
import com.phonoodlesramen.actioncenter.guide.station.SpotBody
import com.phonoodlesramen.actioncenter.guide.station.SpotForget
import com.phonoodlesramen.actioncenter.guide.station.SpotKey
import com.phonoodlesramen.actioncenter.guide.station.StationApi
import com.phonoodlesramen.actioncenter.guide.station.StationDevice
import com.phonoodlesramen.actioncenter.guide.station.StationGuide
import com.phonoodlesramen.actioncenter.guide.station.StationInfo
import com.phonoodlesramen.actioncenter.nfl.GameState
import com.phonoodlesramen.actioncenter.nfl.NflGameDetail
import com.phonoodlesramen.actioncenter.nfl.NflSettings
import com.phonoodlesramen.actioncenter.nfl.NflState
import com.phonoodlesramen.actioncenter.nfl.NflTeamSchedule
import com.phonoodlesramen.actioncenter.nfl.NflWeek
import com.phonoodlesramen.actioncenter.nfl.SeasonPhase
import java.io.IOException
import java.nio.file.Files
import java.security.cert.X509Certificate
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A real station on localhost (a random port, a temporary volume, a fake Plex) driven by TVs
 * with software EC keys over OkHttp, as `:app` will drive it.
 */
class StationServerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var plex: FakePlex
    private lateinit var espn: FakeEspn
    private lateinit var station: Station

    @Before
    fun setUp() {
        plex = FakePlex()
        espn = FakeEspn(System.currentTimeMillis())
        station = start()
    }

    @After
    fun tearDown() {
        station.close()
        plex.close()
        espn.close()
    }

    /** ESPN is the fake one, polled by hand ([nflPoll]). */
    private fun start() = Station(
        StationConfig(port = 0, dataDir = temp.root.toPath(), hosts = listOf("127.0.0.1"), zone = ZoneOffset.UTC),
        Station.Options(
            plexTv = plex.url, espn = espn.url, espnCdn = espn.url, nflPolls = false, log = { logs += it }, changesWaitMillis = 500L,
            approveEveryMillis = 50L, plexPollMillis = 50L, plexRetryMillis = 100L,
        ),
    ).start()

    private fun nflPoll() = runBlocking { station.nfl.poll() }

    @Test
    fun `a TV pairs, is approved by a file drop, and then talks to the station over mutual TLS`() {
        val tv = pair(TestTv("Living room"))
        val client = tv.client()

        val info = client.send("GET", "/v1/station")
        assertEquals(200, info.code)
        assertEquals(StationApi.VERSION, info.headers[StationApi.VERSION_HEADER])
        val station = info.decode<StationInfo>()
        assertEquals(StationApi.VERSION, station.version)
        assertEquals("Z", station.timeZone)
        assertEquals(false, station.plex.linked)

        val devices = client.send("GET", "/v1/devices").decode<List<StationDevice>>()
        assertEquals(listOf("Living room"), devices.map { it.name })
        assertEquals("CN=${devices.single().id}", tv.certificate!!.subjectX500Principal.name)
        assertNotNull(devices.single().pairedAtMillis)
    }

    @Test
    fun `without a certificate, every path but pairing is refused`() {
        val ca = pair(TestTv()).ca!!
        val anonymous = Clients.mutual(key = null, certificate = null, ca = ca)
        val guarded = station.routes.filterNot { it.open }.map { it.method to it.example } +
            listOf("GET" to "/", "GET" to "/v1/nothing", "GET" to "/v2/guide", "DELETE" to "/v1/pair", "PUT" to "/v1/pair/x", "HEAD" to "/v1/guide")
        assertTrue(guarded.size > 20)
        for ((method, path) in guarded) {
            val answer = anonymous.send(method, path, if (method in BODIES) "{}" else null)
            assertEquals("$method $path", 401, answer.code)
            assertEquals("$method $path", StationApi.VERSION, answer.headers[StationApi.VERSION_HEADER])
            if (method != "HEAD") assertEquals(StationHandler.NOT_PAIRED, answer.decode<ApiError>().error)
        }
        // The pairing calls answer without one.
        assertEquals(PairState.EXPIRED, anonymous.send("GET", "/v1/pair/0123").decode<PairStatus>().state)
        assertEquals(400, anonymous.send("POST", "/v1/pair", "{}").code)
    }

    @Test
    fun `an unpaired TV's certificate is answered 403 unpaired, and a TV can unpair itself`() {
        val living = pair(TestTv("Living room"))
        val kitchen = pair(TestTv("Kitchen"))
        val kitchenId = deviceId(living, "Kitchen")

        assertEquals(200, living.client().send("DELETE", "/v1/devices/$kitchenId").code)
        val refused = kitchen.client().send("GET", "/v1/guide")
        assertEquals(403, refused.code)
        assertEquals("""{"error":"unpaired"}""", refused.body)
        assertEquals(StationApi.VERSION, refused.headers[StationApi.VERSION_HEADER])
        assertTrue(logs.any { it.startsWith("Unpaired \"Kitchen\" ($kitchenId), from \"Living room\"") })

        val livingId = deviceId(living, "Living room")
        assertEquals(200, living.client().send("DELETE", "/v1/devices/$livingId").code)
        assertEquals(403, living.client().send("GET", "/v1/station").code)
        assertEquals(404, pair(TestTv("Den")).client().send("DELETE", "/v1/devices/$kitchenId").code)
    }

    @Test
    fun `a certificate from another CA is refused in the handshake`() {
        val ca = pair(TestTv()).ca!!
        val elsewhere = StationCa.load(temp.newFolder("elsewhere").toPath(), emptyList(), System.currentTimeMillis(), {})
        val stranger = TestTv("Stranger")
        val foreign = elsewhere.issueClient("tv-0123456789ab", stranger.keys.public, System.currentTimeMillis())
        val client = Clients.mutual(stranger.keys.private, foreign, ca)
        try {
            val answer = client.send("GET", "/v1/station")
            fail("answered ${answer.code}")
        } catch (e: IOException) {
            // Refused by TLS, before any HTTP.
        }
    }

    @Test
    fun `a channel made through the editor is in the guide, and an unchanged guide answers 304`() {
        val client = pair(TestTv()).client()
        runBlocking { station.plexDatabase.dao().upsertItems(Library.films) }

        val choices = client.send("GET", "/v1/editor/choices").decode<RuleChoices>()
        assertEquals(listOf(Library.MOVIES), choices.sections.map { it.key })

        val draft = ChannelDraft("Made-up Movies", ChannelRule(types = listOf(ChannelRule.MOVIE)))
        val created = client.send("POST", "/v1/channels", json(draft)).decode<Created>()

        val answer = client.send("GET", "/v1/guide")
        assertEquals(200, answer.code)
        assertEquals(created.version.toString(), answer.headers["ETag"])
        val guide = answer.decode<StationGuide>()
        assertEquals(created.version, guide.version)
        val channel = guide.channels.single()
        assertEquals(created.id, channel.id)
        assertEquals("Made-up Movies", channel.name)
        assertTrue(channel.slots.size > 20)
        assertTrue(channel.slots.last().endMillis - System.currentTimeMillis() >= StationApi.HORIZON_MILLIS - 60_000L)
        assertTrue(channel.slots.filterNot { it.filler }.all { it.itemKey in Library.films.map { film -> film.ratingKey } && it.offAir == null })

        val unchanged = client.send("GET", "/v1/guide", headers = mapOf("If-None-Match" to guide.version.toString()))
        assertEquals(304, unchanged.code)
        assertEquals("", unchanged.body)
        assertEquals(StationApi.VERSION, unchanged.headers[StationApi.VERSION_HEADER])

        val renamed = client.send("PATCH", "/v1/channels/${created.id}", json(ChannelPatch(name = "Films", number = 7))).decode<Changed>()
        assertTrue(renamed.version > created.version)
        assertEquals(200, client.send("GET", "/v1/guide", headers = mapOf("If-None-Match" to "\"${guide.version}\"")).code)
        val now = client.send("GET", "/v1/guide").decode<StationGuide>().channels.single()
        assertEquals("Films" to 7, now.name to now.number)

        val preview = client.send("POST", "/v1/preview", json(draft)).decode<Preview>()
        assertTrue(preview.slots.all { it.startMillis < System.currentTimeMillis() + 24 * 60 * 60_000L })

        val copy = client.send("POST", "/v1/channels/${created.id}/duplicate").decode<Created>()
        assertEquals(listOf("Films", "Films 2"), client.send("GET", "/v1/guide").decode<StationGuide>().channels.map { it.name })
        client.send("DELETE", "/v1/channels/${copy.id}").decode<Changed>()
        assertEquals(1, client.send("GET", "/v1/guide").decode<StationGuide>().channels.size)

        val rejected = client.send("PATCH", "/v1/channels/${created.id}", json(ChannelPatch(number = 0)))
        assertEquals(400, rejected.code)
        assertEquals("Channel numbers go from 1 to 9999.", rejected.decode<ApiError>().error)
        assertEquals(400, client.send("POST", "/v1/lineup/remake").code)
        assertEquals(400, client.send("POST", "/v1/channels", "{\"name\": ").code)
    }

    @Test
    fun `two TVs share the house's spot`() {
        val living = pair(TestTv("Living room"))
        val kitchen = pair(TestTv("Kitchen"))
        runBlocking { station.plexDatabase.dao().upsertItems(Library.films) }
        val draft = ChannelDraft("Made-up Movies", ChannelRule(types = listOf(ChannelRule.MOVIE)))
        val channelId = living.client().send("POST", "/v1/channels", json(draft)).decode<Created>().id
        val program = kitchen.client().send("GET", "/v1/guide").decode<StationGuide>().channels.single().slots.first { !it.filler }
        val body = SpotBody(channelId, program.startMillis, program.itemKey!!, 600_000L, System.currentTimeMillis())

        val saved = living.client().send("PUT", "/v1/spot", json(body)).decode<Changed>()
        val seen = kitchen.client().send("GET", "/v1/spot").decode<SpotAnswer>().spot!!
        assertEquals("Living room", seen.room)
        assertEquals(deviceId(living, "Living room"), seen.device)
        assertEquals(600_000L, seen.positionMs)

        // Another channel playing in the kitchen leaves the living room's spot.
        assertEquals(saved.version, kitchen.client().send("POST", "/v1/spot/played", json(ChannelPlayed("c-other"))).decode<Changed>().version)
        assertNotNull(kitchen.client().send("GET", "/v1/spot").decode<SpotAnswer>().spot)

        // Resuming in the kitchen takes it over: it's saved there, and the living room's channel change no longer forgets it.
        kitchen.client().send("PUT", "/v1/spot", json(body.copy(positionMs = 900_000L)))
        living.client().send("POST", "/v1/spot/played", json(ChannelPlayed("c-other")))
        assertEquals("Kitchen", living.client().send("GET", "/v1/spot").decode<SpotAnswer>().spot?.room)

        val forgot = kitchen.client().send("POST", "/v1/spot/played", json(ChannelPlayed("c-other"))).decode<Changed>()
        assertTrue(forgot.version > saved.version)
        assertNull(living.client().send("GET", "/v1/spot").decode<SpotAnswer>().spot)

        living.client().send("PUT", "/v1/spot", json(body))
        living.client().send("DELETE", "/v1/spot", json(SpotForget(SpotKey(channelId, program.startMillis, "not-it"))))
        assertNotNull(kitchen.client().send("GET", "/v1/spot").decode<SpotAnswer>().spot)
        kitchen.client().send("DELETE", "/v1/spot")
        assertEquals("{}", living.client().send("GET", "/v1/spot").body)
    }

    @Test
    fun `changes answers as soon as something changes, and 204 when nothing does`() {
        val client = pair(TestTv()).client()
        val current = client.send("GET", "/v1/changes").decode<Changed>().version

        val quiet = client.send("GET", "/v1/changes?after=$current")
        assertEquals(204, quiet.code)
        assertEquals(StationApi.VERSION, quiet.headers[StationApi.VERSION_HEADER])

        val waiting = CompletableFuture.supplyAsync { client.send("GET", "/v1/changes?after=$current") }
        Thread.sleep(100)
        val saved = client.send("PUT", "/v1/spot", json(SpotBody("c-1", 1_793_046_600_000L, "5101", 0L, System.currentTimeMillis()))).decode<Changed>()
        val heard = waiting.get(5, TimeUnit.SECONDS)
        assertEquals(200, heard.code)
        assertEquals(saved.version, heard.decode<Changed>().version)
        assertEquals(saved.version, client.send("GET", "/v1/changes?after=$current").decode<Changed>().version)
    }

    @Test
    fun `a paired TV approves the next one from its devices list`() {
        val living = pair(TestTv("Living room"))
        val kitchen = TestTv("Kitchen")
        val started = Clients.pairing().send("POST", "/v1/pair", json(PairRequest(kitchen.name, kitchen.model, kitchen.publicKey))).decode<PairStarted>()

        val waiting = living.client().send("GET", "/v1/devices").decode<List<StationDevice>>().single { it.pending }
        assertEquals("Kitchen", waiting.name)
        assertEquals(StationApi.pairingCode(living.ca!!.encoded, kitchen.keys.public.encoded), waiting.code)
        assertEquals("{}", living.client().send("POST", "/v1/devices/${waiting.id}/approve").body)
        assertEquals(404, living.client().send("POST", "/v1/devices/${waiting.id}/approve").code)

        val status = Clients.pairing().send("GET", "/v1/pair/${started.requestId}").decode<PairStatus>()
        assertEquals(PairState.APPROVED, status.state)
        kitchen.certificate = StationCa.readCertificate(status.certificate!!)
        kitchen.ca = StationCa.readCertificate(status.caCertificate!!)
        assertEquals(200, kitchen.client().send("GET", "/v1/guide").code)
    }

    @Test
    fun `a TV still waiting can be refused`() {
        val living = pair(TestTv("Living room"))
        val stranger = TestTv("Stranger")
        val started = Clients.pairing().send("POST", "/v1/pair", json(PairRequest(stranger.name, stranger.model, stranger.publicKey))).decode<PairStarted>()
        val waiting = living.client().send("GET", "/v1/devices").decode<List<StationDevice>>().single { it.pending }
        assertEquals(200, living.client().send("DELETE", "/v1/devices/${waiting.id}").code)
        assertEquals(PairState.REFUSED, Clients.pairing().send("GET", "/v1/pair/${started.requestId}").decode<PairStatus>().state)
    }

    @Test
    fun `the station signs in to its own Plex, links the owned server and syncs its library`() {
        val client = pair(TestTv()).client()
        val started = client.send("POST", "/v1/plex/link").decode<PlexLinkStarted>()
        assertEquals("WXYZ", started.code)

        val linked = eventually { client.send("GET", "/v1/plex/link").decode<PlexLinkStatus>().takeIf { it.state == PlexLinkState.LINKED } }
        assertEquals("Made-up Server", linked.serverName)
        val synced = eventually { client.send("GET", "/v1/station").decode<StationInfo>().takeIf { it.library.lastSyncMillis != null } }
        assertTrue(synced.plex.linked)
        assertEquals("Made-up Server", synced.plex.serverName)
        assertNull(synced.library.error)
        assertTrue("/library/sections/1/all" in plex.asked)
        assertEquals(setOf("Action Center guide station"), plex.deviceNames.toSet())
        eventually { runBlocking { station.plexDatabase.dao().itemCount() }.takeIf { it == 30 } }

        // Its tokens are in its volume, and nowhere in its log.
        val saved = Files.readString(temp.root.toPath().resolve("station/plex.json"))
        assertTrue(FakePlex.TOKEN in saved && FakePlex.SERVER_TOKEN in saved)
        assertTrue(logs.none { FakePlex.TOKEN in it || FakePlex.SERVER_TOKEN in it || "BEGIN" in it })
    }

    @Test
    fun `a restarted station keeps its CA and its TVs, and moves its version on`() {
        val tv = pair(TestTv())
        val before = tv.client().send("GET", "/v1/changes").decode<Changed>().version
        station.close()
        station = start()
        val after = tv.client().send("GET", "/v1/changes").decode<Changed>().version
        assertTrue(after > before)
        assertEquals(listOf(tv.name), tv.client().send("GET", "/v1/devices").decode<List<StationDevice>>().map { it.name })
    }

    @Test
    fun `a TV's certificate still counts on a new or resumed TLS session`() {
        val client = pair(TestTv()).client()
        repeat(3) {
            assertEquals(200, client.send("GET", "/v1/station").code)
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `favorites are kept per profile, a change reaches every TV through changes, and a bad key is refused`() {
        val living = pair(TestTv("Living room"))
        val kitchen = pair(TestTv("Kitchen"))
        val start = kitchen.client().send("GET", "/v1/changes").decode<Changed>().version
        assertEquals(Favorites(emptyList(), 0), living.client().send("GET", "/v1/favorites/owner").decode<Favorites>())

        val waiting = CompletableFuture.supplyAsync { kitchen.client().send("GET", "/v1/changes?after=$start") }
        Thread.sleep(100)
        val saved = living.client().send("PUT", "/v1/favorites/owner", json(Favorites(listOf("c-2", "c-1")))).decode<Favorites>()
        assertEquals(listOf("c-2", "c-1"), saved.channels)
        assertTrue(saved.version > start)
        assertEquals(saved.version, waiting.get(5, TimeUnit.SECONDS).decode<Changed>().version)
        assertEquals(saved, kitchen.client().send("GET", "/v1/favorites/owner").decode<Favorites>())

        kitchen.client().send("PUT", "/v1/favorites/plex:42", json(Favorites(listOf("c-kids"))))
        assertEquals(listOf("c-kids"), living.client().send("GET", "/v1/favorites/plex:42").decode<Favorites>().channels)
        assertEquals(listOf("c-2", "c-1"), living.client().send("GET", "/v1/favorites/owner").decode<Favorites>().channels)

        for (key in listOf("guest", "plex:abc", "plex:")) {
            val refused = living.client().send("GET", "/v1/favorites/$key")
            assertEquals(key, 400, refused.code)
            assertTrue(refused.decode<ApiError>().error.endsWith("and that's neither."))
        }
        assertEquals(400, living.client().send("PUT", "/v1/favorites/guest", json(Favorites(listOf("c-1")))).code)
        assertEquals(400, living.client().send("PUT", "/v1/favorites/owner", "{\"channels\": 3}").code)

        station.close()
        station = start()
        assertEquals(listOf("c-2", "c-1"), living.client().send("GET", "/v1/favorites/owner").decode<Favorites>().channels)
    }

    @Test
    fun `the NFL over mutual TLS, the state with an ETag and 304, another week, a schedule, a game, a logo and the settings`() {
        val client = pair(TestTv()).client()
        val notYet = client.send("GET", "/v1/nfl")
        assertEquals(503, notYet.code)
        assertEquals(NflService.NOT_YET, notYet.decode<ApiError>().error)

        val kickoff = System.currentTimeMillis() - HOUR
        espn.games = listOf(FakeEspn.Game("1", "MIA", "BUF", kickoff, "in", 10, 14, period = 2))
        nflPoll()
        val answer = client.send("GET", "/v1/nfl")
        assertEquals(200, answer.code)
        val state = answer.decode<NflState>()
        assertEquals(state.version.toString(), answer.headers["ETag"])
        assertEquals(SeasonPhase.REGULAR, state.phase)
        assertEquals(GameState.LIVE, state.week!!.games.single().state)
        assertEquals(listOf("SF"), state.week!!.byes)
        assertEquals(4 to 4, state.teams.size to state.standings.size)
        assertEquals(304, client.send("GET", "/v1/nfl", headers = mapOf("If-None-Match" to "\"${state.version}\"")).code)

        espn.weeks["2026/2/7"] = listOf(FakeEspn.Game("7", "PHI", "DAL", kickoff - 7 * FakeEspn.Season.DAY, "post", 21, 17, period = 4))
        assertEquals("PHI", client.send("GET", "/v1/nfl/week/2/7").decode<NflWeek>().games.single().winner)
        assertEquals(404, client.send("GET", "/v1/nfl/week/2/8").code)
        assertEquals(404, client.send("GET", "/v1/nfl/week/x/1").code)
        assertEquals("BUF", client.send("GET", "/v1/nfl/teams/BUF/schedule").decode<NflTeamSchedule>().abbr)
        assertEquals(404, client.send("GET", "/v1/nfl/teams/XYZ/schedule").code)
        assertEquals(listOf("402"), client.send("GET", "/v1/nfl/games/1").decode<NflGameDetail>().homeStats.map { it.value })

        val logo = client.send("GET", "/v1/nfl/logos/BUF?size=l")
        assertEquals(200, logo.code)
        assertEquals("image/png", logo.headers["Content-Type"])
        assertEquals("max-age=604800", logo.headers["Cache-Control"])
        assertArrayEquals(FakeEspn.LOGO, logo.bytes)
        client.send("GET", "/v1/nfl/logos/BUF?size=l")
        assertEquals(1, espn.count("logo"))
        assertEquals(400, client.send("GET", "/v1/nfl/logos/BUF?size=xl").code)

        assertEquals(NflSettings(), client.send("GET", "/v1/nfl/settings").decode<NflSettings>())
        val changed = client.send("PUT", "/v1/nfl/settings", json(NflSettings(yourTeams = listOf("PHI"), hideScores = true))).decode<Changed>()
        assertTrue(changed.version > state.version)
        val after = client.send("GET", "/v1/nfl", headers = mapOf("If-None-Match" to state.version.toString()))
        assertEquals(changed.version.toString(), after.headers["ETag"])
        assertEquals(listOf("PHI"), after.decode<NflState>().settings.yourTeams)
        assertEquals(400, client.send("PUT", "/v1/nfl/settings", json(NflSettings(areaTeams = listOf("XYZ")))).code)
    }

    @Test
    fun `nfl changes answers as soon as a score moves, and leaves the guide's changes alone`() {
        val client = pair(TestTv()).client()
        val kickoff = System.currentTimeMillis() - HOUR
        espn.games = listOf(FakeEspn.Game("1", "MIA", "BUF", kickoff, "in", 10, 14, period = 2))
        nflPoll()
        val current = client.send("GET", "/v1/nfl/changes").decode<Changed>().version
        val guide = client.send("GET", "/v1/changes").decode<Changed>().version
        assertEquals(204, client.send("GET", "/v1/nfl/changes?after=$current").code)

        val waiting = CompletableFuture.supplyAsync { client.send("GET", "/v1/nfl/changes?after=$current") }
        Thread.sleep(100)
        espn.games = listOf(FakeEspn.Game("1", "MIA", "BUF", kickoff, "in", 10, 21, period = 3))
        nflPoll()
        val heard = waiting.get(5, TimeUnit.SECONDS)
        assertEquals(200, heard.code)
        val scored = client.send("GET", "/v1/nfl").decode<NflState>()
        assertEquals(heard.decode<Changed>().version, scored.version)
        assertEquals(21, scored.week!!.games.single().homeScore)
        assertEquals(204, client.send("GET", "/v1/changes?after=$guide").code)
    }

    @Test
    fun `an old TV's calls still work and answer only what they did, and favorites reach it as a guide change`() {
        val old = pair(TestTv("Kitchen"))
        val new = pair(TestTv("Living room"))
        runBlocking { station.plexDatabase.dao().upsertItems(Library.films) }
        new.client().send("POST", "/v1/channels", json(ChannelDraft("Made-up Movies", ChannelRule(types = listOf(ChannelRule.MOVIE)))))

        val answer = old.client().send("GET", "/v1/guide")
        assertEquals(StationApi.MAJOR.toString(), answer.headers[StationApi.VERSION_HEADER]!!.substringBefore('.'))
        val guide = answer.decodeStrict<StationGuide>()
        assertEquals("Made-up Movies", guide.channels.single().name)
        old.client().send("GET", "/v1/station").decodeStrict<StationInfo>()
        old.client().send("GET", "/v1/devices").decodeStrict<List<StationDevice>>()
        old.client().send("GET", "/v1/editor/choices").decodeStrict<RuleChoices>()
        val program = guide.channels.single().slots.first { !it.filler }
        old.client().send("PUT", "/v1/spot", json(SpotBody(guide.channels.single().id, program.startMillis, program.itemKey!!, 0L, System.currentTimeMillis())))
            .decodeStrict<Changed>()
        assertEquals("Kitchen", old.client().send("GET", "/v1/spot").decodeStrict<SpotAnswer>().spot?.room)
        val since = old.client().send("GET", "/v1/changes").decodeStrict<Changed>().version

        val waiting = CompletableFuture.supplyAsync { old.client().send("GET", "/v1/changes?after=$since") }
        Thread.sleep(100)
        new.client().send("PUT", "/v1/favorites/owner", json(Favorites(listOf(guide.channels.single().id))))
        val heard = waiting.get(5, TimeUnit.SECONDS).decodeStrict<Changed>().version
        val again = old.client().send("GET", "/v1/guide", headers = mapOf("If-None-Match" to since.toString()))
        assertEquals(200, again.code)
        assertEquals(heard.toString(), again.headers["ETag"])
        assertEquals(guide.channels.map { it.id to it.name }, again.decodeStrict<StationGuide>().channels.map { it.id to it.name })
    }

    /** Pairs [tv] as the first TV does: the code checked against the log, then approved by a file in `approve/`. */
    private fun pair(tv: TestTv): TestTv {
        val seen = mutableListOf<X509Certificate>()
        val started = Clients.pairing(seen).send("POST", "/v1/pair", json(PairRequest(tv.name, tv.model, tv.publicKey))).decode<PairStarted>()
        val caSeen = seen.last()
        val code = StationApi.pairingCode(caSeen.encoded, tv.keys.public.encoded)
        assertTrue("the log shows the TV's code $code", logs.contains("Pairing: \"${tv.name}\" code $code"))

        Files.createFile(temp.root.toPath().resolve("approve/$code"))
        val status = eventually {
            Clients.pairing().send("GET", "/v1/pair/${started.requestId}").decode<PairStatus>().takeIf { it.state == PairState.APPROVED }
        }
        tv.certificate = StationCa.readCertificate(status.certificate!!)
        tv.ca = StationCa.readCertificate(status.caCertificate!!)
        assertArrayEquals(caSeen.encoded, tv.ca!!.encoded)
        return tv
    }

    private fun deviceId(tv: TestTv, name: String) = tv.client().send("GET", "/v1/devices").decode<List<StationDevice>>().single { it.name == name }.id

    private class Answer(val code: Int, val bytes: ByteArray, val headers: Headers) {
        val body: String get() = bytes.toString(Charsets.UTF_8)

        inline fun <reified T> decode(): T = decode(serializer<T>())

        fun <T> decode(serializer: KSerializer<T>): T = StationApi.json.decodeFromString(serializer, body)

        /** As a TV that knows only these fields reads it: anything new fails. */
        inline fun <reified T> decodeStrict(): T = STRICT.decodeFromString(serializer<T>(), body)
    }

    private fun OkHttpClient.send(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): Answer {
        val requestBody = (body ?: if (method in BODIES) "" else null)?.toRequestBody(JSON)
        val request = Request.Builder().url("https://localhost:${station.port}$path").method(method, requestBody)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return newCall(request).execute().use { Answer(it.code, it.body.bytes(), it.headers) }
    }

    private inline fun <reified T> json(value: T): String = StationApi.json.encodeToString(serializer<T>(), value)

    private fun <T : Any> eventually(within: Long = 10_000L, read: () -> T?): T {
        val until = System.currentTimeMillis() + within
        while (true) {
            read()?.let { return it }
            if (System.currentTimeMillis() > until) fail("still not so after $within ms")
            Thread.sleep(50)
        }
    }

    private companion object {
        val BODIES = setOf("POST", "PUT", "PATCH")
        val JSON = "application/json".toMediaType()
        val STRICT = Json { explicitNulls = false }
        const val HOUR = 60 * 60_000L
    }
}
