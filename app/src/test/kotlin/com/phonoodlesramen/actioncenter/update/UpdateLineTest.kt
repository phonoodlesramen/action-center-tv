package com.phonoodlesramen.actioncenter.update

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/** About's line: what the updater found, and what it's doing. */
class UpdateLineTest {
    private val zone = ZoneOffset.UTC

    /** 11/11/2026 15:10 UTC. */
    private val now = 1_794_409_800_000L
    private val info = UpdateInfo(1, 200, "1.1.0", "v1.1.0", "app-release.apk", 53_572_870, "c".repeat(64), "a".repeat(64))

    private fun line(status: UpdateStatus) = UpdateLine.of(status, now, zone)

    @Test
    fun `a build that doesn't update says why`() {
        assertEquals(UpdateLine("Debug builds don't update themselves."), line(UpdateStatus(enabled = false, debugBuild = true)))
        assertEquals(
            "This build doesn't check for updates: it was built without a repository to check.",
            line(UpdateStatus(enabled = false)).text,
        )
    }

    @Test
    fun `what it found`() {
        assertEquals("Not checked yet. It checks once a day.", line(UpdateStatus()).text)
        assertEquals("Not checked yet. It checks only when you ask.", line(UpdateStatus(mode = UpdateMode.OFF)).text)
        assertEquals("Up to date · checked 2 h ago", line(UpdateStatus(checkedMillis = now - 2 * HOUR)).text)
        assertEquals("1.1.0 is available · 51 MB", line(UpdateStatus(available = info)).text)
        assertEquals("1.1.0 is ready to install", line(UpdateStatus(available = info, ready = true)).text)
    }

    @Test
    fun `what it's doing wins, then a problem, in the caution colour with its time`() {
        assertEquals("Checking for updates…", line(UpdateStatus(work = UpdateWork.Checking)).text)
        assertEquals("Downloading 1.1.0… 34%", line(UpdateStatus(available = info, work = UpdateWork.Downloading(34))).text)
        assertEquals("Checking the download…", line(UpdateStatus(available = info, work = UpdateWork.Verifying)).text)
        assertEquals("Installing 1.1.0…", line(UpdateStatus(available = info, work = UpdateWork.Installing)).text)
        assertEquals(
            UpdateLine("Couldn't reach GitHub (3:10 PM)", caution = true),
            line(UpdateStatus(available = info, problem = "Couldn't reach GitHub", problemMillis = now)),
        )
    }

    @Test
    fun `when it last checked`() {
        assertEquals("just now", UpdateLine.ago(now - 30_000, now, zone))
        assertEquals("12 min ago", UpdateLine.ago(now - 12 * MINUTE, now, zone))
        assertEquals("23 h ago", UpdateLine.ago(now - 23 * HOUR, now, zone))
        assertEquals("on Nov 9", UpdateLine.ago(now - 2 * 24 * HOUR, now, zone))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
