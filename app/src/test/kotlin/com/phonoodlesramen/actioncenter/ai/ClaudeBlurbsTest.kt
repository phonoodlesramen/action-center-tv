package com.phonoodlesramen.actioncenter.ai

import com.phonoodlesramen.actioncenter.ai.BlurbFixtures.KEY
import com.phonoodlesramen.actioncenter.content.Lens
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The night's request to Claude Haiku 5.5 (M11) against a local server: what's sent (and
 * what never is, M8), and every kind of answer. No request reaches api.anthropic.com.
 */
class ClaudeBlurbsTest {
    private val server = MockWebServer()
    private lateinit var client: ClaudeBlurbs

    private val targets = BlurbPicks.choose(BlurbFixtures.picks, Lens.entries.toSet(), BlurbFixtures.titles)
    private val sent = targets.map { it.sent }

    @Before
    fun setUp() {
        server.start()
        client = ClaudeBlurbs(OkHttpClient(), server.url("/v1/messages"))
    }

    @After
    fun tearDown() = server.close()

    private fun respond(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    /** A message as the API sends it, with Haiku 5.5's empty thinking block before the answer. */
    private fun message(answer: String, stopReason: String = "end_turn", input: Int = 5_000, output: Int = 2_500) = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("id", "msg_test")
            put("type", "message")
            put("role", "assistant")
            put("model", ClaudeBlurbs.MODEL)
            putJsonArray("content") {
                addJsonObject {
                    put("type", "thinking")
                    put("thinking", "")
                    put("signature", "sig")
                }
                addJsonObject {
                    put("type", "text")
                    put("text", answer)
                }
            }
            put("stop_reason", stopReason)
            putJsonObject("usage") {
                put("input_tokens", input)
                put("output_tokens", output)
            }
        },
    )

    private fun blurbs(vararg pairs: Pair<String, String>) = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            putJsonArray("blurbs") {
                pairs.forEach { (id, text) ->
                    addJsonObject {
                        put("id", id)
                        put("text", text)
                    }
                }
            }
        },
    )

    private fun requestJson(): JsonObject {
        val request = server.takeRequest()
        return Json.parseToJsonElement(request.body!!.utf8()).jsonObject
    }

    @Test
    fun `the request is one message to Haiku 5_5 with low effort and the blurbs schema`() = runTest {
        respond(200, message(blurbs()))
        client.write(KEY, sent)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/messages", request.url.encodedPath)
        assertEquals(KEY, request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertEquals("application/json", request.headers["Content-Type"])
        // The key goes in its own header, nowhere else.
        assertNull(request.headers["Authorization"])
        assertEquals(1, request.headers.values("x-api-key").size)
        val text = request.body!!.utf8()
        assertFalse(KEY in text)

        val body = Json.parseToJsonElement(text).jsonObject
        assertEquals("claude-haiku-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(4_000, body["max_tokens"]!!.jsonPrimitive.int)
        assertTrue(body["system"]!!.jsonPrimitive.content.contains("at most 90 characters"))
        // Rejected or deprecated: no sampling, no thinking override, no old output_format, no prefill.
        listOf("temperature", "top_p", "top_k", "thinking", "output_format").forEach { assertFalse(it, it in body) }
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)

        val config = body["output_config"]!!.jsonObject
        assertEquals("low", config["effort"]!!.jsonPrimitive.content)
        val format = config["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        val expected = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("blurbs") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("id") { put("type", "string") }
                            putJsonObject("text") { put("type", "string") }
                        }
                        putJsonArray("required") {
                            add("id")
                            add("text")
                        }
                        put("additionalProperties", false)
                    }
                }
            }
            putJsonArray("required") { add("blurbs") }
            put("additionalProperties", false)
        }
        assertEquals(expected, format["schema"])
    }

    @Test
    fun `the picks go as JSON in the user message, with their details and why`() = runTest {
        respond(200, message(blurbs()))
        client.write(KEY, sent)

        val content = requestJson()["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        val picks = Json.parseToJsonElement(content.substringAfter('\n').trim()).jsonObject["picks"]!!.jsonArray
        assertEquals(3, picks.size)
        val heat = picks[0].jsonObject
        assertEquals("p1", heat["id"]!!.jsonPrimitive.content)
        assertEquals("Heat", heat["title"]!!.jsonPrimitive.content)
        assertEquals("film", heat["kind"]!!.jsonPrimitive.content)
        assertEquals(1995, heat["year"]!!.jsonPrimitive.int)
        assertEquals(listOf("Crime", "Thriller"), heat["genres"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("R", heat["rating"]!!.jsonPrimitive.content)
        assertEquals(170, heat["minutes"]!!.jsonPrimitive.int)
        assertEquals("Because you liked Collateral", heat["reason"]!!.jsonPrimitive.content)
        assertEquals(listOf("Rated 8.3", "New this month"), heat["evidence"]!!.jsonArray.map { it.jsonPrimitive.content })
        // The episode goes as its show.
        val show = picks[1].jsonObject
        assertEquals("Harbour Lights", show["title"]!!.jsonPrimitive.content)
        assertEquals("series", show["kind"]!!.jsonPrimitive.content)
        assertEquals(listOf("Drama", "Mystery"), show["genres"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `nothing about the viewer, the server or when they watched goes in the body`() = runTest {
        respond(200, message(blurbs()))
        client.write(KEY, sent)
        val body = server.takeRequest().body!!.utf8()

        listOf(
            // Plex's own keys, paths and section, and the file.
            "rk-48151", "rk-ep-77", "rk-show-9", "section-7", "/library", "file.mkv",
            // Watch dates and positions, in any form.
            BlurbFixtures.VIEWED_AT.toString(), BlurbFixtures.RELEASED, "Last watched", "days ago", "1234000",
            // The plot, which could spoil it.
            "secret twist",
            // The account and server never reach the picks at all.
            "Alex", "Basement Plex", "192.168.",
        ).forEach { assertFalse("\"$it\" was sent", it in body) }
    }

    @Test
    fun `a good answer keeps one sentence per id sent, up to 90 characters`() = runTest {
        val long = "This one has far too many words in it to fit on the line under the title on the home screen."
        respond(
            200,
            message(
                blurbs(
                    "p1" to "  A tense crime night, after the Collateral you liked.  ",
                    "p2" to long,
                    "p3" to "Another heist with real weight. Short too.",
                    // Not sent: ignored.
                    "p9" to "Who's this for?",
                ),
            ),
        )
        val answer = client.write(KEY, sent) as BlurbAnswer.Written

        assertTrue(long.length > ClaudeBlurbs.MAX_CHARS)
        assertEquals(mapOf("p1" to "A tense crime night, after the Collateral you liked."), answer.blurbs)
        assertEquals(TokenUsage(5_000, 2_500), answer.usage)
    }

    @Test
    fun `the cost comes from the usage at Haiku 5_5's prices`() {
        assertEquals(0.60, TokenUsage(1_000_000, 1_000_000).dollars, 1e-9)
        val night = TokenUsage(5_000, 2_500)
        assertEquals(0.00175, night.dollars, 1e-9)
        assertEquals(7_500, night.total)
        assertEquals("about $0.002", BlurbRunLine.dollars(night.dollars))
    }

    @Test
    fun `a refusal is a failure to report, not a crash`() = runTest {
        respond(200, message("", stopReason = "refusal"))
        assertEquals(BlurbAnswer.Failed(ClaudeBlurbs.REFUSED, retry = false), client.write(KEY, sent))
    }

    @Test
    fun `an answer cut short at max_tokens is a failure`() = runTest {
        respond(200, message("""{"blurbs":[{"id":"p1","te""", stopReason = "max_tokens"))
        assertEquals(BlurbAnswer.Failed(ClaudeBlurbs.CUT_SHORT, retry = false), client.write(KEY, sent))
    }

    @Test
    fun `an answer that isn't the JSON asked for can't be read`() = runTest {
        respond(200, message("Here are your blurbs!"))
        assertEquals(BlurbAnswer.Failed(ClaudeBlurbs.UNREADABLE, retry = false), client.write(KEY, sent))
        respond(200, "<html>Bad gateway</html>")
        assertEquals(BlurbAnswer.Failed(ClaudeBlurbs.UNREADABLE, retry = false), client.write(KEY, sent))
        respond(200, """{"stop_reason":"end_turn","content":[]}""")
        assertEquals(BlurbAnswer.Failed(ClaudeBlurbs.UNREADABLE, retry = false), client.write(KEY, sent))
    }

    @Test
    fun `a refused key isn't retried, and busy or no credit is`() = runTest {
        fun error(type: String, message: String) = """{"type":"error","error":{"type":"$type","message":"$message"}}"""
        val cases = listOf(
            401 to BlurbAnswer.Failed(ClaudeBlurbs.KEY_REFUSED, retry = false),
            403 to BlurbAnswer.Failed(ClaudeBlurbs.KEY_REFUSED, retry = false),
            402 to BlurbAnswer.Failed(ClaudeBlurbs.NO_CREDIT, retry = true),
            429 to BlurbAnswer.Failed(ClaudeBlurbs.BUSY, retry = true),
            500 to BlurbAnswer.Failed(ClaudeBlurbs.OVERLOADED, retry = true),
            529 to BlurbAnswer.Failed(ClaudeBlurbs.OVERLOADED, retry = true),
        )
        cases.forEach { (code, expected) ->
            respond(code, error("some_error", "x-api-key: $KEY was no good"))
            val answer = client.write(KEY, sent)
            assertEquals("$code", expected, answer)
        }
        // A low balance comes as a 400 with a billing message.
        respond(400, error("invalid_request_error", "Your credit balance is too low to access the Anthropic API."))
        assertEquals(BlurbAnswer.Failed(ClaudeBlurbs.NO_CREDIT, retry = true), client.write(KEY, sent))
        respond(400, error("invalid_request_error", "messages: Field required"))
        val bad = client.write(KEY, sent) as BlurbAnswer.Failed
        assertFalse(bad.retry)
        assertTrue(bad.reason.startsWith("Claude turned the request down (400)"))
        // Claude's own message, which can quote what was sent, is never shown.
        assertFalse("Field required" in bad.reason)
    }

    @Test
    fun `no network is retried later`() = runTest {
        val gone = ClaudeBlurbs(OkHttpClient(), server.url("/v1/messages"))
        server.close()
        assertEquals(BlurbAnswer.Failed(ClaudeBlurbs.OFFLINE, retry = true), gone.write(KEY, sent))
    }

    @Test
    fun `one sentence of at most 90 characters on one line`() {
        assertEquals("Short and warm.", ClaudeBlurbs.fit(" Short and warm. "))
        assertEquals("Mid-season, and you're close to the end", ClaudeBlurbs.fit("Mid-season, and you're close to the end"))
        assertNull(ClaudeBlurbs.fit(""))
        assertNull(ClaudeBlurbs.fit("Two lines\nnever fit."))
        assertNull(ClaudeBlurbs.fit("One. Two."))
        assertNull(ClaudeBlurbs.fit("Really? Yes."))
        assertNull(ClaudeBlurbs.fit("x".repeat(91)))
        assertEquals("x".repeat(90), ClaudeBlurbs.fit("x".repeat(90)))
    }
}
