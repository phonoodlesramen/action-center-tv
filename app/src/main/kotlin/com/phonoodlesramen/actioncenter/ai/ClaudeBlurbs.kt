package com.phonoodlesramen.actioncenter.ai

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Tokens one request used (`usage`), and what they cost at Claude Haiku 5.5's prices (M11). */
data class TokenUsage(val input: Long = 0, val output: Long = 0) {
    val total: Long get() = input + output
    val dollars: Double get() = input * INPUT_DOLLARS_PER_MILLION / 1_000_000 + output * OUTPUT_DOLLARS_PER_MILLION / 1_000_000

    companion object {
        /** Claude Haiku 5.5, prompts up to 100K tokens (the Claude API reference, cached 6 Oct 2026). */
        const val INPUT_DOLLARS_PER_MILLION = 0.10
        const val OUTPUT_DOLLARS_PER_MILLION = 0.50
    }
}

/** What a night's request came back with. */
sealed interface BlurbAnswer {
    /** By the ids sent; ids that weren't sent and texts that break the rules are already left out. */
    data class Written(val blurbs: Map<String, String>, val usage: TokenUsage) : BlurbAnswer

    /** [reason] is a sentence for Settings, never the key or the request; [retry] when later may work (busy, no credit, offline). */
    data class Failed(val reason: String, val retry: Boolean) : BlurbAnswer
}

/** Writes a blurb for each pick with [key]. Never throws, except to cancel. */
fun interface BlurbAuthor {
    suspend fun write(key: String, picks: List<BlurbPick>): BlurbAnswer
}

/**
 * One request to Claude Haiku 5.5 for the night's blurbs (M11), with the app's own OkHttp
 * and kotlinx.serialization like its Plex and weather calls: the official Java SDK was ruled
 * out for its size (its core jar alone is 40 MB, plus Jackson, kotlin-reflect and OkHttp 4).
 *
 * - **The request:** `POST /v1/messages` with `x-api-key`, `anthropic-version` and JSON;
 *   [MODEL], a system prompt and one user message carrying the picks as JSON, effort
 *   `low`, and a JSON schema for the answer (`output_config.format`). No sampling
 *   parameters (rejected since 2026-10-21), no `thinking` (Haiku 5.5's default stands), no
 *   assistant prefill, not the deprecated `output_format`.
 * - **The answer:** `stop_reason` first (`refusal` and `max_tokens` are failures, not
 *   crashes), then the first `text` block as `{blurbs: [{id, text}]}`. Ids that weren't
 *   sent are ignored, and a text over [MAX_CHARS] characters or of more than one sentence
 *   is dropped: its pick keeps its plain reason.
 * - **Errors** are sentences for Settings ([BlurbAnswer.Failed]): a refused key (401, 403)
 *   isn't retried; no credit (402), too many requests (429), an overloaded or failing
 *   service (5xx, 529) and no network are, by WorkManager, later. The key and the request
 *   body never go into a log or an error.
 */
class ClaudeBlurbs(
    http: OkHttpClient,
    private val url: HttpUrl = MESSAGES_URL.toHttpUrl(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BlurbAuthor {
    /** The shared client's pool, with time to write about 30 blurbs: nothing comes back until all of them are done. */
    private val http = http.newBuilder()
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun write(key: String, picks: List<BlurbPick>): BlurbAnswer = withContext(io) {
        val request = Request.Builder()
            .url(url)
            .header("x-api-key", key)
            .header("anthropic-version", API_VERSION)
            // Bytes, so OkHttp leaves the type as it is (a String body would add a charset).
            .post(body(picks).toByteArray().toRequestBody(JSON_TYPE.toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (response.isSuccessful) read(text, picks.mapTo(mutableSetOf()) { it.id }) else failure(response.code, text)
            }
        } catch (_: IOException) {
            BlurbAnswer.Failed(OFFLINE, retry = true)
        }
    }

    companion object {
        const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
        const val API_VERSION = "2023-06-01"

        /** Exactly this id, no date suffix (D17). */
        const val MODEL = "claude-haiku-5-5"
        const val MAX_TOKENS = 4_000

        /** A blurb's longest, in characters: about the reason line's room on the hero and the lens card. */
        const val MAX_CHARS = 90

        private const val JSON_TYPE = "application/json"
        private const val READ_TIMEOUT_SECONDS = 90L
        private const val CALL_TIMEOUT_SECONDS = 120L

        const val OFFLINE = "Couldn't reach Claude. It tries again later."
        const val KEY_REFUSED = "The key was refused. Add a new key on your phone."
        const val NO_CREDIT = "The Claude account is out of credit. It tries again later."
        const val BUSY = "Claude had too many requests. It tries again later."
        const val OVERLOADED = "Claude was busy. It tries again later."
        const val REFUSED = "Claude declined to write them. The picks keep their own reasons."
        const val CUT_SHORT = "Claude's answer was cut short. The picks keep their own reasons."
        const val UNREADABLE = "Claude's answer couldn't be read. The picks keep their own reasons."

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /** What to write, and how (M11). The picks come in the user message. */
        internal val SYSTEM = """
            You write the short note under each film or series pick on a TV home screen, saying why it suits this viewer tonight.

            Each pick comes with its title, kind, year, genres, content rating and length in minutes, the reason it was picked, and sometimes more evidence. The reason and evidence come from what this viewer has watched and rated.

            For every pick:
            - Write one sentence of at most $MAX_CHARS characters.
            - Ground it in the reason and evidence given; the title's details can add colour.
            - No spoilers: nothing about the story beyond its basic premise.
            - Keep it plain and warm, not salesy: no hype, no exclamation marks, no questions.
            - Don't invent anything about the viewer, and don't repeat the title, which shows above the note.

            Write one note for each pick, with its id.
        """.trimIndent()

        private const val USER_INTRO = "Tonight's picks, as JSON:\n\n"

        /** `{blurbs: [{id, text}]}`, nothing else. */
        internal val SCHEMA: JsonObject = buildJsonObject {
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

        /** The request's JSON for [picks]: nothing in it but what [BlurbPick] carries (M8). */
        internal fun body(picks: List<BlurbPick>): String {
            val user = USER_INTRO + json.encodeToString(PicksJson.serializer(), PicksJson(picks))
            return json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("model", MODEL)
                    put("max_tokens", MAX_TOKENS)
                    put("system", SYSTEM)
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "user")
                            put("content", user)
                        }
                    }
                    putJsonObject("output_config") {
                        put("effort", "low")
                        putJsonObject("format") {
                            put("type", "json_schema")
                            put("schema", SCHEMA)
                        }
                    }
                },
            )
        }

        /** A 200's answer: the stop reason first, then the blurbs from the first text block. */
        internal fun read(text: String, sent: Set<String>): BlurbAnswer = try {
            val message = json.decodeFromString(MessageJson.serializer(), text)
            when (message.stopReason) {
                "end_turn", "stop_sequence" -> {
                    val answer = message.content.firstOrNull { it.type == "text" }?.text
                    if (answer == null) {
                        BlurbAnswer.Failed(UNREADABLE, retry = false)
                    } else {
                        val blurbs = json.decodeFromString(BlurbsJson.serializer(), answer).blurbs
                            .filter { it.id in sent }
                            .mapNotNull { blurb -> fit(blurb.text)?.let { blurb.id to it } }
                            .distinctBy { it.first }
                            .toMap()
                        BlurbAnswer.Written(blurbs, message.usage?.let { TokenUsage(it.input, it.output) } ?: TokenUsage())
                    }
                }
                "refusal" -> BlurbAnswer.Failed(REFUSED, retry = false)
                "max_tokens" -> BlurbAnswer.Failed(CUT_SHORT, retry = false)
                else -> BlurbAnswer.Failed(UNREADABLE, retry = false)
            }
        } catch (_: IllegalArgumentException) {
            // SerializationException is one too.
            BlurbAnswer.Failed(UNREADABLE, retry = false)
        }

        /** An error's sentence, by status and Claude's own error type; the response's message is never shown. */
        internal fun failure(code: Int, text: String): BlurbAnswer.Failed {
            val error = runCatching { json.decodeFromString(ErrorJson.serializer(), text).error }.getOrNull()
            val noCredit = code == 402 || error?.type == "billing_error" || error?.message?.contains("credit balance", ignoreCase = true) == true
            return when {
                code == 401 || code == 403 -> BlurbAnswer.Failed(KEY_REFUSED, retry = false)
                noCredit -> BlurbAnswer.Failed(NO_CREDIT, retry = true)
                code == 429 -> BlurbAnswer.Failed(BUSY, retry = true)
                code >= 500 -> BlurbAnswer.Failed(OVERLOADED, retry = true)
                else -> BlurbAnswer.Failed("Claude turned the request down ($code). The picks keep their own reasons.", retry = false)
            }
        }

        /** Another sentence after a full stop, a question or an exclamation mark. */
        private val SECOND_SENTENCE = Regex("""[.!?…]["'”’)]*\s+\S""")

        /** [text], trimmed, if it's one sentence of at most [MAX_CHARS] characters on one line; null otherwise. */
        internal fun fit(text: String): String? {
            val trimmed = text.trim()
            return trimmed.takeIf {
                it.isNotEmpty() && it.length <= MAX_CHARS && '\n' !in it && '\r' !in it && !SECOND_SENTENCE.containsMatchIn(it)
            }
        }
    }
}

@Serializable
private class PicksJson(val picks: List<BlurbPick>)

@Serializable
private class MessageJson(
    @SerialName("stop_reason") val stopReason: String? = null,
    val content: List<BlockJson> = emptyList(),
    val usage: UsageJson? = null,
)

@Serializable
private class BlockJson(val type: String = "", val text: String? = null)

@Serializable
private class UsageJson(
    @SerialName("input_tokens") val input: Long = 0,
    @SerialName("output_tokens") val output: Long = 0,
)

@Serializable
private class BlurbsJson(val blurbs: List<BlurbJson> = emptyList())

@Serializable
private class BlurbJson(val id: String, val text: String)

@Serializable
private class ErrorJson(val error: ErrorDetailJson? = null)

@Serializable
private class ErrorDetailJson(val type: String? = null, val message: String? = null)
