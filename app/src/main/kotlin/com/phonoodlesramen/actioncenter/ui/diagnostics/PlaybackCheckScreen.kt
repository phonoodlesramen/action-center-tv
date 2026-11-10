package com.phonoodlesramen.actioncenter.ui.diagnostics

import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.player.CheckCase
import com.phonoodlesramen.actioncenter.player.CheckResult
import com.phonoodlesramen.actioncenter.player.PlaybackCheckState
import com.phonoodlesramen.actioncenter.player.PlaybackCheckState.Phase
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.safeArea

/**
 * Diagnostics › Playback check (Phase 2.6): plays a few of your titles from their middle,
 * as a guide channel would, and shows what worked. The picture plays in the box on the
 * left; Back stops it.
 *
 * @param surface receives the video surface the check plays into.
 */
@Composable
fun PlaybackCheckScreen(
    state: PlaybackCheckState,
    onStart: () -> Unit,
    surface: (SurfaceView) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { start.requestFocus() } }
    Column(modifier.fillMaxSize().background(Palette.Background).safeArea(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Playback check", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Plays your largest MKV and titles with HEVC 10-bit, HDR10, Dolby Vision, DD+, TrueHD and DTS from their middle, " +
                "the way a channel joins a program, then checks that Plex recorded nothing.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextDim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.width(VIDEO_WIDTH.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                AndroidView(factory = { context -> SurfaceView(context).also(surface) }, modifier = Modifier.fillMaxSize())
            }
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val idle = state.phase == Phase.READY || state.phase == Phase.DONE
                Button(onClick = { if (idle) onStart() }, modifier = Modifier.focusRequester(start)) {
                    Text(if (state.phase == Phase.DONE) "Run again" else "Start")
                }
                Text(statusOf(state), style = MaterialTheme.typography.titleSmall)
                state.cases.forEachIndexed { index, case ->
                    CaseRow(case, state.results.getOrNull(index), playing = state.playing == index)
                }
                state.dtsTitles?.let { Text("$it of your titles have DTS as their main audio.", style = MaterialTheme.typography.bodyMedium) }
                state.history?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                state.problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Caution) }
            }
        }
    }
}

private const val VIDEO_WIDTH = 480

private fun statusOf(state: PlaybackCheckState) = when (state.phase) {
    Phase.READY -> "Press Start. It takes about a minute."
    Phase.PICKING -> "Choosing titles from your library…"
    Phase.PLAYING -> "Playing ${(state.playing ?: 0) + 1} of ${state.cases.size}…"
    Phase.CHECKING_HISTORY -> "Checking Plex's history…"
    Phase.DONE -> "Done: ${state.results.count { it.passed }} of ${state.results.size} played."
}

@Composable
private fun CaseRow(case: CheckCase, result: CheckResult?, playing: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Eyebrow(case.labels.joinToString(" · "), color = if (playing) Palette.Good else Palette.TextDim)
        Text(case.title, style = MaterialTheme.typography.titleSmall)
        Text(case.detail, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        when {
            result == null -> Unit
            result.passed -> Text(
                "First frame in ${result.firstFrameMillis} ms · ${result.video ?: "video ?"} · ${result.audio ?: "audio ?"}",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Good,
            )
            else -> Text(result.error ?: "Failed", style = MaterialTheme.typography.bodySmall, color = Palette.Bad)
        }
    }
}
