package com.phonoodlesramen.actioncenter.ui.wallpaper.styles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.phonoodlesramen.actioncenter.ui.art.LocalArtUrls
import com.phonoodlesramen.actioncenter.ui.wallpaper.WallpaperScene
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.StillBitmaps
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.StillFrame
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.StillFrames
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.StillImages
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.StillPicture
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.orNothing
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.rememberStillImages
import com.phonoodlesramen.actioncenter.wallpaper.ImageFit
import kotlinx.coroutines.withContext

/**
 * Classic (L1): Tonight's Pick's backdrop, blurred and dimmed, crossfading when the pick changes (L3).
 *
 * Plex's transcoder sends the backdrop at 480×270 (960×540 when the blur is off), it's
 * blurred once in software and scaled up to fill. With no pick yet (first run) it draws
 * nothing, so Midnight shows.
 */
@Composable
fun CoverArtWallpaper(scene: WallpaperScene, modifier: Modifier = Modifier) {
    val images = rememberStillImages()
    val radius = StillFrames.radiusFor(scene.prefs.blur)
    val scale = if (radius == 0) 2 else 1
    val url = scene.pickArt?.let { LocalArtUrls.current.url(it, StillBitmaps.SMALL_WIDTH * scale, StillBitmaps.SMALL_HEIGHT * scale) }
    val frame by produceState<StillFrame?>(null, url, radius) {
        value = url?.let { orNothing { coverFrame(images, it, radius, scale) } }
    }
    StillPicture(frame, ImageFit.FILL, slowPan = false, lateNight = scene.lateNight, modifier = modifier)
}

private suspend fun coverFrame(images: StillImages, url: String, radius: Int, scale: Int): StillFrame? {
    val backdrop = images.load(url, StillBitmaps.SMALL_WIDTH * scale, StillBitmaps.SMALL_HEIGHT * scale, crop = true) ?: return null
    return withContext(images.work) {
        if (radius == 0) StillFrames.fromLarge(images, backdrop, COVER_DIM) else StillFrames.fromSmall(backdrop, radius, COVER_DIM)
    }
}

/** L3 says dimmed: even a dark backdrop gets this much of the background over it. */
private const val COVER_DIM = 0.25f
