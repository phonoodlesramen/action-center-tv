package com.phonoodlesramen.actioncenter.ui.home.rows

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
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
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.phonoodlesramen.actioncenter.home.HomeRowsState
import com.phonoodlesramen.actioncenter.tvprovider.ChannelsAndAppsController
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.HomeScreen
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.theme.LayoutScale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The D-pad into and around Live and Continue in your apps (L8-L10), and the shared row
 * frame (G20 C): the rows line up with the dashboard at every layout scale, and the first
 * row below the dashboard lands in one place.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class OtherAppsRowsFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val hidden = mutableListOf<Pair<String, Boolean>>()
    private val said = mutableListOf<String>()
    private val actions = object : HomeActions {
        override fun say(message: String) {
            said += message
        }
    }
    private val controller = object : ChannelsAndAppsController {
        override fun setAppShown(packageName: String, shown: Boolean) {
            hidden += packageName to shown
        }
    }

    private var scale by mutableFloatStateOf(1f)

    private fun show(rows: HomeRowsState = SampleHomeRows.rows) = compose.setContent {
        // A new home for each scale, as coming back from Settings › Look makes one.
        key(scale) {
            val state = SampleHomeState.state(layoutScale = scale)
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                CompositionLocalProvider(LocalAppBadges provides SampleHomeRows.badges, LocalChannelsAndApps provides controller) {
                    HomeScreen(state, actions, rows = rows)
                }
            }
        }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun card(description: String) = compose.onNodeWithContentDescription(description, substring = true)

    private fun assertFocused(description: String) = compose.onNode(isFocused()).assert(hasContentDescription(description, substring = true))

    private fun top(text: String) = compose.onNodeWithText(text, ignoreCase = true).getBoundsInRoot().top

    @Test
    fun `the rows line up with the dashboard and each other at 90, 100 and 115 percent`() {
        show()
        for (each in LayoutScale.Options) {
            scale = each
            compose.waitForIdle()
            // Unclipped px: the lower rows are below the screen's edge.
            fun place(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().let { it.positionInRoot.x to it.size }
            val dashboard = place(card("Slow Horses")).first
            val found = listOf("LIVE", "CONTINUE IN YOUR APPS", "APPS").associateWith { place(compose.onNodeWithText(it)) } +
                listOf("ESPN", "Bluey").associateWith { place(card(it)) } +
                ("Plezy" to place(compose.onNodeWithText("Plezy")))
            found.forEach { (name, at) -> assertEquals("$each, $name: $found", dashboard, at.first, 1f) }
            // One card size in every row: the apps row's, from RowMetrics.
            val cards = listOf("ESPN", "Bluey", "Plezy").map { found.getValue(it).second }
            cards.forEach { assertEquals("$each: $cards", cards.last(), it) }
        }
    }

    @Test
    fun `up from the next row, Continue in your apps lands where it did coming down, not at the top`() {
        show(SampleHomeRows.rows.copy(channels = emptyList()))
        card("Slow Horses").requestFocus()
        press(Key.DirectionDown)
        assertFocused("Bluey")
        val label = top("Continue in your apps")
        press(Key.DirectionDown)
        compose.onNode(isFocused()).assert(hasText("Plezy", substring = true))
        press(Key.DirectionUp)
        assertFocused("Bluey")
        assertEquals(label, top("Continue in your apps"))
        compose.onNodeWithContentDescription("Settings").assertIsNotDisplayed()
    }

    @Test
    fun `down from Up next reaches Channels, then Continue in your apps, and up comes back`() {
        show()
        card("Slow Horses").requestFocus()
        press(Key.DirectionDown)
        assertFocused("ESPN")
        press(Key.DirectionDown)
        assertFocused("Bluey")
        press(Key.DirectionUp)
        assertFocused("ESPN")
        press(Key.DirectionUp)
        card("Slow Horses").assertIsFocused()
    }

    @Test
    fun `without channels, down from Up next goes straight to Continue in your apps`() {
        show(SampleHomeRows.rows.copy(channels = emptyList()))
        card("Slow Horses").requestFocus()
        press(Key.DirectionDown)
        assertFocused("Bluey")
    }

    @Test
    fun `each row remembers its card`() {
        show()
        card("Bluey").requestFocus()
        press(Key.DirectionRight, times = 2)
        assertFocused("The Boys")
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        assertFocused("The Boys")
    }

    @Test
    fun `channels remember their card`() {
        show()
        card("NBC").requestFocus()
        press(Key.DirectionDown)
        press(Key.DirectionLeft)
        press(Key.DirectionUp)
        assertFocused("NBC")
    }

    @Test
    fun `the row scrolls along with focus`() {
        show()
        card("Bluey").requestFocus()
        press(Key.DirectionRight, times = 6)
        assertFocused("Building a Plex server")
    }

    @Test
    fun `hiding an app keeps focus in the row, on the card that took its place`() {
        show()
        card("Bluey").requestFocus()
        press(Key.Menu)
        compose.onNodeWithText("Hide this app from the row").assertExists()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)

        assertEquals(listOf("com.disney.disneyplus" to false), hidden)
        assertFocused("Restoring a 1970s Sony Trinitron")
        card("Moana 2").assertDoesNotExist()
        assertEquals(listOf("Disney+ is hidden from Continue in your apps. Settings › Channels & apps brings it back."), said)
    }

    @Test
    fun `hiding the only app moves focus up to Channels`() {
        show(SampleHomeRows.rows.copy(continueInApps = SampleHomeRows.continueInApps.filter { it.packageName == "com.disney.disneyplus" }))
        card("Moana 2").requestFocus()
        press(Key.Menu)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)

        compose.onNodeWithText("Continue in your apps", ignoreCase = true).assertDoesNotExist()
        compose.onNode(isFocused()).assert(
            hasContentDescription("ESPN") or hasContentDescription("FOX Sports 1") or hasContentDescription("PBS"),
        )
    }
}
