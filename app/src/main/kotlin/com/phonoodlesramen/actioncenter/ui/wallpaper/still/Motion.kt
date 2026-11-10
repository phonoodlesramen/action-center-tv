package com.phonoodlesramen.actioncenter.ui.wallpaper.still

import android.animation.ValueAnimator
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DoubleState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/**
 * Seconds of slow motion for the pan and the poster drift, advanced every frame at
 * [rate] (late night passes a slower one). Read it while drawing, so a frame only
 * redraws, never recomposes. A double, so it stays smooth after days on screen.
 *
 * It holds still when Android's animator duration scale is 0
 * (`ValueAnimator.areAnimatorsEnabled()`, checked again each time the home comes back),
 * and frames stop while the home is hidden. Tests' infinite-animation policy cancels it,
 * so screenshots show the first frame.
 */
@Composable
fun rememberMotionSeconds(running: Boolean, rate: Float): DoubleState {
    val seconds = remember { mutableDoubleStateOf(0.0) }
    val currentRate by rememberUpdatedState(rate)
    val started = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(running, started) {
        if (!running || !started) return@LaunchedEffect
        var last = withInfiniteAnimationFrameNanos { it }
        while (ValueAnimator.areAnimatorsEnabled()) {
            val now = withInfiniteAnimationFrameNanos { it }
            // A stalled frame moves on by at most a tenth of a second, never a jump.
            seconds.doubleValue += ((now - last) / NANOS).coerceAtMost(MAX_STEP) * currentRate
            last = now
        }
    }
    return seconds
}

private const val NANOS = 1_000_000_000.0
private const val MAX_STEP = 0.1
