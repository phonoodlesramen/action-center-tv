package com.phonoodlesramen.actioncenter.ui.wallpaper.styles

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.core.graphics.withTranslation
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalFocusedArt
import com.phonoodlesramen.actioncenter.ui.wallpaper.WallpaperScene
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.glowPaint
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.rememberArtPalette

/**
 * Creative (L1): The colours of the focused card's art ([LocalFocusedArt]), crossfading as focus moves.
 *
 * - The art is read at 16×9 px through Plex's transcoder and Coil, and gives three
 *   colours, left to right ([com.phonoodlesramen.actioncenter.ui.wallpaper.motion.ArtColors]).
 * - They light the screen as three large soft glows, like a bias light behind the TV.
 * - Focus moving to other art crossfades to its colours (the system animation scale applies).
 * - Before any art has focus (or without a Plex server), a quiet glow of the accent.
 */
@Composable
fun AmbientLightWallpaper(scene: WallpaperScene, modifier: Modifier = Modifier) {
    val art by rememberArtPalette(LocalFocusedArt.current.image)
    val target = art ?: listOf(scene.accent.copy(alpha = ACCENT_ONLY), Color.Transparent, scene.accent.copy(alpha = ACCENT_ONLY * 0.6f))
    val left = fade(target[0])
    val middle = fade(target.getOrElse(1) { target[0] })
    val right = fade(target.getOrElse(2) { target[0] })
    val intensity = if (scene.lateNight) LATE_NIGHT_INTENSITY else 1f
    Spacer(
        modifier.drawWithCache {
            val unit = size.maxDimension
            // Rebuilt only while a crossfade runs: the colours are read here, in the draw cache.
            val glows = listOf(
                Triple(Offset(size.width * 0.12f, size.height * 0.18f), unit * 0.62f, left.value),
                Triple(Offset(size.width * 0.50f, size.height * 1.02f), unit * 0.58f, middle.value),
                Triple(Offset(size.width * 0.90f, size.height * 0.30f), unit * 0.60f, right.value),
            ).map { (center, radius, color) -> Triple(center, radius, glowPaint(color.copy(alpha = 1f), color.alpha * GLOW_ALPHA * intensity, radius)) }
            onDrawBehind {
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    glows.forEach { (center, radius, paint) ->
                        native.withTranslation(center.x, center.y) { drawCircle(0f, 0f, radius, paint) }
                    }
                }
            }
        },
    )
}

@Composable
private fun fade(color: Color): State<Color> = animateColorAsState(color, tween(CROSSFADE_MILLIS), label = "ambient")

private const val CROSSFADE_MILLIS = 900
private const val GLOW_ALPHA = 0.7f
private const val ACCENT_ONLY = 0.45f
private const val LATE_NIGHT_INTENSITY = 0.75f
