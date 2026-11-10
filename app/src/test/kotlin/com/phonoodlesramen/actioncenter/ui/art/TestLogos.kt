package com.phonoodlesramen.actioncenter.ui.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import java.io.IOException
import java.net.URI
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation

/**
 * Made-up logos for screenshot tests (Phase 12): wordmarks of made-up titles drawn here,
 * never a real logo or trademark. A logo path `/logo/NAME` goes to `test://logo/NAME`, and
 * [loader]'s interceptor draws it, fitted inside the box as Plex's transcoder sends it
 * (`minSize=0`). `slow` never arrives and `missing` fails. Art stays placeholder art. Coil
 * runs unconfined, so every logo is loaded and checked before a capture.
 */
object TestLogos {
    /** "STARFALL HARBOR" in white over an amber rule: about 6:1. */
    const val WIDE = "/logo/wide"

    /** "THE LANTERN / KEEPERS" in gold on two lines: about 3:2. */
    const val TALL = "/logo/tall"

    /**
     * "EMBER LINE" and "SCARLET MILE" in a deep red (`#E50914`, luminance 0.17): enough on the
     * hero's scrim, too dark for the warmer lens tints.
     */
    const val RED = "/logo/red"
    const val RED_LENS = "/logo/redlens"

    /** "NIGHT FERRY" in navy, and "IRONWOOD HOLLOW" in black: too dark for every card. */
    const val NAVY = "/logo/navy"
    const val BLACK = "/logo/black"

    /** "PEBBLE & PIP" in yellow and coral, for a kid. */
    const val KID = "/logo/kid"

    /** Never arrives: the title waits in the logo's box. */
    const val SLOW = "/logo/slow"

    val artUrls = object : ArtUrls {
        override fun url(path: String, widthPx: Int, heightPx: Int): String? = null

        override fun logoUrl(path: String, widthPx: Int, heightPx: Int) = "test://logo/${path.substringAfterLast('/')}?w=$widthPx&h=$heightPx"
    }

    fun loader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .coroutineContext(Dispatchers.Unconfined)
        .components { add(Drawn) }
        .build()

    private object Drawn : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val uri = URI(chain.request.data as String)
            val size = uri.query.split('&').associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
            val logo = when (uri.path.removePrefix("/")) {
                "wide" -> wordmark(listOf("STARFALL HARBOR" to 0xFFFFFFFF), size = 140f, rule = 0xFFE5A00D)
                "tall" -> wordmark(listOf("THE LANTERN" to 0xFFF2C14E, "KEEPERS" to 0xFFF2C14E), size = 120f)
                "red" -> wordmark(listOf("EMBER LINE" to 0xFFE50914), size = 140f)
                "redlens" -> wordmark(listOf("SCARLET MILE" to 0xFFE50914), size = 140f)
                "navy" -> wordmark(listOf("NIGHT FERRY" to 0xFF14213D), size = 140f)
                "black" -> wordmark(listOf("IRONWOOD HOLLOW" to 0xFF000000), size = 140f)
                "kid" -> wordmark(listOf("PEBBLE" to 0xFFFFD54F, "& PIP" to 0xFFFF8A65), size = 130f)
                "slow" -> awaitCancellation()
                else -> return ErrorResult(null, chain.request, IOException("404"))
            }
            val scale = minOf(size.getValue("w").toFloat() / logo.width, size.getValue("h").toFloat() / logo.height)
            val fitted = Bitmap.createScaledBitmap(
                logo,
                (logo.width * scale).roundToInt().coerceAtLeast(1),
                (logo.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
            return SuccessResult(fitted.asImage(), chain.request, DataSource.MEMORY)
        }
    }

    /** [lines] of bold text, each in its colour and centred, on transparency, trimmed to them; an optional [rule] under them. */
    private fun wordmark(lines: List<Pair<String, Long>>, size: Float, rule: Long? = null): Bitmap {
        val paints = lines.map { (_, color) ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color.toInt()
                textSize = size
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                letterSpacing = 0.06f
            }
        }
        val bounds = lines.mapIndexed { i, (text, _) -> Rect().also { paints[i].getTextBounds(text, 0, text.length, it) } }
        val gap = size * 0.18f
        val ruleHeight = if (rule != null) size * 0.12f else 0f
        val width = bounds.maxOf { it.width() } + 8
        val height = (bounds.sumOf { it.height() } + gap * (lines.size - 1) + if (rule != null) gap + ruleHeight else 0f).roundToInt() + 8
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        var top = 4f
        lines.forEachIndexed { i, (text, _) ->
            val box = bounds[i]
            canvas.drawText(text, (width - box.width()) / 2f - box.left, top - box.top, paints[i])
            top += box.height() + gap
        }
        if (rule != null) {
            canvas.drawRect(width * 0.2f, top, width * 0.8f, top + ruleHeight, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rule.toInt() })
        }
        return bitmap
    }
}
