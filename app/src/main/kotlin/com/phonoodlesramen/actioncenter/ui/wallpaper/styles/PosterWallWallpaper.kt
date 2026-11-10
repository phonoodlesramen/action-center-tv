package com.phonoodlesramen.actioncenter.ui.wallpaper.styles

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.phonoodlesramen.actioncenter.ui.art.LocalArtUrls
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalWallpaperSources
import com.phonoodlesramen.actioncenter.ui.wallpaper.WallpaperScene
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.LATE_NIGHT_RATE
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.LateNightFilter
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.Legibility
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.StillBitmaps
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.StillImages
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.drawPicture
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.orNothing
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.rememberMotionSeconds
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.rememberStillImages
import kotlin.math.ceil
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Creative (L1): A dim, slowly drifting mosaic of posters from the Plex cache.
 *
 * About 30 random movie and show posters, each decoded at 120×180 px and kept in graphics
 * memory (about 2.6 MB for all of them), repeat along rows that drift in turn left and
 * right. Each poster is drawn as faint as its brightest point needs for dim text over it
 * to keep 4.5:1. Glass cards over it are a plain tint. With no Plex cache (first run, not
 * linked) it draws nothing, so Midnight shows.
 */
@Composable
fun PosterWallWallpaper(scene: WallpaperScene, modifier: Modifier = Modifier) {
    val images = rememberStillImages()
    val sources = LocalWallpaperSources.current
    val artUrls = LocalArtUrls.current
    val posters by produceState(emptyList<PosterWall.Poster>(), sources, artUrls) {
        value = orNothing {
            val urls = sources.posters(PosterWall.COUNT).mapNotNull { artUrls.url(it, PosterWall.WIDTH_PX, PosterWall.HEIGHT_PX) }
            coroutineScope { urls.map { async { orNothing { PosterWall.load(images, it) } } }.awaitAll() }.filterNotNull()
        }.orEmpty()
    }
    val seconds = rememberMotionSeconds(running = posters.isNotEmpty(), rate = if (scene.lateNight) LATE_NIGHT_RATE else 1f)
    val speed = PosterWall.speed(scene.prefs.speed)
    val filter = if (scene.lateNight) LateNightFilter else null
    Canvas(modifier) {
        if (posters.isNotEmpty()) with(PosterWall) { drawWall(posters, seconds.doubleValue * speed.toPx(), filter) }
    }
}

object PosterWall {
    /** How many posters the wall asks the cache for. */
    const val COUNT = 30

    /** Each poster's decode size (2:3). */
    const val WIDTH_PX = 120
    const val HEIGHT_PX = 180

    /** The faintest wall still shows posters this strongly; brighter ones go fainter. */
    const val MOST_ALPHA = 0.4f

    /** On screen: a little over the decode size; dimmed, the softness doesn't show. */
    private val PosterWidth = 72.dp
    private val PosterHeight = 108.dp
    private val Gap = 10.dp

    /** How far each row starts along its loop, in posters, so the columns don't line up. */
    private const val ROW_STAGGER = 0.37f

    /** A poster and how strongly it's drawn. */
    @Immutable
    class Poster(val image: ImageBitmap, val alpha: Float)

    suspend fun load(images: StillImages, url: String): Poster? {
        val bitmap = images.load(url, WIDTH_PX, HEIGHT_PX, crop = true) ?: return null
        return withContext(images.work) {
            val alpha = Legibility.strongestOverBackground(StillBitmaps.pixels(bitmap), MOST_ALPHA)
            Poster(images.keep(bitmap).asImageBitmap(), alpha)
        }
    }

    /** Drift speed per second from Settings' speed (0 to 1). */
    fun speed(setting: Float) = (2f + 14f * setting.coerceIn(0f, 1f)).dp

    /**
     * Rows of posters, each row a loop sliding [travel] px, odd rows the other way. A
     * poster keeps its place in its row's loop, so nothing jumps as the rows move.
     */
    fun DrawScope.drawWall(posters: List<Poster>, travel: Double, filter: ColorFilter?) {
        val pitchX = (PosterWidth + Gap).toPx()
        val pitchY = (PosterHeight + Gap).toPx()
        val slots = ceil(size.width / pitchX).toInt() + 1
        val rows = ceil(size.height / pitchY).toInt() + 1
        val loop = slots * pitchX
        val top = (size.height - rows * pitchY) / 2
        for (row in 0 until rows) {
            val direction = if (row % 2 == 0) 1 else -1
            val shift = (((travel * direction + row * pitchX * ROW_STAGGER) % loop + loop) % loop).toFloat()
            for (slot in 0 until slots) {
                val x = (slot * pitchX + shift) % loop - pitchX
                if (x > size.width) continue
                val poster = posters[(row * slots + slot) % posters.size]
                val y = top + row * pitchY
                drawPicture(poster.image, Rect(x, y, x + PosterWidth.toPx(), y + PosterHeight.toPx()), poster.alpha, filter)
            }
        }
    }
}
