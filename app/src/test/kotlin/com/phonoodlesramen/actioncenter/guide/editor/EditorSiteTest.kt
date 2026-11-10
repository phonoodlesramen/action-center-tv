package com.phonoodlesramen.actioncenter.guide.editor

import com.phonoodlesramen.actioncenter.guide.ChannelDraft
import com.phonoodlesramen.actioncenter.guide.ChannelRule
import com.phonoodlesramen.actioncenter.guide.EditableChannel
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything the phone editor answers, without sockets: the page and sign-in (G17), and
 * every API route against a fake [com.phonoodlesramen.actioncenter.guide.GuideEditor].
 */
class EditorSiteTest {
    private val editor = FakeGuideEditor()
    private val logged = mutableListOf<String>()
    private val signIn = EditorSignIn()
    private val page = "<!doctype html><title>Editor</title>".toByteArray()
    private val site = EditorSite(signIn, EditorApi(editor, logged::add), page)

    private val session: String by lazy { signIn.useKey(signIn.key)!! }

    private fun call(
        method: String,
        target: String,
        body: String? = null,
        signedIn: Boolean = true,
        host: String? = "192.168.1.20:8137",
        contentType: String = "application/json",
    ): HttpResponse {
        val head = buildString {
            append("$method $target HTTP/1.1\r\n")
            if (host != null) append("Host: $host\r\n")
            if (signedIn) append("Cookie: ${EditorSignIn.COOKIE}=$session\r\n")
            if (body != null) append("Content-Type: $contentType\r\nContent-Length: ${body.toByteArray().size}\r\n")
            append("\r\n")
        }
        val request = HttpParser().read(ByteArrayInputStream((head + (body ?: "")).toByteArray()))
        return runBlocking { site.handle(request) }
    }

    private fun HttpResponse.text() = body.decodeToString()

    private fun HttpResponse.json() = Json.parseToJsonElement(text())

    private fun HttpResponse.error() = json().jsonObject["error"]!!.jsonPrimitive.content

    // The page and signing in

    @Test
    fun `the page is HTML that may load nothing from elsewhere`() {
        val response = call("GET", "/", signedIn = false)
        assertEquals(200, response.status)
        assertEquals("text/html; charset=utf-8", response.header("Content-Type"))
        val policy = response.header("Content-Security-Policy")!!
        assertTrue(policy.startsWith("default-src 'none'"))
        assertTrue("connect-src 'self'" in policy)
        assertTrue("frame-ancestors 'none'" in policy)
        assertEquals(page.decodeToString(), response.text())
    }

    @Test
    fun `the QR code's key sets the session cookie once, then redirects without the key`() {
        val first = call("GET", "/?k=${signIn.key}", signedIn = false)
        assertEquals(303, first.status)
        assertEquals("/", first.header("Location"))
        val cookie = first.header("Set-Cookie")!!
        assertTrue(cookie.startsWith("${EditorSignIn.COOKIE}="))
        assertTrue("HttpOnly" in cookie && "SameSite=Strict" in cookie)

        val again = call("GET", "/?k=${signIn.key}", signedIn = false)
        assertEquals(303, again.status)
        assertNull(again.header("Set-Cookie"))
    }

    @Test
    fun `a wrong key redirects to the PIN form without a cookie`() {
        val response = call("GET", "/?k=nope", signedIn = false)
        assertEquals(303, response.status)
        assertNull(response.header("Set-Cookie"))
    }

    @Test
    fun `every API call needs the cookie`() {
        for ((method, target) in listOf("GET" to "/api/lineup", "GET" to "/api/choices", "POST" to "/api/remake", "DELETE" to "/api/channels/classics")) {
            val response = call(method, target, signedIn = false)
            assertEquals("$method $target", 401, response.status)
        }
        assertTrue(editor.calls.isEmpty())
    }

    @Test
    fun `a cookie from an earlier run is refused`() {
        val earlier = EditorSignIn()
        val stale = earlier.useKey(earlier.key)!!
        val request = HttpParser().read(
            ByteArrayInputStream("GET /api/lineup HTTP/1.1\r\nHost: 192.168.1.20\r\nCookie: ${EditorSignIn.COOKIE}=$stale\r\n\r\n".toByteArray()),
        )
        assertEquals(401, runBlocking { site.handle(request) }.status)
    }

    @Test
    fun `the right PIN sets the cookie, which then works`() {
        val response = call("POST", "/signin", """{"pin":"${EditorSignIn.spaced(signIn.pin)}"}""", signedIn = false)
        assertEquals(204, response.status)
        val cookie = response.header("Set-Cookie")!!.substringBefore(';')
        val request = HttpParser().read(
            ByteArrayInputStream("GET /api/lineup HTTP/1.1\r\nHost: 192.168.1.20\r\nCookie: $cookie\r\n\r\n".toByteArray()),
        )
        assertEquals(200, runBlocking { site.handle(request) }.status)
    }

    @Test
    fun `wrong PINs say how many tries are left, then lock, and the key still works`() {
        val wrong = signIn.pin.dropLast(1) + ((signIn.pin.last() - '0' + 1) % 10)
        val first = call("POST", "/signin", """{"pin":"$wrong"}""", signedIn = false)
        assertEquals(401, first.status)
        assertEquals("That PIN doesn't match the TV. 4 tries left.", first.error())
        repeat(3) { call("POST", "/signin", """{"pin":"$wrong"}""", signedIn = false) }
        val fifth = call("POST", "/signin", """{"pin":"$wrong"}""", signedIn = false)
        assertEquals(429, fifth.status)
        assertEquals(EditorSite.LOCKED, fifth.error())
        assertEquals(429, call("POST", "/signin", """{"pin":"${signIn.pin}"}""", signedIn = false).status)
        assertTrue(call("GET", "/?k=${signIn.key}", signedIn = false).header("Set-Cookie") != null)
    }

    @Test
    fun `sign-in asks for six digits, and reads only JSON`() {
        assertEquals(400, call("POST", "/signin", """{"pin":"12"}""", signedIn = false).status)
        assertEquals(400, call("POST", "/signin", """{"code":"123456"}""", signedIn = false).status)
        assertEquals(415, call("POST", "/signin", "pin=123456", signedIn = false, contentType = "application/x-www-form-urlencoded").status)
        assertEquals(405, call("GET", "/signin", signedIn = false).status)
    }

    @Test
    fun `a host name is refused, so a rebinding page elsewhere can't reach the API`() {
        assertEquals(400, call("GET", "/", host = "evil.example:8137").status)
        assertEquals(400, call("GET", "/api/lineup", host = "shield.local").status)
        assertEquals(400, call("GET", "/", host = null).status)
        assertTrue(editor.calls.isEmpty())
    }

    @Test
    fun `IP addresses and localhost are the hosts allowed`() {
        for (host in listOf("192.168.1.20:8137", "192.168.1.20", "127.0.0.1:0", "localhost:8137", "LOCALHOST", "[::1]:8137", "[fe80::1]")) {
            assertTrue(host, EditorSite.hostAllowed(host))
        }
        for (host in listOf("", "evil.example", "1.2.3", "1.2.3.4.5", "256.1.1.1", "1.2.3.4:", "1.2.3.4:http", "[::1", "[]", "[evil.example]", "1.2.3.4:123456")) {
            assertFalse(host, EditorSite.hostAllowed(host))
        }
    }

    @Test
    fun `a body that isn't JSON is 415`() {
        assertEquals(415, call("PUT", "/api/channels/classics/name", "name=x", contentType = "text/plain").status)
        assertTrue(editor.calls.isEmpty())
    }

    @Test
    fun `unknown paths are 404 and wrong methods 405`() {
        assertEquals(404, call("GET", "/favicon.ico").status)
        assertEquals(404, call("GET", "/api/nope").status)
        assertEquals(404, call("POST", "/api/channels/classics/explode", "{}").status)
        assertEquals(405, call("POST", "/", "{}").status)
        val wrong = call("POST", "/api/lineup", "{}")
        assertEquals(405, wrong.status)
        assertEquals("GET", wrong.header("Allow"))
        assertEquals("PUT, DELETE", call("POST", "/api/channels/classics", "{}").header("Allow"))
    }

    // The API, route by route

    @Test
    fun `GET lineup is the lineup's first value, rules in full`() {
        val response = call("GET", "/api/lineup")
        assertEquals(200, response.status)
        assertEquals(HttpResponse.JSON, response.header("Content-Type"))
        val lineup = response.json().jsonArray
        assertEquals(2, lineup.size)
        val classics = lineup[0].jsonObject
        assertEquals("classics", classics["id"]!!.jsonPrimitive.content)
        assertEquals("Big Atlas", classics["onNow"]!!.jsonPrimitive.content)
        val draft = classics["draft"]!!.jsonObject
        assertEquals("Classics", draft["name"]!!.jsonPrimitive.content)
        assertEquals("trailers", draft["filler"]!!.jsonPrimitive.content)
        val rule = draft["rule"]!!.jsonObject
        assertEquals("shuffle", rule["order"]!!.jsonPrimitive.content)
        assertEquals("25", rule["minAgeYears"]!!.jsonPrimitive.content)
        // Defaults are written too, so the page sees every field.
        assertEquals(JsonArray(emptyList()), rule["collections"])
        assertEquals(JsonArray(emptyList()), rule["studios"])
        assertEquals(JsonArray(emptyList()), rule["labels"])
        assertEquals("rotate_shows", lineup[1].jsonObject["draft"]!!.jsonObject["rule"]!!.jsonObject["order"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GET choices`() {
        val choices = call("GET", "/api/choices").json().jsonObject
        assertEquals("The Office", choices["shows"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("1980", choices["decades"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `studios and labels go out in the lineup and the choices, and come back in a draft`() {
        val rule = ChannelRule(studios = listOf("Northlight"), labels = listOf("Favourites"))
        editor.lineup.value = listOf(EditableChannel("northlight", 3, true, ChannelDraft("Northlight", rule), null, null))

        val saved = call("GET", "/api/lineup").json().jsonArray[0].jsonObject["draft"]!!.jsonObject["rule"]!!.jsonObject
        assertEquals(listOf("Northlight"), saved["studios"]!!.strings())
        assertEquals(listOf("Favourites"), saved["labels"]!!.strings())
        val choices = call("GET", "/api/choices").json().jsonObject
        assertEquals(listOf("Northlight", "Tidewater"), choices["studios"]!!.strings())
        assertEquals(listOf("Favourites"), choices["labels"]!!.strings())

        val draft = """{"name":"Tidewater","rule":{"studios":["Tidewater"],"labels":["Favourites","Rewatch"]}}"""
        assertEquals(204, call("PUT", "/api/channels/northlight", draft).status)
        assertEquals(listOf("Tidewater"), editor.lastDraft!!.rule.studios)
        assertEquals(listOf("Favourites", "Rewatch"), editor.lastDraft!!.rule.labels)
        assertEquals(200, call("POST", "/api/preview", """{"name":"Kids","rule":{"labels":["Kids"]}}""").status)
        assertEquals(ChannelRule(labels = listOf("Kids")), editor.lastDraft!!.rule)
    }

    private fun JsonElement.strings() = jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `POST channels creates from a draft and returns its id`() {
        val response = call(
            "POST",
            "/api/channels",
            """{"name":"Late","rule":{"types":["episode"],"order":"in_order","futureField":1},"alignmentMinutes":15,"filler":"up_next"}""",
        )
        assertEquals(201, response.status)
        assertEquals("""{"id":"new-1"}""", response.text())
        assertEquals(listOf("create Late IN_ORDER 15 up_next"), editor.calls)
    }

    @Test
    fun `PUT and DELETE a channel`() {
        assertEquals(204, call("PUT", "/api/channels/classics", """{"name":"Old films","rule":{"genres":["Drama"]}}""").status)
        assertEquals(204, call("DELETE", "/api/channels/classics").status)
        assertEquals(listOf("update classics Old films [Drama]", "delete classics"), editor.calls)
    }

    @Test
    fun `POST duplicate returns the copy's id`() {
        val response = call("POST", "/api/channels/classics/duplicate")
        assertEquals(201, response.status)
        assertEquals("""{"id":"classics-2"}""", response.text())
        assertEquals(listOf("duplicate classics"), editor.calls)
    }

    @Test
    fun `PUT name, number and enabled, and POST move`() {
        assertEquals(204, call("PUT", "/api/channels/classics/name", """{"name":"Old films"}""").status)
        assertEquals(204, call("PUT", "/api/channels/classics/number", """{"number":12}""").status)
        assertEquals(204, call("PUT", "/api/channels/classics/enabled", """{"enabled":false}""").status)
        assertEquals(204, call("POST", "/api/channels/classics/move", """{"index":1}""").status)
        assertEquals(
            listOf("rename classics Old films", "renumber classics 12", "setEnabled classics false", "move classics 1"),
            editor.calls,
        )
    }

    @Test
    fun `ids are percent-decoded`() {
        call("PUT", "/api/channels/late%20night%2F2/name", """{"name":"x"}""")
        assertEquals(listOf("rename late night/2 x"), editor.calls)
    }

    @Test
    fun `POST preview returns start, end, title, episode and filler, and nothing else`() {
        editor.previewSlots = listOf(
            GuideSlot(1_000, 2_000, "Big Atlas", null, "42", null, "/library/metadata/42/thumb?X-Plex-Token=secret", "PG", false, null, "A summary"),
            GuideSlot(2_000, 3_000, "Up next", null, null, null, null, null, true, null),
            GuideSlot(3_000, 4_000, "The Office", "S3E4 · The Coup", "7", "5", null, "TV-14", false, null),
        )
        val response = call("POST", "/api/preview", """{"name":"Test","rule":{}}""")
        assertEquals(200, response.status)
        val slots = response.json().jsonArray.map { it.jsonObject }
        assertEquals(setOf("start", "end", "title", "episode", "filler"), slots[0].keys)
        assertEquals("1000", slots[0]["start"]!!.jsonPrimitive.content)
        assertEquals("2000", slots[0]["end"]!!.jsonPrimitive.content)
        assertEquals("true", slots[1]["filler"]!!.jsonPrimitive.content)
        assertEquals("S3E4 · The Coup", slots[2]["episode"]!!.jsonPrimitive.content)
        assertFalse("X-Plex-Token" in response.text())
        assertFalse("library" in response.text())
        assertEquals(listOf("preview Test"), editor.calls)
    }

    @Test
    fun `POST remake`() {
        assertEquals(204, call("POST", "/api/remake").status)
        assertEquals(listOf("remake"), editor.calls)
    }

    @Test
    fun `EditRejected is 400 with its sentence`() {
        editor.reject = "Channel 12 is taken"
        val response = call("PUT", "/api/channels/classics/number", """{"number":12}""")
        assertEquals(400, response.status)
        assertEquals("Channel 12 is taken", response.error())
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `anything else is 500 with a general sentence, and only its class is logged`() {
        editor.failure = IllegalStateException("token=secret at http://10.0.0.2:32400")
        val response = call("POST", "/api/remake")
        assertEquals(500, response.status)
        assertEquals(EditorApi.GENERAL_ERROR, response.error())
        assertFalse("secret" in response.text())
        assertEquals(listOf("phone editor: IllegalStateException"), logged)
    }

    @Test
    fun `a body that can't be read is 400, and the editor isn't called`() {
        assertEquals(400, call("POST", "/api/channels", "{not json").status)
        assertEquals(400, call("PUT", "/api/channels/classics/number", """{"number":"twelve"}""").status)
        assertEquals(400, call("POST", "/api/channels/classics/move", "{}").status)
        assertEquals(400, call("POST", "/api/preview", """{"rule":{}}""").status)
        assertTrue(editor.calls.isEmpty())
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `an error is a JSON object`() {
        val response = call("GET", "/api/nope")
        assertTrue(response.json() is JsonObject)
    }
}
