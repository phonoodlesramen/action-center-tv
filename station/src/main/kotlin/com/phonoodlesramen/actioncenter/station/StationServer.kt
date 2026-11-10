package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.guide.EditRejected
import com.phonoodlesramen.actioncenter.guide.station.ApiError
import com.phonoodlesramen.actioncenter.guide.station.StationApi
import com.phonoodlesramen.actioncenter.safety.summary
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsExchange
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.GeneralSecurityException
import java.security.cert.X509Certificate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** What a route answers: JSON, bytes of another type (a logo), or a status with no body (`204`, `304`). */
sealed interface Reply {
    val status: Int
    val headers: Map<String, String>

    class Json(override val status: Int, val body: String, override val headers: Map<String, String> = emptyMap()) : Reply

    class Bytes(override val status: Int, val body: ByteArray, val contentType: String, override val headers: Map<String, String> = emptyMap()) : Reply

    class Empty(override val status: Int, override val headers: Map<String, String> = emptyMap()) : Reply

    companion object {
        /** `200` with [value] as JSON. */
        inline fun <reified T> ok(value: T, headers: Map<String, String> = emptyMap()): Reply =
            Json(200, StationApi.json.encodeToString(serializer<T>(), value), headers)

        /** `{}`: an answer with nothing in it. */
        val done: Reply = Json(200, "{}")
    }
}

/** One request as a route sees it. [device] is the paired TV calling, null only on the pairing routes. */
class Call(
    val method: String,
    val params: Map<String, String>,
    val device: PairedDevice?,
    private val exchange: HttpExchange,
    private val maxBody: Int,
) {
    /** The calling TV, on a route that needs a certificate. */
    val tv: PairedDevice get() = device ?: error("No TV on an open route")

    fun param(name: String): String = params.getValue(name)

    fun header(name: String): String? = exchange.requestHeaders.getFirst(name)

    fun query(name: String): String? = exchange.requestURI.rawQuery?.split('&')?.firstNotNullOfOrNull { pair ->
        val (key, value) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (key == name) URLDecoder.decode(value, Charsets.UTF_8) else null
    }

    /** The body, up to [maxBody] bytes; more is `413`. */
    fun bytes(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        exchange.requestBody.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > maxBody) throw ApiException(413, "That request is too big.")
            }
        }
        return out.toByteArray()
    }

    inline fun <reified T> body(): T = decode(serializer<T>(), bytes()) ?: throw ApiException(400, UNREADABLE)

    /** Null for an empty body: `DELETE /v1/spot` without `only`. */
    inline fun <reified T> optionalBody(): T? = decode(serializer<T>(), bytes())

    fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T? {
        val text = bytes.toString(Charsets.UTF_8)
        if (text.isBlank()) return null
        return try {
            StationApi.json.decodeFromString(serializer, text)
        } catch (e: IllegalArgumentException) {
            // SerializationException is one; a bad enum value or a missing field too.
            throw ApiException(400, UNREADABLE)
        }
    }

    companion object {
        const val UNREADABLE = "The guide station couldn't read that request."
    }
}

/** One of the API's calls: `{name}` in [pattern] is a path parameter. [open] routes need no certificate. */
class Route(val method: String, val pattern: String, val open: Boolean = false, val handle: suspend (Call) -> Reply) {
    private val parts = pattern.trim('/').split('/')

    /** The path's parameters when it's this route's, else null. */
    fun match(path: String): Map<String, String>? {
        val segments = path.trim('/').split('/')
        if (segments.size != parts.size) return null
        val params = HashMap<String, String>()
        for ((part, segment) in parts.zip(segments)) {
            if (part.startsWith('{') && part.endsWith('}')) {
                if (segment.isEmpty()) return null
                params[part.substring(1, part.length - 1)] = segment
            } else if (part != segment) {
                return null
            }
        }
        return params
    }

    /** A path this route answers (each parameter "x"): what the tests call it with. */
    val example: String get() = "/" + parts.joinToString("/") { if (it.startsWith('{')) "x" else it }
}

/**
 * Every request comes through here. The one gate: a request that isn't exactly `POST /v1/pair`
 * or `GET /v1/pair/{id}` must carry a client certificate this station issued and hasn't
 * revoked, and that's checked before the path is even looked up, so a caller without one
 * learns nothing, not even which paths exist.
 *
 * TLS has already refused a certificate from any other CA (the server trusts only its own).
 * A connection with no certificate gets through the handshake (`wantClientAuth`, so the
 * pairing calls work on the same port), and is answered `401` here.
 */
class StationHandler(
    private val routes: List<Route>,
    private val ca: StationCa,
    private val devices: DeviceStore,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val method = exchange.requestMethod.uppercase()
        val path = exchange.requestURI.path.orEmpty()
        val reply = try {
            respond(exchange, method, path)
        } catch (e: ApiException) {
            error(e.status, e.message)
        } catch (e: EditRejected) {
            error(400, e.message)
        } catch (e: Exception) {
            val route = routes.firstOrNull { it.method == method && it.match(path) != null }?.pattern ?: "?"
            log("$method $route: ${e.summary()}")
            error(500, "Something went wrong on the guide station. Try again.")
        }
        write(exchange, reply)
    }

    private fun respond(exchange: HttpExchange, method: String, path: String): Reply {
        val open = routes.any { it.open && it.method == method && it.match(path) != null }
        val device = if (open) null else authenticate(exchange)
        val matches = routes.mapNotNull { route -> route.match(path)?.let { route to it } }
        if (matches.isEmpty()) throw ApiException(404, "The guide station has nothing at that address.")
        val (route, params) = matches.firstOrNull { it.first.method == method } ?: throw ApiException(405, "That address doesn't take a $method.")
        if (device != null) devices.seen(device.id, clock())
        val call = Call(method, params, device, exchange, if (open) OPEN_BODY_MAX else BODY_MAX)
        return runBlocking { route.handle(call) }
    }

    /** The paired TV behind the connection's certificate; `401` without one, `403` once it's unpaired. */
    private fun authenticate(exchange: HttpExchange): PairedDevice {
        val session = (exchange as? HttpsExchange)?.sslSession ?: throw ApiException(401, NOT_PAIRED)
        val certificate = try {
            session.peerCertificates.firstOrNull() as? X509Certificate
        } catch (e: SSLPeerUnverifiedException) {
            null
        } ?: throw ApiException(401, NOT_PAIRED)
        // TLS checked it already; checked again here, so this gate never depends on that alone.
        try {
            certificate.verify(ca.caCertificate.publicKey)
            certificate.checkValidity()
        } catch (e: GeneralSecurityException) {
            throw ApiException(401, NOT_PAIRED)
        }
        return when (val caller = devices.identify(certificate)) {
            is Caller.Paired -> caller.device
            Caller.Unpaired -> throw ApiException(403, UNPAIRED)
        }
    }

    private fun error(status: Int, message: String?): Reply =
        Reply.Json(status, StationApi.json.encodeToString(ApiError.serializer(), ApiError(message ?: "Something went wrong.")))

    private fun write(exchange: HttpExchange, reply: Reply) {
        try {
            val headers = exchange.responseHeaders
            headers.set(StationApi.VERSION_HEADER, StationApi.VERSION)
            headers.set("Cache-Control", "no-store")
            reply.headers.forEach(headers::set)
            when {
                // A HEAD answer has no body, whatever the route would have sent.
                reply is Reply.Empty || exchange.requestMethod.equals("HEAD", ignoreCase = true) -> exchange.sendResponseHeaders(reply.status, -1)
                reply is Reply.Json -> {
                    headers.set("Content-Type", "application/json; charset=utf-8")
                    headers.set("Vary", "Accept-Encoding")
                    var body = reply.body.toByteArray(Charsets.UTF_8)
                    if (body.size > GZIP_OVER && exchange.requestHeaders.getFirst("Accept-Encoding").orEmpty().contains("gzip")) {
                        body = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(body) } }.toByteArray()
                        headers.set("Content-Encoding", "gzip")
                    }
                    exchange.sendResponseHeaders(reply.status, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                reply is Reply.Bytes -> {
                    headers.set("Content-Type", reply.contentType)
                    exchange.sendResponseHeaders(reply.status, reply.body.size.toLong())
                    exchange.responseBody.use { it.write(reply.body) }
                }
            }
        } catch (e: IOException) {
            // The TV went away mid-answer; it asks again.
        } finally {
            exchange.close()
        }
    }

    companion object {
        /** The API doc's answer to a revoked certificate, word for word: the TVs look for it. */
        const val UNPAIRED = "unpaired"
        const val NOT_PAIRED = "Pair this TV with the guide station first."
        private const val BODY_MAX = 1024 * 1024
        private const val OPEN_BODY_MAX = 16 * 1024

        /** Bigger answers (the guide) are gzipped for a TV that takes it; OkHttp always does. */
        private const val GZIP_OVER = 1024
    }
}

/**
 * The station's HTTPS server: the JDK's own (U3), TLS 1.3 only, on one port. It asks every
 * client for a certificate (`wantClientAuth`) and trusts only the station's CA, so a
 * certificate from anywhere else fails the handshake; [StationHandler] refuses a missing one.
 */
class StationServer(port: Int, ca: StationCa, handler: HttpHandler) : AutoCloseable {
    private val executor: ExecutorService = virtualThreads() ?: Executors.newCachedThreadPool { task ->
        Thread(task, "station-http").apply { isDaemon = true }
    }

    private val server: HttpsServer = HttpsServer.create(InetSocketAddress(port), 0).apply {
        val context = ca.serverContext()
        httpsConfigurator = object : HttpsConfigurator(context) {
            override fun configure(params: HttpsParameters) {
                val ssl = context.defaultSSLParameters
                ssl.protocols = arrayOf("TLSv1.3")
                ssl.wantClientAuth = true
                params.setSSLParameters(ssl)
            }
        }
        createContext("/", handler)
        executor = this@StationServer.executor
    }

    val port: Int get() = server.address.port

    fun start() = server.start()

    override fun close() {
        server.stop(1)
        executor.shutdownNow()
    }

    private companion object {
        /** A thread per request: Java 21's virtual threads in the container; a cached pool on an older JDK (CI's 17). */
        fun virtualThreads(): ExecutorService? = try {
            Executors::class.java.getMethod("newVirtualThreadPerTaskExecutor").invoke(null) as ExecutorService
        } catch (e: ReflectiveOperationException) {
            null
        }
    }
}
