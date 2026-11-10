package com.phonoodlesramen.actioncenter.profiles

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.phonoodlesramen.actioncenter.plex.FakeBox
import com.phonoodlesramen.actioncenter.plex.SecretBox
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Each kid's server token (K2): sealed in its own file, read back, forgotten. The tokens are made up. */
class SealedKidTokensTest {
    private val file = File(Files.createTempDirectory("kids").toFile(), "kid_tokens.preferences_pb")

    private fun TestScope.store() = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }

    @Test
    fun `a token is sealed, read back for its kid only, and forgotten`() = runTest {
        val store = store()
        val tokens = SealedKidTokens(store, FakeBox())

        tokens.save("kid-maya", "maya-server-token")
        tokens.save("kid-leo", "leo-server-token")

        assertEquals("maya-server-token", tokens.serverToken("kid-maya"))
        assertEquals("leo-server-token", tokens.serverToken("kid-leo"))
        val stored = store.data.first().asMap().values.map { it.toString() }
        assertFalse("never stored in plain text: $stored", stored.any { "server-token" in it })

        tokens.forget("kid-maya")

        assertNull(tokens.serverToken("kid-maya"))
        assertEquals("leo-server-token", tokens.serverToken("kid-leo"))
        assertNull(tokens.serverToken("kid-nobody"))
    }

    @Test
    fun `a kid's Plex Home UUID is kept beside their token, and forgotten with it (Phase 11)`() = runTest {
        val tokens = SealedKidTokens(store(), FakeBox())

        assertNull("linked before Phase 11", tokens.plexUuid("kid-maya"))
        tokens.save("kid-maya", "maya-server-token")
        tokens.saveUuid("kid-maya", "u-maya")
        assertEquals("u-maya", tokens.plexUuid("kid-maya"))

        tokens.forget("kid-maya")

        assertNull(tokens.plexUuid("kid-maya"))
        assertNull(tokens.serverToken("kid-maya"))
    }

    @Test
    fun `a token that can't be unsealed reads as none`() = runTest {
        val store = store()
        SealedKidTokens(store, FakeBox()).save("kid-maya", "maya-server-token")

        val lostKey = SealedKidTokens(store, object : SecretBox {
            override fun seal(plain: String) = plain
            override fun open(sealed: String): String? = null
        })

        assertNull(lostKey.serverToken("kid-maya"))
    }
}
