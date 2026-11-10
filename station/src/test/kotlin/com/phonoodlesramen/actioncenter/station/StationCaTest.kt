package com.phonoodlesramen.actioncenter.station

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StationCaTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val now = 1_793_047_260_000L
    private val logs = mutableListOf<String>()

    private fun load(hosts: List<String> = listOf("192.168.1.10")) = StationCa.load(temp.root.toPath().resolve("station"), hosts, now, { logs += it })

    @Test
    fun `the CA is EC P-256, self-signed, a CA for end certificates only`() {
        val ca = load().caCertificate
        assertEquals("EC", ca.publicKey.algorithm)
        assertEquals(256, (ca.publicKey as ECPublicKey).params.order.bitLength())
        ca.verify(ca.publicKey)
        assertEquals(0, ca.basicConstraints)
        assertTrue(ca.keyUsage[5]) // keyCertSign
        assertEquals("CN=${StationCa.CA_NAME}", ca.subjectX500Principal.name)
        assertEquals(listOf("Made the station's certificate authority", "Made the server certificate for 192.168.1.10, localhost"), logs)
    }

    @Test
    fun `the CA is kept and never made again`() {
        val first = load()
        val again = load()
        assertArrayEquals(first.caDer, again.caDer)
        assertArrayEquals(first.serverCertificate.encoded, again.serverCertificate.encoded)
        assertEquals(2, logs.size)
    }

    @Test
    fun `the server certificate names STATION_HOSTS and localhost, signed by the CA`() {
        val ca = load(listOf("192.168.1.10", "nas.local"))
        val server = ca.serverCertificate
        server.verify(ca.caCertificate.publicKey)
        assertEquals(setOf(7 to "192.168.1.10", 2 to "nas.local", 2 to "localhost"), sans(server))
        assertEquals(listOf("1.3.6.1.5.5.7.3.1"), server.extendedKeyUsage) // serverAuth
        assertEquals(-1, server.basicConstraints)
    }

    @Test
    fun `new hosts make a new server certificate, under the same CA`() {
        val first = load(listOf("192.168.1.10"))
        val moved = load(listOf("192.168.1.20"))
        assertArrayEquals(first.caDer, moved.caDer)
        assertNotEquals(first.serverCertificate.serialNumber, moved.serverCertificate.serialNumber)
        assertEquals(setOf(7 to "192.168.1.20", 2 to "localhost"), sans(moved.serverCertificate))
        moved.serverCertificate.verify(first.caCertificate.publicKey)
    }

    @Test
    fun `a CA that won't read stops the station rather than being made again`() {
        load()
        val dir = temp.root.toPath().resolve("station")
        Files.writeString(dir.resolve("ca.pem"), "not a certificate")
        try {
            load()
            fail("loaded a broken CA")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("can't be read"))
        }
        Files.delete(dir.resolve("ca-key.pem"))
        try {
            load()
            fail("made a CA beside half of the old one")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("can't be read"))
        }
    }

    @Test
    fun `a TV's certificate is its key, CN its device id, client authentication, 10 years`() {
        val ca = load()
        val tv = TestTv()
        val issued = ca.issueClient("tv-0a1b2c3d4e5f", tv.keys.public, now)
        issued.verify(ca.caCertificate.publicKey)
        assertArrayEquals(tv.keys.public.encoded, issued.publicKey.encoded)
        assertEquals("CN=tv-0a1b2c3d4e5f", issued.subjectX500Principal.name)
        assertEquals(ca.caCertificate.subjectX500Principal, issued.issuerX500Principal)
        assertEquals(listOf("1.3.6.1.5.5.7.3.2"), issued.extendedKeyUsage) // clientAuth
        assertTrue(issued.keyUsage[0]) // digitalSignature
        assertEquals(-1, issued.basicConstraints)
        val years = (issued.notAfter.time - now) / (365L * 24 * 60 * 60_000L)
        assertEquals(10L, years)
        assertTrue(issued.notBefore.time <= now)
        assertTrue(issued.serialNumber.signum() > 0)
        assertNotEquals(issued.serialNumber, ca.issueClient("tv-0a1b2c3d4e5f", tv.keys.public, now).serialNumber)
    }

    @Test
    fun `only EC P-256 keys are taken`() {
        StationCa.ecP256(TestTv().keys.public.encoded)
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp384r1")) }.generateKeyPair().public.encoded
        for (key in listOf(rsa, p384, byteArrayOf(1, 2, 3))) {
            try {
                StationCa.ecP256(key)
                fail("took a key that isn't P-256")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `its files are mode 600 in a folder only it reads`() {
        assumeTrue("POSIX file modes", SecretFiles.hasModes)
        load()
        val dir = temp.root.toPath().resolve("station")
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
        Files.list(dir).use { files ->
            files.forEach { assertEquals(it.fileName.toString(), "rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(it))) }
        }
    }

    @Test
    fun `its key files hold keys, and nothing it logged holds one`() {
        load()
        val key = Files.readString(temp.root.toPath().resolve("station/ca-key.pem"))
        assertTrue(key.startsWith("-----BEGIN PRIVATE KEY-----"))
        assertFalse(logs.any { "BEGIN" in it || it.length > 120 })
    }

    private fun sans(cert: X509Certificate): Set<Pair<Int, String>> =
        cert.subjectAlternativeNames.map { (it[0] as Int) to (it[1] as String) }.toSet()
}
