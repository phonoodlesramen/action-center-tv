package com.phonoodlesramen.actioncenter.plex

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One audio or subtitle stream of a title's file, as Plex lists it in the title's details
 * (2.7, G13): what the guide's player needs to match it to Media3's tracks and to name it
 * in the options. Plex keys only, never a token or an address.
 */
data class PlexStream(
    /** Plex's own id for the stream. */
    val id: String,
    /** [AUDIO] or [SUBTITLES]. */
    val type: Int,
    /** Where it sits in the file, counting every stream; null for a subtitle file next to it. */
    val index: Int?,
    /** ISO 639-2, e.g. "eng" or "ger". */
    val languageCode: String?,
    /** "English". */
    val language: String?,
    /** "English (EAC3 5.1)", "English Forced (SRT External)". */
    val displayTitle: String?,
    /** [displayTitle] with the stream's own title: "Commentary (English AC3 Stereo)". */
    val extendedDisplayTitle: String?,
    /** Plex's choice for this file: the operator's, in Plex or Plezy, or Plex's own from the account's languages. */
    val selected: Boolean,
    val default: Boolean,
    val forced: Boolean,
    /** "eac3", "dca", "srt", "pgs". */
    val codec: String?,
    val channels: Int?,
    /** A subtitle file next to the title's file: its path on the server, `/library/streams/{id}`. */
    val key: String?,
    /** Closed captions inside the video stream: not a track of their own in the file. */
    val inVideo: Boolean = false,
) {
    companion object {
        const val AUDIO = 2
        const val SUBTITLES = 3
    }
}

/**
 * Reads a title's audio and subtitle streams from the linked server's
 * `GET /library/metadata/{ratingKey}`, under `MediaContainer.Metadata[0].Media[0].Part[0].Stream[]`
 * (official PMS spec; `forced` as python-plexapi reads it). The token goes in a header,
 * never the URL, and nothing here logs.
 *
 * Its own reader, apart from [PlexLibrary], so the cache's API stays as Phase 3 has it.
 */
class PlexStreams(
    private val http: OkHttpClient,
    private val headers: PlexHeaders,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * The audio and subtitle streams of [ratingKey]'s first file, in Plex's order. Null when
     * the title is gone (404) or has no file. Throws [PlexException] when the server can't be
     * reached or answers something else.
     */
    suspend fun streams(access: ServerAccess, ratingKey: String): List<PlexStream>? {
        require(ratingKey.all(Char::isDigit)) { "Bad Plex ratingKey: $ratingKey" }
        val url = "${access.baseUri.trimEnd('/')}/library/metadata/$ratingKey".toHttpUrlOrNull()
            ?: throw PlexException.Failed(0, "Bad server address")
        val body = withContext(io) {
            val request = headers.apply(Request.Builder().url(url), access.token).build()
            try {
                http.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> null
                        response.code == 401 -> throw PlexException.Unauthorized()
                        !response.isSuccessful -> throw PlexException.Failed(response.code, "/library/metadata answered ${response.code}")
                        else -> response.body.string()
                    }
                }
            } catch (e: PlexException) {
                throw e
            } catch (e: IOException) {
                throw PlexException.Unreachable(e)
            }
        } ?: return null
        return try {
            parse(body)
        } catch (_: SerializationException) {
            throw PlexException.Failed(200, "/library/metadata: unexpected answer")
        } catch (_: IllegalArgumentException) {
            throw PlexException.Failed(200, "/library/metadata: unexpected answer")
        }
    }

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The first part's audio and subtitle streams; null when the title has no file. */
        fun parse(body: String): List<PlexStream>? {
            val container = json.parseToJsonElement(body).jsonObject["MediaContainer"].obj()
            val part = container?.get("Metadata").first()?.get("Media").first()?.get("Part").first() ?: return null
            return (part["Stream"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toStream() }
        }

        private fun JsonObject.toStream(): PlexStream? {
            val type = int("streamType")?.takeIf { it == PlexStream.AUDIO || it == PlexStream.SUBTITLES } ?: return null
            val id = text("id") ?: return null
            return PlexStream(
                id = id,
                type = type,
                index = int("index"),
                languageCode = text("languageCode"),
                language = text("language"),
                displayTitle = text("displayTitle"),
                extendedDisplayTitle = text("extendedDisplayTitle"),
                selected = flag("selected"),
                default = flag("default"),
                forced = flag("forced"),
                codec = text("codec"),
                channels = int("channels"),
                key = text("key"),
                inVideo = flag("embeddedInVideo"),
            )
        }

        private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

        private fun JsonElement?.first(): JsonObject? = (this as? JsonArray)?.firstOrNull() as? JsonObject

        private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        private fun JsonObject.int(name: String): Int? = text(name)?.toIntOrNull()

        /** A real server sends true/false or 1/0 (as `hidden` in [PlexLibrary]): read either. */
        private fun JsonObject.flag(name: String): Boolean = text(name).let { it == "1" || it.equals("true", ignoreCase = true) }
    }
}
