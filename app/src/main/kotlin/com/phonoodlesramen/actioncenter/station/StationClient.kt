package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.guide.ChannelDraft
import com.phonoodlesramen.actioncenter.guide.RuleChoices
import com.phonoodlesramen.actioncenter.guide.station.ApiError
import com.phonoodlesramen.actioncenter.guide.station.ChannelPatch
import com.phonoodlesramen.actioncenter.guide.station.ChannelPlayed
import com.phonoodlesramen.actioncenter.guide.station.Changed
import com.phonoodlesramen.actioncenter.guide.station.Created
import com.phonoodlesramen.actioncenter.guide.station.Favorites
import com.phonoodlesramen.actioncenter.guide.station.PairRequest
import com.phonoodlesramen.actioncenter.guide.station.PairStarted
import com.phonoodlesramen.actioncenter.guide.station.PairStatus
import com.phonoodlesramen.actioncenter.guide.station.PlexLinkStarted
import com.phonoodlesramen.actioncenter.guide.station.PlexLinkStatus
import com.phonoodlesramen.actioncenter.guide.station.Preview
import com.phonoodlesramen.actioncenter.guide.station.SpotAnswer
import com.phonoodlesramen.actioncenter.guide.station.SpotBody
import com.phonoodlesramen.actioncenter.guide.station.SpotForget
import com.phonoodlesramen.actioncenter.guide.station.StationApi
import com.phonoodlesramen.actioncenter.guide.station.StationDevice
import com.phonoodlesramen.actioncenter.guide.station.StationGuide
import com.phonoodlesramen.actioncenter.guide.station.StationInfo
import com.phonoodlesramen.actioncenter.nfl.NflGameDetail
import com.phonoodlesramen.actioncenter.nfl.NflSettings
import com.phonoodlesramen.actioncenter.nfl.NflState
import com.phonoodlesramen.actioncenter.nfl.NflTeamSchedule
import com.phonoodlesramen.actioncenter.nfl.NflWeek
import java.io.IOException
import java.security.cert.X509Certificate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Why a call to the guide station didn't work. Its message is a sentence a TV can show.
 */
sealed class StationException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The station couldn't be reached, or the TLS handshake failed. */
    class Unreachable(cause: Throwable? = null) : StationException(CANT_REACH, cause)

    /** `403 {"error":"unpaired"}`: the station revoked this TV's certificate; or `401`: it sent none the station knows. */
    class Unpaired : StationException(UNPAIRED)

    /** What answered isn't a guide station this TV knows (no `X-Station-Version`, or another major). */
    class Incompatible(message: String) : StationException(message)

    /** The station said no: its own sentence (`{"error": …}`), and the status. */
    class Refused(message: String, val status: Int) : StationException(message)

    companion object {
        const val CANT_REACH = "Can't reach the guide station."
        const val UNPAIRED = "This TV isn't paired with the guide station anymore."
    }
}

/**
 * The guide station's API (`docs/design/guide-station-api.md`) from a TV: one call per route,
 * over [pairing] (no client certificate, any station certificate, its chain read) or
 * [paired] (mutual TLS, the station's CA pinned). Every answer must carry the station's
 * version header with a major this TV knows. Failures are [StationException]s.
 */
internal class StationClient(
    host: String,
    port: Int,
    private val pairing: PairingClient,
    private val paired: OkHttpClient?,
    changesWaitMillis: Long = StationApi.CHANGES_WAIT_MILLIS,
) {
    private val base: HttpUrl = HttpUrl.Builder().scheme("https").host(host).port(port).build()
    private val waiting: OkHttpClient? = paired?.let { StationTls.waiting(it, changesWaitMillis) }
    private val json = StationApi.json

    // Pairing (open).

    /** `POST /v1/pair`, with the chain the station showed. */
    suspend fun pair(request: PairRequest): Pair<PairStarted, List<X509Certificate>> =
        send(pairing.http, post("pair", PairRequest.serializer(), request)) { it.read(PairStarted.serializer()) to pairing.chain }

    /** `GET /v1/pair/{requestId}`, with the chain the station showed. */
    suspend fun pairStatus(requestId: String): Pair<PairStatus, List<X509Certificate>> =
        send(pairing.http, get(url { addPathSegment("pair").addPathSegment(requestId) })) { it.read(PairStatus.serializer()) to pairing.chain }

    // The guide.

    /** `GET /v1/guide`, or null for `304` when [etag] is still current. The ETag comes back with it. */
    suspend fun guide(etag: String?): Pair<StationGuide, String?>? {
        val request = Request.Builder().url(url { addPathSegment("guide") }).apply { if (etag != null) header("If-None-Match", etag) }.build()
        return send(mtls(), request) { if (it.code == NOT_MODIFIED) null else it.read(StationGuide.serializer()) to it.header("ETag") }
    }

    /** `GET /v1/changes?after=`: the new version as soon as there is one, null when the wait ran out (`204`). */
    suspend fun changes(after: Long): Long? {
        val client = waiting ?: throw StationException.Unpaired()
        val request = get(url { addPathSegment("changes").addQueryParameter("after", after.toString()) })
        return send(client, request) { if (it.code == NO_CONTENT) null else it.read(Changed.serializer()).version }
    }

    // Editing.

    suspend fun choices(): RuleChoices = call(get(url { addPathSegment("editor").addPathSegment("choices") }), RuleChoices.serializer())

    suspend fun create(draft: ChannelDraft): Created = call(post("channels", ChannelDraft.serializer(), draft), Created.serializer())

    suspend fun update(id: String, draft: ChannelDraft): Changed =
        call(Request.Builder().url(channel(id)).put(body(ChannelDraft.serializer(), draft)).build(), Changed.serializer())

    suspend fun patch(id: String, patch: ChannelPatch): Changed =
        call(Request.Builder().url(channel(id)).patch(body(ChannelPatch.serializer(), patch)).build(), Changed.serializer())

    suspend fun duplicate(id: String): Created =
        call(Request.Builder().url(channel(id, "duplicate")).post(EMPTY).build(), Created.serializer())

    suspend fun delete(id: String): Changed = call(Request.Builder().url(channel(id)).delete().build(), Changed.serializer())

    suspend fun preview(draft: ChannelDraft): Preview = call(post("preview", ChannelDraft.serializer(), draft), Preview.serializer())

    suspend fun remake(): Changed = call(Request.Builder().url(url { addPathSegment("lineup").addPathSegment("remake") }).post(EMPTY).build(), Changed.serializer())

    // The house's spot.

    suspend fun spot(): SpotAnswer = call(get(url { addPathSegment("spot") }), SpotAnswer.serializer())

    suspend fun saveSpot(body: SpotBody): Changed =
        call(Request.Builder().url(url { addPathSegment("spot") }).put(body(SpotBody.serializer(), body)).build(), Changed.serializer())

    suspend fun forgetSpot(forget: SpotForget): Changed =
        call(Request.Builder().url(url { addPathSegment("spot") }).delete(body(SpotForget.serializer(), forget)).build(), Changed.serializer())

    suspend fun played(played: ChannelPlayed): Changed =
        call(post("spot/played", ChannelPlayed.serializer(), played), Changed.serializer())

    // A profile's favorite channels (1.2). A 1.1 station answers 404.

    /** `GET /v1/favorites/{key}`: the profile's channels, in its order, and their version. */
    suspend fun favorites(key: String): Favorites = call(get(favoritesUrl(key)), Favorites.serializer())

    /** `PUT /v1/favorites/{key}`: the profile's whole list. Only the answer's version is read, so `{version}` will do. */
    suspend fun saveFavorites(key: String, channels: List<String>): Long =
        call(Request.Builder().url(favoritesUrl(key)).put(body(Favorites.serializer(), Favorites(channels))).build(), Favorites.serializer()).version

    private fun favoritesUrl(key: String) = url { addPathSegment("favorites").addPathSegment(key) }

    // The NFL (1.2). A 1.1 station answers 404 to `GET /v1/nfl`.

    /** `GET /v1/nfl`, or null for `304` when [etag] is still current. The ETag comes back with it. */
    suspend fun nfl(etag: String?): Pair<NflState, String?>? {
        val request = Request.Builder().url(nflUrl { this }).apply { if (etag != null) header("If-None-Match", etag) }.build()
        return send(mtls(), request) { if (it.code == NOT_MODIFIED) null else it.read(NflState.serializer()) to it.header("ETag") }
    }

    /** `GET /v1/nfl/changes?after=`: the NFL's new version as soon as there is one, null when the wait ran out (`204`). */
    suspend fun nflChanges(after: Long): Long? {
        val client = waiting ?: throw StationException.Unpaired()
        val request = get(nflUrl { addPathSegment("changes").addQueryParameter("after", after.toString()) })
        return send(client, request) { if (it.code == NO_CONTENT) null else it.read(Changed.serializer()).version }
    }

    /** `GET /v1/nfl/week/{type}/{week}?season=`. */
    suspend fun nflWeek(seasonType: Int, week: Int, seasonYear: Int?): NflWeek = call(
        get(nflUrl { addPathSegment("week").addPathSegment(seasonType.toString()).addPathSegment(week.toString()).season(seasonYear) }),
        NflWeek.serializer(),
    )

    /** `GET /v1/nfl/teams/{abbr}/schedule?season=`: the regular season. */
    suspend fun nflSchedule(abbr: String, seasonYear: Int?): NflTeamSchedule =
        call(get(nflUrl { addPathSegment("teams").addPathSegment(abbr).addPathSegment("schedule").season(seasonYear) }), NflTeamSchedule.serializer())

    /** `GET /v1/nfl/games/{id}`. */
    suspend fun nflGame(id: String): NflGameDetail = call(get(nflUrl { addPathSegment("games").addPathSegment(id) }), NflGameDetail.serializer())

    /** `GET /v1/nfl/logos/{abbr}?size=s|l`: the PNG's bytes. */
    suspend fun nflLogo(abbr: String, size: String): ByteArray =
        send(mtls(), get(nflUrl { addPathSegment("logos").addPathSegment(abbr).addQueryParameter("size", size) })) { it.body.bytes() }

    /** `PUT /v1/nfl/settings`: the house's NFL settings (B). Returns the NFL's new version. */
    suspend fun saveNflSettings(settings: NflSettings): Long =
        call(Request.Builder().url(nflUrl { addPathSegment("settings") }).put(body(NflSettings.serializer(), settings)).build(), Changed.serializer()).version

    private fun nflUrl(path: HttpUrl.Builder.() -> HttpUrl.Builder) = url { addPathSegment("nfl").path() }

    private fun HttpUrl.Builder.season(year: Int?) = apply { if (year != null) addQueryParameter("season", year.toString()) }

    // Devices and the station.

    suspend fun devices(): List<StationDevice> = call(get(url { addPathSegment("devices") }), ListSerializer(StationDevice.serializer()))

    suspend fun approve(id: String) {
        call(Request.Builder().url(url { addPathSegment("devices").addPathSegment(id).addPathSegment("approve") }).post(EMPTY).build(), JsonObject.serializer())
    }

    suspend fun unpair(id: String) {
        call(Request.Builder().url(url { addPathSegment("devices").addPathSegment(id) }).delete().build(), JsonObject.serializer())
    }

    suspend fun station(): StationInfo = call(get(url { addPathSegment("station") }), StationInfo.serializer())

    suspend fun startPlexLink(): PlexLinkStarted =
        call(Request.Builder().url(url { addPathSegment("plex").addPathSegment("link") }).post(EMPTY).build(), PlexLinkStarted.serializer())

    suspend fun plexLink(): PlexLinkStatus = call(get(url { addPathSegment("plex").addPathSegment("link") }), PlexLinkStatus.serializer())

    // How.

    private fun mtls(): OkHttpClient = paired ?: throw StationException.Unpaired()

    private suspend fun <T> call(request: Request, serializer: KSerializer<T>): T = send(mtls(), request) { it.read(serializer) }

    private fun url(path: HttpUrl.Builder.() -> HttpUrl.Builder): HttpUrl = base.newBuilder().addPathSegment(PREFIX).path().build()

    private fun channel(id: String, action: String? = null): HttpUrl = url {
        addPathSegment("channels").addPathSegment(id).apply { if (action != null) addPathSegment(action) }
    }

    private fun get(url: HttpUrl) = Request.Builder().url(url).get().build()

    private fun <T> post(path: String, serializer: KSerializer<T>, value: T) =
        Request.Builder().url(url { addPathSegments(path) }).post(body(serializer, value)).build()

    private fun <T> body(serializer: KSerializer<T>, value: T): RequestBody = json.encodeToString(serializer, value).toRequestBody(JSON)

    private fun <T> Response.read(serializer: KSerializer<T>): T {
        val text = body.string()
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw StationException.Incompatible(UNREADABLE)
        } catch (e: IllegalArgumentException) {
            throw StationException.Incompatible(UNREADABLE)
        }
    }

    /**
     * Sends [request] on [client] and reads the answer with [read], on OkHttp's thread; the
     * call is cancelled with its coroutine. Every answer's version is checked first, then
     * its status: `403 unpaired`, or another failure with the station's own sentence.
     */
    private suspend fun <T> send(client: OkHttpClient, request: Request, read: (Response) -> T): T {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(StationException.Unreachable(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching { response.use { answer(it, read) } }
                        result.fold(continuation::resume) { e ->
                            continuation.resumeWithException(if (e is IOException) StationException.Unreachable(e) else e)
                        }
                    }
                },
            )
        }
    }

    private fun <T> answer(response: Response, read: (Response) -> T): T {
        checkVersion(response.header(StationApi.VERSION_HEADER))
        if (response.isSuccessful || response.code == NOT_MODIFIED) return read(response)
        val sentence = runCatching { json.decodeFromString(ApiError.serializer(), response.body.string()).error }.getOrNull()
        // 403 "unpaired": the station revoked this TV. 401: it got no certificate it knows, so this TV isn't paired with it.
        if ((response.code == FORBIDDEN && sentence == UNPAIRED) || response.code == UNAUTHORIZED) throw StationException.Unpaired()
        throw StationException.Refused(sentence?.takeIf { it.isNotBlank() } ?: "The guide station couldn't do that (${response.code}).", response.code)
    }

    companion object {
        private const val PREFIX = "v1"
        private const val NOT_MODIFIED = 304
        private const val NO_CONTENT = 204
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val UNPAIRED = "unpaired"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)
        private const val UNREADABLE = "The guide station's answer couldn't be read. Is this TV's Action Center up to date?"

        /** A station whose major version this TV doesn't know is refused (or no version: it isn't a station). */
        fun checkVersion(header: String?) {
            if (header == null) throw StationException.Incompatible("That address isn't a guide station.")
            val major = header.substringBefore('.').trim().toIntOrNull()
            if (major != StationApi.MAJOR) {
                throw StationException.Incompatible("The guide station is version $header, which this TV doesn't know. Update Action Center on this TV.")
            }
        }
    }
}
