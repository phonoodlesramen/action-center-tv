package com.phonoodlesramen.actioncenter.ui.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.apps.BannerChoice
import com.phonoodlesramen.actioncenter.apps.BannerFile
import com.phonoodlesramen.actioncenter.apps.CustomBanners
import com.phonoodlesramen.actioncenter.apps.CustomBannersState
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.apps.LocalCustomBanners
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.HomeScreen
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Custom banners (L7) in the apps row and Choose art, from made-up banner files drawn
 * like the operator's (660×375, a dark rounded tile with the name in colour). Coil runs
 * unconfined here, so each banner is decoded before the capture.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class CustomBannersScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val folder: File = Files.createTempDirectory("Icons").toFile()

    private val files = listOf(
        banner("Netflix.png", "NETFLIX", 0xFFE50914.toInt()),
        banner("YouTube.png", "YouTube", 0xFFFF3D3D.toInt()),
        banner("Spotify.png", "Spotify", 0xFF1ED760.toInt()),
        banner("Plex.png", "plex", 0xFFE5A00D.toInt()),
        banner("Disney+.png", "Disney+", 0xFF6FA8FF.toInt()),
        banner("PrimeVideo.png", "prime video", 0xFF00A8E1.toInt()),
        banner("Flat icons/NETFLIX.png", "N", 0xFFE50914.toInt(), dark = false),
    )

    @Before
    fun unconfinedImages() {
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(ApplicationProvider.getApplicationContext()).coroutineContext(Dispatchers.Unconfined).build(),
        )
    }

    @After
    fun resetImages() = SingletonImageLoader.reset()

    private fun show(banners: CustomBanners) = compose.setContent {
        val state = SampleHomeState.state()
        CompositionLocalProvider(LocalCustomBanners provides banners) {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                HomeScreen(state, HomeActions.None)
            }
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun appsRowWithCustomBanners() {
        show(FixedBanners(CustomBannersState(files)))
        compose.onNodeWithText("Plezy").requestFocus()
        compose.waitForIdle()
        capture("home_apps_row_custom_banners")
    }

    @Test
    fun chooseArt() {
        show(FixedBanners(CustomBannersState(files)))
        compose.onNodeWithText("Plezy").requestFocus()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onRoot().performKeyInput { pressKey(Key.Menu) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        capture("home_choose_art")
    }

    /** A banner like the operator's: a dark rounded tile with the name in colour, or a flat logo on transparency. */
    private fun banner(name: String, text: String, color: Int, dark: Boolean = true): BannerFile {
        val bitmap = Bitmap.createBitmap(660, 375, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (dark) {
            canvas.drawRoundRect(RectF(6f, 6f, 654f, 369f), 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFF15181C.toInt() })
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = if (text.length > 2) 96f else 220f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(text, 330f, 187f - (paint.ascent() + paint.descent()) / 2, paint)
        val file = File(folder, name).apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return BannerFile(name, file.path)
    }
}

private class FixedBanners(state: CustomBannersState) : CustomBanners {
    override val state: StateFlow<CustomBannersState> = MutableStateFlow(state)
    override fun refresh() {}
    override fun choose(app: LaunchableApp, choice: BannerChoice) {}
}
