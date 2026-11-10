package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.guide.station.PairRequest
import com.phonoodlesramen.actioncenter.guide.station.PairState
import com.phonoodlesramen.actioncenter.guide.station.StationApi
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Base64
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

class PairingTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var now = 1_793_047_260_000L
    private val logs = mutableListOf<String>()
    private lateinit var ca: StationCa
    private lateinit var devices: DeviceStore
    private lateinit var approveDir: Path
    private lateinit var pairing: Pairing

    @Before
    fun setUp() {
        val root = temp.root.toPath()
        ca = StationCa.load(root.resolve("station"), listOf("192.168.1.10"), now, {})
        devices = DeviceStore(root.resolve("station/devices.json"))
        approveDir = root.resolve("approve").also { Files.createDirectories(it) }
        pairing = Pairing(ca, devices, approveDir, { now }, { logs += it })
    }

    private fun ask(tv: TestTv) = pairing.start(PairRequest(tv.name, tv.model, tv.publicKey))

    private fun codeOf(tv: TestTv) = StationApi.pairingCode(ca.caDer, tv.keys.public.encoded)

    @Test
    fun `a request logs the code the TV works out from the CA and its own key`() {
        val tv = TestTv("Living room")
        val started = ask(tv)
        assertEquals(listOf("Pairing: \"Living room\" code ${codeOf(tv)}"), logs)
        assertTrue(started.requestId.matches(Regex("[0-9a-f]{32}")))
        assertEquals(now + 5 * 60_000L, started.expiresAtMillis)
        assertEquals(PairState.PENDING, pairing.status(started.requestId).state)
        assertNull(pairing.status(started.requestId).certificate)
    }

    @Test
    fun `a TV waiting shows in the devices list with its code`() {
        val tv = TestTv("Kitchen", "Google TV Streamer")
        ask(tv)
        val waiting = pairing.pending().single()
        assertTrue(waiting.pending)
        assertEquals("Kitchen", waiting.name)
        assertEquals("Google TV Streamer", waiting.model)
        assertEquals(codeOf(tv), waiting.code)
        assertTrue(waiting.id.matches(Regex("tv-[0-9a-f]{12}")))
    }

    @Test
    fun `a file named after the code approves the TV and is removed`() {
        val tv = TestTv()
        val started = ask(tv)
        val file = Files.createFile(approveDir.resolve(codeOf(tv)))
        pairing.scanApprovals()

        assertFalse(Files.exists(file))
        val status = pairing.status(started.requestId)
        assertEquals(PairState.APPROVED, status.state)
        val certificate = StationCa.readCertificate(status.certificate!!)
        certificate.verify(ca.caCertificate.publicKey)
        assertArrayEquals(tv.keys.public.encoded, certificate.publicKey.encoded)
        assertArrayEquals(ca.caDer, StationCa.readCertificate(status.caCertificate!!).encoded)
        val paired = devices.all().single()
        assertEquals("Living room", paired.name)
        assertEquals(certificate.serialHex, paired.serial)
        assertEquals("CN=${paired.id}", certificate.subjectX500Principal.name)
        assertEquals(Caller.Paired(paired), devices.identify(certificate))
        assertTrue(logs.last().startsWith("Paired \"Living room\" (code ${codeOf(tv)}) as tv-"))
        assertTrue(pairing.pending().isEmpty())
    }

    @Test
    fun `a folder, a lowercase name or a name with an extension approves too`() {
        val one = TestTv("Den")
        val two = TestTv("Kitchen")
        val three = TestTv("Office")
        val asked = listOf(ask(one), ask(two), ask(three))
        Files.createDirectory(approveDir.resolve(codeOf(one)))
        Files.createFile(approveDir.resolve(codeOf(two).lowercase()))
        Files.createFile(approveDir.resolve("${codeOf(three)}.txt"))
        pairing.scanApprovals()

        assertEquals(List(3) { PairState.APPROVED }, asked.map { pairing.status(it.requestId).state })
        Files.list(approveDir).use { assertEquals(0L, it.count()) }
    }

    @Test
    fun `a code no TV is waiting with is removed and logged, and anything else is left alone`() {
        Files.createFile(approveDir.resolve("ABC123"))
        Files.createFile(approveDir.resolve("notes.txt"))
        Files.createDirectory(approveDir.resolve("@eaDir"))
        pairing.scanApprovals()

        assertFalse(Files.exists(approveDir.resolve("ABC123")))
        assertTrue(Files.exists(approveDir.resolve("notes.txt")))
        assertTrue(Files.exists(approveDir.resolve("@eaDir")))
        assertEquals(listOf("No TV is waiting to pair with code ABC123 (codes last 5 minutes)"), logs)
        assertTrue(devices.all().isEmpty())
    }

    @Test
    fun `a request expires after 5 minutes, and its code no longer approves it`() {
        val tv = TestTv()
        val started = ask(tv)
        now += 5 * 60_000L - 1
        assertEquals(PairState.PENDING, pairing.status(started.requestId).state)
        now += 1
        assertEquals(PairState.EXPIRED, pairing.status(started.requestId).state)

        Files.createFile(approveDir.resolve(codeOf(tv)))
        pairing.scanApprovals()
        assertEquals(PairState.EXPIRED, pairing.status(started.requestId).state)
        assertTrue(devices.all().isEmpty())
        assertTrue(pairing.pending().isEmpty())
    }

    @Test
    fun `an approved TV collects its certificate for an hour after its code's time, then the request is forgotten`() {
        val tv = TestTv()
        val started = ask(tv)
        assertTrue(pairing.approveDevice(pairing.pending().single().id))
        now += 5 * 60_000L + 59 * 60_000L
        assertEquals(PairState.APPROVED, pairing.status(started.requestId).state)
        now += 60_000L
        assertEquals(PairState.EXPIRED, pairing.status(started.requestId).state)
    }

    @Test
    fun `a request the station doesn't know reads as expired`() {
        assertEquals(PairState.EXPIRED, pairing.status("00000000000000000000000000000000").state)
    }

    @Test
    fun `at most 5 requests a minute, bad ones included`() {
        repeat(4) { ask(TestTv("Room $it")) }
        try {
            pairing.start(PairRequest("Bad", "TV", "not base64!"))
            fail("took a bad key")
        } catch (e: ApiException) {
            assertEquals(400, e.status)
        }
        try {
            ask(TestTv("One too many"))
            fail("took a sixth request in a minute")
        } catch (e: ApiException) {
            assertEquals(429, e.status)
        }
        now += 60_000L
        ask(TestTv("A minute later"))
        assertEquals(5, pairing.pending().size)
    }

    @Test
    fun `a request needs a room name and an EC P-256 key`() {
        val rsa = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded)
        val cases = listOf(
            PairRequest(" \t ", "TV", TestTv().publicKey),
            PairRequest("x".repeat(Pairing.MAX_NAME + 1), "TV", TestTv().publicKey),
            PairRequest("Den", "TV", rsa),
        )
        for (request in cases) {
            now += 60_000L
            try {
                pairing.start(request)
                fail("took $request")
            } catch (e: ApiException) {
                assertEquals(400, e.status)
            }
        }
        assertTrue(pairing.pending().isEmpty())
    }

    @Test
    fun `a paired TV approves or refuses a waiting one by its id`() {
        val kitchen = ask(TestTv("Kitchen"))
        val den = ask(TestTv("Den"))
        val (kitchenId, denId) = pairing.pending().map { it.id }
        assertTrue(pairing.approveDevice(kitchenId))
        assertTrue(pairing.refuseDevice(denId))
        assertFalse(pairing.approveDevice(denId))
        assertFalse(pairing.refuseDevice("tv-000000000000"))
        assertEquals(PairState.APPROVED, pairing.status(kitchen.requestId).state)
        assertEquals(PairState.REFUSED, pairing.status(den.requestId).state)
        assertEquals(listOf("Kitchen"), devices.all().map { it.name })
    }

    @Test
    fun `unpairing revokes the TV's serial for good, and leaves the others paired`() {
        val kitchen = ask(TestTv("Kitchen"))
        val den = ask(TestTv("Den"))
        pairing.pending().forEach { pairing.approveDevice(it.id) }
        val kitchenCert = StationCa.readCertificate(pairing.status(kitchen.requestId).certificate!!)
        val denCert = StationCa.readCertificate(pairing.status(den.requestId).certificate!!)
        val kitchenId = devices.all().first { it.name == "Kitchen" }.id

        assertEquals("Kitchen", devices.revoke(kitchenId)!!.name)
        assertEquals(Caller.Unpaired, devices.identify(kitchenCert))
        assertTrue(devices.isRevoked(kitchenCert.serialHex))
        assertTrue(devices.identify(denCert) is Caller.Paired)
        assertNull(devices.revoke(kitchenId))

        // The list survives a restart, revocations included.
        val reloaded = DeviceStore(temp.root.toPath().resolve("station/devices.json"))
        assertEquals(Caller.Unpaired, reloaded.identify(kitchenCert))
        assertEquals(listOf("Den"), reloaded.all().map { it.name })
    }

    @Test
    fun `a certificate this station issued to no paired TV is unpaired`() {
        val stray = ca.issueClient("tv-ffffffffffff", TestTv().keys.public, now)
        assertEquals(Caller.Unpaired, devices.identify(stray))
    }

    @Test
    fun `dropped names read as Crockford codes`() {
        assertEquals("ABC123", Pairing.codeOf("abc123"))
        assertEquals("ABC123", Pairing.codeOf("ABC123.txt"))
        assertEquals("AB0110", Pairing.codeOf("ABOIL0"))
        assertNull(Pairing.codeOf("ABC12"))
        assertNull(Pairing.codeOf("ABC1234"))
        assertNull(Pairing.codeOf("ABCU12"))
        assertNull(Pairing.codeOf(".ABC123"))
        assertNull(Pairing.codeOf("@eaDir"))
    }

    @Test
    fun `a TV last seen is written at most every few minutes`() {
        val tv = TestTv()
        ask(tv)
        pairing.approveDevice(pairing.pending().single().id)
        val id = devices.all().single().id
        val file = temp.root.toPath().resolve("station/devices.json")

        devices.seen(id, now)
        devices.flush()
        assertEquals(now, DeviceStore(file).byId(id)!!.lastSeenMillis)
        devices.seen(id, now + 60_000L)
        devices.flush()
        assertEquals(now, DeviceStore(file).byId(id)!!.lastSeenMillis)
        assertEquals(now + 60_000L, devices.byId(id)!!.lastSeenMillis)
        devices.seen(id, now + 5 * 60_000L)
        devices.flush()
        assertEquals(now + 5 * 60_000L, DeviceStore(file).byId(id)!!.lastSeenMillis)
    }
}
