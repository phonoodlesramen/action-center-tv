package com.phonoodlesramen.actioncenter.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.phonoodlesramen.actioncenter.player.GuidePlayerState
import com.phonoodlesramen.actioncenter.ui.home.PillButton
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import kotlin.math.roundToInt

/**
 * The banner's control row (P2): Back 10, Play/Pause, Forward 30, and Back to live while behind
 * live, as the home's pill buttons. Play/Pause takes focus each time the row comes up. It's
 * one focus group (`focusRestorer().focusGroup()`, in that order): while it's up the activity
 * hands Left, Right and OK to Compose, so they move along it and press, and nothing else is
 * focusable over the picture. Back to live hands focus to Play/Pause before it goes.
 *
 * Where the four don't fit the banner's column (behind live at 115%), the skips read "10" and
 * "30": their icons still say which way.
 */
@Composable
internal fun ControlRow(state: GuidePlayerState, actions: PlayerActions, modifier: Modifier = Modifier) {
    val playPause = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playPause.requestFocus() } }
    val paused = state.behind?.paused == true
    val playLabel = if (paused) "Play" else "Pause"
    val behind = state.behind != null
    BoxWithConstraints(modifier) {
        val full = rowWidth(listOfNotNull(BACK, playLabel, FORWARD, LIVE.takeIf { behind })) <= maxWidth
        Row(
            modifier = Modifier.focusRestorer().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(
                text = if (full) BACK else "10",
                icon = PlayerIcons.Rewind,
                onClick = { actions.skip(forward = false) },
                modifier = Modifier.semantics { contentDescription = "Back 10 seconds" },
            )
            PillButton(
                text = playLabel,
                icon = if (paused) AppIcons.Play else PlayerIcons.Pause,
                onClick = { actions.playPause() },
                modifier = Modifier.focusRequester(playPause),
            )
            PillButton(
                text = if (full) FORWARD else "30",
                icon = PlayerIcons.FastForward,
                onClick = { actions.skip(forward = true) },
                modifier = Modifier.semantics { contentDescription = "Forward 30 seconds" },
            )
            if (behind) {
                PillButton(
                    text = LIVE,
                    icon = PlayerIcons.Live,
                    onClick = {
                        // The button goes once the picture's live: focus stays in the row.
                        runCatching { playPause.requestFocus() }
                        actions.backToLive()
                    },
                )
            }
        }
    }
}

/** How wide a row of pill buttons with these labels is: each label, plus the pill's padding and icon. */
@Composable
private fun rowWidth(labels: List<String>): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val text = with(LocalDensity.current) { labels.sumOf { measurer.measure(it, style, maxLines = 1).size.width }.toDp() }
    return text + PILL_CHROME * labels.size + ROW_GAP * (labels.size - 1)
}

private const val BACK = "Back 10"
private const val FORWARD = "Forward 30"
private const val LIVE = "Back to live"
private val ROW_GAP = 10.dp

/** A [PillButton]'s width beside its label: 16 dp padding each side, a 16 dp icon and 6 dp after it. */
private val PILL_CHROME = 54.dp

/**
 * The banner's progress with the scrub's marker (P1): the bar as the banner draws it, and a
 * white tick at [marker] (0 to 1), where letting go of ⏪ or ⏩ will land.
 */
@Composable
internal fun MarkedProgress(fraction: Float, marker: Float, modifier: Modifier = Modifier, live: Float? = null) {
    Box(modifier.height(MARKER_HEIGHT)) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.22f)),
        ) {
            if (live != null) Box(Modifier.height(4.dp).fillMaxWidth(live.coerceIn(0f, 1f)).background(Color.White.copy(alpha = 0.4f)))
            Box(Modifier.height(4.dp).fillMaxWidth(fraction.coerceIn(0f, 1f)).background(LocalAccent.current))
        }
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .at(marker.coerceIn(0f, 1f))
                .size(MARKER_WIDTH, MARKER_HEIGHT)
                .background(Color.White, RoundedCornerShape(MARKER_WIDTH / 2)),
        )
    }
}

/** Places a child centred on [fraction] of its parent's width, kept inside it. */
private fun Modifier.at(fraction: Float) = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints())
    val width = constraints.maxWidth
    layout(width, placeable.height) {
        val x = (width * fraction - placeable.width / 2f).roundToInt().coerceIn(0, (width - placeable.width).coerceAtLeast(0))
        placeable.place(x, 0)
    }
}

private val MARKER_HEIGHT = 14.dp
private val MARKER_WIDTH = 3.dp
