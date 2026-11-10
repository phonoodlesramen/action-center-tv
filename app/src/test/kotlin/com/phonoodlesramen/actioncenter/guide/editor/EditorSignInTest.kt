package com.phonoodlesramen.actioncenter.guide.editor

import com.phonoodlesramen.actioncenter.guide.editor.EditorSignIn.PinResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone editor's sign-in (G17): the one-time key, the PIN, the lockout and the session cookie. */
class EditorSignInTest {
    private val signIn = EditorSignIn()

    /** A wrong PIN: the right one with its last digit changed. */
    private val wrongPin get() = signIn.pin.dropLast(1) + ((signIn.pin.last() - '0' + 1) % 10)

    private fun cookieFor(session: String) = "theme=dark; ${EditorSignIn.COOKIE}=$session; other=1"

    @Test
    fun `the PIN is six digits and the key is 128 bits of base64url`() {
        assertTrue(signIn.pin.matches(Regex("[0-9]{6}")))
        assertTrue(signIn.key.matches(Regex("[A-Za-z0-9_-]{22}")))
    }

    @Test
    fun `each run has its own key`() {
        assertNotEquals(signIn.key, EditorSignIn().key)
    }

    @Test
    fun `the key works once`() {
        val session = signIn.useKey(signIn.key)
        assertNotNull(session)
        assertTrue(signIn.isSignedIn(cookieFor(session!!)))
        assertNull(signIn.useKey(signIn.key))
    }

    @Test
    fun `a wrong key doesn't sign in, and doesn't use up the key`() {
        val wrongKey = (if (signIn.key[0] == 'A') "B" else "A") + signIn.key.drop(1)
        assertNull(signIn.useKey(wrongKey))
        assertNull(signIn.useKey(""))
        assertNotNull(signIn.useKey(signIn.key))
    }

    @Test
    fun `the right PIN signs in, with or without its space`() {
        val first = signIn.tryPin(signIn.pin)
        assertTrue(first is PinResult.SignedIn)
        val spaced = signIn.tryPin(EditorSignIn.spaced(signIn.pin))
        assertTrue(spaced is PinResult.SignedIn)
        assertTrue(signIn.isSignedIn(cookieFor((first as PinResult.SignedIn).session)))
        assertTrue(signIn.isSignedIn(cookieFor((spaced as PinResult.SignedIn).session)))
    }

    @Test
    fun `wrong PINs count down, and the fifth locks PIN entry for the run`() {
        for (left in 4 downTo 1) assertEquals(PinResult.Wrong(left), signIn.tryPin(wrongPin))
        assertEquals(PinResult.Locked, signIn.tryPin(wrongPin))
        assertEquals(PinResult.Locked, signIn.tryPin(signIn.pin))
    }

    @Test
    fun `after the lockout the key still works`() {
        repeat(EditorSignIn.MAX_WRONG_PINS) { signIn.tryPin(wrongPin) }
        assertEquals(PinResult.Locked, signIn.tryPin(signIn.pin))
        assertNotNull(signIn.useKey(signIn.key))
    }

    @Test
    fun `something that isn't six digits isn't counted`() {
        repeat(10) {
            assertEquals(PinResult.NotAPin, signIn.tryPin("12"))
            assertEquals(PinResult.NotAPin, signIn.tryPin("abcdef"))
        }
        assertTrue(signIn.tryPin(signIn.pin) is PinResult.SignedIn)
    }

    @Test
    fun `only this run's sessions are signed in`() {
        val other = EditorSignIn()
        val theirs = other.useKey(other.key)!!
        assertFalse(signIn.isSignedIn(cookieFor(theirs)))
        assertFalse(signIn.isSignedIn(null))
        assertFalse(signIn.isSignedIn(""))
        assertFalse(signIn.isSignedIn("${EditorSignIn.COOKIE}="))
        assertFalse(signIn.isSignedIn("not_${EditorSignIn.COOKIE}=${theirs}"))
    }

    @Test
    fun `signing in tells the TV a phone is connected`() {
        assertFalse(signIn.signedIn.value)
        signIn.tryPin(wrongPin)
        assertFalse(signIn.signedIn.value)
        signIn.useKey(signIn.key)
        assertTrue(signIn.signedIn.value)
    }

    @Test
    fun `the cookie is for this site only and hidden from script`() {
        assertEquals("ac_editor=abc; Path=/; HttpOnly; SameSite=Strict", EditorSignIn.cookie("abc"))
    }

    @Test
    fun `the TV shows the PIN in two threes`() {
        assertEquals("042 817", EditorSignIn.spaced("042817"))
    }

    @Test
    fun `a result never prints the session`() {
        val result = signIn.tryPin(signIn.pin) as PinResult.SignedIn
        assertFalse(result.session in result.toString())
    }
}
