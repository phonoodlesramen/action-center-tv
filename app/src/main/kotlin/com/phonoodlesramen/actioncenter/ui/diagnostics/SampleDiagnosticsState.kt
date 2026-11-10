package com.phonoodlesramen.actioncenter.ui.diagnostics

import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.handoff.ProbeResult
import com.phonoodlesramen.actioncenter.home.HomeState
import com.phonoodlesramen.actioncenter.home.HomeStateLoader
import com.phonoodlesramen.actioncenter.home.OpenedBy
import com.phonoodlesramen.actioncenter.system.DeviceInfo
import com.phonoodlesramen.actioncenter.system.PackageState
import com.phonoodlesramen.actioncenter.system.WatchNextResult
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Made-up data for previews and screenshot tests. Not real device values. */
internal object SampleDiagnosticsState {
    val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 26, 20, 30, 0, 0, ZoneOffset.UTC)

    val state = HomeState(
        stockLauncher = PackageState.DISABLED,
        apps = listOf(
            app("com.disney.disneyplus", "Disney+"),
            app("org.xbmc.kodi", "Kodi"),
            app("com.netflix.ninja", "Netflix"),
            app("com.plexapp.android", "Plex"),
            app("com.edde746.plezy", "Plezy"),
            app("com.amazon.amazonvideo.livingroom", "Prime Video"),
            app("com.spotify.tv.android", "Spotify"),
            app("tv.twitch.android.app", "Twitch"),
            app("com.google.android.youtube.tv", "YouTube"),
        ),
        probes = HomeStateLoader.PROBE_CANDIDATES.mapIndexed { i, spec -> ProbeResult(spec, resolves = i != 1) },
        device = DeviceInfo(
            model = "NVIDIA SHIELD Android TV",
            androidRelease = "11",
            sdkInt = 30,
            buildDisplay = "RQ1A.210105.003",
            buildIncremental = "sample",
            displayPx = "1920×1080 @ 60 Hz",
            densityDpi = 320,
            screenDp = "960×540 dp",
            totalRamMb = 2_900,
            memoryClassMb = 256,
            lowRamDevice = false,
        ),
        watchNext = WatchNextResult.Counts(
            watchNext = mapOf("com.edde746.plezy" to 4, "com.google.android.youtube.tv" to 2),
            preview = mapOf("com.netflix.ninja" to 12),
        ),
        openedBy = OpenedBy("MAIN + HOME", now.toInstant().toEpochMilli()),
        lastKey = "DPAD_CENTER",
    )

    private fun app(packageName: String, label: String) =
        LaunchableApp(packageName, "$packageName.MainActivity", label)
}
