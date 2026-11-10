package com.phonoodlesramen.actioncenter.ai

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.phonoodlesramen.actioncenter.ai.BlurbFixtures.KEY
import com.phonoodlesramen.actioncenter.plex.FakeBox
import com.phonoodlesramen.actioncenter.settings.Settings
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Settings › AI blurbs' controller (M7-M11): the schedule follows the switch, a first run
 * comes as soon as it's on with a key (in either order) and never at start-up, off clears
 * the blurbs, and the status shows the key's last 4 characters and the last run.
 */
class StoredAiBlurbsTest {
    private val dir = Files.createTempDirectory("ai").toFile()
    private val settings = MutableStateFlow(Settings())
    private val schedule = FakeSchedule()
    private var cleared = 0
    private val logs = mutableListOf<String>()

    /** Tuesday 27 October 2026, 8:15 PM UTC. */
    private val now = LocalDateTime.of(2026, 10, 27, 20, 15).atZone(ZoneOffset.UTC)

    private class FakeSchedule : BlurbSchedule {
        val calls = mutableListOf<String>()

        override fun nightly() {
            calls += "nightly"
        }

        override fun runNow() {
            calls += "now"
        }

        override fun cancel() {
            calls += "cancel"
        }
    }

    private class Parts(val controller: StoredAiBlurbs, val vault: AiKeyVault, val runs: BlurbRuns)

    private fun TestScope.parts(): Parts {
        val vault = AiKeyVault(File(dir, AiKeyVault.FILE), FakeBox(), StandardTestDispatcher(testScheduler))
        val runs = BlurbRuns(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "ai_blurbs.preferences_pb") })
        val controller = StoredAiBlurbs(settings, vault, runs, schedule, { cleared++ }, backgroundScope, clock = { now }, log = logs::add)
        return Parts(controller, vault, runs)
    }

    @Test
    fun `off, nothing scheduled, and the blurbs are cleared`() = runTest {
        parts()
        runCurrent()

        assertEquals(listOf("cancel"), schedule.calls)
        assertEquals(1, cleared)
    }

    @Test
    fun `turned on with a key stored, scheduled nightly, and written once now`() = runTest {
        parts().vault.save(KEY)
        runCurrent()
        schedule.calls.clear()

        settings.value = Settings(aiBlurbs = true)
        runCurrent()

        assertEquals(listOf("nightly", "now"), schedule.calls)
    }

    @Test
    fun `turned on first, then a key from the phone, written once the key arrives`() = runTest {
        val parts = parts()
        settings.value = Settings(aiBlurbs = true)
        runCurrent()
        assertEquals(listOf("nightly"), schedule.calls.distinct())
        assertFalse("now" in schedule.calls)

        parts.controller.saveKey(KEY)
        runCurrent()

        assertEquals(1, schedule.calls.count { it == "now" })
    }

    @Test
    fun `already on with a key at start-up, kept scheduled, nothing sent`() = runTest {
        settings.value = Settings(aiBlurbs = true)
        AiKeyVault(File(dir, AiKeyVault.FILE), FakeBox(), StandardTestDispatcher(testScheduler)).save(KEY)

        parts()
        runCurrent()

        assertEquals(listOf("nightly"), schedule.calls)
    }

    @Test
    fun `turned off, cancelled, and the blurbs cleared at once`() = runTest {
        settings.value = Settings(aiBlurbs = true)
        parts().vault.save(KEY)
        runCurrent()
        cleared = 0

        settings.value = Settings(aiBlurbs = false)
        runCurrent()

        assertEquals("cancel", schedule.calls.last())
        assertEquals(1, cleared)
    }

    @Test
    fun `write now runs it now`() = runTest {
        val parts = parts()
        runCurrent()
        schedule.calls.clear()

        parts.controller.writeNow()

        assertEquals(listOf("now"), schedule.calls)
    }

    @Test
    fun `the status shows the key's last 4 characters and the last run, and forget key empties it`() = runTest {
        val parts = parts()
        assertEquals(AiBlurbsStatus(), parts.controller.status.first())

        parts.controller.saveKey(KEY)
        parts.runs.save(BlurbRun(now.minusHours(16).minusMinutes(13).toInstant().toEpochMilli(), 28, TokenUsage(5_030, 2_500)))
        assertEquals(
            AiBlurbsStatus(keyEnding = "wxYZ", lastRun = "Last night at 4:02 AM: 28 blurbs, 7,530 tokens, about $0.002"),
            parts.controller.status.first(),
        )
        assertEquals("wxYZ", parts.controller.keyEnding())

        parts.runs.save(BlurbRun(now.toInstant().toEpochMilli(), error = ClaudeBlurbs.KEY_REFUSED))
        assertEquals(true, parts.controller.status.first().failed)

        parts.controller.forgetKey()
        runCurrent()
        assertNull(parts.controller.status.first().keyEnding)
        assertNull(parts.vault.key())
    }

    @Test
    fun `a schedule that fails never throws, and off still clears the blurbs`() = runTest {
        val failing = object : BlurbSchedule {
            override fun nightly() = throw IllegalStateException("WorkManager is not initialized")
            override fun runNow() = throw IllegalStateException("WorkManager is not initialized")
            override fun cancel() = throw IllegalStateException("WorkManager is not initialized")
        }
        val vault = AiKeyVault(File(dir, AiKeyVault.FILE), FakeBox(), StandardTestDispatcher(testScheduler))
        val runs = BlurbRuns(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "ai_blurbs.preferences_pb") })
        val controller = StoredAiBlurbs(settings, vault, runs, failing, { cleared++ }, backgroundScope, clock = { now }, log = logs::add)
        runCurrent()
        assertEquals(1, cleared)

        controller.writeNow()
        settings.value = Settings(aiBlurbs = true)
        runCurrent()

        assertEquals(listOf("ai: IllegalStateException"), logs.distinct())
    }
}
