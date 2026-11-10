package com.phonoodlesramen.actioncenter.ui.wallpaper.still

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.phonoodlesramen.actioncenter.ui.art.ArtFilters
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalCardSurface
import com.phonoodlesramen.actioncenter.wallpaper.ImageFit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A picture ready to draw: what the wallpaper shows, its frosted copy for glass cards
 * (the same bitmap when the picture is already blurred enough), and the scrim that keeps
 * text readable over it.
 *
 * @param drift which way the slow pan moves: x and y in -1..1.
 * @param smooth false draws whole pixels, unfiltered: a dithered picture keeps its dither.
 */
@Immutable
class StillFrame(
    val image: ImageBitmap,
    val frosted: ImageBitmap,
    val scrim: Scrim,
    val drift: Offset = Offset.Zero,
    val smooth: Boolean = true,
)

/** Turning bitmaps into [StillFrame]s. Runs on [StillImages.work]: the blur and the scrim read every pixel. */
object StillFrames {
    /** Glass is always at least this blurred, on the 480 px copy (64 px on screen). */
    const val GLASS_RADIUS = 16

    /** Settings' blur (0 sharp to 1 strongest) as a radius on the 480 px copy. */
    fun radiusFor(blur: Float): Int = (blur.coerceIn(0f, 1f) * StackBlur.MAX_RADIUS).roundToInt()

    /** A frame from a small picture (480×270 or so) shown blurred by [radius] and scaled up. */
    fun fromSmall(small: Bitmap, radius: Int, leastScrim: Float, drift: Offset = Offset.Zero): StillFrame {
        val shown = if (radius > 0) StackBlur.blur(small, radius) else small
        val frosted = if (radius >= GLASS_RADIUS) shown else StackBlur.blur(small, GLASS_RADIUS)
        val scrim = Legibility.scrimFor(StillBitmaps.pixels(shown), shown.width, shown.height, leastScrim)
        return StillFrame(shown.asImageBitmap(), frosted.asImageBitmap(), scrim, drift)
    }

    /**
     * A frame from a sharp, screen-sized picture: the scrim and the frosted copy come
     * from a small copy, and [images] keeps the big one for drawing.
     */
    fun fromLarge(images: StillImages, large: Bitmap, leastScrim: Float, drift: Offset = Offset.Zero): StillFrame {
        val small = StillBitmaps.small(large)
        val frosted = StackBlur.blur(small, GLASS_RADIUS)
        val scrim = Legibility.scrimFor(StillBitmaps.pixels(small), small.width, small.height, leastScrim)
        if (small !== large) small.recycle()
        return StillFrame(images.keep(large).asImageBitmap(), frosted.asImageBitmap(), scrim, drift)
    }

    /** A steady direction for a picture's slow pan, from its name. */
    fun driftFor(name: String): Offset {
        val angle = (name.hashCode().toLong() and 0xFFFF) / 65_536.0 * 2 * PI
        return Offset(cos(angle).toFloat(), sin(angle).toFloat())
    }
}

/**
 * Where a picture goes on a [canvas]: covering it ([ImageFit.FILL], cropping the
 * overflow) or whole inside it ([ImageFit.FIT]), then the slow pan at [pan] (0 to 1):
 * up to [PAN_ZOOM] larger, drifting along [drift] as far as the overflow allows.
 */
fun placement(width: Float, height: Float, canvas: Size, fit: ImageFit, pan: Float = 0f, drift: Offset = Offset.Zero): Rect {
    if (width <= 0f || height <= 0f) return Rect.Zero
    val base = if (fit == ImageFit.FILL) max(canvas.width / width, canvas.height / height) else min(canvas.width / width, canvas.height / height)
    val scale = base * (1f + PAN_ZOOM * pan)
    val w = width * scale
    val h = height * scale
    val slackX = max(0f, (w - canvas.width) / 2)
    val slackY = max(0f, (h - canvas.height) / 2)
    val centre = Offset(canvas.width / 2 + drift.x * slackX * pan, canvas.height / 2 + drift.y * slackY * pan)
    return Rect(centre.x - w / 2, centre.y - h / 2, centre.x + w / 2, centre.y + h / 2)
}

/** The slow pan's zoom at its widest. */
const val PAN_ZOOM = 0.08f

/** One slow pan out and back, in seconds (late night runs the clock at half speed). */
const val PAN_PERIOD_SECONDS = 90f

/** The pan's position (0 to 1 and back) after [seconds]: eased at both ends. */
fun panAt(seconds: Double): Float = ((1 - cos(2 * PI * seconds / PAN_PERIOD_SECONDS)) / 2).toFloat()

/**
 * Draws [image] scaled into [place]: filtered, at any fraction of a pixel (the slow pan
 * glides), or with [smooth] off on whole pixels, each copied as it is.
 */
fun DrawScope.drawPicture(image: ImageBitmap, place: Rect, alpha: Float, filter: ColorFilter?, smooth: Boolean = true) {
    if (place.isEmpty || alpha <= 0f) return
    if (!smooth) {
        drawImage(
            image = image,
            dstOffset = IntOffset(place.left.roundToInt(), place.top.roundToInt()),
            dstSize = IntSize(place.width.roundToInt(), place.height.roundToInt()),
            alpha = alpha,
            colorFilter = filter,
            filterQuality = FilterQuality.None,
        )
        return
    }
    translate(place.left, place.top) {
        scale(place.width / image.width, place.height / image.height, pivot = Offset.Zero) {
            drawImage(image, alpha = alpha, colorFilter = filter)
        }
    }
}

/** The frame on screen, the one before it, and the crossfade between them. */
@Stable
class StillFade {
    var previous: StillFrame? by mutableStateOf(null)
        private set
    var current: StillFrame? by mutableStateOf(null)
        private set
    val progress = Animatable(1f)

    internal suspend fun show(frame: StillFrame?) {
        if (frame === current) return
        // Mid-fade, keep whichever of the two is showing more.
        previous = if (progress.value >= 0.5f || previous == null) current else previous
        current = frame
        progress.snapTo(0f)
        progress.animateTo(1f, tween(FADE_MILLIS))
        previous = null
    }

    /** Draws both frames: the wallpaper's [StillFrame.image], or for glass its [StillFrame.frosted] copy. */
    fun DrawScope.drawFade(canvas: Size, fit: ImageFit, pan: Float, filter: ColorFilter?, frosted: Boolean) {
        val p = progress.value
        previous?.let { drawFrame(it, canvas, fit, pan, if (current == null) 1f - p else 1f, filter, frosted) }
        current?.let { drawFrame(it, canvas, fit, pan, p, filter, frosted) }
    }

    private fun DrawScope.drawFrame(frame: StillFrame, canvas: Size, fit: ImageFit, pan: Float, alpha: Float, filter: ColorFilter?, frosted: Boolean) {
        // Both copies share the picture's shape, so the frosted one lands exactly behind.
        val place = placement(frame.image.width.toFloat(), frame.image.height.toFloat(), canvas, fit, pan, frame.drift)
        drawPicture(if (frosted) frame.frosted else frame.image, place, alpha, filter, frame.smooth)
        with(frame.scrim) { drawScrim(canvas, alpha) }
    }

    companion object {
        const val FADE_MILLIS = 900
    }
}

/**
 * A still picture as the wallpaper (Cover art, Your images): it crossfades when [frame]
 * changes, pans slowly with [slowPan], dims in late night (spec §2), and publishes its
 * frosted copy for glass cards. A null [frame] draws nothing, so Midnight shows.
 */
@Composable
fun StillPicture(frame: StillFrame?, fit: ImageFit, slowPan: Boolean, lateNight: Boolean, modifier: Modifier = Modifier) {
    val fade = remember { StillFade() }
    LaunchedEffect(frame) { fade.show(frame) }
    val seconds = rememberMotionSeconds(running = slowPan && frame != null, rate = if (lateNight) LATE_NIGHT_RATE else 1f)
    val look by rememberUpdatedState(Look(fit, slowPan, if (lateNight) LateNightFilter else null))
    val pan = { if (look.slowPan) panAt(seconds.doubleValue) else 0f }
    val painter: BackdropPainter = remember(fade) {
        { wallpaper -> with(fade) { drawFade(wallpaper, look.fit, pan(), look.filter, frosted = true) } }
    }
    Canvas(modifier.publishesBackdrop(LocalCardSurface.current.backdrop, painter)) {
        with(fade) { drawFade(size, look.fit, pan(), look.filter, frosted = false) }
    }
}

private data class Look(val fit: ImageFit, val slowPan: Boolean, val filter: ColorFilter?)

/** Late night: artwork at 75% brightness and 80% saturation (spec §2). */
val LateNightFilter: ColorFilter = ColorFilter.colorMatrix(ArtFilters.LateNight)

/** Late night calms the motion to half speed. */
const val LATE_NIGHT_RATE = 0.5f
