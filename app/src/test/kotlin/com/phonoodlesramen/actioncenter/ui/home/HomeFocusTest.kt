package com.phonoodlesramen.actioncenter.ui.home

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.phonoodlesramen.actioncenter.apps.BannerChoice
import com.phonoodlesramen.actioncenter.apps.BannerFile
import com.phonoodlesramen.actioncenter.apps.CustomBanners
import com.phonoodlesramen.actioncenter.apps.CustomBannersState
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.apps.LocalCustomBanners
import com.phonoodlesramen.actioncenter.apps.key
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The D-pad rules of spec §3, one test per row of its focus table, plus Home, quick
 * actions, the apps row and its scrolling (L5, L6), and Choose art (L7).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class HomeFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var focus by mutableStateOf(FocusRequest())
    private var state by mutableStateOf(SampleHomeState.state())
    private val banners = FakeCustomBanners()
    private val opened = mutableListOf<String>()

    /** Hiding an app updates the state as the view model would; opening things is recorded. */
    private val actions = object : HomeActions {
        override fun setHidden(app: LaunchableApp, hidden: Boolean) {
            state = state.copy(hiddenApps = state.hiddenApps + app.key, launchpad = state.launchpad - app)
        }

        override fun openGuide(channelId: String?) {
            opened += "guide"
        }

        override fun play(id: String, title: String) {
            opened += "play $title"
        }

        override fun previousLensPick() {
            opened += "back"
        }

        override fun openSearch() {
            opened += "search"
        }
    }

    private fun show(initial: HomeUiState = SampleHomeState.state()) {
        state = initial
        compose.setContent {
            CompositionLocalProvider(LocalCustomBanners provides banners) {
                ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                    HomeScreen(state, actions, focus = focus)
                }
            }
        }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun focusOn(text: String) = compose.onNodeWithText(text).requestFocus()

    private fun assertFocused(text: String) = compose.onNodeWithText(text).assertIsFocused()

    private fun upNext(title: String) = compose.onNodeWithContentDescription(title, substring = true)

    private fun guide() = compose.onNodeWithText("Your library as TV channels")

    private fun focusedText() = compose.onNode(isFocused()).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()

    /** Wholly inside the screen: nothing of it under an edge. */
    private fun SemanticsNodeInteraction.assertOnScreen() = apply {
        val bounds = getBoundsInRoot()
        val screen = compose.onRoot().getBoundsInRoot()
        assertTrue("$bounds is outside $screen", bounds.top >= screen.top && bounds.bottom <= screen.bottom)
    }

    @Test
    fun `Play has focus when the home screen opens`() {
        show()
        assertFocused("Play S2E9")
    }

    @Test
    fun `before Plex, Get a new code has focus`() {
        show(SampleHomeState.state(sample = false))
        assertFocused("Get a new code")
    }

    @Test
    fun `Home while already home goes back to Play, at the top`() {
        show()
        focusOn("YouTube")
        focus = FocusRequest(HomeFocus.PLAY, serial = 1)
        compose.waitForIdle()
        assertFocused("Play S2E9")
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun `hero buttons go up to the top bar`() {
        show()
        press(Key.DirectionUp)
        compose.onNode(isFocused()).assert(hasContentDescription("Settings"))
    }

    @Test
    fun `right from the last hero button goes to the lens card`() {
        show()
        press(Key.DirectionRight, times = 2)
        assertFocused("Not tonight")
        press(Key.DirectionRight)
        assertFocused("Watch again")
    }

    @Test
    fun `down from the hero goes to Up next, which remembers its card`() {
        show()
        press(Key.DirectionDown)
        upNext("Slow Horses").assertIsFocused()
        press(Key.DirectionRight)
        upNext("Shōgun").assertIsFocused()
        press(Key.DirectionUp)
        assertFocused("Play S2E9")
        press(Key.DirectionDown)
        upNext("Shōgun").assertIsFocused()
    }

    @Test
    fun `the lens card goes down to Quick Watch`() {
        show()
        focusOn("Watch again")
        press(Key.DirectionDown)
        assertFocused("The Bear")
    }

    @Test
    fun `Up from Watch or Next reaches Back at the card's top left, Down comes back, and Back asks for the title before`() {
        show()
        focusOn("Watch again")
        press(Key.DirectionUp)
        compose.onNode(isFocused()).assert(hasContentDescription("Back"))
        press(Key.DirectionDown)
        assertFocused("Watch again")

        focusOn("Next")
        press(Key.DirectionUp)
        compose.onNode(isFocused()).assert(hasContentDescription("Back"))
        press(Key.DirectionCenter)
        assertEquals(listOf("back"), opened)

        press(Key.DirectionUp)
        compose.onNodeWithContentDescription("Back").assertIsNotFocused()
    }

    @Test
    fun `the magnifier sits just before the gear and opens Search, and Back from Search lands on it (Phase 15)`() {
        show()
        press(Key.DirectionUp)
        compose.onNode(isFocused()).assert(hasContentDescription("Settings"))
        press(Key.DirectionLeft)
        compose.onNode(isFocused()).assert(hasContentDescription("Search"))
        press(Key.DirectionCenter)
        assertEquals(listOf("search"), opened)

        focusOn("Watch again")
        focus = FocusRequest(HomeFocus.SEARCH, serial = 1)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Search").assertIsFocused()
    }

    @Test
    fun `coming into the lens card lands on Watch, not Back`() {
        show()
        press(Key.DirectionUp)
        compose.onNode(isFocused()).assert(hasContentDescription("Settings"))
        // The gear sits over the lens card, and Back is the card's nearest button to it.
        press(Key.DirectionDown)
        assertFocused("Watch again")
    }

    @Test
    fun `Quick Watch goes left to the hero button it came from`() {
        show()
        press(Key.DirectionRight, times = 3)
        press(Key.DirectionDown)
        assertFocused("The Bear")
        press(Key.DirectionLeft)
        assertFocused("Not tonight")
    }

    @Test
    fun `Quick Watch goes down to the Guide card`() {
        show()
        focusOn("Abbott Elementary")
        press(Key.DirectionDown)
        guide().assertIsFocused()
    }

    @Test
    fun `Up next goes right to the Guide card`() {
        show()
        upNext("Bake Off").requestFocus()
        press(Key.DirectionRight)
        guide().assertIsFocused()
    }

    @Test
    fun `the Guide card goes up to Quick Watch and left to Up next`() {
        show()
        guide().requestFocus()
        press(Key.DirectionUp)
        assertFocused("Abbott Elementary")
        guide().requestFocus()
        press(Key.DirectionLeft)
        upNext("Bake Off").assertIsFocused()
    }

    @Test
    fun `OK on the Guide card opens the Guide`() {
        show()
        guide().requestFocus()
        press(Key.DirectionCenter)
        assertEquals(listOf("guide"), opened)
        guide().assertIsFocused()
    }

    @Test
    fun `at the top the apps row peeks below the dashboard with its label whole`() {
        show()
        compose.onNodeWithText("APPS").assertIsDisplayed().assertOnScreen()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun `Up next goes down to the apps row, which scrolls into view whole`() {
        show()
        press(Key.DirectionDown, times = 2)
        assertFocused("Plezy")
        compose.onNodeWithText("Plezy").assertOnScreen()
        compose.onNodeWithText("APPS").assertIsDisplayed().assertOnScreen()
        // The top bar scrolled away and Row A meets the top edge; Up next stays in view.
        compose.onNodeWithContentDescription("Settings").assertIsNotDisplayed()
        upNext("Slow Horses").assertIsDisplayed()
    }

    @Test
    fun `the Guide card goes down to the apps row`() {
        show()
        guide().requestFocus()
        press(Key.DirectionDown)
        compose.onNode(isFocused()).assert(hasText("Spotify") or hasText("Plex"))
    }

    @Test
    fun `up from the apps row goes back to the dashboard, scrolled to the top`() {
        show()
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionUp)
        upNext("Slow Horses").assertIsFocused()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun `the apps row remembers its card`() {
        show()
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionRight)
        assertFocused("YouTube")
        press(Key.DirectionUp)
        press(Key.DirectionDown)
        assertFocused("YouTube")
    }

    @Test
    fun `the apps row has every app, launchpad order first, then All apps`() {
        show()
        press(Key.DirectionDown, times = 2)
        val seen = mutableListOf<String>()
        repeat(10) {
            seen += focusedText().substringAfterLast(", ")
            press(Key.DirectionRight)
        }
        assertEquals(
            listOf("Plezy", "YouTube", "Netflix", "Spotify", "Plex", "Disney+", "Kodi", "Prime Video", "Twitch", "All apps"),
            seen,
        )
        compose.onNodeWithText("All apps").assertIsFocused().assertOnScreen()
    }

    @Test
    fun `Back from All apps lands on the All apps card, even scrolled out of its row`() {
        show()
        focus = FocusRequest(HomeFocus.ALL_APPS, serial = 1)
        compose.waitForIdle()
        assertFocused("All apps")
        compose.onNodeWithText("All apps").assertOnScreen()
    }

    @Test
    fun `hiding an app moves focus to the card that takes its place`() {
        show()
        focusOn("YouTube")
        press(Key.Menu)
        assertFocused("Open")
        press(Key.DirectionDown)
        assertFocused("Hide from launchpad")
        press(Key.DirectionCenter)
        compose.onNodeWithText("YouTube").assertDoesNotExist()
        assertFocused("Netflix")
    }

    @Test
    fun `Choose art opens from quick actions, and Back returns to the card`() {
        show()
        focusOn("YouTube")
        press(Key.Menu)
        press(Key.DirectionDown, times = 2)
        assertFocused("Choose art")
        press(Key.DirectionCenter)
        compose.onNodeWithText("CHOOSE ART").assertIsDisplayed()
        // The exact normalised name wins over Youtube TV.png and the Flat icons copy.
        compose.onNodeWithText("Automatic · YouTube.png").assertIsDisplayed()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithText("CHOOSE ART").assertDoesNotExist()
        assertFocused("YouTube")
        assertTrue(banners.chosen.isEmpty())
    }

    @Test
    fun `picking art saves it for that app and returns to its card`() {
        show()
        focusOn("YouTube")
        press(Key.Menu)
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionCenter)
        // Automatic has focus; the app's own banner is next to it.
        press(Key.DirectionRight)
        press(Key.DirectionCenter)

        assertEquals(listOf("YouTube" to BannerChoice.OwnBanner), banners.chosen.map { it.first.label to it.second })
        compose.onNodeWithText("CHOOSE ART").assertDoesNotExist()
        assertFocused("YouTube")
    }

    @Test
    fun `the menu button opens quick actions, and Back returns focus to the opener`() {
        show()
        focusOn("Not tonight")
        press(Key.Menu)
        assertFocused("Play")
        compose.onNodeWithText("Why this pick?").assertExists()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithText("Why this pick?").assertDoesNotExist()
        assertFocused("Not tonight")
    }

    @Test
    fun `holding OK opens quick actions, and letting go doesn't pick the first one`() {
        show()
        fun ok(action: Int, repeat: Int) =
            compose.onRoot().performKeyPress(KeyEvent(NativeKeyEvent(0L, 0L, action, NativeKeyEvent.KEYCODE_DPAD_CENTER, repeat)))
        // The press, its first repeat (the long click), more repeats once the sheet has focus, then the release.
        ok(NativeKeyEvent.ACTION_DOWN, 0)
        ok(NativeKeyEvent.ACTION_DOWN, 1)
        compose.waitForIdle()
        ok(NativeKeyEvent.ACTION_DOWN, 2)
        ok(NativeKeyEvent.ACTION_UP, 0)
        compose.waitForIdle()

        assertFocused("Play")
        compose.onNodeWithText("Why this pick?").assertExists()
        assertTrue(opened.isEmpty())
        // A press made in the sheet still picks.
        press(Key.DirectionCenter)
        assertEquals(listOf("play Severance"), opened)
    }

    @Test
    fun `quick actions keep focus inside the sheet`() {
        show()
        press(Key.Menu)
        press(Key.DirectionLeft)
        press(Key.DirectionUp)
        assertFocused("Play")
    }
}

/** An Icons folder with the operator's three YouTube banners, recording what Choose art saves. */
private class FakeCustomBanners : CustomBanners {
    val chosen = mutableListOf<Pair<LaunchableApp, BannerChoice>>()

    override val state: StateFlow<CustomBannersState> = MutableStateFlow(
        CustomBannersState(
            files = listOf("Flat icons/YOUTUBE.png", "YouTube.png", "Youtube TV.png").map { BannerFile(it, "/nowhere/$it") },
        ),
    )

    override fun refresh() {}

    override fun choose(app: LaunchableApp, choice: BannerChoice) {
        chosen += app to choice
    }
}
