package com.phonoodlesramen.actioncenter.ui.guide

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.safeArea
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.IdlePolicy
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.IdleTakeover
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.ScreenCare
import java.time.ZoneId

/**
 * The guide's screen care (G10): Now showing's idle rule. Showing means dimmed and
 * drifting; the first key press wakes it and does nothing else. The full guide and a
 * program's details each keep one.
 */
@Stable
class GuideScreenCare(clock: () -> Long = System::currentTimeMillis) : IdleTakeover(clock)

/**
 * A full screen of the guide's (the full guide, a program's details) with screen care
 * (G10): the background, the safe area plus the drift's room, and after the idle minutes
 * a dim over everything, while the area drifts a few dp each minute. The first key only
 * wakes it ([IdleTakeover]).
 *
 * @param content lays itself out in the area it's given: the safe area, padded so the drift never moves text out of it.
 * @param overlay goes over the content and under the dim (the guide's quick actions).
 */
@Composable
internal fun GuideCaredScreen(
    care: GuideScreenCare,
    idleMinutes: Int?,
    lastInputMillis: () -> Long,
    nowMillis: Long,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit = {},
    content: @Composable (area: Modifier) -> Unit,
) {
    WatchIdle(care, idleMinutes, lastInputMillis)
    Box(modifier.fillMaxSize().background(Palette.Background).onPreviewKeyEvent { care.onKey(it) }) {
        val drift = rememberDrift(care.showing, nowMillis, zone)
        content(
            Modifier
                .fillMaxSize()
                .safeArea()
                .padding(horizontal = ScreenCare.MAX_X_DP.dp, vertical = ScreenCare.MAX_Y_DP.dp)
                .offset { drift.value },
        )
        overlay()
        AnimatedVisibility(
            visible = care.showing,
            enter = fadeIn(tween(DIM_IN_MILLIS)),
            exit = fadeOut(tween(DIM_OUT_MILLIS)),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(Modifier.fillMaxSize().background(Palette.Scrim.copy(alpha = DIM)))
        }
    }
}

/** With nothing else to focus, the screen holds focus itself, so keys still reach screen care. */
@Composable
internal fun rememberFallbackFocus(enabled: Boolean): FocusRequester {
    val self = remember { FocusRequester() }
    LaunchedEffect(enabled) {
        if (enabled) runCatching { self.requestFocus() }
    }
    return self
}

/** Runs screen care's idle watch while the screen is in front, as Now showing does. */
@Composable
private fun WatchIdle(care: GuideScreenCare, idleMinutes: Int?, lastInputMillis: () -> Long) {
    val lastInput by rememberUpdatedState(lastInputMillis)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(care, idleMinutes, lifecycle) {
        if (idleMinutes == null) {
            care.wake()
            return@LaunchedEffect
        }
        // Only while the screen is in front: the player or Home pauses it, and coming back counts as a key press.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            care.watch(IdlePolicy.minutes(idleMinutes), { lastInput() })
        }
    }
}

/** While dimmed, the minute's [ScreenCare] offset, eased over a second; read at placement, so nothing recomposes. */
@Composable
private fun rememberDrift(showing: Boolean, nowMillis: Long, zone: ZoneId) = with(LocalDensity.current) {
    val (x, y) = if (showing) ScreenCare.drift(GuideTime.minuteOfDay(nowMillis, zone)) else 0f to 0f
    animateIntOffsetAsState(IntOffset(x.dp.roundToPx(), y.dp.roundToPx()), tween(DRIFT_MILLIS), label = "drift")
}

/** How far screen care dims the screen: text stays readable, the panel stops glowing. */
private const val DIM = 0.6f
private const val DIM_IN_MILLIS = 1_200
private const val DIM_OUT_MILLIS = 250
private const val DRIFT_MILLIS = 1_000
