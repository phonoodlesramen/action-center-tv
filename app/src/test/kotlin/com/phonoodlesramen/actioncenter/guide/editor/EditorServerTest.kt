package com.phonoodlesramen.actioncenter.guide.editor

import com.phonoodlesramen.actioncenter.guide.editor.EditorServer.State
import com.phonoodlesramen.actioncenter.guide.editor.EditorServer.StopReason
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The phone editor's server on a real socket (plan's testing strategy): a run on port 0 on
 * loopback, hit with OkHttp, and stopped by the screen, by idling or by having no network
 * or no free port.
 */
class EditorServerTest {
    private val editor = FakeGuideEditor()
    private val logged = mutableListOf<String>()
    private val servers = mutableListOf<EditorServer>()
    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    /** The real page, as the app bundles it. */
    private val page = File("src/main/assets/editor/index.html").readBytes()

    private fun server(
        ports: List<Int> = listOf(0),
        idleMillis: Long = EditorServer.IDLE_MILLIS,
        address: () -> InetAddress? = { InetAddress.getLoopbackAddress() },
    ) = EditorServer(editor, { page }, address, ports, idleMillis, log = { synchronized(logged) { logged += it } }).also { servers += it }

    private fun EditorServer.await(predicate: (State) -> Boolean): State = runBlocking { withTimeout(5_000) { state.first(predicate) } }

    private fun EditorServer.running(): State.Running {
        start()
        return await { it is State.Running } as State.Running
    }

    private fun get(url: String, cookie: String? = null) = http.newCall(
        Request.Builder().url(url).apply { if (cookie != null) header("Cookie", cookie) }.build(),
    ).execute()

    /** Sends [raw] on a plain socket and returns everything the server writes back. */
    private fun raw(running: State.Running, raw: String): String {
        val port = running.address.substringAfterLast(':').toInt()
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(raw.toByteArray())
            socket.getOutputStream().flush()
            return socket.getInputStream().readBytes().decodeToString()
        }
    }

    @After
    fun stopAll() = servers.forEach { it.stop(StopReason.CLOSED) }

    @Test
    fun `serves the bundled page as HTML on its address`() {
        val running = server().running()
        assertTrue(running.address.matches(Regex("http://127\\.0\\.0\\.1:\\d+")))
        assertTrue(running.pin.matches(Regex("[0-9]{6}")))
        get(running.address + "/").use { response ->
            assertEquals(200, response.code)
            assertEquals("text/html; charset=utf-8", response.header("Content-Type"))
            assertTrue(response.header("Content-Security-Policy")!!.startsWith("default-src 'none'"))
            assertEquals("close", response.header("Connection"))
            assertEquals(null, response.header("Access-Control-Allow-Origin"))
            val html = response.body.string()
            assertTrue(html.startsWith("<!doctype html>"))
            assertTrue("<title>Plex Guide channels</title>" in html)
        }
    }

    @Test
    fun `the bundled page loads nothing from elsewhere`() {
        val html = page.decodeToString()
        assertFalse(Regex("""\b(src|href)\s*=\s*["']?(https?:)?//""").containsMatchIn(html))
        assertFalse("@import" in html)
        assertFalse(Regex("""url\(\s*["']?(https?:)?//""").containsMatchIn(html))
        // No em dashes in what people read.
        assertFalse('—' in html)
    }

    @Test
    fun `the QR link signs the phone in, the API answers with the cookie, and the TV hears it`() {
        val server = server()
        val running = server.running()
        assertFalse(running.phoneConnected)
        assertTrue(running.link.startsWith(running.address + "/?k="))

        get(running.address + "/api/lineup").use { assertEquals(401, it.code) }

        val cookie = get(running.link).use { response ->
            assertEquals(303, response.code)
            assertEquals("/", response.header("Location"))
            response.header("Set-Cookie")!!.substringBefore(';')
        }
        get(running.address + "/api/lineup", cookie).use { response ->
            assertEquals(200, response.code)
            assertTrue(response.body.string().startsWith("""[{"id":"classics","number":1"""))
        }
        server.await { it is State.Running && it.phoneConnected }

        get(running.link).use { assertEquals(null, it.header("Set-Cookie")) }
    }

    @Test
    fun `a change goes through to the editor, and a rejection comes back as 400`() {
        val running = server().running()
        val cookie = get(running.link).use { it.header("Set-Cookie")!!.substringBefore(';') }
        val json = "application/json".toMediaType()
        fun put(body: String) = http.newCall(
            Request.Builder().url(running.address + "/api/channels/classics/number").header("Cookie", cookie).put(body.toRequestBody(json)).build(),
        ).execute()

        put("""{"number":12}""").use { assertEquals(204, it.code) }
        editor.reject = "Channel 3 is taken"
        put("""{"number":3}""").use { response ->
            assertEquals(400, response.code)
            assertEquals("""{"error":"Channel 3 is taken"}""", response.body.string())
        }
        assertEquals(listOf("renumber classics 12"), editor.calls)
    }

    @Test
    fun `malformed and oversized requests are answered and closed`() {
        val running = server().running()
        assertTrue(raw(running, "NONSENSE\r\n\r\n").startsWith("HTTP/1.1 400 Bad Request\r\n"))
        val tooBig = raw(running, "POST /signin HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: ${HttpParser.MAX_BODY_BYTES + 1}\r\n\r\n")
        assertTrue(tooBig.startsWith("HTTP/1.1 413 "))
        val patch = raw(running, "PATCH / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        assertTrue(patch.startsWith("HTTP/1.1 405 "))
        assertTrue("Allow: GET, POST, PUT, DELETE\r\n" in patch)
    }

    @Test
    fun `stopping closes the port and every open connection`() {
        val server = server()
        val running = server.running()
        val port = running.address.substringAfterLast(':').toInt()
        // A browser's spare connection: open, nothing sent.
        Socket(InetAddress.getLoopbackAddress(), port).use { idle ->
            idle.soTimeout = 5_000
            // Let the server accept it before stopping.
            Thread.sleep(200)
            server.stop(StopReason.CLOSED)
            assertEquals(State.Stopped(StopReason.CLOSED), server.state.value)
            assertEquals(-1, idle.getInputStream().read())
        }
        assertRefused(port)
    }

    @Test
    fun `fifteen minutes without a request stops it, and the screen can start it again`() {
        val server = server(idleMillis = 300)
        val first = server.running()
        server.await { it == State.Stopped(StopReason.IDLE) }
        assertRefused(first.address.substringAfterLast(':').toInt())

        val second = server.running()
        assertNotEquals(first.link, second.link)
        get(second.address + "/").use { assertEquals(200, it.code) }
    }

    @Test
    fun `requests keep it running`() {
        val server = server(idleMillis = 600)
        val running = server.running()
        repeat(6) {
            Thread.sleep(200)
            get(running.address + "/").use { assertEquals(200, it.code) }
        }
        assertTrue(server.state.value is State.Running)
    }

    @Test
    fun `without a network it says so`() {
        val server = server(address = { null })
        server.start()
        assertEquals(State.NoNetwork, server.await { it != State.Starting })
    }

    @Test
    fun `a taken port moves on to the next, and all taken says so`() {
        ServerSocket().use { taken ->
            taken.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            val busy = taken.localPort
            val next = server(ports = listOf(busy, 0)).running()
            assertNotEquals(busy, next.address.substringAfterLast(':').toInt())

            val none = server(ports = listOf(busy))
            none.start()
            assertEquals(State.NoPort, none.await { it != State.Starting })
        }
    }

    @Test
    fun `stopping before the port opens leaves nothing running`() {
        val server = server()
        server.start()
        server.stop(StopReason.AWAY)
        Thread.sleep(200)
        assertEquals(State.Stopped(StopReason.AWAY), server.state.value)
    }

    @Test
    fun `starting twice is one run`() {
        val server = server()
        val running = server.running()
        server.start()
        assertEquals(running, server.state.value)
    }

    @Test
    fun `its state never prints the PIN or the key`() {
        val running = server().running()
        val text = running.toString()
        assertFalse(running.pin in text)
        assertFalse(running.link in text)
        assertFalse(running.link.substringAfter("k=") in text)
    }

    @Test
    fun `nothing is logged in normal use`() {
        val running = server().running()
        get(running.link).use { }
        get(running.address + "/").use { }
        assertTrue(logged.isEmpty())
    }

    /**
     * The port refuses connections within a moment. On Linux a listening socket closed while
     * a thread is blocked in `accept()` goes only once that thread wakes, so a connection can
     * still land just after `stop()` returns (Windows closes it at once).
     */
    private fun assertRefused(port: Int) {
        val until = System.nanoTime() + REFUSED_WITHIN_NANOS
        while (true) {
            try {
                Socket(InetAddress.getLoopbackAddress(), port).close()
            } catch (_: IOException) {
                return // Refused, as expected.
            }
            if (System.nanoTime() > until) fail("port $port still open")
            Thread.sleep(20)
        }
    }

    private companion object {
        const val REFUSED_WITHIN_NANOS = 2_000_000_000L
    }
}
