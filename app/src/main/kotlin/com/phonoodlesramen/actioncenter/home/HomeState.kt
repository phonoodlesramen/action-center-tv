package com.phonoodlesramen.actioncenter.home

import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.handoff.ProbeResult
import com.phonoodlesramen.actioncenter.handoff.label
import com.phonoodlesramen.actioncenter.safety.CrashRecord
import com.phonoodlesramen.actioncenter.safety.StartupMode
import com.phonoodlesramen.actioncenter.system.DeviceInfo
import com.phonoodlesramen.actioncenter.system.PackageState
import com.phonoodlesramen.actioncenter.system.WatchNextResult
import com.phonoodlesramen.actioncenter.tvprovider.TvProviderSnapshot

/** What started the home activity, e.g. "MAIN + HOME" or "ALL_APPS" (long-press Home). */
data class OpenedBy(val description: String, val atMillis: Long)

/** Everything the probe/safe-mode home screen shows. Null means "not read yet". */
data class HomeState(
    val mode: StartupMode = StartupMode.NORMAL,
    val lastCrash: CrashRecord? = null,
    val stockLauncher: PackageState? = null,
    val apps: List<LaunchableApp> = emptyList(),
    val probes: List<ProbeResult> = emptyList(),
    val device: DeviceInfo? = null,
    val watchNext: WatchNextResult? = null,
    /** Other apps' channels and programs, for the Diagnostics dump. Null until read or without the permission. */
    val tvProvider: TvProviderSnapshot? = null,
    val openedBy: OpenedBy? = null,
    /** Last remote button the app received, e.g. "MENU" (Home never reaches apps). */
    val lastKey: String? = null,
    /** Sections that failed to load, as "Section: Exception: message". */
    val problems: List<String> = emptyList(),
) {
    val stockLauncherIsBack: Boolean get() = stockLauncher == PackageState.ENABLED
}

/** One logcat line per finding, so scripts/device-report.ps1 can collect them. */
fun HomeState.reportLines(): List<String> = buildList {
    add("mode=$mode stockLauncher=$stockLauncher apps=${apps.size}")
    lastCrash?.let { add("lastCrash=${it.summary}") }
    probes.forEach { add("probe resolves=${it.resolves} ${it.spec.label()}") }
    add(
        when (val result = watchNext) {
            null -> "watchNext=unknown"
            WatchNextResult.NotGranted -> "watchNext=not granted"
            is WatchNextResult.Failed -> "watchNext=failed ${result.reason}"
            is WatchNextResult.Counts -> "watchNext=${result.watchNext} preview=${result.preview}"
        },
    )
    device?.let { add("device=$it") }
    problems.forEach { add("problem $it") }
}
