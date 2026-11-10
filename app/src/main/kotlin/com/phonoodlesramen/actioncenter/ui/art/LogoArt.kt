package com.phonoodlesramen.actioncenter.ui.art

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow
import kotlinx.coroutines.withContext

/**
 * The dark-logo check (Phase 12, L2): some logos are black or dark, and vanish on the dark
 * cards. A logo shows when its visible pixels, averaged in relative luminance (WCAG, linear
 * light), have WCAG's contrast for graphics, 3:1, against the background of the card it sits
 * on ([readable]); otherwise the title keeps its place. Each card passes its own background:
 * the hero its scrim (`#080B0F`, 0.003, so a logo needs 0.11), the lens card its lens's
 * lightest stop (For the family's `#1E3F3A`, 0.041, needs 0.22).
 *
 * Made-up test logos: white (1.0) and amber `#E5A00D` (0.42) show everywhere; red `#E50914`
 * (0.17) shows on the hero and not on the lighter lens tints; blue `#1F3A93` (0.05), navy and
 * black show nowhere.
 */
object LogoCheck {
    /** At least half opaque: an antialiased edge or a soft shadow doesn't count. */
    const val VISIBLE_ALPHA = 128

    /** WCAG 2's contrast for graphics and large text. */
    const val MIN_CONTRAST = 3f

    /** sRGB to linear light, per channel value. */
    private val LINEAR = FloatArray(256) { value ->
        val c = value / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    /** The relative luminance of one opaque sRGB [argb] colour. */
    fun luminance(argb: Int): Float =
        (0.2126 * LINEAR[argb shr 16 and 0xFF] + 0.7152 * LINEAR[argb shr 8 and 0xFF] + 0.0722 * LINEAR[argb and 0xFF]).toFloat()

    /** [color]'s relative luminance: a card's background. */
    fun luminance(color: Color): Float = luminance(color.toArgb())

    /** The average relative luminance of the visible pixels in [argb] (unpremultiplied); null when none are visible. */
    fun luminance(argb: IntArray): Float? {
        var sum = 0.0
        var count = 0
        for (pixel in argb) {
            if (pixel ushr 24 < VISIBLE_ALPHA) continue
            sum += luminance(pixel)
            count++
        }
        return if (count == 0) null else (sum / count).toFloat()
    }

    /** [bitmap]'s [luminance]: it's the small one decoded for the box, so every pixel is read. */
    fun luminance(bitmap: Bitmap): Float? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return luminance(pixels)
    }

    /**
     * Whether a logo of [logo] luminance reads on a background of [background] luminance:
     * at least [MIN_CONTRAST] lighter. A logo with nothing visible (null) never does.
     */
    fun readable(logo: Float?, background: Float): Boolean = logo != null && (logo + 0.05f) / (background + 0.05f) >= MIN_CONTRAST
}

/**
 * Loads a logo from [url] fitted inside [widthPx]×[heightPx] (the transcoder sends it no
 * bigger, and Coil decodes it no bigger) and checks it against its card's [background]
 * luminance ([LogoCheck.readable]) where Coil decodes, off the main thread. Null when it
 * fails or is too dark there: the title stays.
 */
internal suspend fun loadLogo(context: Context, url: String, widthPx: Int, heightPx: Int, background: Float): ImageBitmap? {
    val loader = SingletonImageLoader.get(context)
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(widthPx, heightPx)
        .scale(Scale.FIT)
        // The check reads the pixels back, which a hardware bitmap doesn't allow.
        .allowHardware(false)
        .build()
    return try {
        val result = loader.execute(request) as? SuccessResult ?: return null
        withContext(loader.defaults.decoderCoroutineContext) {
            val bitmap = result.image.toBitmap()
            if (LogoCheck.readable(LogoCheck.luminance(bitmap), background)) bitmap.asImageBitmap() else null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // A logo never breaks a card: the title shows.
        null
    }
}

/** A logo's box: [height] tall, and the logo's [url] at [widthPx]×[heightPx], the most it may be. */
data class LogoBox(val url: String, val widthPx: Int, val heightPx: Int, val height: Dp)

/**
 * [path]'s logo in a box up to [widthPx] wide and [height] tall, or null: no logo, a box
 * under [min] (the title's line, since the title waits in the box), or no server to load it
 * from. Then the title shows, as before.
 */
@Composable
fun rememberLogoBox(path: String?, widthPx: Int, height: Dp, min: Dp): LogoBox? {
    val urls = LocalArtUrls.current
    val density = LocalDensity.current
    return remember(path, widthPx, height, min, urls, density) {
        if (path == null || height < min || widthPx <= 0) return@remember null
        val heightPx = with(density) { height.roundToPx() }
        urls.logoUrl(path, widthPx, heightPx)?.let { LogoBox(it, widthPx, heightPx, height) }
    }
}

/**
 * A title's logo in the title's place (Phase 12). [modifier] gives the box: its height, and
 * a width to fill or up to. [url] is [ArtUrls.logoUrl] at [widthPx]×[heightPx], the box's
 * own pixels. [title] shows in the box until the logo loads, then crossfades to it, and
 * stays if the logo can't load or is too dark to read on [background], the card behind it
 * ([LogoCheck.readable]). The logo is left-aligned, centred in the box's height and never
 * stretched; its content description is [description], the title. Late night dims it like
 * the art; nothing else tints it (the lens card's sepia is for art).
 */
@Composable
fun LogoArt(
    url: String,
    widthPx: Int,
    heightPx: Int,
    description: String,
    background: Color,
    lateNight: Boolean,
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val backgroundLuminance = LogoCheck.luminance(background)
    // A new logo starts from the title, never from the last title's logo.
    val logo = remember(url, widthPx, heightPx, backgroundLuminance) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(logo) { logo.value = loadLogo(context.applicationContext, url, widthPx, heightPx, backgroundLuminance) }
    Crossfade(targetState = logo.value, animationSpec = tween(250), label = "logo", modifier = modifier) { shown ->
        Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            if (shown == null) {
                title()
            } else {
                Image(
                    bitmap = shown,
                    contentDescription = description,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    colorFilter = if (lateNight) ColorFilter.colorMatrix(ArtFilters.LateNight) else null,
                    modifier = Modifier
                        .padding(vertical = LOGO_INSET)
                        .aspectRatio(shown.width.toFloat() / shown.height, matchHeightConstraintsFirst = true),
                )
            }
        }
    }
}

/**
 * A logo is trimmed to its letters, where a title's line has room above and below them: this
 * keeps it off the text above and below the box.
 */
private val LOGO_INSET = 2.dp
