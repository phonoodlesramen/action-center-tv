package com.phonoodlesramen.actioncenter.station

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phonoodlesramen.actioncenter.guide.ChannelDraft
import com.phonoodlesramen.actioncenter.guide.ChannelRule
import com.phonoodlesramen.actioncenter.guide.EditRejected
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideDatabase
import com.phonoodlesramen.actioncenter.guide.GuideEngine
import com.phonoodlesramen.actioncenter.guide.KidGuide
import com.phonoodlesramen.actioncenter.guide.LineupServer
import com.phonoodlesramen.actioncenter.guide.RoomGuideEditor
import com.phonoodlesramen.actioncenter.guide.station.PairState
import com.phonoodlesramen.actioncenter.guide.station.StationApi
import com.phonoodlesramen.actioncenter.guide.station.StationSpot
import com.phonoodlesramen.actioncenter.player.MemorySpots
import com.phonoodlesramen.actioncenter.player.SavedSpot
import com.phonoodlesramen.actioncenter.plex.cache.PlexDatabase
import com.phonoodlesramen.actioncenter.station.FakeStation.Companion.MINUTE
import com.phonoodlesramen.actioncenter.station.FakeStation.Companion.NOW
import com.phonoodlesramen.actioncenter.ui.nfl.SampleNfl
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The TV's side of the guide station (Phase 8) against a fake station over HTTPS with mutual
 * TLS and a software key: pairing (the code, approved, refused, expired, a machine in the
 * middle), the guide copied into `guide.db` and read through `GuideSource`, ETag, `changes`,
 * offline, editing, the house's spot across rooms, being unpaired, and leaving.
 */
@RunWith(RobolectricTestRunner::class)
class GuideStationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, GuideDatabase::class.java).allowMainThreadQueries().build()
    private val store = MemoryStationStore()
    private val keys = SoftwareKeys()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lineupServer = LineupServer.InMemory("an-old-server")
    private val fake = FakeStation().start()
    private val station = station()
    private val source = StationGuideSource(db, station.guideCopy, clock = { NOW })

    private fun station(http: OkHttpClient = OkHttpClient()) = GuideStation(
        store, { keys }, http, db, lineupServer, linkedServer = { "server-1" }, model = "SHIELD Android TV", scope = scope,
        clock = { NOW }, timing = FAST,
    )

    @After
    fun tearDown() {
        scope.cancel()
        fake.stop()
        db.close()
    }

    private fun <T> await(flow: Flow<T>, what: String = "", until: (T) -> Boolean): T = runBlocking {
        var last: T? = null
        try {
            withTimeout(10_000) { flow.first { last = it; until(it) } }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $what; last: $last", e)
        }
    }

    /** Pairs with [fake] as the kitchen TV, approves it on the station, and waits for the first copy. */
    private fun pairAndCopy() {
        station.pair(fake.address, "Kitchen")
        val pairing = await(station.view, "the code") { it.phase is StationPhase.Pairing }.phase as StationPhase.Pairing
        fake.approve(pairing.code)
        await(station.view, "paired") { it.phase is StationPhase.Paired }
        await(store.saved, "the copy") { it.guide != null && it.deviceId != null }
    }

    private fun ready(): GuideCardState.Ready = runBlocking { withTimeout(10_000) { source.card.filterIsInstance<GuideCardState.Ready>().first() } }

    @Test
    fun `pairing shows the code both ends work out, and approval pairs the TV and copies the guide`() = runBlocking<Unit> {
        station.pair(fake.address, "Kitchen")
        val pairing = await(station.view, "the code") { it.phase is StationPhase.Pairing }.phase as StationPhase.Pairing

        // The TV's code is over the CA it saw and its own key, and the station shows the same one.
        assertEquals(StationApi.pairingCode(fake.ca.encoded, keys.publicKey().encoded), pairing.code)
        assertEquals(fake.pendingCode(), pairing.code)
        assertEquals(PairState.PENDING, pairing.state)
        assertFalse(store.current.paired)

        fake.approve(pairing.code)
        val view = await(station.view, "paired") { it.phase is StationPhase.Paired }
        assertTrue(view.paired)
        assertEquals("Kitchen", view.room)
        assertEquals(fake.address, view.address)
        val saved = await(store.saved, "the copy") { it.guide != null && it.deviceId != null }
        assertNotNull(saved.certificate)
        assertEquals(StationTls.pem(fake.ca), saved.caCertificate)
        assertEquals(fake.devices().single().id, saved.deviceId)
        assertTrue(fake.calls.any { it.startsWith("GET /v1/guide") })
        assertEquals(listOf("Comedy", "Cartoons"), ready().channels.map { it.name })
    }

    @Test
    fun `a refused pairing says so, and an expired one too`() {
        station.pair(fake.address, "Kitchen")
        var pairing = await(station.view, "the code") { it.phase is StationPhase.Pairing }.phase as StationPhase.Pairing
        fake.refuse(pairing.code)
        val refused = await(station.view, "refused") { (it.phase as? StationPhase.Pairing)?.state == PairState.REFUSED }
        assertFalse(refused.paired)
        assertNull(store.current.pairing)

        station.pair(fake.address, "Kitchen")
        pairing = await(station.view, "a new code") { (it.phase as? StationPhase.Pairing)?.state == PairState.PENDING }.phase as StationPhase.Pairing
        fake.expire(pairing.code)
        await(station.view, "expired") { (it.phase as? StationPhase.Pairing)?.state == PairState.EXPIRED }
        assertFalse(store.current.paired)

        station.cancelPairing()
        await(station.view, "unpaired") { it.phase == StationPhase.Unpaired }
    }

    @Test
    fun `a machine in the middle shows another code, and its certificate authority is never trusted`() {
        val middle = FakeStation().start()
        try {
            station.pair(fake.address, "Kitchen")
            val pairing = await(station.view, "the code") { it.phase is StationPhase.Pairing }.phase as StationPhase.Pairing
            // Another CA gives the two ends different codes, so comparing them catches it.
            assertNotEquals(StationApi.pairingCode(middle.ca.encoded, keys.publicKey().encoded), pairing.code)

            // And an approval that hands over a CA other than the one whose code showed is refused.
            fake.caOnApproval = middle.ca
            fake.approve(pairing.code)
            val refused = await(station.view, "refused") { (it.phase as? StationPhase.Pairing)?.state == PairState.REFUSED }
            assertNotNull(refused.problem)
            assertFalse(store.current.paired)
        } finally {
            middle.stop()
        }
    }

    @Test
    fun `the copy is read through GuideSource, with each channel's ratings for kids and programs off air`() {
        pairAndCopy()
        val lineup = ready()
        val comedy = lineup.channels.first { it.id == "c-comedy" }
        assertEquals(setOf("TV-PG", "TV-14"), comedy.contentRatings)
        assertEquals("This title is no longer in your library", comedy.slotAt(NOW + 45 * MINUTE)?.offAir)
        assertNull(comedy.slotAt(NOW + 5 * MINUTE)?.offAir)
        assertEquals("comedy 2", comedy.slotAt(NOW + 5 * MINUTE)?.title)
        // Kids see the channels whose ratings are all inside their ceiling (KidGuide, unchanged).
        val kids = KidGuide.of(lineup, setOf("TV-Y", "TV-Y7", "G")) as GuideCardState.Ready
        assertEquals(listOf("Cartoons"), kids.channels.map { it.name })
        // guide.db holds the channels as this TV's own would be, scheduled to where the copy ends.
        val channels = runBlocking { db.dao().channels() }
        assertEquals(listOf(3, 7), channels.map { it.number })
        assertEquals(NOW + StationApi.HORIZON_MILLIS, channels.first().scheduledUntil)
    }

    @Test
    fun `an unchanged guide is a 304, and an edit elsewhere is copied over the old one`() = runBlocking<Unit> {
        pairAndCopy()
        assertTrue(station.refresh())
        assertEquals(1, fake.notModified)

        fake.edit { guide -> guide.copy(channels = guide.channels.filter { it.id == "c-cartoons" }) }
        assertTrue(station.refresh())
        assertEquals(listOf("Cartoons"), await(source.card, "one channel") { (it as? GuideCardState.Ready)?.channels?.size == 1 }.let { (it as GuideCardState.Ready).channels.map { c -> c.name } })
        assertEquals(listOf("c-cartoons"), db.dao().channels().map { it.id })
        assertTrue(db.dao().slots("c-comedy").isEmpty())
    }

    @Test
    fun `a change on the station reaches the TV through changes while the home is in front`() {
        pairAndCopy()
        val following = scope.launch { station.keepUp() }
        try {
            fake.edit { guide -> guide.copy(channels = guide.channels.map { if (it.id == "c-comedy") it.copy(name = "Sitcoms") else it }) }
            val lineup = await(source.card, "the rename") { (it as? GuideCardState.Ready)?.channels?.any { c -> c.name == "Sitcoms" } == true }
            assertNotNull(lineup)
        } finally {
            following.cancel()
        }
    }

    @Test
    fun `with the station down the TV keeps its copy and greys editing out, then picks up again`() = runBlocking<Unit> {
        pairAndCopy()
        val editor = StationGuideEditor(station, localEditor().lineup)
        fake.down = true

        assertFalse(station.refresh())
        val view = await(station.view, "unreachable") { it.reach == StationReach.UNREACHABLE }
        assertEquals("Can't reach the guide station", view.editingLocked)
        assertEquals(listOf("Comedy", "Cartoons"), ready().channels.map { it.name })
        try {
            editor.rename("c-comedy", "Sitcoms")
            fail("an edit with the station down")
        } catch (e: EditRejected) {
            assertEquals("Can't reach the guide station.", e.message)
        }

        fake.down = false
        assertTrue(station.refresh())
        assertNull(await(station.view, "connected") { it.reach == StationReach.CONNECTED }.editingLocked)
    }

    @Test
    fun `edits go to the station one for one, and the copy follows at once`() = runBlocking<Unit> {
        pairAndCopy()
        val editor = StationGuideEditor(station, localEditor().lineup)
        fake.calls.clear()

        assertEquals(FakeStation.CHOICES, editor.choices())
        editor.rename("c-comedy", "Sitcoms")
        assertEquals("Sitcoms", await(editor.lineup, "the rename") { list -> list.any { it.draft.name == "Sitcoms" } }.first { it.id == "c-comedy" }.draft.name)
        editor.renumber("c-comedy", 12)
        editor.move("c-cartoons", 0)
        editor.setEnabled("c-cartoons", false)
        val draft = ChannelDraft("Westerns", ChannelRule(genres = listOf("Western")))
        assertEquals("c-westerns", editor.create(draft))
        editor.update("c-westerns", draft.copy(name = "Old West"))
        assertEquals("c-comedy-2", editor.duplicate("c-comedy"))
        editor.delete("c-comedy-2")
        assertEquals(2, editor.preview(draft).size)
        editor.remakeStarterLineup()

        val edits = fake.calls.filter { !it.startsWith("GET") }
        assertEquals(
            listOf(
                """PATCH /v1/channels/c-comedy {"name":"Sitcoms"}""",
                """PATCH /v1/channels/c-comedy {"number":12}""",
                """PATCH /v1/channels/c-cartoons {"index":0}""",
                """PATCH /v1/channels/c-cartoons {"enabled":false}""",
                """POST /v1/channels {"name":"Westerns","rule":{"genres":["Western"]}}""",
                """PUT /v1/channels/c-westerns {"name":"Old West","rule":{"genres":["Western"]}}""",
                "POST /v1/channels/c-comedy/duplicate",
                "DELETE /v1/channels/c-comedy-2",
                """POST /v1/preview {"name":"Westerns","rule":{"genres":["Western"]}}""",
                "POST /v1/lineup/remake",
            ),
            edits,
        )
        assertTrue(fake.calls.contains("GET /v1/editor/choices"))
        // Each edit copied the guide at once.
        assertEquals(listOf("Old West", "Sitcoms", "Cartoons").sorted(), db.dao().channels().map { it.name }.sorted())
    }

    @Test
    fun `studios and labels cross to the station and back, and an older station's choices have none`() = runBlocking<Unit> {
        pairAndCopy()
        val editor = StationGuideEditor(station, localEditor().lineup)

        // A 1.0.0 station's answer, from before Phase 9: the phone editor hides both rows (F3).
        fake.choicesJson = """{"sections":[{"key":"1","title":"Movies"}],"movieGenres":["Comedy"],"tvGenres":["Sitcom"],""" +
            """"shows":[{"key":"4800","title":"Frasier"}],"collections":[],"decades":[1990],"contentRatings":["TV-PG"]}"""
        assertEquals(FakeStation.CHOICES, editor.choices())
        assertEquals(emptyList<String>() to emptyList<String>(), editor.choices().let { it.studios to it.labels })

        fake.choicesJson = null
        fake.choices = FakeStation.CHOICES.copy(studios = listOf("Northlight", "Tidewater"), labels = listOf("Favourites"))
        assertEquals(fake.choices, editor.choices())

        fake.calls.clear()
        val rule = ChannelRule(studios = listOf("Northlight"), labels = listOf("Favourites"))
        assertEquals("c-northlight", editor.create(ChannelDraft("Northlight", rule)))
        assertEquals(
            """POST /v1/channels {"name":"Northlight","rule":{"studios":["Northlight"],"labels":["Favourites"]}}""",
            fake.calls.single { it.startsWith("POST") },
        )
        // The station's guide brings the rule back whole.
        val copied = db.dao().channels().single { it.name == "Northlight" }
        assertEquals(rule, ChannelRule.fromJson(copied.rule))
    }

    @Test
    fun `an edit the station turns down shows its own sentence`() = runBlocking<Unit> {
        pairAndCopy()
        val editor = StationGuideEditor(station, localEditor().lineup)
        fake.reject = "Channel 7 is taken by Cartoons."
        try {
            editor.renumber("c-comedy", 7)
            fail("a refused edit")
        } catch (e: EditRejected) {
            assertEquals("Channel 7 is taken by Cartoons.", e.message)
        }
        // The station answered: editing isn't greyed out.
        assertNull(withTimeoutOrNull(500) { station.view.first { it.reach != StationReach.CONNECTED } })
    }

    @Test
    fun `the house's spot names another room, not this one, and playing here forgets only this TV's`() = runBlocking<Unit> {
        pairAndCopy()
        val spots = StationSpots(station)
        val program = fake.guide.channels.first { it.id == "c-comedy" }.slots[1]

        // Paused in the living room: the kitchen's Back-to card names it.
        fake.addDevice("living", "Living room")
        fake.saveSpotElsewhere(StationSpot("c-comedy", program.startMillis, program.itemKey!!, 9 * MINUTE, NOW - 9 * MINUTE, "living", "Living room"))
        assertTrue(station.refresh())
        val elsewhere = await(spots.spot, "the living room's spot") { it != null }!!
        assertEquals("Living room", elsewhere.room)
        // Another channel playing here doesn't forget the living room's spot.
        spots.channelPlays("c-cartoons")
        assertEquals("Living room", spots.spot.first()?.room)
        assertNotNull(fake.spot)

        // Paused here: this TV's own spot names no room, and the station has it as the kitchen's.
        val mine = SavedSpot("c-cartoons", program.startMillis, "cartoons-1", 4 * MINUTE, NOW)
        spots.save(mine)
        assertEquals(mine, spots.spot.first())
        assertEquals("Kitchen", fake.spot?.room)
        assertTrue(station.refresh())
        assertNull(spots.spot.first()?.room)

        // Another channel playing here forgets it, here and on the station.
        spots.channelPlays("c-comedy")
        assertNull(spots.spot.first())
        assertNull(fake.spot)
        assertTrue(fake.calls.any { it.startsWith("POST /v1/spot/played") })
    }

    @Test
    fun `a pause while the NAS is down shows here, and reaches the station once it's back`() = runBlocking<Unit> {
        pairAndCopy()
        val spots = StationSpots(station)
        val mine = SavedSpot("c-comedy", NOW - 30 * MINUTE, "comedy-1", 4 * MINUTE, NOW)
        fake.down = true
        spots.save(mine)
        assertEquals(mine, spots.spot.first())
        assertNotNull(store.current.spot.unsent)
        assertNull(fake.spot)

        fake.down = false
        assertTrue(station.refresh())
        assertEquals("comedy-1", fake.spot?.itemKey)
        assertNull(store.current.spot.unsent)
        assertEquals(mine, spots.spot.first())

        spots.clear(only = mine.copy(itemKey = "another"))
        assertEquals(mine, spots.spot.first())
        spots.clear(only = mine)
        assertNull(spots.spot.first())
        assertNull(fake.spot)
    }

    @Test
    fun `unpaired by the station, the TV keeps its copy and says so`() = runBlocking<Unit> {
        pairAndCopy()
        fake.revoke(store.current.deviceId!!)
        assertFalse(station.refresh())
        val view = await(station.view, "unpaired") { it.reach == StationReach.UNPAIRED }
        assertEquals(StationException.UNPAIRED, view.editingLocked)
        assertTrue(store.current.unpaired)
        assertTrue(store.current.paired)
        assertEquals(listOf("Comedy", "Cartoons"), ready().channels.map { it.name })
    }

    @Test
    fun `a call the station answers 401 means this TV isn't paired with it`() = runBlocking<Unit> {
        pairAndCopy()
        fake.noCertificates = true
        assertFalse(station.refresh())
        val view = await(station.view, "not paired") { it.reach == StationReach.UNPAIRED }
        assertEquals(StationException.UNPAIRED, view.editingLocked)
        assertTrue(store.current.unpaired)
        assertEquals(listOf("Comedy", "Cartoons"), ready().channels.map { it.name })
    }

    @Test
    fun `leaving tells the station, and the engine carries on from the copy with no remake`() = runBlocking<Unit> {
        pairAndCopy()
        val plex = Room.inMemoryDatabaseBuilder(context, PlexDatabase::class.java).allowMainThreadQueries().build()
        try {
            val engine = GuideEngine(db, plex, clock = { NOW }, zone = { ZoneOffset.UTC }, log = {}, linkedServer = { "server-1" }, lineupServer = lineupServer)
            val guide = GuideSwitch(station.paired).source(local = engine, station = source)
            val spots = GuideSwitch(station.paired).spots(local = MemorySpots(), station = StationSpots(station))
            assertEquals(GuideCardState.Ready::class, withTimeout(10_000) { guide.card.first { it is GuideCardState.Ready } }::class)
            val id = store.current.deviceId!!

            station.leave()
            await(station.paired, "left") { !it }

            assertTrue(fake.devices().none { it.id == id })
            assertTrue(keys.deleted)
            assertEquals(StationSaved(host = fake.host, port = fake.port, room = "Kitchen"), store.current)
            assertEquals("server-1", lineupServer.get())
            assertTrue(lineupServer.familyAdded())
            // The engine reads the same lineup, and has nothing to add: the copy reaches 48 hours ahead.
            engine.extend()
            val lineup = withTimeout(10_000) { guide.card.filterIsInstance<GuideCardState.Ready>().first() }
            assertEquals(listOf("Comedy", "Cartoons"), lineup.channels.map { it.name })
            assertEquals("comedy 2", lineup.channels.first().slotAt(NOW + 5 * MINUTE)?.title)
            assertNull(spots.spot.first())
        } finally {
            plex.close()
        }
    }

    @Test
    fun `a station of a major version this TV doesn't know is refused`() {
        fake.versionHeader = "2.0.0"
        station.pair(fake.address, "Kitchen")
        val view = await(station.view, "refused") { it.problem != null }
        assertTrue(view.problem!!.contains("2.0.0"))
        assertEquals(StationPhase.Unpaired, view.phase)
    }

    @Test
    fun `once paired, the TV trusts only its own station`() = runBlocking<Unit> {
        pairAndCopy()
        val other = FakeStation().start()
        try {
            store.update { it.copy(host = other.host, port = other.port) }
            assertFalse(station.refresh())
            await(station.view, "unreachable") { it.reach == StationReach.UNREACHABLE }
            assertTrue(other.calls.isEmpty())
        } finally {
            other.stop()
        }
    }

    @Test
    fun `devices are approved and unpaired from here, and the station's Plex code shows`() = runBlocking<Unit> {
        pairAndCopy()
        fake.addPending("living", "Living room", code = "7K3QPA")
        fake.addDevice("bedroom", "Bedroom")
        fake.plexLinked = false
        val following = scope.launch { station.follow() }
        try {
            var paired = await(station.view, "the devices") { (it.phase as? StationPhase.Paired)?.devices?.size == 3 }.phase as StationPhase.Paired
            assertEquals(false, paired.station?.plex?.linked)
            assertEquals(store.current.deviceId, paired.thisDevice)

            station.approve(paired.devices!!.first { it.id == "living" })
            await(station.view, "approved") { (it.phase as? StationPhase.Paired)?.devices?.any { d -> d.id == "living" && !d.pending } == true }
            station.unpair(paired.devices!!.first { it.id == "bedroom" })
            await(station.view, "unpaired") { (it.phase as? StationPhase.Paired)?.devices?.none { d -> d.id == "bedroom" } == true }
            assertTrue(fake.calls.contains("POST /v1/devices/living/approve"))
            assertTrue(fake.calls.contains("DELETE /v1/devices/bedroom"))

            station.linkPlex()
            paired = await(station.view, "the Plex code") { (it.phase as? StationPhase.Paired)?.plexCode != null }.phase as StationPhase.Paired
            assertEquals("PLX4", paired.plexCode)
            fake.plexLinked = true
            await(station.view, "linked") { (it.phase as? StationPhase.Paired)?.let { p -> p.plexCode == null && p.station?.plex?.linked == true } == true }
        } finally {
            following.cancel()
        }
    }

    @Test
    fun `a station read that was out when the Plex code came keeps the code`() = runBlocking<Unit> {
        pairAndCopy()
        fake.plexLinked = false
        val gate = java.util.concurrent.CountDownLatch(1)
        fake.stationGate = gate
        val following = scope.launch { station.follow() }
        try {
            // A read is out, its station answer held, when the code comes (CI caught the old race).
            assertTrue("a read went out", fake.stationHeld.await(10, java.util.concurrent.TimeUnit.SECONDS))
            station.linkPlex()
            await(station.view, "the Plex code") { (it.phase as? StationPhase.Paired)?.plexCode != null }
            fake.stationGate = null
            gate.countDown()

            val blanked = withTimeoutOrNull(1_000) { station.view.first { (it.phase as? StationPhase.Paired)?.plexCode == null } }
            assertNull("the read that was out blanked the code", blanked)
        } finally {
            fake.stationGate = null
            gate.countDown()
            following.cancel()
        }
    }

    @Test
    fun `the NFL comes from the station over mutual TLS with its ETag, and a 1_1 station says it needs an update`() = runBlocking<Unit> {
        pairAndCopy()
        // A 1.1 station: no NFL, and that's no reason to grey editing out.
        assertEquals(NflReply.Outdated, station.nfl(null))
        assertEquals(StationReach.CONNECTED, station.view.value.reach)

        fake.nfl = SampleNfl.state()
        val copied = station.nfl(null) as NflReply.Answered
        assertEquals(57L to "57", copied.value!!.first.version to copied.value!!.second)
        assertEquals(SampleNfl.live.id, copied.value!!.first.week!!.games.first { it.state == com.phonoodlesramen.actioncenter.nfl.GameState.LIVE }.id)
        assertEquals(NflReply.Answered(null), station.nfl("57"))
        // A 1.2 station's 404 is its sentence, not an outdated station.
        assertEquals(NflReply.Failed("ESPN has no games that week."), station.nflWeek(2, 7, 2026))
        assertTrue((station.nflLogo("PHI", "s") as NflReply.Answered).value.contentEquals(byteArrayOf(7, 7, 7)))

        fake.down = true
        assertEquals(false, (station.nfl("57") as NflReply.Failed).reached)
    }

    private fun localEditor(): RoomGuideEditor {
        val plex = Room.inMemoryDatabaseBuilder(context, PlexDatabase::class.java).allowMainThreadQueries().build()
        val engine = GuideEngine(db, plex, clock = { NOW }, zone = { ZoneOffset.UTC }, log = {})
        return RoomGuideEditor(db, plex, engine, log = {})
    }

    companion object {
        val FAST = GuideStation.Timing(
            pollMillis = 50,
            refreshEveryMillis = 60_000,
            retryMillis = 100,
            retryMaxMillis = 400,
            changesWaitMillis = 1_000,
            followMillis = 100,
        )
    }
}
