package com.phonoodlesramen.actioncenter.station

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideSource
import com.phonoodlesramen.actioncenter.guide.SlotEntity
import com.phonoodlesramen.actioncenter.guide.station.SpotBody
import com.phonoodlesramen.actioncenter.guide.station.StationSlot
import com.phonoodlesramen.actioncenter.player.MemorySpots
import com.phonoodlesramen.actioncenter.player.SavedSpot
import com.phonoodlesramen.actioncenter.ui.settings.FakeGuideEditor
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.security.cert.CertificateException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The guide station's smaller parts on the TV: addresses, trust, ids, what's kept, and the switch between guides. */
class StationPartsTest {
    @Test
    fun `an address is the NAS's IP or name, on 8443 unless another port is typed`() {
        assertEquals("192.168.1.20" to 8443, GuideStation.parseAddress("192.168.1.20"))
        assertEquals("192.168.1.20" to 9443, GuideStation.parseAddress(" 192.168.1.20:9443 "))
        assertEquals("nas.local" to 8443, GuideStation.parseAddress("https://nas.local/"))
        assertEquals("fe80::1" to 8443, GuideStation.parseAddress("[fe80::1]"))
        assertEquals("fe80::1" to 9000, GuideStation.parseAddress("[fe80::1]:9000"))
        assertNull(GuideStation.parseAddress(""))
        assertNull(GuideStation.parseAddress("192.168.1.20:99999"))
        assertNull(GuideStation.parseAddress("192.168.1.20:port"))
        assertNull(GuideStation.parseAddress("my nas"))
        assertNull(GuideStation.parseAddress("nas/guide"))
    }

    @Test
    fun `the TV trusts the station's CA and its server certificates, and nothing else`() {
        val caKeys = TestCertificates.ecKeyPair()
        val ca = TestCertificates.certificate("CA", caKeys.public.encoded, "CA", caKeys.private, BigInteger.ONE, ca = true)
        val trust = StationTrustManager(ca)
        fun cert(usage: String?, signer: java.security.PrivateKey = caKeys.private, issuer: String = "CA") =
            TestCertificates.certificate("station", TestCertificates.ecKeyPair().public.encoded, issuer, signer, BigInteger.TEN, usage = usage)

        trust.checkServerTrusted(arrayOf(cert(TestCertificates.SERVER_AUTH), ca), "EC")
        trust.checkServerTrusted(arrayOf(cert(usage = null)), "EC")
        trust.checkServerTrusted(arrayOf(ca), "EC")
        // Another TV's certificate (client only) can't stand in for the station.
        rejects { trust.checkServerTrusted(arrayOf(cert(TestCertificates.CLIENT_AUTH)), "EC") }
        // Nor can a certificate from another CA, even one that names this CA as its issuer.
        val other = TestCertificates.ecKeyPair()
        rejects { trust.checkServerTrusted(arrayOf(cert(TestCertificates.SERVER_AUTH, signer = other.private)), "EC") }
        rejects { trust.checkServerTrusted(arrayOf(cert(TestCertificates.SERVER_AUTH, signer = other.private, issuer = "Other")), "EC") }
        // An expired one neither.
        val expired = TestCertificates.certificate(
            "station", TestCertificates.ecKeyPair().public.encoded, "CA", caKeys.private, BigInteger.TEN,
            notBefore = java.time.Instant.parse("2020-01-01T00:00:00Z"), notAfter = java.time.Instant.parse("2021-01-01T00:00:00Z"),
        )
        rejects { trust.checkServerTrusted(arrayOf(expired), "EC") }
        rejects { trust.checkServerTrusted(emptyArray(), "EC") }
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("trusted")
        } catch (e: CertificateException) {
            // As it should.
        }
    }

    @Test
    fun `the key manager offers this TV's certificate for an EC key only`() {
        val keys = SoftwareKeys()
        val caKeys = TestCertificates.ecKeyPair()
        val certificate = TestCertificates.certificate("Kitchen", keys.publicKey().encoded, "CA", caKeys.private, BigInteger.valueOf(100))
        val manager = StationKeyManager(keys, listOf(certificate))
        val alias = manager.chooseClientAlias(arrayOf("EC"), null, null)!!
        assertNull(manager.chooseClientAlias(arrayOf("RSA"), null, null))
        assertEquals(listOf(certificate), manager.getCertificateChain(alias)!!.toList())
        assertEquals(keys.privateKey(), manager.getPrivateKey(alias))
        assertNull(manager.chooseServerAlias("EC", null, null))
    }

    @Test
    fun `a TV's id may be its certificate's serial, in hex or decimal`() {
        val keys = SoftwareKeys()
        val caKeys = TestCertificates.ecKeyPair()
        val pem = StationTls.pem(TestCertificates.certificate("Kitchen", keys.publicKey().encoded, "CA", caKeys.private, BigInteger.valueOf(300)))
        assertEquals(setOf("12c", "12C", "300"), GuideStation.serialIds(pem))
        assertEquals(emptySet<String>(), GuideStation.serialIds(null))
        assertEquals(emptySet<String>(), GuideStation.serialIds("not a certificate"))
    }

    @Test
    fun `the station's slots keep their kinds in guide db`() {
        val program = StationSlot(0, 60_000, "Frasier", itemKey = "5101")
        assertEquals(SlotEntity.PROGRAM, program.toEntity("c").kind)
        assertEquals(SlotEntity.TRAILER, program.copy(filler = true).toEntity("c").kind)
        assertEquals(SlotEntity.UP_NEXT, program.copy(filler = true, itemKey = null).toEntity("c").kind)
        val back = program.toEntity("c").toGuideSlot("Off air")
        assertEquals(program.toGuideSlot().copy(offAir = "Off air"), back)
        // Filler is never off air.
        assertNull(program.copy(filler = true).toEntity("c").toGuideSlot("Off air").offAir)
    }

    @Test
    fun `what's kept survives a restart, and a file that can't be read is never paired`() = runBlocking {
        val dir = Files.createTempDirectory("station").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = File(dir, "guide_station.preferences_pb")
            val prefs = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val store = DataStoreStationStore(prefs)
            assertEquals(StationSaved(), store.saved.first())
            val kept = StationSaved(
                host = "192.168.1.20", port = 9443, room = "Kitchen", certificate = "PEM", caCertificate = "CA PEM", deviceId = "64",
                guide = GuideCopy(
                    version = 412, etag = "\"412\"", updatedAtMillis = 1, checkedAtMillis = 2, timeZone = "America/Los_Angeles",
                    channels = mapOf("c-comedy" to ChannelExtras(contentRatings = listOf("TV-PG"), offAir = mapOf(1_793_000_000_000L to "Gone"))),
                ),
                spot = SpotCopy(unsent = UnsentSpot(put = SpotBody("c-comedy", 1, "5101", 2, 3))),
            )
            store.update { kept }
            assertEquals(kept, store.saved.first())
            assertTrue(store.saved.first().paired)
            assertEquals("192.168.1.20:9443", kept.address)

            prefs.edit { it[stringPreferencesKey("station")] = "{not json" }
            assertEquals(StationSaved(), store.saved.first())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an unpaired TV reads its own guide, editor and spot, and a paired one the station's`() = runBlocking {
        val paired = MutableStateFlow(false)
        val switch = GuideSwitch(paired)
        val local = object : GuideSource {
            override val card = flowOf<GuideCardState>(GuideCardState.NothingToFill)
        }
        val remote = object : GuideSource {
            override val card = flowOf<GuideCardState>(GuideCardState.NoneOn)
        }
        val guide = switch.source(local, remote)
        val ownEditor = FakeGuideEditor()
        val stationEditor = FakeGuideEditor(emptyList())
        val editor = switch.editor(ownEditor, stationEditor)
        val ownSpots = MemorySpots(SavedSpot("a", 1, "k", 2, 3))
        val houseSpots = MemorySpots(SavedSpot("b", 1, "k", 2, 3, room = "Living room"))
        val spots = switch.spots(ownSpots, houseSpots)

        assertEquals(GuideCardState.NothingToFill, guide.card.first())
        editor.rename("comedy", "Sitcoms")
        assertEquals(listOf("rename comedy Sitcoms"), ownEditor.calls)
        assertEquals("a", spots.spot.first()?.channelId)

        paired.value = true
        assertEquals(GuideCardState.NoneOn, guide.card.first())
        editor.rename("comedy", "Laughs")
        assertEquals(listOf("rename comedy Laughs"), stationEditor.calls)
        assertEquals(emptyList<Any>(), editor.lineup.first())
        assertEquals("Living room", spots.spot.first()?.room)
        spots.clear()
        assertNull(houseSpots.value)
        assertEquals("a", ownSpots.value?.channelId)
    }
}
