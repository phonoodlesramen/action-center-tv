package com.phonoodlesramen.actioncenter.ai

import com.phonoodlesramen.actioncenter.ai.BlurbFixtures.KEY
import com.phonoodlesramen.actioncenter.guide.editor.EditorServer
import com.phonoodlesramen.actioncenter.guide.editor.EditorServer.State
import com.phonoodlesramen.actioncenter.guide.editor.EditorServer.StopReason
import java.io.File
import java.net.InetAddress
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Add a key on your phone (M10) on a real socket, like the phone editor's tests: the key
 * page on the editor's server and sign-in, on loopback, hit with OkHttp. Only a signed-in
 * phone can send a key, and the key never comes back, nor goes in a log.
 */
class AiKeyPageTest {
    private class Drop : AiKeyDrop {
        @Volatile var key: String? = null

        override suspend fun keyEnding() = key?.let(AiKeys::ending)

        override suspend fun saveKey(key: String) {
            this.key = key
        }
    }

    private val drop = Drop()
    private val saved = mutableListOf<String>()
    private val logged = mutableListOf<String>()
    private val http = OkHttpClient.Builder().followRedirects(false).callTimeout(5, TimeUnit.SECONDS).build()

    /** The real page, as the app bundles it. */
    private val page = File("src/main/assets/editor/key.html").readBytes()

    private val server = EditorServer(
        api = AiKeyApi(drop, onSaved = { synchronized(saved) { saved += it } }, log = { synchronized(logged) { logged += it } }),
        page = { page },
        address = { InetAddress.getLoopbackAddress() },
        ports = listOf(0),
        log = { synchronized(logged) { logged += it } },
    )

    private fun running(): State.Running {
        server.start()
        return runBlocking { withTimeout(5_000) { server.state.first { it is State.Running } } } as State.Running
    }

    private fun get(url: String, cookie: String? = null) = http.newCall(
        Request.Builder().url(url).apply { if (cookie != null) header("Cookie", cookie) }.build(),
    ).execute()

    private fun post(url: String, body: String, cookie: String? = null, type: String = "application/json") = http.newCall(
        Request.Builder().url(url).apply { if (cookie != null) header("Cookie", cookie) }.post(body.toRequestBody(type.toMediaType())).build(),
    ).execute()

    private fun signIn(running: State.Running) = get(running.link).use { it.header("Set-Cookie")!!.substringBefore(';') }

    @After
    fun stop() = server.stop(StopReason.CLOSED)

    @Test
    fun `serves the key page, with the editor's content policy`() {
        val running = running()
        get(running.address + "/").use { response ->
            assertEquals(200, response.code)
            assertEquals("text/html; charset=utf-8", response.header("Content-Type"))
            assertTrue(response.header("Content-Security-Policy")!!.startsWith("default-src 'none'"))
            val html = response.body.string()
            assertTrue(html.startsWith("<!doctype html>"))
            assertTrue("<title>Claude API key</title>" in html)
        }
    }

    @Test
    fun `the page loads nothing from elsewhere and has one field for the key`() {
        val html = page.decodeToString()
        assertFalse(Regex("""\b(src|href)\s*=\s*["']?(https?:)?//""").containsMatchIn(html))
        assertFalse("@import" in html)
        assertFalse(Regex("""url\(\s*["']?(https?:)?//""").containsMatchIn(html))
        assertFalse(Regex("""https?://""").containsMatchIn(html))
        // No em dashes in what people read.
        assertFalse('—' in html)
        assertEquals(1, Regex("""id: 'key'""").findAll(html).count())
        assertTrue("type: 'password'" in html)
    }

    @Test
    fun `without signing in, no key goes in`() {
        val running = running()
        get(running.address + "/api/key").use { assertEquals(401, it.code) }
        post(running.address + "/api/key", """{"key":"$KEY"}""").use { response ->
            assertEquals(401, response.code)
            assertFalse(KEY in response.body.string())
        }
        assertNull(drop.key)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a signed-in phone sends the key, it's stored, and only its last 4 characters come back`() {
        val running = running()
        val cookie = signIn(running)
        get(running.address + "/api/key", cookie).use { assertEquals("""{"ending":null}""", it.body.string()) }

        post(running.address + "/api/key", """{"key":"  $KEY\n"}""", cookie).use { response ->
            assertEquals(200, response.code)
            assertEquals("""{"ending":"wxYZ"}""", response.body.string())
        }

        assertEquals(KEY, drop.key)
        assertEquals(listOf("wxYZ"), saved)
        get(running.address + "/api/key", cookie).use { assertEquals("""{"ending":"wxYZ"}""", it.body.string()) }
    }

    @Test
    fun `the PIN signs a typed address in too`() {
        val running = running()
        val cookie = post(running.address + "/signin", """{"pin":"${running.pin}"}""").use { response ->
            assertEquals(204, response.code)
            response.header("Set-Cookie")!!.substringBefore(';')
        }
        post(running.address + "/api/key", """{"key":"$KEY"}""", cookie).use { assertEquals(200, it.code) }
        assertEquals(KEY, drop.key)
    }

    @Test
    fun `something that isn't a key is turned down without repeating it`() {
        val running = running()
        val cookie = signIn(running)
        val plexToken = "plex-token-AbCdEfGhIjKlMnOpQrStUv"

        post(running.address + "/api/key", """{"key":"$plexToken"}""", cookie).use { response ->
            assertEquals(400, response.code)
            val body = response.body.string()
            assertTrue("start with sk-ant-" in body)
            assertFalse(plexToken in body)
        }
        post(running.address + "/api/key", "not json", cookie).use { assertEquals(400, it.code) }
        // A plain form from another site can't post here.
        post(running.address + "/api/key", "key=$KEY", cookie, type = "application/x-www-form-urlencoded").use { assertEquals(415, it.code) }
        assertNull(drop.key)
    }

    @Test
    fun `only the key route is there`() {
        val running = running()
        val cookie = signIn(running)
        get(running.address + "/api/lineup", cookie).use { assertEquals(404, it.code) }
        http.newCall(Request.Builder().url(running.address + "/api/key").header("Cookie", cookie).delete().build()).execute().use {
            assertEquals(405, it.code)
        }
    }

    @Test
    fun `nothing is logged, and never the key`() {
        val running = running()
        val cookie = signIn(running)
        post(running.address + "/api/key", """{"key":"$KEY"}""", cookie).use { }
        assertTrue(logged.isEmpty())
    }
}
