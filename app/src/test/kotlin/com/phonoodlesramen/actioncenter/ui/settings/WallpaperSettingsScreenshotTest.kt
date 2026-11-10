package com.phonoodlesramen.actioncenter.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.media.MediaKind
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalWallpaperSources
import com.phonoodlesramen.actioncenter.wallpaper.CardStyle
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperController
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperPrefs
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperSources
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperStyle
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Wallpaper (L1-L4), at the Shield's 1080p TV configuration. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class WallpaperSettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private class FakeController(prefs: WallpaperPrefs, private val canRead: Boolean) : WallpaperController {
        override val prefs = MutableStateFlow(prefs)

        override fun update(change: (WallpaperPrefs) -> WallpaperPrefs) {
            prefs.value = change(prefs.value)
        }

        override fun folders() = listOf("Download", "Pictures/Icons", "Pictures/Wallpapers", "Pictures/Wallpapers/Wallpaper images")

        override fun canReadFolders() = canRead
    }

    private val folder = object : WallpaperSources {
        override fun files(folder: String, kind: MediaKind) = when (kind) {
            MediaKind.IMAGE -> listOf("Aurora over the fjord.png", "Lake at blue hour.jpg", "Neon city rain.png").map(::File)
            MediaKind.VIDEO -> listOf("Fireplace.mp4", "Ocean waves 4K.mp4").map(::File)
        }
    }

    private fun show(controller: WallpaperController) {
        val state = SampleHomeState.state()
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                CompositionLocalProvider(LocalWallpaperSources provides folder) {
                    SettingsScreen(state, SettingsActions.None, onBack = {}, initialSection = SettingsSection.WALLPAPER, wallpaper = controller)
                }
            }
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun midnightByDefault() {
        show(FakeController(WallpaperPrefs(), canRead = true))
        capture("settings_wallpaper")
    }

    /** Scrolled down to its own controls: the folder, the files, fit, pan, blur and dim. */
    @Test
    fun yourImagesWithItsFolderAndFiles() {
        show(FakeController(WallpaperPrefs(style = WallpaperStyle.YOUR_IMAGES, cardStyle = CardStyle.GLASS), canRead = true))
        compose.onNodeWithText("Show it whole").requestFocus()
        capture("settings_wallpaper_your_images")
    }

    @Test
    fun videoLoopBeforeTheStorageGrant() {
        show(FakeController(WallpaperPrefs(style = WallpaperStyle.VIDEO_LOOP, dim = 0.3f), canRead = false))
        compose.onNodeWithText("+").requestFocus()
        capture("settings_wallpaper_no_grant")
    }

    @Test
    fun theFolderChooser() {
        show(FakeController(WallpaperPrefs(style = WallpaperStyle.YOUR_IMAGES), canRead = true))
        compose.onNodeWithText("Change").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        capture("settings_wallpaper_folders")
    }

    @Test
    fun choicesChangeTheSettings() {
        val controller = FakeController(WallpaperPrefs(), canRead = true)
        show(controller)
        compose.onNodeWithText("Cover art").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Strongest").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("+").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("+").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Frosted glass").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        val prefs = controller.prefs.value
        assertEquals(WallpaperStyle.COVER_ART, prefs.style)
        assertEquals(1f, prefs.blur)
        assertEquals(0.2f, prefs.dim, 0.0001f)
        assertEquals(CardStyle.GLASS, prefs.cardStyle)
    }

    @Test
    fun choosingAFolderStartsItsSlideshow() {
        val controller = FakeController(WallpaperPrefs(style = WallpaperStyle.YOUR_IMAGES, image = "Lake at blue hour.jpg"), canRead = true)
        show(controller)
        compose.onNodeWithText("Change").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Download").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals("Download", controller.prefs.value.folder)
        assertEquals(null, controller.prefs.value.image)
        compose.onNodeWithText("Change").assertExists()
    }

    @Test
    fun theStepsStopAtTheirEnds() {
        assertEquals(0.8f, WallpaperSteps.dim(0.8f, 1), 0.0001f)
        assertEquals(0f, WallpaperSteps.dim(0f, -1), 0.0001f)
        assertEquals(0.4f, WallpaperSteps.dim(0.33f, 1), 0.0001f)
        assertEquals(10, WallpaperSteps.step(WallpaperSteps.SlideshowMinutes, 5, 1))
        assertEquals(5, WallpaperSteps.step(WallpaperSteps.SlideshowMinutes, 7, -1))
        assertEquals(60, WallpaperSteps.step(WallpaperSteps.SlideshowMinutes, 60, 1))
        assertEquals(1, WallpaperSteps.step(WallpaperSteps.IdleMinutes, 1, -1))
        assertEquals(0.6f, WallpaperSteps.nearest(WallpaperSteps.Blur, 0.55f))
    }
}
