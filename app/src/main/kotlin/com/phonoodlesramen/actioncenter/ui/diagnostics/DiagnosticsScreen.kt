package com.phonoodlesramen.actioncenter.ui.diagnostics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.phonoodlesramen.actioncenter.R
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.handoff.ProbeResult
import com.phonoodlesramen.actioncenter.handoff.label
import com.phonoodlesramen.actioncenter.home.HomeState
import com.phonoodlesramen.actioncenter.safety.CrashRecord
import com.phonoodlesramen.actioncenter.safety.StartupMode
import com.phonoodlesramen.actioncenter.system.PackageState
import com.phonoodlesramen.actioncenter.system.WatchNextResult
import com.phonoodlesramen.actioncenter.tvprovider.TvProgram
import com.phonoodlesramen.actioncenter.tvprovider.TvProviderSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Phase 0's probe screen: the minimum that is safe to live on as the Shield's home.
 * It is the safe-mode screen and Settings › Diagnostics.
 *
 * @param focusResetKey changes when Home is pressed while already home: scroll to the
 *   top and focus the first action again.
 */
@Composable
fun DiagnosticsScreen(
    state: HomeState,
    now: ZonedDateTime,
    actions: DiagnosticsActions,
    modifier: Modifier = Modifier,
    focusResetKey: Int = 0,
) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Palette.Accent)) {
        Surface(
            modifier = modifier.fillMaxSize(),
            colors = SurfaceDefaults.colors(containerColor = Palette.Background, contentColor = Palette.Text),
        ) {
            val gridState = rememberLazyGridState()
            val firstFocus = remember { FocusRequester() }
            LaunchedEffect(focusResetKey) {
                gridState.scrollToItem(0)
                runCatching { firstFocus.requestFocus() }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(APP_COLUMNS),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                // TV safe area: 48 dp left/right, 27 dp top/bottom.
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 27.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                fullWidth("header") { Header(now) }
                if (state.mode == StartupMode.SAFE) {
                    fullWidth("safe-mode") { SafeModeBanner(state.lastCrash, now.zone, actions::leaveSafeMode) }
                }
                if (state.stockLauncherIsBack) fullWidth("stock-launcher") { StockLauncherBanner() }
                if (state.problems.isNotEmpty()) fullWidth("problems") { ProblemsBanner(state.problems) }
                fullWidth("actions") { QuickActions(actions, firstFocus) }
                fullWidth("apps-title") { SectionTitle("All apps", "${state.apps.size}") }
                items(state.apps, key = { "app:${it.packageName}/${it.activityName}" }) { app ->
                    AppTile(app, onClick = { actions.launchApp(app) })
                }
                fullWidth("device-title") { SectionTitle("Device check") }
                fullWidth("device") { DevicePanel(state, now.zone, actions::forceCrash) }
                fullWidth("probes") { ProbePanel(state.probes) }
                fullWidth("watch-next") { WatchNextPanel(state.watchNext) }
                state.tvProvider?.let { snapshot -> fullWidth("tv-provider") { TvProviderPanel(snapshot) } }
            }
        }
    }
}

private const val APP_COLUMNS = 5

private object Palette {
    val Background = Color(0xFF0F1720)
    val Panel = Color(0xFF1A2430)
    val PanelFocused = Color(0xFF2A3644)
    val Accent = Color(0xFF4FC3F7)
    val Text = Color(0xFFE6EDF3)
    val TextDim = Color(0xFF9AA7B4)
    val Good = Color(0xFF81C784)
    val Bad = Color(0xFFE57373)
    val SafeMode = Color(0xFFFFB74D)
    val OnSafeMode = Color(0xFF1F1300)
    val Warning = Color(0xFF5C1F1F)
}

private val TimeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val DateFormat = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val DayFormat = DateTimeFormatter.ofPattern("EEE d MMM")

private fun formatMoment(millis: Long, zone: ZoneId): String {
    val moment = Instant.ofEpochMilli(millis).atZone(zone)
    return "${moment.format(DayFormat)}, ${moment.format(TimeFormat)}"
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable LazyGridItemScope.() -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }, content = content)

@Composable
private fun Header(now: ZonedDateTime) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(now.format(TimeFormat), style = MaterialTheme.typography.headlineMedium)
            Text(now.format(DateFormat), style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
        }
    }
}

@Composable
private fun Banner(container: Color, content: Color, body: @Composable RowScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = SurfaceDefaults.colors(containerColor = container, contentColor = content),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            content = body,
        )
    }
}

@Composable
private fun SafeModeBanner(crash: CrashRecord?, zone: ZoneId, onLeave: () -> Unit) {
    Banner(Palette.SafeMode, Palette.OnSafeMode) {
        Column(Modifier.weight(1f)) {
            Text("Safe mode", style = MaterialTheme.typography.titleLarge)
            Text(
                text = crash?.let { "The home screen crashed (${formatMoment(it.timeMillis, zone)}): ${it.summary}" }
                    ?: "The home screen crashed last time.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(
            onClick = onLeave,
            colors = ButtonDefaults.colors(
                containerColor = Palette.OnSafeMode,
                contentColor = Palette.SafeMode,
                focusedContainerColor = Palette.Text,
                focusedContentColor = Palette.OnSafeMode,
            ),
        ) { Text("Leave safe mode") }
    }
}

@Composable
private fun StockLauncherBanner() {
    Banner(Palette.Warning, Palette.Text) {
        Column {
            Text("The stock launcher is enabled", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "So Home opens it instead of Action Center: it isn't set up yet, or an update or OTA " +
                    "re-enabled com.google.android.tvlauncher. Run scripts/set-default-home.ps1.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ProblemsBanner(problems: List<String>) {
    Banner(Palette.Warning, Palette.Text) {
        Column {
            Text("Some checks failed", style = MaterialTheme.typography.titleLarge)
            problems.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun QuickActions(actions: DiagnosticsActions, firstFocus: FocusRequester) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = actions::openSettings, modifier = Modifier.focusRequester(firstFocus)) {
            Text("Settings")
        }
        Button(onClick = actions::playTestTrailer) { Text("Play test trailer") }
        Button(onClick = actions::openPlezy) { Text("Open Plezy") }
        Button(onClick = actions::openPlex) { Text("Open Plex") }
        Button(onClick = actions::openPlaybackCheck) { Text("Playback check") }
        Button(onClick = actions::openSearchCheck) { Text("Search check") }
    }
}

@Composable
private fun SectionTitle(title: String, detail: String? = null) {
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        detail?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Palette.TextDim) }
    }
}

@Composable
private fun panelColors() = ClickableSurfaceDefaults.colors(
    containerColor = Palette.Panel,
    contentColor = Palette.Text,
    focusedContainerColor = Palette.PanelFocused,
    focusedContentColor = Palette.Text,
)

@Composable
private fun focusBorder() = ClickableSurfaceDefaults.border(
    focusedBorder = Border(BorderStroke(2.dp, Palette.Accent)),
)

@Composable
private fun AppTile(app: LaunchableApp, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(72.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = panelColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = focusBorder(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A focusable panel, so the D-pad can scroll down to it. */
@Composable
private fun Panel(
    title: String,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        onClick = {},
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = panelColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = focusBorder(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, labelColor: Color = Palette.TextDim) {
    Row {
        Text(label, modifier = Modifier.width(150.dp), style = MaterialTheme.typography.bodyMedium, color = labelColor)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DevicePanel(state: HomeState, zone: ZoneId, onForceCrash: () -> Unit) {
    Panel("This Shield", onLongClick = onForceCrash) {
        val device = state.device
        if (device == null) {
            InfoLine("Device", "not read yet")
        } else {
            InfoLine("Model", device.model)
            InfoLine("Android", "${device.androidRelease} (API ${device.sdkInt})")
            InfoLine("Build", "${device.buildDisplay} (${device.buildIncremental})")
            InfoLine("Display", "${device.displayPx}, ${device.densityDpi} dpi, ${device.screenDp}")
            InfoLine(
                "Memory",
                "${device.totalRamMb} MB total, ${device.memoryClassMb} MB per app" +
                    if (device.lowRamDevice) ", low-RAM device" else "",
            )
        }
        InfoLine("Stock launcher", state.stockLauncher?.let(::describe) ?: "unknown")
        InfoLine("Startup", if (state.mode == StartupMode.SAFE) "safe mode" else "normal")
        InfoLine("Opened by", state.openedBy?.let { "${it.description} (${formatMoment(it.atMillis, zone)})" } ?: "unknown")
        InfoLine("Last key", state.lastKey ?: "none yet: press a remote button")
        Text(
            text = "Hold OK on this panel to force a test crash (checks safe mode).",
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextDim,
        )
    }
}

private fun describe(state: PackageState) = when (state) {
    PackageState.ENABLED -> "enabled (Home may open it)"
    PackageState.DISABLED -> "disabled"
    PackageState.MISSING -> "not installed"
}

@Composable
private fun ProbePanel(probes: List<ProbeResult>) {
    Panel("Handoff probes") {
        if (probes.isEmpty()) InfoLine("Status", "not run yet")
        probes.forEach { probe ->
            InfoLine(
                label = if (probe.resolves) "handled" else "not handled",
                value = probe.spec.label(),
                labelColor = if (probe.resolves) Palette.Good else Palette.Bad,
            )
        }
    }
}

@Composable
private fun WatchNextPanel(result: WatchNextResult?) {
    Panel("Watch Next probe") {
        when (result) {
            null -> InfoLine("Status", "not run yet")
            WatchNextResult.NotGranted -> {
                InfoLine("Status", "READ_TV_LISTINGS not granted")
                Text(
                    text = "Allow it in Settings › Setup, or with scripts/grant-permissions.ps1, then press Home.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextDim,
                )
            }
            is WatchNextResult.Failed -> InfoLine("Status", "failed: ${result.reason}")
            is WatchNextResult.Counts -> {
                InfoLine("Watch Next", result.watchNext.formatCounts())
                InfoLine("Preview rows", result.preview.formatCounts())
            }
        }
    }
}

/** Per app: its home-screen channels and how many rows it published. Opening Device check logs every row. */
@Composable
private fun TvProviderPanel(snapshot: TvProviderSnapshot) {
    Panel("Other apps' rows (opening Device check logs each one)") {
        val apps = (snapshot.channels.map { it.packageName } + snapshot.programs.map { it.packageName }).distinct().sorted()
        if (apps.isEmpty()) InfoLine("Status", "none")
        apps.forEach { pkg ->
            val channels = snapshot.channels.filter { it.packageName == pkg }.joinToString { it.displayName.ifBlank { "#${it.id}" } }
            val watchNext = snapshot.programs.count { it.packageName == pkg && it.kind == TvProgram.Kind.WATCH_NEXT }
            val preview = snapshot.programs.count { it.packageName == pkg && it.kind == TvProgram.Kind.PREVIEW }
            InfoLine(pkg, "watch next $watchNext · preview $preview" + if (channels.isEmpty()) "" else " · channels: $channels")
        }
    }
}

private fun Map<String, Int>.formatCounts() =
    if (isEmpty()) "none" else entries.joinToString { "${it.key} (${it.value})" }

@Preview(device = Devices.TV_1080p)
@Composable
private fun DiagnosticsScreenPreview() {
    DiagnosticsScreen(SampleDiagnosticsState.state, SampleDiagnosticsState.now, DiagnosticsActions.None)
}
