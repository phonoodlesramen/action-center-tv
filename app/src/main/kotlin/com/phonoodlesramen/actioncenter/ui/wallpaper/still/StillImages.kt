package com.phonoodlesramen.actioncenter.ui.wallpaper.still

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.scale
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers

/**
 * Where the still wallpapers get their pictures. The app decodes with Coil; screenshot
 * tests decode their own small files, without the network or a background thread.
 */
interface StillImages {
    /**
     * [model] (an art URL or a [java.io.File]) decoded to fit inside [widthPx]×[heightPx]
     * or, with [crop], cut from its centre to that shape. Never larger than asked: a
     * smaller picture keeps its own size. Software ARGB_8888, so it can be blurred and
     * read; null when it can't be loaded.
     *
     * @param cache keep it in Coil's memory cache (posters). Wallpaper-sized pictures
     *   skip it, so they don't push the cards' art out (spec §6).
     */
    suspend fun load(model: Any, widthPx: Int, heightPx: Int, crop: Boolean, cache: Boolean = false): Bitmap?

    /** Readies a big, sharp picture for drawing, once its small copy is made. */
    fun keep(picture: Bitmap): Bitmap = picture

    /** Blocking reads, the blur and the rest run here: off the main thread in the app. */
    val work: CoroutineContext
}

/** Null in the app (Coil); screenshot tests provide their own. */
val LocalStillImages = staticCompositionLocalOf<StillImages?> { null }

@Composable
fun rememberStillImages(): StillImages {
    LocalStillImages.current?.let { return it }
    val context = LocalContext.current.applicationContext
    return remember(context) { CoilStillImages(context) }
}

/**
 * Coil 3 with an explicit size: `Precision.INEXACT` so a picture smaller than the size
 * isn't scaled up in memory, `Scale.FIT` (or `FILL` and then a centre crop), software
 * bitmaps (`allowHardware(false)`) so the pixels can be read.
 */
class CoilStillImages(
    private val context: Context,
    private val loader: ImageLoader = SingletonImageLoader.get(context),
) : StillImages {
    override val work: CoroutineContext = Dispatchers.IO

    override suspend fun load(model: Any, widthPx: Int, heightPx: Int, crop: Boolean, cache: Boolean): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val request = ImageRequest.Builder(context)
            .data(model)
            .size(widthPx, heightPx)
            .scale(if (crop) Scale.FILL else Scale.FIT)
            .precision(Precision.INEXACT)
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .allowHardware(false)
            .memoryCachePolicy(if (cache) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .build()
        val bitmap = (loader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
        return if (crop) StillBitmaps.centreCrop(bitmap, widthPx, heightPx) else bitmap
    }

    /** Moves it to graphics memory: a software bitmap would be held twice, once more as the GPU's texture. */
    override fun keep(picture: Bitmap): Bitmap = StillBitmaps.toHardware(picture)
}

/** Runs [block]; any failure but cancellation means there's nothing to show (Midnight stays). */
suspend fun <T> orNothing(block: suspend () -> T?): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** Bitmap helpers for the still wallpapers. */
object StillBitmaps {
    /** The wallpapers' small working copy: blurred, read for the scrim, drawn under glass. */
    const val SMALL_WIDTH = 480
    const val SMALL_HEIGHT = 270

    /**
     * [source] cut from its centre to [width]:[height] and scaled down to at most
     * [width]×[height]. Returns [source] itself when it already fits.
     */
    fun centreCrop(source: Bitmap, width: Int, height: Int): Bitmap {
        val target = width.toFloat() / height
        val cropWidth = minOf(source.width, (source.height * target).toInt()).coerceAtLeast(1)
        val cropHeight = minOf(source.height, (source.width / target).toInt()).coerceAtLeast(1)
        val scale = minOf(1f, width.toFloat() / cropWidth)
        if (cropWidth == source.width && cropHeight == source.height && scale == 1f) return source
        val matrix = Matrix().apply { setScale(scale, scale) }
        return Bitmap.createBitmap(
            source,
            (source.width - cropWidth) / 2,
            (source.height - cropHeight) / 2,
            cropWidth,
            cropHeight,
            matrix,
            true,
        )
    }

    /** A copy no wider than [SMALL_WIDTH] and no taller than [SMALL_HEIGHT], same shape. */
    fun small(source: Bitmap): Bitmap {
        val scale = minOf(1f, SMALL_WIDTH.toFloat() / source.width, SMALL_HEIGHT.toFloat() / source.height)
        if (scale == 1f) return source
        return source.scale((source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1))
    }

    fun pixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

    /**
     * A big picture moved to graphics memory only, so it isn't held twice (once for the
     * app, once as the GPU's texture). Falls back to [source] where that isn't possible.
     */
    fun toHardware(source: Bitmap): Bitmap {
        val hardware = runCatching { source.copy(Bitmap.Config.HARDWARE, false) }.getOrNull() ?: return source
        source.recycle()
        return hardware
    }
}
