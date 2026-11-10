package com.phonoodlesramen.actioncenter.guide.editor

import com.phonoodlesramen.actioncenter.guide.editor.EditorSignIn.PinResult

/**
 * What a phone page's `/api` answers once [EditorSite] has checked the session: the guide
 * editor's ([EditorApi], G17) or the AI key page's (M10). Paths are after `/api`.
 */
fun interface PhoneApi {
    suspend fun handle(method: String, path: List<String>, body: ByteArray): HttpResponse
}

/**
 * Everything the phone editor's server answers, for one run (G17):
 *
 * - **`GET /`** is the page ([page]). With the QR code's `?k=`, a key that works sets the
 *   session cookie; either way it redirects to `/`, so the key never stays in the
 *   address bar or the history.
 * - **`POST /signin`** with `{"pin"}` sets the cookie for the right PIN. The page shows
 *   this form when `/api` answers 401.
 * - **`/api/…`** needs the cookie, else 401; then [api] ([EditorApi] for the guide editor).
 * - **Requests with a body** must be JSON (415 otherwise), so a plain form on another site
 *   can't post here. There are no CORS headers.
 * - **The `Host`** must be an IP address or `localhost`: a name could be a DNS rebinding
 *   attack, a web page elsewhere pointing its own name at the Shield.
 */
class EditorSite(private val signIn: EditorSignIn, private val api: PhoneApi, private val page: ByteArray) {
    suspend fun handle(request: HttpRequest): HttpResponse {
        if (!hostAllowed(request.header("host"))) return HttpResponse.error(400, "Open the address shown on the TV")
        val path = request.segments
        if (request.body.isNotEmpty() && !isJson(request.header("content-type"))) {
            return HttpResponse.error(415, "Send JSON")
        }
        return when {
            path.isEmpty() -> root(request)
            path == listOf("signin") -> signin(request)
            path.first() == "api" -> when {
                signIn.isSignedIn(request.header("cookie")) -> api.handle(request.method, path.drop(1), request.body)
                else -> HttpResponse.error(401, "Sign in first")
            }
            else -> HttpResponse.error(404, "Not found")
        }
    }

    private fun root(request: HttpRequest): HttpResponse {
        if (request.method != "GET") return HttpResponse.error(405, "Not allowed", listOf("Allow" to "GET"))
        val key = request.query["k"] ?: return HttpResponse(200, PAGE_HEADERS, page)
        val session = signIn.useKey(key)
        val cookie = session?.let { listOf("Set-Cookie" to EditorSignIn.cookie(it)) }.orEmpty()
        return HttpResponse.seeOther("/", cookie)
    }

    private fun signin(request: HttpRequest): HttpResponse {
        if (request.method != "POST") return HttpResponse.error(405, "Not allowed", listOf("Allow" to "POST"))
        val pin = runCatching { EditorJson.json.decodeFromString(PinBody.serializer(), request.body.decodeToString()).pin }
            .getOrElse { return HttpResponse.error(400, EditorApi.UNREADABLE) }
        return when (val result = signIn.tryPin(pin)) {
            is PinResult.SignedIn -> HttpResponse.noContent(listOf("Set-Cookie" to EditorSignIn.cookie(result.session)))
            is PinResult.Wrong -> HttpResponse.error(
                401,
                "That PIN doesn't match the TV. ${result.triesLeft} ${if (result.triesLeft == 1) "try" else "tries"} left.",
            )
            PinResult.NotAPin -> HttpResponse.error(400, "Enter the 6 digits shown on the TV.")
            PinResult.Locked -> HttpResponse.error(429, LOCKED)
        }
    }

    companion object {
        const val LOCKED = "Too many wrong PINs. Scan the QR code on the TV instead, or press Back on the TV and open Edit on your phone again."

        /**
         * The page is one file with its CSS and script inline, and loads nothing else: the
         * policy says so, so nothing can be added from outside.
         */
        private const val POLICY = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
            "connect-src 'self'; img-src 'self' data:; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"

        private val PAGE_HEADERS = listOf(
            "Content-Type" to "text/html; charset=utf-8",
            "Content-Security-Policy" to POLICY,
        )

        private fun isJson(contentType: String?) =
            contentType?.substringBefore(';')?.trim()?.equals("application/json", ignoreCase = true) == true

        /** An IPv4 address, a bracketed IPv6 one, or `localhost`, with or without a port. */
        internal fun hostAllowed(host: String?): Boolean {
            if (host.isNullOrEmpty()) return false
            val bracketed = host.startsWith('[')
            val end = if (bracketed) host.indexOf(']') + 1 else host.indexOf(':').let { if (it < 0) host.length else it }
            if (end <= 0) return false
            val name = host.substring(0, end)
            val port = host.substring(end)
            if (port.isNotEmpty() && !(port[0] == ':' && port.drop(1).isDigits(maxLength = 5))) return false
            return when {
                bracketed -> name.length > 2 && name.substring(1, name.length - 1).all { it in HEX_DIGITS || it == ':' || it == '.' }
                name.equals("localhost", ignoreCase = true) -> true
                else -> name.split('.').let { parts -> parts.size == 4 && parts.all { it.isDigits(maxLength = 3) && it.toInt() <= 255 } }
            }
        }

        private const val HEX_DIGITS = "0123456789abcdefABCDEF"

        private fun String.isDigits(maxLength: Int) = isNotEmpty() && length <= maxLength && all { it in '0'..'9' }
    }
}
