package com.phonoodlesramen.actioncenter.update

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [Updater]: a check finds a newer release; only Install downloads, checks and hands the APK to
 * Android; every failure is About's line, never a crash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdaterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val key = "a".repeat(64)
    private val mine = ApkFacts(PACKAGE, 100, setOf(key), "1.0.0")
    private val newer = UpdateInfo(1, 200, "1.1.0", "v1.1.0", "app-release.apk", 3, "c".repeat(64), key, notesUrl = NOTES)

    private class FakeFeed : UpdateFeed {
        var latest: ReleaseFile = ReleaseFile.None
        var downloadFails: String? = null
        var throws = false
        var downloads = 0

        override suspend fun latest(): ReleaseFile = if (throws) error("feed broke") else latest

        override suspend fun download(info: UpdateInfo, dest: File, progress: (Int) -> Unit): String? {
            downloads++
            downloadFails?.let { return it }
            dest.parentFile?.mkdirs()
            dest.writeBytes(byteArrayOf(1, 2, 3))
            progress(100)
            return null
        }
    }

    private inner class FakeInspector : ApkInspector {
        var installed = mine
        var archive: ApkFacts? = mine.copy(versionCode = 200, versionName = "1.1.0")
        override fun archive(file: File) = archive?.takeIf { file.exists() }
        override fun installed() = installed
    }

    private class FakeInstaller : UpdateInstaller {
        val handed = mutableListOf<File>()
        val byPress = mutableListOf<Boolean>()
        var fails: String? = null
        override suspend fun install(apk: File, byPress: Boolean): String? = fails ?: null.also {
            handed += apk
            this.byPress += byPress
        }
    }

    private class FakeSchedule : UpdateSchedule {
        var daily = 0
        var cancelled = 0
        var downloads = 0
        override fun daily() {
            daily++
        }

        override fun cancel() {
            cancelled++
        }

        override fun downloadSoon() {
            downloads++
        }
    }

    private val feed = FakeFeed()
    private val inspector = FakeInspector()
    private val installer = FakeInstaller()
    private val schedule = FakeSchedule()
    private val store = UpdateStore.InMemory()
    private val dir by lazy { File(tmp.root, "updates") }
    private var canInstall = true
    private var free = Long.MAX_VALUE
    private var now = 1_000L
    private var idle = IdleSnapshot()

    private fun TestScope.updater(config: UpdateConfig = UpdateConfig("someone/action-center-tv"), sdk: Int = 30) = Updater(
        config = config,
        feed = feed,
        inspector = inspector,
        installer = installer,
        store = store,
        dir = dir,
        canInstall = { canInstall },
        schedule = schedule,
        scope = backgroundScope,
        clock = { now },
        sdk = sdk,
        freeBytes = { free },
        idle = { idle },
        io = UnconfinedTestDispatcher(testScheduler),
    )

    private fun TestScope.status(updater: Updater): UpdateStatus {
        runCurrent()
        return updater.status.value
    }

    @Test
    fun `a check that finds a newer release offers it, one that isn't newer doesn't`() = runTest {
        val updater = updater()
        feed.latest = ReleaseFile.Found(newer)
        updater.check()
        assertEquals(newer, status(updater).available)
        assertEquals(1_000L, status(updater).checkedMillis)
        assertFalse(status(updater).ready)

        feed.latest = ReleaseFile.Found(newer.copy(versionCode = 100, versionName = "1.0.0"))
        now = 2_000L
        updater.check()
        assertNull(status(updater).available)
        assertEquals(2_000L, status(updater).checkedMillis)
        assertEquals(0, feed.downloads)
    }

    @Test
    fun `a release with no update json is up to date, a failure says why until a check works`() = runTest {
        val updater = updater()
        feed.latest = ReleaseFile.Failed("Couldn't reach GitHub")
        updater.check()
        assertEquals("Couldn't reach GitHub", status(updater).problem)
        assertNull(status(updater).checkedMillis)

        feed.latest = ReleaseFile.None
        updater.check()
        assertNull(status(updater).problem)
        assertNull(status(updater).available)
        assertEquals(1_000L, status(updater).checkedMillis)

        feed.latest = ReleaseFile.TooNew
        updater.check()
        assertEquals("The latest release needs a newer Action Center: install it by hand", status(updater).problem)
    }

    @Test
    fun `Install downloads, checks, and hands the file to Android's installer`() = runTest {
        val updater = updater()
        feed.latest = ReleaseFile.Found(newer)
        updater.check()
        updater.install()
        runCurrent()
        assertEquals(listOf(File(dir, "200.apk")), installer.handed)
        assertEquals(UpdateWork.Installing, status(updater).work)
        assertTrue(status(updater).ready)
        assertNull(status(updater).problem)

        // Android's dialog was cancelled: it's ready to install again, and isn't downloaded again.
        updater.installerSaid(InstallerAnswer.Cancelled)
        assertEquals(UpdateWork.Idle, status(updater).work)
        updater.install()
        runCurrent()
        assertEquals(1, feed.downloads)
        assertEquals(2, installer.handed.size)
    }

    @Test
    fun `a second press while Android's installer has it starts nothing`() = runTest {
        val updater = updater()
        feed.latest = ReleaseFile.Found(newer)
        updater.check()
        updater.install()
        runCurrent()
        updater.install()
        runCurrent()
        assertEquals(1, installer.handed.size)
    }

    @Test
    fun `when Android's installer says nothing, it stops saying Installing`() = runTest {
        val updater = updater()
        feed.latest = ReleaseFile.Found(newer)
        updater.check()
        updater.install()
        runCurrent()
        assertEquals(UpdateWork.Installing, status(updater).work)
        advanceTimeBy(2 * 60_000L + 1)
        assertEquals(UpdateWork.Idle, status(updater).work)
    }

    @Test
    fun `a download that isn't this app, newer and signed with its key is deleted, never installed`() = runTest {
        val updater = updater()
        feed.latest = ReleaseFile.Found(newer)
        updater.check()
        inspector.archive = mine.copy(versionCode = 200, signers = setOf("b".repeat(64)))
        updater.install()
        runCurrent()
        assertEquals("The download isn't signed with this copy's key, so it was deleted", status(updater).problem)
        assertFalse(File(dir, "200.apk").exists())
        assertFalse(status(updater).ready)
        assertTrue(installer.handed.isEmpty())
    }

    @Test
    fun `a failed download, no room, no permission or too old an Android installs nothing, and says why`() = runTest {
        val updater = updater()
        feed.latest = ReleaseFile.Found(newer)
        updater.check()

        canInstall = false
        updater.install()
        runCurrent()
        assertEquals("Android needs Install unknown apps turned on for Action Center first", status(updater).problem)
        assertEquals(0, feed.downloads)

        canInstall = true
        free = 5
        updater.install()
        runCurrent()
        assertEquals("There isn't room on this TV for the 0 MB update", status(updater).problem)
        assertEquals(0, feed.downloads)

        free = Long.MAX_VALUE
        feed.downloadFails = "The download stopped short"
        updater.install()
        runCurrent()
        assertEquals("The download stopped short", status(updater).problem)

        feed.latest = ReleaseFile.Found(newer.copy(minSdk = 31))
        updater.check()
        updater.install()
        runCurrent()
        assertEquals("1.1.0 needs a newer Android than this TV's", status(updater).problem)
        assertTrue(installer.handed.isEmpty())
    }

    @Test
    fun `Android's installer failing says so`() = runTest {
        val updater = updater()
        feed.latest = ReleaseFile.Found(newer)
        updater.check()
        installer.fails = "Android didn't allow the install (no)"
        updater.install()
        runCurrent()
        assertEquals("Android didn't allow the install (no)", status(updater).problem)
        assertEquals(UpdateWork.Idle, status(updater).work)

        updater.installerSaid(InstallerAnswer.Failed("INSTALL_FAILED_INSUFFICIENT_STORAGE"))
        assertEquals("Android didn't install it: INSTALL_FAILED_INSUFFICIENT_STORAGE", status(updater).problem)
    }

    @Test
    fun `nothing it calls can make it throw`() = runTest {
        val updater = updater()
        feed.throws = true
        updater.check()
        assertEquals("Something went wrong: feed broke", status(updater).problem)
        assertEquals(UpdateWork.Idle, status(updater).work)
    }

    @Test
    fun `a newer version says Updated to once, and tidies what it downloaded`() = runTest {
        dir.mkdirs()
        File(dir, "200.apk").writeBytes(byteArrayOf(1))
        store.update { it.copy(seenVersionCode = 100, latest = newer.encode(), downloaded = 200, problem = "old") }
        inspector.installed = mine.copy(versionCode = 200, versionName = "1.1.0")

        val updater = updater()
        assertEquals("Updated to 1.1.0", updater.started())
        assertNull(updater.started())
        val record = store.record.first()
        assertEquals(200L, record.seenVersionCode)
        assertNull(record.downloaded)
        assertNull(record.problem)
        assertEquals(listOf<String>(), dir.list()!!.toList())
        assertNull(status(updater).available)
    }

    @Test
    fun `the first start, or the same version again, says nothing`() = runTest {
        assertNull(updater().started())
        assertEquals(100L, store.record.first().seenVersionCode)
        assertNull(updater().started())
    }

    @Test
    fun `Ask me keeps the daily check, and Off cancels it`() = runTest {
        val updater = updater()
        updater.started()
        assertEquals(1, schedule.daily)
        updater.setMode(UpdateMode.OFF)
        runCurrent()
        assertEquals(1, schedule.cancelled)
        assertEquals(UpdateMode.OFF, status(updater).mode)
        updater.setMode(UpdateMode.ASK)
        runCurrent()
        assertEquals(2, schedule.daily)
    }

    @Test
    fun `a build that doesn't update never checks, schedules or installs`() = runTest {
        val updater = updater(UpdateConfig("", debugBuild = true))
        feed.latest = ReleaseFile.Found(newer)
        updater.check()
        updater.install()
        assertNull(updater.started())
        updater.setMode(UpdateMode.OFF)
        runCurrent()
        val status = status(updater)
        assertFalse(status.enabled)
        assertTrue(status.debugBuild)
        assertNull(status.available)
        assertEquals(0, schedule.daily + schedule.cancelled)
        assertEquals(0, feed.downloads)
    }

    @Test
    fun `Install automatically has a newer release downloaded, Ask me doesn't`() = runTest {
        val updater = updater()
        feed.latest = ReleaseFile.Found(newer)
        updater.check()
        assertEquals(0, schedule.downloads)

        store.setMode(UpdateMode.AUTOMATIC)
        updater.check()
        assertEquals(1, schedule.downloads)
    }

    @Test
    fun `on Android 11, Install automatically downloads and checks it, then waits for a press`() = runTest {
        store.setMode(UpdateMode.AUTOMATIC)
        val updater = updater(sdk = 30)
        feed.latest = ReleaseFile.Found(newer)
        updater.check()
        idle = IdleSnapshot(screenOff = true)
        updater.downloadAhead()
        assertTrue(status(updater).ready)
        assertTrue(installer.handed.isEmpty())
        assertEquals(UpdateChip.Ready("1.1.0"), UpdateChip.of(status(updater), kid = false))

        // The chip installs it: Android's dialog comes next.
        updater.chipPressed(openAbout = { error("not About") })
        runCurrent()
        assertEquals(listOf(true), installer.byPress)
    }

    @Test
    fun `on Android 12, a downloaded update installs by itself once the TV is idle, and only then`() = runTest {
        store.setMode(UpdateMode.AUTOMATIC)
        val updater = updater(sdk = 31)
        feed.latest = ReleaseFile.Found(newer)
        updater.check()
        idle = IdleSnapshot(homeInFront = true, lastTouchMillis = now)
        updater.downloadAhead()
        assertTrue(status(updater).ready)
        assertTrue(installer.handed.isEmpty())
        assertEquals("1.1.0 is ready, and installs once the TV is idle", UpdateLine.of(status(updater), now, java.time.ZoneOffset.UTC).text)

        // The minute's look: untouched for 30 minutes now, and nothing playing.
        updater.started()
        now += IdleRule.UNTOUCHED_MILLIS
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(listOf(false), installer.byPress)
    }

    @Test
    fun `never while anything plays, and never twice for one version`() = runTest {
        store.setMode(UpdateMode.AUTOMATIC)
        val updater = updater(sdk = 31)
        feed.latest = ReleaseFile.Found(newer)
        updater.check()
        idle = IdleSnapshot(screenOff = true, playing = true)
        updater.downloadAhead()
        assertTrue(installer.handed.isEmpty())

        idle = IdleSnapshot(screenOff = true)
        updater.started()
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(1, installer.handed.size)

        // Android wanted a press after all: it waits for one, and never tries by itself again.
        updater.installerSaid(InstallerAnswer.NeedsPress)
        runCurrent()
        assertEquals(UpdateWork.Idle, status(updater).work)
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(1, installer.handed.size)
        assertEquals(UpdateChip.Ready("1.1.0"), UpdateChip.of(status(updater), kid = false))
    }

    @Test
    fun `the chip opens About until it's downloaded, and a kid never sees it`() = runTest {
        val updater = updater()
        feed.latest = ReleaseFile.Found(newer)
        updater.check()
        assertEquals(UpdateChip.Available("1.1.0"), UpdateChip.of(status(updater), kid = false))
        assertNull(UpdateChip.of(status(updater), kid = true))
        var about = 0
        updater.chipPressed(openAbout = { about++ })
        assertEquals(1, about)
        assertEquals(0, feed.downloads)
    }

    @Test
    fun `turning Install automatically on with an update waiting downloads it`() = runTest {
        val updater = updater()
        feed.latest = ReleaseFile.Found(newer)
        updater.check()
        updater.setMode(UpdateMode.AUTOMATIC)
        runCurrent()
        assertEquals(1, schedule.downloads)
    }

    private companion object {
        const val PACKAGE = "com.phonoodlesramen.actioncenter"
        const val NOTES = "https://github.com/someone/action-center-tv/releases/tag/v1.1.0"
    }
}
