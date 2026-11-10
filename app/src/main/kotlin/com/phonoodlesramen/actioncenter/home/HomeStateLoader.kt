package com.phonoodlesramen.actioncenter.home

import com.phonoodlesramen.actioncenter.apps.LaunchableAppsRepository
import com.phonoodlesramen.actioncenter.handoff.Handoffs
import com.phonoodlesramen.actioncenter.handoff.IntentProbe
import com.phonoodlesramen.actioncenter.handoff.PlayIn
import com.phonoodlesramen.actioncenter.safety.CrashGuard
import com.phonoodlesramen.actioncenter.safety.StartupMode
import com.phonoodlesramen.actioncenter.safety.summary
import com.phonoodlesramen.actioncenter.system.DeviceInfoSource
import com.phonoodlesramen.actioncenter.system.StockLauncherMonitor
import com.phonoodlesramen.actioncenter.system.WatchNextProbe
import com.phonoodlesramen.actioncenter.tvprovider.TvProviderReader

/**
 * Builds the home screen's state. It never throws: a failing section is listed in
 * [HomeState.problems] and the rest still loads, so safe mode can't crash-loop on
 * the same fault.
 */
class HomeStateLoader(
    private val crashGuard: CrashGuard,
    private val stockLauncher: StockLauncherMonitor,
    private val apps: LaunchableAppsRepository,
    private val intentProbe: IntentProbe,
    private val device: DeviceInfoSource,
    private val watchNext: WatchNextProbe,
    private val tvProvider: TvProviderReader? = null,
) {
    fun load(): HomeState {
        val problems = mutableListOf<String>()
        fun <T> section(name: String, fallback: T, read: () -> T): T = try {
            read()
        } catch (e: Exception) {
            problems += "$name: ${e.summary()}"
            fallback
        }

        return HomeState(
            // If we can't tell, assume the worst and stay minimal.
            mode = section("Crash guard", StartupMode.SAFE) { crashGuard.startupMode() },
            lastCrash = section("Crash guard", null) { crashGuard.lastCrash() },
            stockLauncher = section("Stock launcher", null) { stockLauncher.state() },
            apps = section("All apps", emptyList()) { apps.apps() },
            probes = section("Handoff probes", emptyList()) { intentProbe.probe(PROBE_CANDIDATES) },
            device = section("Device", null) { device.read() },
            watchNext = section("Watch Next", null) { watchNext.probe() },
            tvProvider = section("TV provider", null) { tvProvider?.takeIf { it.canRead() }?.read() },
            problems = problems.distinct(),
        )
    }

    companion object {
        /** A long-lived public video, used only to check which app opens trailers. */
        const val TEST_TRAILER_ID = "dQw4w9WgXcQ"

        /** Placeholder ids: the screen only checks which apps handle each handoff, Plezy's and the Plex app's (Phase 11). */
        val PROBE_CANDIDATES = (
            Handoffs.trailer(TEST_TRAILER_ID) +
                Handoffs.play(PlayIn.PLEZY, serverId = "probe", ratingKey = "0") +
                Handoffs.play(PlayIn.PLEX, serverId = "probe", ratingKey = "0")
            ).distinct()
    }
}
