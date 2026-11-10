package com.phonoodlesramen.actioncenter.guide.editor

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The phone editor's hand-written HTTP/1.1 (plan §4): methods, lengths, the limits and malformed requests. */
class HttpParserTest {
    private val parser = HttpParser()

    private fun parse(text: String, parser: HttpParser = this.parser) = parser.read(ByteArrayInputStream(text.toByteArray()))

    private fun request(head: String, body: String = "") = "$head\r\nHost: 192.168.1.20:8137\r\n${
        if (body.isEmpty()) "" else "Content-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n"
    }\r\n$body"

    private fun status(text: String, parser: HttpParser = this.parser): Int = try {
        parse(text, parser)
        fail("parsed: $text")
        0
    } catch (e: HttpError) {
        e.status
    }

    private fun gone(text: String, parser: HttpParser = this.parser) {
        try {
            parse(text, parser)
            fail("parsed: $text")
        } catch (_: ClientGone) {
            // As expected: nobody is left to answer.
        }
    }

    @Test
    fun `a GET has its path decoded by segment and its query`() {
        val request = parse(request("GET /api/channels/a%20b%2Fc?k=abc-_123&x=1+2 HTTP/1.1"))
        assertEquals("GET", request.method)
        assertEquals(listOf("api", "channels", "a b/c"), request.segments)
        assertEquals(mapOf("k" to "abc-_123", "x" to "1 2"), request.query)
        assertEquals("192.168.1.20:8137", request.header("Host"))
        assertEquals(0, request.body.size)
    }

    @Test
    fun `the root is no segments`() {
        assertEquals(emptyList<String>(), parse(request("GET / HTTP/1.1")).segments)
    }

    @Test
    fun `POST, PUT and DELETE are read with their bodies`() {
        for (method in listOf("POST", "PUT", "DELETE")) {
            val request = parse(request("$method /api/remake HTTP/1.1", """{"name":"Été"}"""))
            assertEquals(method, request.method)
            assertEquals("""{"name":"Été"}""", request.body.decodeToString())
        }
    }

    @Test
    fun `HTTP 1_0 is read too`() {
        assertEquals("GET", parse("GET / HTTP/1.0\r\n\r\n").method)
    }

    @Test
    fun `other methods are 405, a malformed one 400`() {
        assertEquals(405, status(request("PATCH / HTTP/1.1")))
        assertEquals(405, status(request("HEAD / HTTP/1.1")))
        assertEquals(405, status(request("get / HTTP/1.1")))
        assertEquals(400, status(request("G(T / HTTP/1.1")))
    }

    @Test
    fun `a request line that isn't three parts is 400, another version 505`() {
        assertEquals(400, status("GET /\r\n\r\n"))
        assertEquals(400, status("GET  / HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1 extra\r\n\r\n"))
        assertEquals(505, status("GET / HTTP/2.0\r\n\r\n"))
    }

    @Test
    fun `only origin-form targets in visible ASCII`() {
        assertEquals(400, status(request("GET http://192.168.1.20:8137/ HTTP/1.1")))
        assertEquals(400, status(request("GET * HTTP/1.1")))
        assertEquals(400, status(request("GET /café HTTP/1.1")))
    }

    @Test
    fun `a broken escape or invalid UTF-8 in the path is 400`() {
        assertEquals(400, status(request("GET /api/%zz HTTP/1.1")))
        assertEquals(400, status(request("GET /api/%4 HTTP/1.1")))
        assertEquals(400, status(request("GET /api/%ff%fe HTTP/1.1")))
    }

    @Test
    fun `malformed headers are 400`() {
        assertEquals(400, status("GET / HTTP/1.1\r\nNo colon here\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nHost : x\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: x\r\n folded\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\n: empty\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nX-Bad: a\u0001b\r\n\r\n"))
    }

    @Test
    fun `header names are case-insensitive, and repeated cookies are joined`() {
        val request = parse("GET / HTTP/1.1\r\nhOsT: x\r\nCookie: a=1\r\nCookie: b=2\r\nAccept: a\r\nAccept: b\r\n\r\n")
        assertEquals("x", request.header("HOST"))
        assertEquals("a=1; b=2", request.header("cookie"))
        assertEquals("a, b", request.header("accept"))
    }

    @Test
    fun `Content-Length must be digits, and repeats must agree`() {
        assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n"))
        assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: 1e3\r\n\r\n"))
        assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: \r\n\r\n"))
        assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: 12345678901\r\n\r\n"))
        assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\nabc"))
        assertEquals("ab", parse("POST / HTTP/1.1\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\nab").body.decodeToString())
    }

    @Test
    fun `without a length there's no body, and bytes past the length are left`() {
        assertEquals(0, parse("POST / HTTP/1.1\r\n\r\n{}").body.size)
        assertEquals("{}", parse("POST / HTTP/1.1\r\nContent-Length: 2\r\n\r\n{}extra").body.decodeToString())
    }

    @Test
    fun `a chunked body is 411`() {
        assertEquals(411, status("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"))
        assertEquals(411, status("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\nContent-Length: 5\r\n\r\n0\r\n\r\n"))
    }

    @Test
    fun `a body up to 256 KB is read, over it is 413 before reading it`() {
        val limit = HttpParser.MAX_BODY_BYTES
        val body = ByteArray(limit) { 'a'.code.toByte() }
        val request = parser.read(ByteArrayInputStream("POST / HTTP/1.1\r\nContent-Length: $limit\r\n\r\n".toByteArray() + body))
        assertArrayEquals(body, request.body)
        assertEquals(413, status("POST / HTTP/1.1\r\nContent-Length: ${limit + 1}\r\n\r\n"))
    }

    @Test
    fun `headers up to 16 KB are read, past it is 431`() {
        val head = "GET / HTTP/1.1\r\nX-Pad: "
        val fits = head + "a".repeat(HttpParser.MAX_HEAD_BYTES - head.length - 4) + "\r\n\r\n"
        assertEquals(HttpParser.MAX_HEAD_BYTES, fits.length)
        assertEquals("GET", parse(fits).method)
        val over = head + "a".repeat(HttpParser.MAX_HEAD_BYTES - head.length - 3) + "\r\n\r\n"
        assertEquals(431, status(over))
    }

    @Test
    fun `a body cut short, or no request at all, means the client is gone`() {
        gone("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc")
        gone("")
        gone("GET / HTTP/1.1\r\nHost: x\r\n")
    }

    @Test
    fun `past the deadline the client is gone`() {
        var now = 0L
        val slow = HttpParser(clock = { now++ })
        try {
            slow.read(ByteArrayInputStream("GET / HTTP/1.1\r\n\r\n".toByteArray()), deadline = 5)
            fail("read past the deadline")
        } catch (_: ClientGone) {
            // As expected.
        }
    }

    @Test
    fun `blank lines before the request line and bare line feeds are fine`() {
        val request = parse("\r\n\nGET /api/lineup HTTP/1.1\nHost: x\n\n")
        assertEquals(listOf("api", "lineup"), request.segments)
        assertEquals("x", request.header("host"))
    }

    @Test
    fun `a response is the status, its headers, the common ones, a length and close`() {
        val out = ByteArrayOutputStream()
        HttpWriter.write(out, HttpResponse.json(200, """{"a":1}"""))
        val text = out.toString(Charsets.ISO_8859_1.name())
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue("Content-Type: application/json; charset=utf-8\r\n" in text)
        assertTrue("Content-Length: 7\r\n" in text)
        assertTrue("Connection: close\r\n" in text)
        assertTrue("Cache-Control: no-store\r\n" in text)
        assertTrue("Referrer-Policy: no-referrer\r\n" in text)
        assertTrue("X-Content-Type-Options: nosniff\r\n" in text)
        assertTrue("Access-Control" !in text)
        assertTrue(text.endsWith("\r\n\r\n{\"a\":1}"))
    }

    @Test
    fun `a 204 has no length`() {
        val out = ByteArrayOutputStream()
        HttpWriter.write(out, HttpResponse.noContent())
        val text = out.toString(Charsets.ISO_8859_1.name())
        assertTrue(text.startsWith("HTTP/1.1 204 No Content\r\n"))
        assertFalse("Content-Length" in text)
    }

    @Test
    fun `error bodies are JSON sentences`() {
        val response = HttpResponse.error(400, "Channel 12 is \"taken\"")
        assertEquals("""{"error":"Channel 12 is \"taken\""}""", response.body.decodeToString())
        assertNull(response.header("Allow"))
    }
}
