package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.guide.ChannelDraft
import com.phonoodlesramen.actioncenter.guide.ChannelEntity
import com.phonoodlesramen.actioncenter.guide.ChannelRule
import com.phonoodlesramen.actioncenter.guide.Choice
import com.phonoodlesramen.actioncenter.guide.RuleChoices
import com.phonoodlesramen.actioncenter.guide.station.ApiError
import com.phonoodlesramen.actioncenter.guide.station.ChannelPatch
import com.phonoodlesramen.actioncenter.guide.station.ChannelPlayed
import com.phonoodlesramen.actioncenter.guide.station.Changed
import com.phonoodlesramen.actioncenter.guide.station.Created
import com.phonoodlesramen.actioncenter.guide.station.Favorites
import com.phonoodlesramen.actioncenter.guide.station.PairRequest
import com.phonoodlesramen.actioncenter.guide.station.PairStarted
import com.phonoodlesramen.actioncenter.guide.station.PairState
import com.phonoodlesramen.actioncenter.guide.station.PairStatus
import com.phonoodlesramen.actioncenter.guide.station.PlexLinkStarted
import com.phonoodlesramen.actioncenter.guide.station.PlexLinkState
import com.phonoodlesramen.actioncenter.guide.station.PlexLinkStatus
import com.phonoodlesramen.actioncenter.guide.station.Preview
import com.phonoodlesramen.actioncenter.guide.station.ProfileKeys
import com.phonoodlesramen.actioncenter.guide.station.SpotAnswer
import com.phonoodlesramen.actioncenter.guide.station.SpotBody
import com.phonoodlesramen.actioncenter.guide.station.SpotForget
import com.phonoodlesramen.actioncenter.guide.station.StationApi
import com.phonoodlesramen.actioncenter.guide.station.StationChannel
import com.phonoodlesramen.actioncenter.guide.station.StationDevice
import com.phonoodlesramen.actioncenter.guide.station.StationGuide
import com.phonoodlesramen.actioncenter.guide.station.StationInfo
import com.phonoodlesramen.actioncenter.guide.station.StationLibrary
import com.phonoodlesramen.actioncenter.guide.station.StationPlex
import com.phonoodlesramen.actioncenter.guide.station.StationSlot
import com.phonoodlesramen.actioncenter.guide.station.StationSpot
import com.phonoodlesramen.actioncenter.nfl.NflState
import java.math.BigInteger
import java.security.KeyFactory
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.Protocol

/**
 * A guide station for tests (`docs/design/guide-station-api.md`): HTTPS on TLS 1.3 with its
 * own CA, `pair` open, everything else needing a client certificate its CA signed (a
 * missing one is refused; a revoked one is `403 unpaired`). It keeps a guide, edits it a
 * little, holds `changes` open, and keeps the house's spot by its rules.
 */
class FakeStation(
    /** How long a `changes` call is held before `204`. */
    private val holdMillis: Long = 400,
    /** What `X-Station-Version` says. */
    var versionHeader: String = StationApi.VERSION,
    /** The house's clock, for the spot and pairing. */
    var nowMillis: Long = NOW,
) {
    private val json = StationApi.json
    private val caKeys = TestCertificates.ecKeyPair()
    val ca: X509Certificate = TestCertificates.certificate("Guide station CA", caKeys.public.encoded, "Guide station CA", caKeys.private, BigInteger.ONE, ca = true)
    private val serverKeys = TestCertificates.ecKeyPair()
    private val serverCertificate =
        TestCertificates.certificate("guide-station", serverKeys.public.encoded, "Guide station CA", caKeys.private, BigInteger.TWO, usage = TestCertificates.SERVER_AUTH)

    private val server = MockWebServer()
    private val lock = Object()

    private data class Pending(val name: String, val model: String, val spki: ByteArray, val code: String, var state: PairState, var certificate: X509Certificate? = null)

    private val pending = LinkedHashMap<String, Pending>()
    private val devices = LinkedHashMap<String, StationDevice>()
    private val revoked = HashSet<String>()
    private var nextSerial = 100L

    /** The station's guide; [edit] changes it and bumps the version. */
    var guide: StationGuide = sampleGuide(version = 1)
        private set

    /** The version `changes` follows: the guide's and the spot's. */
    var version = 1L
        private set

    var spot: StationSpot? = null
        private set

    /** Each profile's favorites, by key (1.2): a change bumps [version], as `changes` tells the TVs. */
    private val favorites = HashMap<String, Favorites>()

    /** False: a 1.1 station, which answers favorites' calls 404. */
    @Volatile
    var keepsFavorites = true

    /** Every call after pairing: "PATCH /v1/channels/c-comedy {…}". */
    val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** Guide calls answered `304`. */
    @Volatile
    var notModified = 0
        private set

    /** The next edit is turned down with this sentence. */
    @Volatile
    var reject: String? = null

    /** As if this TV's certificate never arrived: every call past pairing is 401. */
    @Volatile
    var noCertificates = false

    /** The NAS is down: every connection is dropped. */
    @Volatile
    var down = false

    /** A machine in the middle: approval hands over this CA instead of the station's own. */
    @Volatile
    var caOnApproval: X509Certificate? = null

    var plexLinked = true
    var librarySynced = true

    /** What `editor/choices` answers; [choicesJson], when set, is sent as it is instead (an older station's). */
    @Volatile
    var choices: RuleChoices = CHOICES

    @Volatile
    var choicesJson: String? = null

    /** Holds every `GET /v1/station` until it opens: a slow station, so a test can act while a read is out. */
    @Volatile
    var stationGate: java.util.concurrent.CountDownLatch? = null

    /** Opens once a `GET /v1/station` is being held at [stationGate]. */
    val stationHeld = java.util.concurrent.CountDownLatch(1)

    val host: String get() = server.hostName
    val port: Int get() = server.port
    val address: String get() = "$host:$port"

    fun start(port: Int = 0): FakeStation {
        server.useHttps(TestCertificates.serverContext(serverKeys.private, listOf(serverCertificate, ca), ca).socketFactory)
        server.requestClientAuth()
        server.protocols = listOf(Protocol.HTTP_1_1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (down) MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket(closeSocket = true, shutdownInput = true, shutdownOutput = true)).build() else handle(request)
        }
        server.start(port)
        return this
    }

    fun stop() = server.close()

    /** The code the station shows for its one pending TV (its log line). */
    fun pendingCode(): String? = synchronized(lock) { pending.values.lastOrNull { it.state == PairState.PENDING }?.code }

    /** `approve ABC123` in the station's terminal. */
    fun approve(code: String) = synchronized(lock) {
        val request = pending.values.first { it.code == code && it.state == PairState.PENDING }
        val serial = BigInteger.valueOf(nextSerial++)
        request.certificate = TestCertificates.certificate(
            request.name, request.spki, "Guide station CA", caKeys.private, serial, usage = TestCertificates.CLIENT_AUTH,
        )
        request.state = PairState.APPROVED
        devices[serial.toString(16)] = StationDevice(serial.toString(16), request.name, request.model, pairedAtMillis = nowMillis, lastSeenMillis = nowMillis)
    }

    fun refuse(code: String) = synchronized(lock) { pending.values.last { it.code == code }.state = PairState.REFUSED }

    fun expire(code: String) = synchronized(lock) { pending.values.last { it.code == code }.state = PairState.EXPIRED }

    /** Another TV, already paired. */
    fun addDevice(id: String, name: String, model: String = "Google TV") = synchronized(lock) {
        devices[id] = StationDevice(id, name, model, pairedAtMillis = nowMillis - DAY, lastSeenMillis = nowMillis)
    }

    /** Another TV, waiting for approval with its code. */
    fun addPending(id: String, name: String, code: String) = synchronized(lock) {
        devices[id] = StationDevice(id, name, "Google TV", pending = true, code = code)
    }

    fun devices(): List<StationDevice> = synchronized(lock) { devices.values.toList() }

    fun revoke(id: String) = synchronized(lock) {
        revoked += id
        devices.remove(id)
    }

    /** An edit made elsewhere (another TV, the phone): the guide changes and `changes` answers. */
    fun edit(change: (StationGuide) -> StationGuide) = synchronized(lock) {
        version++
        guide = change(guide).copy(version = version, updatedAtMillis = nowMillis)
        lock.notifyAll()
    }

    /** [key]'s favorites as the station keeps them, or null before any are saved. */
    fun favoritesOf(key: String): Favorites? = synchronized(lock) { favorites[key] }

    /** Favorites changed on another TV. */
    fun saveFavoritesElsewhere(key: String, channels: List<String>) = synchronized(lock) {
        version++
        favorites[key] = Favorites(channels, version)
        lock.notifyAll()
    }

    /** A spot saved on another TV. */
    fun saveSpotElsewhere(spot: StationSpot) = synchronized(lock) {
        this.spot = spot
        version++
        lock.notifyAll()
    }

    private fun handle(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath.removePrefix("/v1/").split('/')
        val method = request.method
        val body = request.body?.utf8().orEmpty()
        // Pairing is open.
        if (path[0] == "pair") {
            return when (method) {
                "POST" -> synchronized(lock) {
                    val asked = json.decodeFromString(PairRequest.serializer(), body)
                    val spki = Base64.getDecoder().decode(asked.publicKey)
                    // The key must be a key.
                    KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
                    val id = UUID.randomUUID().toString()
                    pending[id] = Pending(asked.name, asked.model, spki, StationApi.pairingCode(ca.encoded, spki), PairState.PENDING)
                    ok(PairStarted.serializer(), PairStarted(id, nowMillis + StationApi.PAIRING_MILLIS))
                }
                else -> synchronized(lock) {
                    val asked = pending[path[1]] ?: return ok(PairStatus.serializer(), PairStatus(PairState.EXPIRED))
                    val certificate = asked.certificate
                    ok(
                        PairStatus.serializer(),
                        PairStatus(asked.state, certificate?.let(StationTls::pem), if (asked.state == PairState.APPROVED) StationTls.pem(caOnApproval ?: ca) else null),
                    )
                }
            }
        }
        // Everything else needs this station's certificate.
        val leaf = request.handshake?.peerCertificates?.firstOrNull() as? X509Certificate
        if (leaf == null || noCertificates) return error(401, "Pair this TV with the guide station first.")
        val caller = leaf.serialNumber.toString(16)
        synchronized(lock) {
            if (caller in revoked || caller !in devices) return error(403, "unpaired")
            devices[caller] = devices.getValue(caller).copy(lastSeenMillis = nowMillis)
        }
        val call = "$method /v1/${path.joinToString("/")}" + if (body.isEmpty()) "" else " $body"
        if (path[0] != "changes") calls += call
        return when {
            path == listOf("guide") -> synchronized(lock) {
                val etag = guide.version.toString()
                if (request.headers["If-None-Match"] == etag) {
                    notModified++
                    MockResponse.Builder().code(304).addHeader(StationApi.VERSION_HEADER, versionHeader).addHeader("ETag", etag).build()
                } else {
                    MockResponse.Builder().addHeader(StationApi.VERSION_HEADER, versionHeader).addHeader("ETag", etag)
                        .body(json.encodeToString(StationGuide.serializer(), guide)).build()
                }
            }
            path == listOf("changes") -> {
                val after = request.url.queryParameter("after")?.toLong() ?: 0
                synchronized(lock) {
                    val until = System.currentTimeMillis() + holdMillis
                    while (version <= after && System.currentTimeMillis() < until) lock.wait((until - System.currentTimeMillis()).coerceAtLeast(1))
                    if (version > after) ok(Changed.serializer(), Changed(version)) else MockResponse.Builder().code(204).addHeader(StationApi.VERSION_HEADER, versionHeader).build()
                }
            }
            path == listOf("editor", "choices") -> choicesJson?.let { MockResponse.Builder().addHeader(StationApi.VERSION_HEADER, versionHeader).body(it).build() }
                ?: ok(RuleChoices.serializer(), choices)
            path[0] == "channels" || path[0] == "preview" || path[0] == "lineup" -> editing(method, path, body)
            path[0] == "spot" -> spotCall(method, path, body, caller)
            path[0] == "favorites" && path.size == 2 -> favoritesCall(method, path[1], body)
            path[0] == "nfl" -> nflCall(request, path)
            path == listOf("devices") -> ok(ListSerializer(StationDevice.serializer()), devices())
            path[0] == "devices" && method == "POST" -> synchronized(lock) {
                val device = devices[path[1]] ?: return error(404, "There's no such TV.")
                devices[path[1]] = device.copy(pending = false, code = null, pairedAtMillis = nowMillis)
                MockResponse.Builder().addHeader(StationApi.VERSION_HEADER, versionHeader).body("{}").build()
            }
            path[0] == "devices" && method == "DELETE" -> {
                revoke(path[1])
                MockResponse.Builder().addHeader(StationApi.VERSION_HEADER, versionHeader).body("{}").build()
            }
            path == listOf("station") -> {
                stationGate?.let { gate ->
                    stationHeld.countDown()
                    gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
                }
                ok(
                    StationInfo.serializer(),
                    StationInfo(
                        StationApi.VERSION,
                        StationPlex(plexLinked, if (plexLinked) "Den PC" else null),
                        StationLibrary(lastSyncMillis = if (librarySynced) nowMillis - 12 * MINUTE else null),
                        "America/Los_Angeles",
                    ),
                )
            }
            path == listOf("plex", "link") && method == "POST" -> ok(PlexLinkStarted.serializer(), PlexLinkStarted("PLX4", nowMillis + 10 * MINUTE))
            path == listOf("plex", "link") -> ok(PlexLinkStatus.serializer(), PlexLinkStatus(if (plexLinked) PlexLinkState.LINKED else PlexLinkState.WAITING))
            else -> error(404, "No such call.")
        }
    }

    private fun editing(method: String, path: List<String>, body: String): MockResponse {
        reject?.let {
            reject = null
            return error(400, it)
        }
        return synchronized(lock) {
            when {
                path == listOf("preview") -> ok(Preview.serializer(), Preview(guide.channels.first().slots.take(2)))
                path == listOf("lineup", "remake") -> changed { it }
                path == listOf("channels") && method == "POST" -> {
                    val draft = json.decodeFromString(ChannelDraft.serializer(), body)
                    val id = "c-${draft.name.lowercase()}"
                    edit { g -> g.copy(channels = g.channels + channel(id, g.channels.size + 1, draft.name, draft.rule, emptyList())) }
                    ok(Created.serializer(), Created(id, version))
                }
                path.size == 3 && path[2] == "duplicate" -> {
                    edit { g -> g.copy(channels = g.channels + g.channels.first { it.id == path[1] }.copy(id = "${path[1]}-2", number = g.channels.size + 1)) }
                    ok(Created.serializer(), Created("${path[1]}-2", version))
                }
                method == "PATCH" -> {
                    val patch = json.decodeFromString(ChannelPatch.serializer(), body)
                    changed { g ->
                        g.copy(
                            channels = g.channels.map { c ->
                                if (c.id != path[1]) c else c.copy(name = patch.name ?: c.name, number = patch.number ?: c.number, enabled = patch.enabled ?: c.enabled)
                            },
                        )
                    }
                }
                method == "PUT" -> {
                    val draft = json.decodeFromString(ChannelDraft.serializer(), body)
                    changed { g -> g.copy(channels = g.channels.map { c -> if (c.id != path[1]) c else c.copy(name = draft.name, rule = draft.rule) }) }
                }
                method == "DELETE" -> changed { g -> g.copy(channels = g.channels.filter { it.id != path[1] }) }
                else -> error(404, "No such call.")
            }
        }
    }

    private fun changed(change: (StationGuide) -> StationGuide): MockResponse {
        edit(change)
        return ok(Changed.serializer(), Changed(version))
    }

    /** The house's spot by `SpotRules`' station side: the latest save wins, and `played` forgets only the caller's own. */
    private fun spotCall(method: String, path: List<String>, body: String, caller: String): MockResponse = synchronized(lock) {
        when {
            path == listOf("spot") && method == "GET" -> return ok(SpotAnswer.serializer(), SpotAnswer(spot))
            path == listOf("spot") && method == "PUT" -> {
                val saved = json.decodeFromString(SpotBody.serializer(), body)
                spot = StationSpot(saved.channelId, saved.slotStartMillis, saved.itemKey, saved.positionMs, saved.savedAtMillis, caller, devices.getValue(caller).name)
            }
            path == listOf("spot") && method == "DELETE" -> {
                val only = if (body.isEmpty()) null else json.decodeFromString(SpotForget.serializer(), body).only
                val current = spot
                if (only == null || (current != null && current.channelId == only.channelId && current.slotStartMillis == only.slotStartMillis && current.itemKey == only.itemKey)) spot = null
            }
            path == listOf("spot", "played") -> {
                val played = json.decodeFromString(ChannelPlayed.serializer(), body)
                if (spot?.device == caller && spot?.channelId != played.channelId) spot = null
            }
            else -> return error(404, "No such call.")
        }
        version++
        lock.notifyAll()
        ok(Changed.serializer(), Changed(version))
    }

    /**
     * The NFL (1.2), just enough for the TV's client: `GET /v1/nfl` with its ETag, another week
     * ESPN has no games for, and a logo. With [nfl] null it's a 1.1 station: no such calls.
     */
    private fun nflCall(request: RecordedRequest, path: List<String>): MockResponse {
        val state = synchronized(lock) { nfl } ?: return error(404, "No such call.")
        return when {
            path == listOf("nfl") -> {
                val etag = state.version.toString()
                if (request.headers["If-None-Match"] == etag) {
                    MockResponse.Builder().code(304).addHeader(StationApi.VERSION_HEADER, versionHeader).addHeader("ETag", etag).build()
                } else {
                    MockResponse.Builder().addHeader(StationApi.VERSION_HEADER, versionHeader).addHeader("ETag", etag)
                        .body(json.encodeToString(NflState.serializer(), state)).build()
                }
            }
            path.getOrNull(1) == "week" -> error(404, "ESPN has no games that week.")
            path.getOrNull(1) == "logos" -> MockResponse.Builder().addHeader(StationApi.VERSION_HEADER, versionHeader).addHeader("Content-Type", "image/png")
                .body(okio.Buffer().write(byteArrayOf(7, 7, 7))).build()
            else -> error(404, "No such call.")
        }
    }

    /** The house's NFL (1.2); null: a 1.1 station, which answers the NFL's calls 404. */
    @Volatile
    var nfl: NflState? = null

    /** `GET`, `PUT /v1/favorites/{key}`: a key never saved has none, version 0; a 1.1 station has no such call. */
    private fun favoritesCall(method: String, key: String, body: String): MockResponse {
        if (!keepsFavorites) return error(404, "No such call.")
        if (!ProfileKeys.isValid(key)) return error(400, "That isn't a profile.")
        return synchronized(lock) {
            when (method) {
                "GET" -> ok(Favorites.serializer(), favorites[key] ?: Favorites())
                "PUT" -> {
                    val asked = json.decodeFromString(Favorites.serializer(), body)
                    version++
                    favorites[key] = Favorites(asked.channels, version)
                    lock.notifyAll()
                    ok(Favorites.serializer(), favorites.getValue(key))
                }
                else -> error(404, "No such call.")
            }
        }
    }

    private fun <T> ok(serializer: KSerializer<T>, value: T) =
        MockResponse.Builder().addHeader(StationApi.VERSION_HEADER, versionHeader).body(json.encodeToString(serializer, value)).build()

    private fun error(code: Int, sentence: String) =
        MockResponse.Builder().code(code).addHeader(StationApi.VERSION_HEADER, versionHeader).body(json.encodeToString(ApiError.serializer(), ApiError(sentence))).build()

    companion object {
        /** A weeknight at the station: 1793000000000 is a Thursday evening in late October 2026. */
        const val NOW = 1_793_000_000_000L
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE

        val CHOICES = RuleChoices(
            sections = listOf(Choice("1", "Movies")),
            movieGenres = listOf("Comedy"),
            tvGenres = listOf("Sitcom"),
            shows = listOf(Choice("4800", "Frasier")),
            collections = emptyList(),
            decades = listOf(1990),
            contentRatings = listOf("TV-PG"),
        )

        fun channel(id: String, number: Int, name: String, rule: ChannelRule, slots: List<StationSlot>, ratings: List<String> = emptyList(), unrated: Boolean = false) =
            StationChannel(
                id = id, number = number, name = name, enabled = true, alignmentMinutes = 30, filler = ChannelEntity.FILLER_TRAILERS,
                rule = rule, contentRatings = ratings, unrated = unrated, slots = slots,
            )

        /** Half-hour programs from an hour ago to 48 hours ahead, each channel's own. */
        fun slots(prefix: String, rating: String, from: Long = NOW - 60 * MINUTE, until: Long = NOW + StationApi.HORIZON_MILLIS): List<StationSlot> =
            generateSequence(from) { it + 30 * MINUTE }.takeWhile { it < until }.mapIndexed { i, start ->
                StationSlot(start, start + 30 * MINUTE, "$prefix $i", episode = "S1 · E${i + 1}", itemKey = "$prefix-$i", showKey = prefix, art = "/art/$prefix/$i", contentRating = rating)
            }.toList()

        /** Comedy (TV-PG and TV-14) and Cartoons (TV-Y): a kid sees Cartoons only. The program after now on Comedy is off air. */
        fun sampleGuide(version: Long) = StationGuide(
            version = version,
            updatedAtMillis = NOW - 5 * MINUTE,
            timeZone = "America/Los_Angeles",
            channels = listOf(
                channel(
                    "c-comedy", 3, "Comedy", ChannelRule(types = listOf(ChannelRule.EPISODE), genres = listOf("Sitcom")),
                    slots("comedy", "TV-PG").map { if (it.startMillis == NOW + 30 * MINUTE - 30 * MINUTE + 30 * MINUTE) it.copy(offAir = "This title is no longer in your library") else it },
                    ratings = listOf("TV-PG", "TV-14"),
                ),
                channel(
                    "c-cartoons", 7, "Cartoons", ChannelRule(types = listOf(ChannelRule.EPISODE), contentRatings = listOf("TV-Y")),
                    slots("cartoons", "TV-Y"),
                    ratings = listOf("TV-Y"),
                ),
            ),
        )
    }
}
