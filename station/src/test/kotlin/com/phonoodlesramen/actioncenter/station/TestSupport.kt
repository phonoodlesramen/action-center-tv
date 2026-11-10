package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import java.security.KeyPair
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * A TV as the tests play it: a software EC P-256 key (the real TVs keep theirs in the Android
 * Keystore) and OkHttp, as `:app` will talk to the station.
 */
internal class TestTv(val name: String = "Living room", val model: String = "SHIELD Android TV") {
    val keys: KeyPair = StationCa.ecKeyPair()
    val publicKey: String get() = Base64.getEncoder().encodeToString(keys.public.encoded)

    var certificate: X509Certificate? = null
    var ca: X509Certificate? = null

    /** After pairing: its certificate on every call, and the station's CA as its only trust anchor. */
    fun client(): OkHttpClient = Clients.mutual(keys.private, certificate ?: error("not paired"), ca ?: error("not paired"))
}

internal object Clients {
    /**
     * A TV during pairing: it doesn't know the station's CA yet, so it takes any certificate
     * and keeps the chain it saw ([seen]), to work its code out from the CA in it.
     */
    fun pairing(seen: MutableList<X509Certificate> = mutableListOf()): OkHttpClient {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                seen.clear()
                seen.addAll(chain)
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLSv1.3").apply { init(null, arrayOf(trust), null) }
        return base().sslSocketFactory(context.socketFactory, trust).hostnameVerifier { _, _ -> true }.build()
    }

    /** Trusting [ca] only, and sending [certificate] (with [key]) when there is one. */
    fun mutual(key: PrivateKey?, certificate: X509Certificate?, ca: X509Certificate): OkHttpClient {
        val password = "test".toCharArray()
        val keyManagers = if (key != null && certificate != null) {
            val store = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("tv", key, password, arrayOf(certificate))
            }
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }.keyManagers
        } else {
            null
        }
        val anchors = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("ca", ca)
        }
        val trust = TrustManagerFactory.getInstance("PKIX").apply { init(anchors) }.trustManagers.single() as X509ExtendedTrustManager
        val context = SSLContext.getInstance("TLSv1.3").apply { init(keyManagers, arrayOf(trust), null) }
        return base().sslSocketFactory(context.socketFactory, trust).build()
    }

    private fun base() = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        // A refused handshake should fail the call, not be tried again on another route.
        .retryOnConnectionFailure(false)
}

/** A made-up library: every title here is invented. */
internal object Library {
    const val MOVIES = "1"

    fun movie(key: String, title: String, minutes: Int = 100, rating: String? = "PG", file: Boolean = true) = ItemEntity(
        ratingKey = key, sectionKey = MOVIES, type = "movie", title = title, year = 2010, durationMs = minutes * 60_000L,
        viewCount = 0, lastViewedAt = null, addedAt = 1_780_000_000, updatedAt = 1_780_000_000, contentRating = rating,
        audienceRating = null, rating = null, thumb = "/library/metadata/$key/thumb", art = "/library/metadata/$key/art",
        genres = "Comedy", syncedAt = 1, summary = "A made-up film called $title.",
        partKey = if (file) "/library/parts/$key/1/file.mkv" else null, container = "mkv",
    )

    /** Thirty made-up films of 100 minutes: more than two days of a channel. */
    val films: List<ItemEntity> = (1..30).map { movie("${100 + it}", "Made-up Film $it") }
}

/**
 * plex.tv and a Plex Media Server, both faked on one MockWebServer (never the real plex.tv):
 * a sign-in code that's claimed on the first check, an account owning one server reached
 * on this same address, and a Movies library of [movies].
 */
internal class FakePlex : AutoCloseable {
    val server = MockWebServer()

    @Volatile
    var movies: List<Pair<String, String>> = (1..30).map { "${100 + it}" to "Made-up Film $it" }

    /** Which paths were asked for, in order. */
    val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The `X-Plex-Device-Name` each request said it came from. */
    val deviceNames: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        server.start()
    }

    val url: HttpUrl get() = server.url("/")

    private fun answer(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        asked += path
        request.headers["X-Plex-Device-Name"]?.let(deviceNames::add)
        return when {
            request.method == "POST" && path == "/api/v2/pins" -> json(pin(token = null))
            path.startsWith("/api/v2/pins/") -> json(pin(token = TOKEN))
            path == "/api/v2/user" -> json("""{"id": 1234567, "uuid": "0a1b2c3d", "username": "made-up-user", "title": "Made-up User"}""")
            path == "/api/v2/resources" -> json(resources())
            path == "/identity" -> json("""{"MediaContainer": {"machineIdentifier": "$MACHINE"}}""")
            path == "/hubs/continueWatching" -> json("""{"MediaContainer": {"Hub": []}}""")
            path == "/library/sections/all" -> json("""{"MediaContainer": {"Directory": [{"key": "1", "type": "movie", "title": "Movies"}]}}""")
            path == "/library/sections/1/all" -> json(moviesPage())
            path == "/status/sessions/history/all" -> json("""{"MediaContainer": {"Metadata": []}}""")
            path.startsWith("/library/metadata/") -> json("""{"MediaContainer": {"Metadata": []}}""")
            else -> MockResponse.Builder().code(404).build()
        }
    }

    private fun pin(token: String?): String {
        val expires = Instant.now().plusSeconds(900)
        val auth = if (token == null) "null" else "\"$token\""
        return """{"id": 4242, "code": "WXYZ", "expiresIn": 900, "expiresAt": "$expires", "authToken": $auth}"""
    }

    private fun resources(): String {
        val uri = server.url("/").toString().trimEnd('/')
        return """[{"name": "Made-up Server", "clientIdentifier": "$MACHINE", "provides": "server", "owned": true,
            "accessToken": "$SERVER_TOKEN", "connections": [{"protocol": "http", "address": "${server.hostName}", "port": ${server.port},
            "uri": "$uri", "local": true, "relay": false, "IPv6": false}]}]"""
    }

    private fun moviesPage(): String {
        val items = movies.joinToString(",") { (key, title) ->
            """{"ratingKey": "$key", "type": "movie", "title": "$title", "year": 2010, "duration": 6000000, "addedAt": 1780000000,
                "updatedAt": 1780000000, "contentRating": "PG", "Media": [{"id": 1, "duration": 6000000, "container": "mkv",
                "Part": [{"id": 1, "key": "/library/parts/$key/1/file.mkv", "duration": 6000000, "container": "mkv"}]}]}"""
        }
        return """{"MediaContainer": {"offset": 0, "totalSize": ${movies.size}, "Metadata": [$items]}}"""
    }

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    override fun close() = server.close()

    companion object {
        const val MACHINE = "made-up-machine-0001"
        const val TOKEN = "made-up-user-token"
        const val SERVER_TOKEN = "made-up-server-token"
    }
}
