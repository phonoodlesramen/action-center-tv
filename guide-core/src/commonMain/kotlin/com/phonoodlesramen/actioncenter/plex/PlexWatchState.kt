package com.phonoodlesramen.actioncenter.plex

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * What the guide writes to Plex (Phase 11, amending G6): a title's resume point, when a
 * viewer branches into Plezy or Plex mid-program, and "watched", when a program is watched
 * to its credits. Nothing else: no timeline, no session on the server's dashboard.
 * Each write goes to the user whose token [ServerAccess] carries (K3).
 */
interface PlexWatchApi {
    /**
     * Plex's resume point for [ratingKey], at [positionMs] into it: Continue Watching and
     * Plezy start there. The server ignores a time under a minute (`minimumProgressTime`).
     */
    suspend fun setPosition(access: ServerAccess, ratingKey: String, positionMs: Long)

    /** Marks [ratingKey] watched: its play count goes up and its resume point is cleared. Plex adds no history row. */
    suspend fun markWatched(access: ServerAccess, ratingKey: String)
}

/**
 * [PlexWatchApi] on the linked server, with the same headers and errors as [PlexLibrary]:
 * the token in the `X-Plex-Token` header (never in a URL or a log), [PlexException] when
 * the server can't be reached or says no.
 *
 * - **The resume point:** `GET /:/progress?key=…&identifier=com.plexapp.plugins.library&time=…&state=stopped`.
 *   Not in Plex's official spec: python-plexapi's `updateProgress`, whose live-server test
 *   shows the title in Continue Watching afterwards, and PlexTraktSync use it. The
 *   official `/:/timeline` is a playback heartbeat, and a `stopped` report alone doesn't
 *   stick (Plezy's notes, PMS 1.43).
 * - **Watched:** `PUT /:/scrobble?identifier=com.plexapp.plugins.library&key=…` (official
 *   spec): it "just sets the state as played", with no view history.
 */
class PlexWatchState(
    private val http: OkHttpClient,
    private val headers: PlexHeaders,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PlexWatchApi {
    override suspend fun setPosition(access: ServerAccess, ratingKey: String, positionMs: Long) {
        val url = url(access, "/:/progress", ratingKey).newBuilder()
            .addQueryParameter("key", ratingKey)
            .addQueryParameter("identifier", LIBRARY)
            .addQueryParameter("time", positionMs.coerceAtLeast(0).toString())
            .addQueryParameter("state", "stopped")
            .build()
        send(Request.Builder().url(url).get(), access)
    }

    override suspend fun markWatched(access: ServerAccess, ratingKey: String) {
        val url = url(access, "/:/scrobble", ratingKey).newBuilder()
            .addQueryParameter("identifier", LIBRARY)
            .addQueryParameter("key", ratingKey)
            .build()
        send(Request.Builder().url(url).put(ByteArray(0).toRequestBody()), access)
    }

    private fun url(access: ServerAccess, path: String, ratingKey: String): HttpUrl {
        require(ratingKey.isNotEmpty() && ratingKey.all(Char::isDigit)) { "Bad Plex ratingKey: $ratingKey" }
        return "${access.baseUri.trimEnd('/')}$path".toHttpUrlOrNull() ?: throw PlexException.Failed(0, "Bad server address")
    }

    /** Sends [builder]'s request with [access]'s token; the answer's body (empty, from a real server) isn't read. */
    private suspend fun send(builder: Request.Builder, access: ServerAccess) = withContext(io) {
        val request = headers.apply(builder, access.token).build()
        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 401 -> throw PlexException.Unauthorized()
                    !response.isSuccessful -> throw PlexException.Failed(response.code, "${request.url.encodedPath} answered ${response.code}")
                }
            }
        } catch (e: PlexException) {
            throw e
        } catch (e: IOException) {
            throw PlexException.Unreachable(e)
        }
    }

    private companion object {
        /** The library's own identifier, which both calls name. */
        const val LIBRARY = "com.plexapp.plugins.library"
    }
}
