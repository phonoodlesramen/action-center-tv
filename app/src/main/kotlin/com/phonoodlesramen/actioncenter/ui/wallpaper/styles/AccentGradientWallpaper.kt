package com.phonoodlesramen.actioncenter.ui.wallpaper.styles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import com.phonoodlesramen.actioncenter.ui.wallpaper.WallpaperScene
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.AccentGradient
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.Scrim
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.StillFrame
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.StillPicture
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.orNothing
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.rememberStillImages
import com.phonoodlesramen.actioncenter.wallpaper.ImageFit
import kotlinx.coroutines.withContext

/**
 * Classic (L1): A deep, dithered gradient in the accent colour.
 *
 * The accent arrives already dimmed in late night, and the glow is held dark enough for
 * the top bar's text, so it needs no scrim. Glass cards show the same gradient: it's
 * already as smooth as a blur would make it. It crossfades when the accent changes.
 */
@Composable
fun AccentGradientWallpaper(scene: WallpaperScene, modifier: Modifier = Modifier) {
    val images = rememberStillImages()
    val frame by produceState<StillFrame?>(null, scene.accent, scene.lateNight) {
        value = orNothing {
            withContext(images.work) {
                val gradient = AccentGradient.render(scene.accent, scene.lateNight).asImageBitmap()
                StillFrame(gradient, frosted = gradient, scrim = Scrim.None, smooth = false)
            }
        }
    }
    StillPicture(frame, ImageFit.FILL, slowPan = false, lateNight = false, modifier = modifier)
}
