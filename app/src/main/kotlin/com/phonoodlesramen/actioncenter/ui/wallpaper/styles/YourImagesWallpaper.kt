package com.phonoodlesramen.actioncenter.ui.wallpaper.styles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.phonoodlesramen.actioncenter.media.MediaKind
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalWallpaperSources
import com.phonoodlesramen.actioncenter.ui.wallpaper.WallpaperScene
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.StillBitmaps
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.StillFrame
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.StillFrames
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.StillImages
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.StillPicture
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.orNothing
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.rememberStillImages
import com.phonoodlesramen.actioncenter.wallpaper.ImageFit
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.withContext

/**
 * Classic (L1): One image or a slideshow from the chosen folder, with an optional slow pan (L2).
 *
 * Sharp images are decoded at no more than 1920×1080 (the Shield's screen; the folder
 * holds 5120×5120 PNGs) and kept in graphics memory only. With a blur, only a 480×270
 * copy is decoded. The slideshow moves on with the clock, every `slideshowMinutes`.
 * An empty folder, or no storage grant, draws nothing.
 */
@Composable
fun YourImagesWallpaper(scene: WallpaperScene, modifier: Modifier = Modifier) {
    val images = rememberStillImages()
    val sources = LocalWallpaperSources.current
    val prefs = scene.prefs
    val slot = if (prefs.image == null) YourImages.slideshowSlot(scene.now, prefs.slideshowMinutes) else 0L
    // The folder is read again at each slide, so new files join the show.
    val file by produceState<File?>(null, sources, prefs.folder, prefs.image, slot) {
        value = orNothing { withContext(images.work) { YourImages.choose(sources.files(prefs.folder, MediaKind.IMAGE), prefs.image, slot) } }
    }
    val radius = StillFrames.radiusFor(prefs.imageBlur)
    val frame by produceState<StillFrame?>(null, file, prefs.fit, radius) {
        value = file?.let { orNothing { YourImages.frame(images, it, prefs.fit, radius) } }
    }
    StillPicture(frame, prefs.fit, prefs.slowPan, scene.lateNight, modifier)
}

object YourImages {
    /** The Shield's screen in pixels: no image is decoded larger. */
    const val MAX_WIDTH = 1920
    const val MAX_HEIGHT = 1080

    /** The slideshow's position: it moves on every [minutes] of the clock. */
    fun slideshowSlot(now: LocalDateTime, minutes: Int): Long = now.toEpochSecond(ZoneOffset.UTC) / 60 / minutes.coerceAtLeast(1)

    /** The file named [chosen] when it's still there, else the slideshow's file for [slot]. */
    fun choose(files: List<File>, chosen: String?, slot: Long): File? {
        if (files.isEmpty()) return null
        chosen?.let { name -> files.firstOrNull { it.name == name }?.let { return it } }
        return files[Math.floorMod(slot, files.size.toLong()).toInt()]
    }

    suspend fun frame(images: StillImages, file: File, fit: ImageFit, radius: Int): StillFrame? {
        val crop = fit == ImageFit.FILL
        val drift = StillFrames.driftFor(file.name)
        return if (radius > 0) {
            val small = images.load(file, StillBitmaps.SMALL_WIDTH, StillBitmaps.SMALL_HEIGHT, crop) ?: return null
            withContext(images.work) { StillFrames.fromSmall(small, radius, leastScrim = 0f, drift) }
        } else {
            val large = images.load(file, MAX_WIDTH, MAX_HEIGHT, crop) ?: return null
            withContext(images.work) { StillFrames.fromLarge(images, large, leastScrim = 0f, drift) }
        }
    }
}
