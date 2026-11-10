package com.phonoodlesramen.actioncenter.guide.editor

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who may use the phone editor, for one run of the server (G17). A new run makes a new
 * [pin] and [key], and forgets every session.
 *
 * - **The QR code** carries [key], 128 random bits: opening it once signs that phone in
 *   ([useKey]). Scanning it proves you can see the TV, so it needs no PIN.
 * - **A typed address** asks for the 6-digit [pin] ([tryPin]). After [MAX_WRONG_PINS]
 *   wrong ones, PIN entry is locked for the run; the key still works.
 * - **A session** is a random cookie, `HttpOnly` and `SameSite=Strict` ([cookie]); every
 *   `/api` call needs one ([isSignedIn]).
 *
 * Secrets are compared in constant time and never logged.
 */
class EditorSignIn(private val random: SecureRandom = SecureRandom()) {
    /** Six digits, leading zeros kept ("042817"); the TV shows it as "042 817". */
    val pin: String = random.nextInt(PIN_RANGE).toString().padStart(PIN_DIGITS, '0')

    /** The QR code's one-time key, base64url without padding. */
    val key: String = token()

    private var keyUsed = false
    private var wrongPins = 0
    private val sessions = mutableListOf<ByteArray>()
    private val _signedIn = MutableStateFlow(false)

    /** True once any phone has signed in this run: the TV says "Your phone is connected". */
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /** A new session for [candidate] if it's the key and hasn't been used; null otherwise. */
    @Synchronized
    fun useKey(candidate: String): String? {
        if (keyUsed || !same(candidate, key)) return null
        keyUsed = true
        return newSession()
    }

    @Synchronized
    fun tryPin(candidate: String): PinResult {
        if (wrongPins >= MAX_WRONG_PINS) return PinResult.Locked
        val digits = candidate.filterNot { it == ' ' || it == '-' }
        // A typo that isn't six digits isn't a guess: it doesn't count against the limit.
        if (digits.length != PIN_DIGITS || !digits.all { it in '0'..'9' }) return PinResult.NotAPin
        if (same(digits, pin)) return PinResult.SignedIn(newSession())
        wrongPins++
        return if (wrongPins >= MAX_WRONG_PINS) PinResult.Locked else PinResult.Wrong(MAX_WRONG_PINS - wrongPins)
    }

    /** Whether a `Cookie` header carries one of this run's sessions. */
    @Synchronized
    fun isSignedIn(cookieHeader: String?): Boolean {
        val session = cookieHeader?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$COOKIE=") }
            ?.substringAfter('=')
            ?: return false
        val candidate = session.toByteArray()
        // Every session is compared, so the time taken doesn't say which one nearly matched.
        return sessions.fold(false) { found, known -> MessageDigest.isEqual(candidate, known) or found }
    }

    @Synchronized
    private fun newSession(): String {
        val session = token()
        sessions += session.toByteArray()
        _signedIn.value = true
        return session
    }

    private fun token(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    sealed interface PinResult {
        /** The right PIN: [session] is the new session's cookie value. */
        class SignedIn(val session: String) : PinResult {
            override fun toString() = "SignedIn"
        }

        data class Wrong(val triesLeft: Int) : PinResult

        /** Not six digits; not counted. */
        data object NotAPin : PinResult

        /** Too many wrong PINs this run; the QR code's key still works. */
        data object Locked : PinResult
    }

    companion object {
        const val COOKIE = "ac_editor"
        const val MAX_WRONG_PINS = 5
        const val PIN_DIGITS = 6
        private const val PIN_RANGE = 1_000_000

        /** 128 bits. */
        private const val TOKEN_BYTES = 16

        /** The session cookie: this run only, never readable by script, never sent from another site. */
        fun cookie(session: String) = "$COOKIE=$session; Path=/; HttpOnly; SameSite=Strict"

        /** "042817" as "042 817", for the TV. */
        fun spaced(pin: String) = if (pin.length == PIN_DIGITS) "${pin.take(3)} ${pin.drop(3)}" else pin
    }
}
