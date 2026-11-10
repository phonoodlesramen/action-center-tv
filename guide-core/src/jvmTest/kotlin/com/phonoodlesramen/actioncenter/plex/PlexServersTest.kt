package com.phonoodlesramen.actioncenter.plex

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlexServersTest {
    private fun connection(uri: String, address: String, local: Boolean = false, relay: Boolean = false, ipv6: Boolean = false, port: Int = 32400) =
        PlexConnection(uri, "https", address, port, local, relay, ipv6)

    private val basement = PlexServer(
        name = "Basement",
        clientIdentifier = "aaaa1111",
        accessToken = "server-token",
        owned = true,
        connections = listOf(
            connection("https://203-0-113-7.aaaa1111.plex.direct:32400", "203.0.113.7"),
            connection("https://203-0-113-200.relay.plex.direct:8443", "203.0.113.200", relay = true, port = 8443),
            connection("https://fd00--20.aaaa1111.plex.direct:32400", "fd00::20", local = true, ipv6 = true),
            connection("https://192-168-1-20.aaaa1111.plex.direct:32400", "192.168.1.20", local = true),
        ),
    )

    @Test
    fun `only your own servers count (P2)`() {
        val shared = basement.copy(name = "Friend's", clientIdentifier = "eeee", owned = false)

        assertEquals(listOf(basement), PlexServers.owned(listOf(shared, basement)))
    }

    @Test
    fun `local first with a plain-IP fallback, then remote, relay last (P3)`() {
        assertEquals(
            listOf(
                "https://192-168-1-20.aaaa1111.plex.direct:32400" to ConnectionKind.LOCAL,
                "http://192.168.1.20:32400" to ConnectionKind.LOCAL,
                "https://fd00--20.aaaa1111.plex.direct:32400" to ConnectionKind.LOCAL,
                "https://203-0-113-7.aaaa1111.plex.direct:32400" to ConnectionKind.REMOTE,
                "https://203-0-113-200.relay.plex.direct:8443" to ConnectionKind.RELAY,
            ),
            PlexServers.candidates(basement).map { it.uri to it.kind },
        )
    }

    private val good = MockWebServer()
    private val impostor = MockWebServer()

    @After
    fun tearDown() {
        good.close()
        impostor.close()
    }

    private fun identity(machine: String) = MockResponse.Builder()
        .body("""{"MediaContainer":{"size":0,"claimed":true,"machineIdentifier":"$machine","version":"1.43.5"}}""")
        .build()

    @Test
    fun `reach skips dead connections and other machines, and takes the first that is this server`() = runTest {
        good.start()
        impostor.start()
        impostor.enqueue(identity("someone-else"))
        good.enqueue(identity("aaaa1111"))
        val dead = "http://127.0.0.1:9" // Discard port: nothing listens.
        val server = basement.copy(
            connections = listOf(
                connection(dead, "127.0.0.1", local = true).copy(protocol = "http"),
                connection(impostor.url("/").toString(), "127.0.0.2", local = true),
                connection(good.url("/").toString(), "127.0.0.3"),
            ),
        )
        val reach = PlexServerReach(OkHttpClient(), PlexHeaders({ "client-123" }, "0.2.0", TEST_DEVICE), timeoutSeconds = 2)

        val found = reach.reach(server)

        assertEquals(good.url("/").toString().trimEnd('/'), found?.uri)
        assertEquals(ConnectionKind.REMOTE, found?.kind)
        assertEquals("server-token", good.takeRequest().headers["X-Plex-Token"])
    }

    @Test
    fun `no connection answering means null`() = runTest {
        val reach = PlexServerReach(OkHttpClient(), PlexHeaders({ "c" }, "0.2.0", TEST_DEVICE), timeoutSeconds = 1)

        assertNull(reach.reach(basement.copy(connections = listOf(connection("http://127.0.0.1:9", "127.0.0.1", local = true)))))
    }
}
