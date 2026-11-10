package com.phonoodlesramen.actioncenter.station

import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StationConfigTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the defaults are port 8443 and the data volume`() {
        val config = StationConfig.fromEnv(emptyMap())
        assertEquals(8443, config.port)
        assertEquals(Path.of("/data"), config.dataDir)
        assertEquals(emptyList<String>(), config.hosts)
    }

    @Test
    fun `the environment sets the port, the volume, the certificate's hosts and the zone`() {
        val config = StationConfig.fromEnv(
            mapOf("STATION_PORT" to "9443", "STATION_DATA" to "/volume1/station", "STATION_HOSTS" to " 192.168.1.10, nas.local ,,192.168.1.10", "TZ" to "America/Los_Angeles"),
        )
        assertEquals(9443, config.port)
        assertEquals(Path.of("/volume1/station"), config.dataDir)
        assertEquals(listOf("192.168.1.10", "nas.local"), config.hosts)
        assertEquals(ZoneId.of("America/Los_Angeles"), config.zone)
        assertEquals(Path.of("/volume1/station/approve"), config.approveDir)
    }

    @Test
    fun `a host that isn't an address or a name, or a zone that isn't one, is left out and logged`() {
        val logs = mutableListOf<String>()
        val config = StationConfig.fromEnv(mapOf("STATION_HOSTS" to "192.168.1.10,bad host/name", "TZ" to "Mars/Olympus"), logs::add)
        assertEquals(listOf("192.168.1.10"), config.hosts)
        assertEquals(ZoneId.systemDefault(), config.zone)
        assertEquals(2, logs.size)
    }

    @Test
    fun `a port that isn't one stops the station`() {
        for (port in listOf("http", "0", "70000")) {
            try {
                StationConfig.fromEnv(mapOf("STATION_PORT" to port))
                fail("took port $port")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
}

/** `approve <CODE>` in the container's terminal: it leaves the code for the running station. */
class ApproveCommandTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val config get() = StationConfig(dataDir = temp.root.toPath())

    @Test
    fun `it leaves the code in approve, and says so once the station takes it`() {
        val said = mutableListOf<String>()
        val file = temp.root.toPath().resolve("approve/ABC123")
        // The station, taking it as it would within a second.
        val station = CompletableFuture.runAsync {
            while (!Files.exists(file)) Thread.sleep(10)
            Files.delete(file)
        }
        assertEquals(0, approve(listOf("abc123"), config, said::add))
        station.join()
        assertTrue(said.single().startsWith("The station took code ABC123"))
    }

    @Test
    fun `without a station to take it, the code stays and it says so`() {
        val said = mutableListOf<String>()
        assertEquals(1, approve(listOf("ABC123"), config, said::add, waitMillis = 200L))
        assertTrue(Files.exists(temp.root.toPath().resolve("approve/ABC123")))
        assertTrue(said.single().contains("Is the station running?"))
    }

    @Test
    fun `a code that isn't one is refused`() {
        val said = mutableListOf<String>()
        assertEquals(2, approve(listOf("ABC"), config, said::add))
        assertEquals(2, approve(emptyList(), config, said::add))
        assertFalse(Files.exists(temp.root.toPath().resolve("approve")))
    }
}
