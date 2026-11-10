package com.phonoodlesramen.actioncenter.ai

import com.phonoodlesramen.actioncenter.ai.BlurbFixtures.KEY
import com.phonoodlesramen.actioncenter.plex.FakeBox
import com.phonoodlesramen.actioncenter.plex.SecretBox
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key's store (M10), with a fake for the Keystore (Robolectric has no AndroidKeyStore):
 * [FakeBox] reverses what it seals, so a key stored in plain text would fail these tests.
 */
class AiKeyVaultTest {
    private val file = File(Files.createTempDirectory("no_backup").toFile(), AiKeyVault.FILE)

    private fun TestScope.vault(box: SecretBox = FakeBox()) = AiKeyVault(file, box, StandardTestDispatcher(testScheduler))

    @Test
    fun `a key goes in sealed and comes back out`() = runTest {
        val vault = vault()
        assertNull(vault.key())
        assertNull(vault.ending.first())

        vault.save(KEY)

        assertEquals(KEY, vault.key())
        assertEquals("wxYZ", vault.ending.first())
        assertTrue(file.isFile)
        assertFalse(KEY in file.readText())
        assertFalse(File(file.parentFile, file.name + ".new").exists())
    }

    @Test
    fun `a new key replaces the old one`() = runTest {
        val vault = vault()
        vault.save(KEY)
        vault.save("sk-ant-api03-TESTONLY-second-key-0000-abcd")

        assertEquals("abcd", vault.ending.first())
        assertEquals(1, file.parentFile!!.listFiles()!!.size)
    }

    @Test
    fun `forget key deletes it`() = runTest {
        val vault = vault()
        vault.save(KEY)

        vault.forget()

        assertFalse(file.exists())
        assertNull(vault.key())
        assertNull(vault.ending.first())
    }

    @Test
    fun `a key that can't be opened is no key`() = runTest {
        vault().save(KEY)
        val lost = vault(
            object : SecretBox {
                override fun seal(plain: String) = error("not used")
                override fun open(sealed: String): String? = null
            },
        )
        assertNull(lost.key())
        assertNull(lost.ending.first())
    }

    @Test
    fun `only the last 4 characters are ever shown`() {
        assertEquals("wxYZ", AiKeys.ending(KEY))
        assertEquals(4, AiKeys.ending(KEY).length)
    }

    @Test
    fun `what a pasted key must look like, said without repeating it`() {
        assertNull(AiKeys.problem(KEY))
        val wrong = listOf(
            "",
            "sk-ant-api03 with spaces in it 0000000000",
            "sk-ant-api03-line\nbreak-000000000000000",
            "plex-token-AbCdEfGhIjKlMnOpQrStUv",
            "sk-ant-short",
            "sk-ant-" + "x".repeat(600),
        )
        wrong.forEach { key ->
            val problem = AiKeys.problem(key)
            assertTrue(key, problem != null)
            if (key.isNotEmpty()) assertFalse(problem!!.contains(key))
        }
    }
}
