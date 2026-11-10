package com.phonoodlesramen.actioncenter.ui.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.phonoodlesramen.actioncenter.media.MediaKind
import com.phonoodlesramen.actioncenter.ui.art.ArtUrls
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.StillBitmaps
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.StillImages
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperSources
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Wallpaper pictures for screenshot tests, with no network and no background thread:
 * files decode where they are, `test://backdrop` is a film backdrop from the test
 * resources, and `test://poster/N` is a poster drawn here.
 */
object TestWallpapers {
    /** A wallpaper folder holding one picture (src/test/resources/wallpaper). */
    val lake: File = resource("wallpaper/Lake at blue hour.jpg")

    val sources = object : WallpaperSources {
        override suspend fun posters(limit: Int) = (0 until minOf(limit, 30)).map { "/library/metadata/$it/thumb" }

        override fun files(folder: String, kind: MediaKind) = if (kind == MediaKind.IMAGE) listOf(lake) else emptyList()
    }

    /** Posters go to `test://poster/N`; anything else is the backdrop. */
    val artUrls = ArtUrls { path, _, _ ->
        if (path.endsWith("/thumb")) "test://poster/${path.split('/')[3]}" else "test://backdrop"
    }

    val images = object : StillImages {
        override val work: CoroutineContext = EmptyCoroutineContext

        override suspend fun load(model: Any, widthPx: Int, heightPx: Int, crop: Boolean, cache: Boolean): Bitmap? {
            val decoded = when {
                model is File -> BitmapFactory.decodeFile(model.path)
                model == "test://backdrop" -> BitmapFactory.decodeFile(resource("wallpaper/backdrop.jpg").path)
                model is String && model.startsWith("test://poster/") -> poster(model.substringAfterLast('/').toInt())
                else -> null
            } ?: return null
            if (crop) return StillBitmaps.centreCrop(decoded, widthPx, heightPx)
            val scale = minOf(1f, widthPx.toFloat() / decoded.width, heightPx.toFloat() / decoded.height)
            return if (scale == 1f) decoded else Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        }
    }

    /** A 120×180 poster: a two-colour gradient with a title band, its colours from [index]. */
    private fun poster(index: Int): Bitmap {
        val hues = listOf(0xFFE57373, 0xFF64B5F6, 0xFFFFD54F, 0xFF81C784, 0xFFBA68C8, 0xFFFF8A65, 0xFF4DB6AC, 0xFFF06292)
        val top = hues[index % hues.size].toInt()
        val bottom = hues[(index * 3 + 1) % hues.size].toInt() and 0xFF7F7F7F.toInt()
        val bitmap = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 120f, 180f, top, bottom, Shader.TileMode.CLAMP) })
        canvas.drawRect(10f, 130f, 110f, 146f, Paint().apply { color = 0xFFF5F5F5.toInt() })
        canvas.drawCircle(60f, 70f, 26f + index % 4 * 4, Paint().apply { color = 0x66FFFFFF })
        return bitmap
    }

    private fun resource(path: String): File = File(requireNotNull(TestWallpapers::class.java.classLoader?.getResource(path)) { path }.toURI())
}
