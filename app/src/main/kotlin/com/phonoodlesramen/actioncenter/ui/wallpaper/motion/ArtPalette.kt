package com.phonoodlesramen.actioncenter.ui.wallpaper.motion

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.phonoodlesramen.actioncenter.ui.art.ArtUrls
import com.phonoodlesramen.actioncenter.ui.art.LocalArtUrls
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Three colours from a title's art (a Plex path), left to right; null when there's no art to read. */
fun interface ArtPaletteSource {
    suspend fun colors(path: String): List<Color>?
}

/** Null uses Plex art through Coil ([CoilArtPalette]); screenshot tests provide their own images. */
val LocalArtPaletteSource = staticCompositionLocalOf<ArtPaletteSource?> { null }

/**
 * Reads art at [ArtColors.WIDTH]×[ArtColors.HEIGHT] pixels: Plex's transcoder shrinks it,
 * so a few hundred bytes cross the network and Coil caches a bitmap of 576 bytes.
 */
class CoilArtPalette(private val context: Context, private val urls: ArtUrls) : ArtPaletteSource {
    override suspend fun colors(path: String): List<Color>? {
        val url = urls.url(path, ArtColors.WIDTH, ArtColors.HEIGHT) ?: return null
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(ArtColors.WIDTH, ArtColors.HEIGHT)
            // The pixels are read back, which a hardware bitmap doesn't allow.
            .allowHardware(false)
            .build()
        return try {
            val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
            withContext(Dispatchers.Default) {
                val bitmap = result.image.toBitmap(ArtColors.WIDTH, ArtColors.HEIGHT)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                ArtColors.extract(pixels, bitmap.width, bitmap.height).map { Color(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A wallpaper never breaks the home screen: no colours, so the accent shows.
            null
        }
    }
}

/**
 * The colours of [path]'s art, null until they load (and with no art or no server). A
 * new path keeps the old colours until its own arrive, so the wallpaper never flashes.
 */
@Composable
fun rememberArtPalette(path: String?): State<List<Color>?> {
    val custom = LocalArtPaletteSource.current
    val context = LocalContext.current
    val urls = LocalArtUrls.current
    val source = custom ?: remember(context, urls) { CoilArtPalette(context.applicationContext, urls) }
    return produceState<List<Color>?>(initialValue = null, path, source) {
        value = path?.let { source.colors(it) }
    }
}
