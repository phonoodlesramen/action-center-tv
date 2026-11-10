package com.phonoodlesramen.actioncenter.home

import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.apps.LaunchableAppsRepository
import com.phonoodlesramen.actioncenter.handoff.Handoffs
import com.phonoodlesramen.actioncenter.handoff.IntentProbe
import com.phonoodlesramen.actioncenter.handoff.PlayIn
import com.phonoodlesramen.actioncenter.safety.CrashGuard
import com.phonoodlesramen.actioncenter.safety.CrashRecord
import com.phonoodlesramen.actioncenter.safety.CrashStore
import com.phonoodlesramen.actioncenter.safety.InMemoryCrashStore
import com.phonoodlesramen.actioncenter.safety.StartupMode
import com.phonoodlesramen.actioncenter.system.DeviceInfo
import com.phonoodlesramen.actioncenter.system.PackageState
import com.phonoodlesramen.actioncenter.system.StockLauncherMonitor
import com.phonoodlesramen.actioncenter.system.TvProgramsReader
import com.phonoodlesramen.actioncenter.system.WatchNextProbe
import com.phonoodlesramen.actioncenter.system.WatchNextResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStateLoaderTest {
    private val crashStore = InMemoryCrashStore()
    private var stockLauncher = PackageState.DISABLED
    private var apps: () -> List<LaunchableApp> = { listOf(LaunchableApp("a.pkg", "a.pkg.Main", "Plezy")) }

    private fun loader(store: CrashStore = crashStore) = HomeStateLoader(
        crashGuard = CrashGuard(store),
        stockLauncher = StockLauncherMonitor { stockLauncher },
        apps = LaunchableAppsRepository(source = { apps() }, ownPackage = "own.pkg"),
        intentProbe = IntentProbe { true },
        device = { DEVICE },
        watchNext = WatchNextProbe(NoTvListings),
    )

    @Test
    fun `a normal start has no banners`() {
        val state = loader().load()

        assertEquals(StartupMode.NORMAL, state.mode)
        assertFalse(state.stockLauncherIsBack)
        assertTrue(state.problems.isEmpty())
        assertEquals(listOf("Plezy"), state.apps.map { it.label })
        assertEquals(DEVICE, state.device)
        assertEquals(WatchNextResult.NotGranted, state.watchNext)
    }

    @Test
    fun `after a crash it loads safe mode with the crash`() {
        crashStore.write(CrashRecord(42L, "IllegalStateException: boom"))

        val state = loader().load()

        assertEquals(StartupMode.SAFE, state.mode)
        assertEquals(CrashRecord(42L, "IllegalStateException: boom"), state.lastCrash)
    }

    @Test
    fun `flags the stock launcher when it is back`() {
        stockLauncher = PackageState.ENABLED

        assertTrue(loader().load().stockLauncherIsBack)
    }

    @Test
    fun `probes the trailer and play handoffs in order`() {
        val state = loader().load()

        assertEquals(HomeStateLoader.PROBE_CANDIDATES, state.probes.map { it.spec })
        assertEquals(
            (
                Handoffs.trailer(HomeStateLoader.TEST_TRAILER_ID) +
                    Handoffs.play(PlayIn.PLEZY, "probe", "0") + Handoffs.play(PlayIn.PLEX, "probe", "0")
                ).distinct(),
            HomeStateLoader.PROBE_CANDIDATES,
        )
    }

    @Test
    fun `a failing section is reported and the rest still loads`() {
        apps = { throw IllegalStateException("boom") }

        val state = loader().load()

        assertEquals(listOf("All apps: IllegalStateException: boom"), state.problems)
        assertTrue(state.apps.isEmpty())
        assertNotNull(state.device)
    }

    @Test
    fun `it never throws, and a broken crash store means safe mode`() {
        val brokenStore = object : CrashStore {
            override fun read(): CrashRecord? = throw IllegalStateException("prefs gone")
            override fun write(record: CrashRecord) = Unit
            override fun clear() = Unit
        }
        stockLauncher = PackageState.ENABLED
        apps = { throw IllegalStateException("boom") }

        val state = loader(brokenStore).load()

        assertEquals(StartupMode.SAFE, state.mode)
        assertTrue(state.problems.any { it.startsWith("Crash guard:") })
        assertTrue(state.problems.any { it.startsWith("All apps:") })
    }

    private object NoTvListings : TvProgramsReader {
        override fun canRead() = false
        override fun watchNextPackages() = emptyList<String>()
        override fun previewPackages() = emptyList<String>()
    }

    private companion object {
        val DEVICE = DeviceInfo(
            model = "NVIDIA SHIELD Android TV",
            androidRelease = "11",
            sdkInt = 30,
            buildDisplay = "RQ1A.210105.003",
            buildIncremental = "1",
            displayPx = "1920×1080 @ 60 Hz",
            densityDpi = 320,
            screenDp = "960×540 dp",
            totalRamMb = 2_900,
            memoryClassMb = 256,
            lowRamDevice = false,
        )
    }
}
