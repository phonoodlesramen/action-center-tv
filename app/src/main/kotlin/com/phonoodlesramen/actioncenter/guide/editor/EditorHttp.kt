package com.phonoodlesramen.actioncenter.guide.editor

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/**
 * One request to the phone editor's server, as [HttpParser] read it.
 *
 * @param segments the path's segments, each percent-decoded: `/api/channels/a%20b` is
 *   `["api", "channels", "a b"]`, and `/` is empty.
 * @param query the query's parameters, decoded; the last of a repeated name wins.
 * @param headers by lower-case name; repeats are joined as HTTP allows.
 */
class HttpRequest(
    val method: String,
    val segments: List<String>,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/** A response to write: [HttpWriter] adds the length, `Connection: close` and the headers every response carries. */
class HttpResponse(val status: Int, val headers: List<Pair<String, String>> = emptyList(), val body: ByteArray = ByteArray(0)) {
    /** The first value of header [name], for tests. */
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    companion object {
        const val JSON = "application/json; charset=utf-8"

        fun json(status: Int, text: String) = HttpResponse(status, listOf("Content-Type" to JSON), text.toByteArray())

        /** `{"error": "…"}`: the page shows [message] as it is, so it's a sentence for people. */
        fun error(status: Int, message: String, headers: List<Pair<String, String>> = emptyList()) =
            HttpResponse(status, listOf("Content-Type" to JSON) + headers, EditorJson.error(message).toByteArray())

        fun noContent(headers: List<Pair<String, String>> = emptyList()) = HttpResponse(204, headers)

        /** 303 to [location]: the browser follows it with a GET. */
        fun seeOther(location: String, headers: List<Pair<String, String>> = emptyList()) =
            HttpResponse(303, listOf("Location" to location) + headers)
    }
}

/** A request the server answers with [status] and [message] without routing it. */
class HttpError(val status: Int, message: String) : Exception(message)

/** The phone closed the connection, or sent nothing in time: there's no one to answer. */
class ClientGone : IOException()

/**
 * HTTP/1.1 requests, by hand (plan §4): the request line and headers up to [maxHead]
 * bytes, then a body of exactly `Content-Length` bytes, up to [maxBody]. No chunked
 * bodies, no keep-alive: the server answers once and closes.
 *
 * - **Methods:** GET, POST, PUT and DELETE; any other is 405, a malformed one 400.
 * - **Limits:** a head over [maxHead] is 431, a body over [maxBody] is 413, and a
 *   `Transfer-Encoding` is 411 (send a length instead).
 * - **Malformed** request lines, headers, lengths or paths are 400; a body cut short is
 *   [ClientGone], since nobody is left to answer.
 * - **Slow clients:** the socket's read timeout bounds each read, and [deadline] (on
 *   [clock]) bounds the whole request.
 */
class HttpParser(
    private val maxHead: Int = MAX_HEAD_BYTES,
    private val maxBody: Int = MAX_BODY_BYTES,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    fun read(input: InputStream, deadline: Long = Long.MAX_VALUE): HttpRequest {
        val lines = readHead(input, deadline)
        val (method, target) = requestLine(lines.first())
        val headers = headers(lines.drop(1))
        val (segments, query) = target(target)
        if (headers.containsKey("transfer-encoding")) throw HttpError(411, "Send a Content-Length")
        val length = headers["content-length"]?.let(::contentLength) ?: 0
        if (length > maxBody) throw HttpError(413, "That's too much at once")
        val body = readBody(input, length.toInt(), deadline)
        return HttpRequest(method, segments, query, headers, body)
    }

    /** The request line and header lines, without their line ends; blank lines before the request line are skipped. */
    private fun readHead(input: InputStream, deadline: Long): List<String> {
        val lines = mutableListOf<String>()
        val line = ByteArrayOutputStream()
        var total = 0
        while (true) {
            if (clock() > deadline) throw ClientGone()
            val b = input.read()
            if (b == -1) throw ClientGone()
            if (++total > maxHead) throw HttpError(431, "The request's headers are too large")
            if (b != '\n'.code) {
                line.write(b)
                continue
            }
            // Latin-1 keeps every byte; anything outside ASCII fails the checks below.
            val text = line.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
            line.reset()
            when {
                text.isNotEmpty() -> lines += text
                lines.isNotEmpty() -> return lines
            }
        }
    }

    private fun requestLine(line: String): Pair<String, String> {
        val parts = line.split(' ')
        if (parts.size != 3 || parts.any { it.isEmpty() }) throw HttpError(400, "Bad request")
        val (method, target, version) = parts
        if (!method.isToken()) throw HttpError(400, "Bad request")
        if (version != "HTTP/1.1" && version != "HTTP/1.0") throw HttpError(505, "HTTP/1.1 only")
        if (method !in METHODS) throw HttpError(405, "Not allowed")
        return method to target
    }

    private fun headers(lines: List<String>): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        for (line in lines) {
            val colon = line.indexOf(':')
            // No folded lines (RFC 9112 §5.2), and no space before the colon (§5.1).
            if (colon <= 0 || line[0] == ' ' || line[0] == '\t') throw HttpError(400, "Bad header")
            val name = line.substring(0, colon)
            if (!name.isToken()) throw HttpError(400, "Bad header")
            val value = line.substring(colon + 1).trim(' ', '\t')
            if (value.any { it.code < 0x20 && it != '\t' || it.code == 0x7F }) throw HttpError(400, "Bad header")
            val key = name.lowercase()
            val previous = headers[key]
            headers[key] = when {
                previous == null -> value
                key == "content-length" && previous == value -> value
                key == "content-length" || key == "host" -> throw HttpError(400, "Bad header")
                key == "cookie" -> "$previous; $value"
                else -> "$previous, $value"
            }
        }
        return headers
    }

    private fun contentLength(value: String): Long {
        if (value.isEmpty() || value.length > MAX_LENGTH_DIGITS || !value.all { it in '0'..'9' }) {
            throw HttpError(400, "Bad Content-Length")
        }
        return value.toLong()
    }

    /** Origin-form only (`/path?query`), in visible ASCII: no absolute URLs, no `*`. */
    private fun target(target: String): Pair<List<String>, Map<String, String>> {
        if (!target.startsWith('/') || target.any { it.code !in 0x21..0x7E }) throw HttpError(400, "Bad request")
        val path = target.substringBefore('?')
        val segments = path.split('/').filter { it.isNotEmpty() }.map { decode(it, plusIsSpace = false) }
        val query = if ('?' in target) target.substringAfter('?') else ""
        val params = query.split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('='), plusIsSpace = true) to decode(it.substringAfter('=', ""), plusIsSpace = true)
        }
        return segments to params
    }

    private fun readBody(input: InputStream, length: Int, deadline: Long): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            if (clock() > deadline) throw ClientGone()
            val n = input.read(body, read, length - read)
            if (n == -1) throw ClientGone()
            read += n
        }
        return body
    }

    companion object {
        const val MAX_HEAD_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 256 * 1024
        val METHODS = setOf("GET", "POST", "PUT", "DELETE")

        /** Ten digits is already far past [MAX_BODY_BYTES]; more can't be a real length. */
        private const val MAX_LENGTH_DIGITS = 10

        private const val TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~"

        private fun String.isToken() = isNotEmpty() && all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in TOKEN_SYMBOLS }

        /** Percent-decoding as UTF-8; a broken escape or invalid UTF-8 is 400. */
        internal fun decode(text: String, plusIsSpace: Boolean): String {
            if ('%' !in text && !(plusIsSpace && '+' in text)) return text
            val bytes = ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '%' -> {
                        val hex = text.substring(i + 1, minOf(i + 3, text.length))
                        val value = hex.takeIf { it.length == 2 }?.toIntOrNull(16) ?: throw HttpError(400, "Bad request")
                        bytes.write(value)
                        i += 3
                    }
                    c == '+' && plusIsSpace -> {
                        bytes.write(' '.code)
                        i++
                    }
                    else -> {
                        // The target is visible ASCII already, so one char is one byte.
                        bytes.write(c.code)
                        i++
                    }
                }
            }
            return try {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            } catch (_: CharacterCodingException) {
                throw HttpError(400, "Bad request")
            }
        }
    }
}

/**
 * Writes a [HttpResponse] and nothing after it: every response closes the connection.
 * Every response also says not to cache it, sniff it, frame it or send a referrer, so
 * the one-time key never leaves in a `Referer`.
 */
object HttpWriter {
    fun write(output: OutputStream, response: HttpResponse) {
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            for ((name, value) in response.headers + COMMON_HEADERS) append(name).append(": ").append(value).append("\r\n")
            // RFC 9110 §8.6: never a length on a 204.
            if (response.status != 204) append("Content-Length: ").append(response.body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(response.body)
        output.flush()
    }

    private val COMMON_HEADERS = listOf(
        "Cache-Control" to "no-store",
        "X-Content-Type-Options" to "nosniff",
        "X-Frame-Options" to "DENY",
        "Referrer-Policy" to "no-referrer",
    )

    fun reason(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        303 -> "See Other"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        411 -> "Length Required"
        413 -> "Content Too Large"
        415 -> "Unsupported Media Type"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        505 -> "HTTP Version Not Supported"
        else -> "Unknown"
    }
}
