package com.phonoodlesramen.actioncenter.ui.wallpaper.still

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * The app's decoder (Coil 3) on real files: no picture is decoded larger than asked, so
 * the operator's 5120×5120 PNGs come out at screen size or smaller.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoilStillImagesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val images = CoilStillImages(context, ImageLoader.Builder(context).build())

    private fun png(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF203040.toInt())
        canvas.drawRect(width / 4f, height / 4f, width * 3 / 4f, height * 3 / 4f, Paint().apply { color = 0xFFE0A060.toInt() })
        val file = folder.newFile("${width}x$height.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    /** Coil may hop to the main thread, so it keeps turning while the test waits. */
    private fun load(file: File, width: Int, height: Int, crop: Boolean): Bitmap? {
        val result = CoroutineScope(Dispatchers.IO).async { images.load(file, width, height, crop) }
        while (!result.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return runBlocking { result.await() }
    }

    private fun Bitmap.size() = width to height

    @Test
    fun aHugeSquareIsCutToTheScreenWhenItFills() {
        val bitmap = load(png(3200, 3200), 1920, 1080, crop = true)
        assertNotNull(bitmap)
        assertEquals(1920 to 1080, bitmap!!.size())
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
    }

    @Test
    fun aHugeSquareFitsInsideTheScreenWhole() {
        assertEquals(1080 to 1080, load(png(3200, 3200), 1920, 1080, crop = false)!!.size())
    }

    @Test
    fun aWidePictureFitsByItsWidth() {
        assertEquals(1920 to 800, load(png(2400, 1000), 1920, 1080, crop = false)!!.size())
    }

    @Test
    fun aSmallPictureIsNeverScaledUp() {
        assertEquals(800 to 450, load(png(800, 450), 1920, 1080, crop = true)!!.size())
        assertEquals(640 to 360, load(png(640, 360), 1920, 1080, crop = false)!!.size())
    }

    @Test
    fun theSmallCopyForBlurIsAtMost480By270() {
        assertEquals(480 to 270, load(png(3200, 3200), StillBitmaps.SMALL_WIDTH, StillBitmaps.SMALL_HEIGHT, crop = true)!!.size())
        val large = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        assertEquals(480 to 270, StillBitmaps.small(large).size())
    }

    @Test
    fun aMissingFileIsNothing() {
        assertEquals(null, load(File(folder.root, "gone.png"), 1920, 1080, crop = true))
    }

    @Test
    fun keepingABigPictureKeepsItsSize() {
        val kept = images.keep(Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888))
        assertEquals(1920 to 1080, kept.size())
    }
}
