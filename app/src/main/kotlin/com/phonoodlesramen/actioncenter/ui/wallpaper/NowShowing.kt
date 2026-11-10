package com.phonoodlesramen.actioncenter.ui.wallpaper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.ui.art.TitleArt
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.weatherIcon
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.IdlePolicy
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.IdleTakeover
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.ScreenCare
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperStyle
import com.phonoodlesramen.actioncenter.weather.display
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Now showing (L1, Frontier): after the idle minutes in Settings, the cards fade and
 * Tonight's Pick fills the screen with the clock and weather. The first key press only
 * brings the cards back.
 *
 * The idle rule is [IdlePolicy] and the key handling [IdleTakeover], shared with any
 * later screen that takes over when the remote is left alone (the Plex Guide).
 */
@Stable
class NowShowingState(clock: () -> Long = System::currentTimeMillis) : IdleTakeover(clock)

/**
 * Drawn over the home screen. It covers the cards itself, so the home needs no changes:
 * the pick's backdrop, or with no pick the wallpaper again, under the clock and weather.
 * It never holds the screen on; the Shield's screensaver still comes on after its own
 * timeout.
 *
 * @param lastInputMillis when the remote was last pressed (wall clock), read every 15 seconds at most.
 */
@Composable
fun NowShowingOverlay(
    scene: WallpaperScene,
    state: NowShowingState,
    lastInputMillis: () -> Long,
    modifier: Modifier = Modifier,
) {
    val enabled = scene.prefs.nowShowing
    val minutes = scene.prefs.nowShowingMinutes
    val lastInput by rememberUpdatedState(lastInputMillis)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(enabled, minutes, lifecycle) {
        if (!enabled) {
            state.wake()
            return@LaunchedEffect
        }
        // Only while the home is in front: Home, another app or the screensaver pauses it,
        // which brings the cards back, and coming back counts as a key press.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            state.watch(IdlePolicy.minutes(minutes), { lastInput() })
        }
    }
    AnimatedVisibility(
        visible = state.showing,
        enter = fadeIn(tween(FADE_IN_MILLIS)),
        exit = fadeOut(tween(FADE_OUT_MILLIS)),
        modifier = modifier,
    ) {
        NowShowingScreen(scene, Modifier.fillMaxSize())
    }
}

/**
 * What Now showing shows: Tonight's Pick's backdrop at full screen (spec §6: requested at
 * the screen's pixel size) with its title, the clock and the weather on scrims. With no
 * pick, the clock and weather over the wallpaper. The text moves a few dp every minute
 * ([ScreenCare]), for OLED TVs.
 */
@Composable
internal fun NowShowingScreen(scene: WallpaperScene, modifier: Modifier = Modifier) {
    val pickId = scene.pickId
    Box(modifier.background(Palette.Background)) {
        if (pickId != null) {
            TitleArt(id = pickId, image = scene.pickArt, lateNight = scene.lateNight, modifier = Modifier.matchParentSize())
            // Scrims for the title (bottom left) and the clock (top right), spec §6.
            Box(
                Modifier.matchParentSize().drawBehind {
                    drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Palette.Scrim.copy(alpha = 0.88f)))
                    drawRect(
                        Brush.radialGradient(
                            0f to Palette.Scrim.copy(alpha = 0.72f),
                            1f to Color.Transparent,
                            center = Offset(size.width, 0f),
                            radius = size.width * 0.5f,
                        ),
                    )
                },
            )
        } else {
            // The cards hide under this, so the wallpaper is drawn again. A second video
            // player isn't worth it for the rare no-pick case: Video loop shows Midnight here.
            val wallpaper = if (scene.prefs.style == WallpaperStyle.VIDEO_LOOP) {
                scene.copy(prefs = scene.prefs.copy(style = WallpaperStyle.MIDNIGHT))
            } else {
                scene
            }
            WallpaperLayer(wallpaper, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Palette.Scrim.copy(alpha = 0.35f)))
        }
        val drift = rememberDrift(scene)
        Clock(scene, Modifier.align(Alignment.TopEnd).padding(horizontal = SIDE, vertical = TOP_BOTTOM).offset { drift.value })
        if (pickId != null) {
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = SIDE, vertical = TOP_BOTTOM).widthIn(max = 560.dp)
                    .offset { drift.value },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Eyebrow("Tonight's pick", color = Palette.Text.copy(alpha = 0.8f))
                Text(
                    text = scene.pickTitle.orEmpty(),
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The clock, big, with the weather under it when there's a fresh reading. */
@Composable
private fun Clock(scene: WallpaperScene, modifier: Modifier = Modifier) {
    val nowMillis = scene.now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val weather = scene.weather.display(nowMillis)
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(scene.now.format(ClockFormat), style = MaterialTheme.typography.displayMedium)
            Text(
                text = scene.now.format(AmPmFormat),
                style = MaterialTheme.typography.titleLarge,
                color = Palette.TextDim,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (weather != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(weatherIcon(weather.sky, weather.isDay), contentDescription = null, modifier = Modifier.size(24.dp), tint = Palette.TextDim)
                Text(weather.temperature, style = MaterialTheme.typography.titleLarge)
                Text(weather.condition, style = MaterialTheme.typography.titleMedium, color = Palette.TextDim)
            }
        }
    }
}

/** The minute's [ScreenCare] offset, eased over a second; read at placement, so nothing recomposes. */
@Composable
private fun rememberDrift(scene: WallpaperScene) = with(LocalDensity.current) {
    val (x, y) = ScreenCare.drift(scene.now.hour * 60 + scene.now.minute)
    animateIntOffsetAsState(IntOffset(x.dp.roundToPx(), y.dp.roundToPx()), tween(DRIFT_MILLIS), label = "drift")
}

private val ClockFormat = DateTimeFormatter.ofPattern("h:mm")
private val AmPmFormat = DateTimeFormatter.ofPattern("a")

/** The safe margins (48 × 27 dp) plus room for the drift, so text never leaves the safe area. */
private val SIDE = (48 + ScreenCare.MAX_X_DP).dp
private val TOP_BOTTOM = (27 + ScreenCare.MAX_Y_DP).dp

private const val FADE_IN_MILLIS = 1_200
private const val FADE_OUT_MILLIS = 250
private const val DRIFT_MILLIS = 1_000
