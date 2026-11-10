package com.phonoodlesramen.actioncenter.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
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
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.LensCursor
import com.phonoodlesramen.actioncenter.content.LensPrefs
import com.phonoodlesramen.actioncenter.content.LensSchedule
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.home.lensDeck
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import com.phonoodlesramen.actioncenter.ui.home.rows.SampleHomeRows
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The kids' home (K7, K8, K9) for Maya, 9, on a weeknight: what's on it and what isn't, her
 * lens card (R1), My apps from her allowlist only, the kid-safe Live row, and the D-pad
 * reaching every panel.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class KidHomeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var focus by mutableStateOf(FocusRequest())
    private val done = mutableListOf<String>()

    private val actions = object : HomeActions {
        override fun launch(app: LaunchableApp) {
            done += "launch ${app.label}"
        }

        override fun openAllApps() {
            done += "all apps"
        }

        override fun play(id: String, title: String) {
            done += "play $title"
        }

        override fun tuneIn(channelId: String) {
            done += "tune in $channelId"
        }
    }

    /** With YouTube TV's networks and Continue in your apps on offer, which a kid's home leaves out. */
    private fun show(state: HomeUiState = SampleHomeState.kid(), guide: GuideCardState = SampleGuide.withKidChannels(state.nowMillis)) {
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                HomeScreen(state, actions, focus = focus, rows = SampleHomeRows.rows, guide = guide)
            }
        }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun assertFocused(text: String) = compose.onNodeWithText(text).assertIsFocused()

    private fun upNext(title: String) = compose.onNodeWithContentDescription(title, substring = true)

    private fun live(name: String) = compose.onNodeWithContentDescription("$name,", substring = true)

    @Test
    fun `Play has focus, under Hi Maya, with her pick and Remember when`() {
        show()
        assertFocused("Play")
        compose.onNodeWithText("Hi Maya!").assertIsDisplayed()
        compose.onNodeWithText("PICK FOR YOU").assertIsDisplayed()
        compose.onNodeWithText("The Wild Robot").assertIsDisplayed()
        compose.onNodeWithText("Not now").assertIsDisplayed()
        compose.onNodeWithText("REMEMBER WHEN").assertIsDisplayed()
        compose.onNodeWithText("Paddington 2").assertIsDisplayed()
        // No Quick Watch, no lens card's Next, no Guide card.
        compose.onNodeWithText("Next").assertDoesNotExist()
        compose.onNodeWithText("Your library as TV channels").assertDoesNotExist()
        compose.onNodeWithText("Coming up at", substring = true).assertDoesNotExist()
    }

    @Test
    fun `without a Throwback, the time's lens leads, For the family on a Saturday morning`() {
        show(SampleHomeState.kid(SampleHomeState.saturdayMorning))
        compose.onNodeWithText("FOR THE FAMILY").assertIsDisplayed()
        compose.onNodeWithText("My Neighbor Totoro").assertIsDisplayed()
        compose.onNodeWithText("Watch").assertIsDisplayed()
    }

    @Test
    fun `with Throwback off in Settings, Remember when doesn't lead`() {
        show(SampleHomeState.kid(disabledLenses = setOf(Lens.THROWBACK)))
        compose.onNodeWithText("REMEMBER WHEN").assertDoesNotExist()
        compose.onNodeWithText("CLASSIC").assertIsDisplayed()
        compose.onNodeWithText("The Iron Giant").assertIsDisplayed()
    }

    /**
     * The kids' lens card (R1) with your lens card's quick actions: Another lens moves it on to
     * the next lens with a title, in Settings order, as the view model does; Don't show this
     * says which title. Focus comes back to the card's button.
     */
    @Test
    fun `the lens card's quick actions are yours, and Another lens moves it on`() {
        var cursor = LensCursor()
        // Settings order, so Another lens is the next lens down the list.
        val inOrder = LensPrefs(shuffle = false)
        var home by mutableStateOf(SampleHomeState.kid(lensPrefs = inOrder))
        val lensActions = object : HomeActions by actions {
            override fun nextLens() {
                done += "another lens"
                cursor = cursor.nextLens(home.lensDeck(), LensSchedule.lead(home.now, home.schedule))
                home = SampleHomeState.kid(cursor = cursor, lensPrefs = inOrder)
            }

            override fun dontShowLensPick() {
                done += "don't show ${home.lens?.pick?.title}"
            }
        }
        compose.setContent {
            ActionCenterTheme(accent = home.accent, layoutScale = home.layoutScale) {
                HomeScreen(home, lensActions, focus = focus, rows = SampleHomeRows.rows, guide = SampleGuide.withKidChannels(home.nowMillis))
            }
        }
        compose.onNodeWithText("Watch again").requestFocus()
        press(Key.Menu)
        compose.onNode(isFocused()).assert(hasText("Watch again"))
        for (action in listOf("Details", "Another lens", "Don't show this")) compose.onNodeWithText(action).assertExists()

        press(Key.DirectionDown, times = 2)
        assertFocused("Another lens")
        press(Key.DirectionCenter)

        compose.onNodeWithText("REMEMBER WHEN").assertDoesNotExist()
        compose.onNodeWithText("CLASSIC").assertIsDisplayed()
        compose.onNodeWithText("The Iron Giant").assertIsDisplayed()
        // She's never seen it.
        assertFocused("Watch")

        press(Key.Menu)
        press(Key.DirectionDown, times = 3)
        assertFocused("Don't show this")
        press(Key.DirectionCenter)
        assertEquals(listOf("another lens", "don't show The Iron Giant"), done)
        assertFocused("Watch")
    }

    @Test
    fun `My apps shows three apps from her allowlist, then All apps`() {
        show()
        compose.onNodeWithText("MY APPS").assertIsDisplayed()
        for (allowed in listOf("Plezy", "YouTube Kids", "Disney+")) compose.onNodeWithText(allowed).assertIsDisplayed()
        compose.onNodeWithText("All apps").assertIsDisplayed()
        // Not on her list, or past the first three of it. (Plex is the top bar's status.)
        for (other in listOf("Netflix", "YouTube", "Spotify", "Kodi", "Prime Video", "Twitch", "Minecraft", "PBS KIDS Video")) {
            compose.onNodeWithText(other).assertDoesNotExist()
        }
    }

    @Test
    fun `below the dashboard is only the Live row, with only her kid-safe channels`() {
        show()
        press(Key.DirectionDown, times = 2)
        live("Family").assertIsFocused()
        compose.onNodeWithText("LIVE").assertIsDisplayed()
        live("Cartoon Club").assertExists()
        for (grownUp in listOf("Movies", "Sci-Fi", "TV Mix", "Classics", "Comedy", "Ridge Line Marathon", "Teen Movies")) live(grownUp).assertDoesNotExist()
        // No YouTube TV networks, no Continue in your apps, no Apps row.
        compose.onNodeWithContentDescription("ESPN").assertDoesNotExist()
        compose.onNodeWithText("CONTINUE IN YOUR APPS").assertDoesNotExist()
        compose.onNodeWithText("APPS").assertDoesNotExist()
        press(Key.DirectionCenter)
        assertEquals(listOf("tune in family"), done)
    }

    @Test
    fun `with no kid-safe channel there's no Live row`() {
        show(guide = SampleGuide.ready())
        compose.onNodeWithText("LIVE").assertDoesNotExist()
        press(Key.DirectionDown, times = 2)
        upNext("Avatar").assertIsFocused()
    }

    @Test
    fun `hero buttons go up to the top bar, and right to Remember when`() {
        show()
        press(Key.DirectionUp)
        compose.onNode(isFocused()).assert(hasContentDescription("Settings"))
        // The gear sits over the right-hand card, as over your lens card.
        press(Key.DirectionDown)
        assertFocused("Watch again")
        press(Key.DirectionLeft)
        assertFocused("Play")
        press(Key.DirectionRight, times = 2)
        assertFocused("Not now")
        press(Key.DirectionRight)
        assertFocused("Watch again")
    }

    @Test
    fun `Remember when goes down to My apps, and My apps left to Up next`() {
        show()
        compose.onNodeWithText("Watch again").requestFocus()
        press(Key.DirectionDown)
        assertFocused("Plezy")
        press(Key.DirectionRight)
        assertFocused("YouTube Kids")
        press(Key.DirectionDown)
        assertFocused("All apps")
        press(Key.DirectionLeft)
        assertFocused("Disney+")
        press(Key.DirectionLeft)
        upNext("Hilda").assertIsFocused()
        press(Key.DirectionLeft)
        upNext("Avatar").assertIsFocused()
    }

    @Test
    fun `Up next goes up to the hero, which remembers its button, and back down to its card`() {
        show()
        press(Key.DirectionDown)
        upNext("Avatar").assertIsFocused()
        press(Key.DirectionRight)
        upNext("Hilda").assertIsFocused()
        press(Key.DirectionUp)
        assertFocused("Play")
        press(Key.DirectionDown)
        upNext("Hilda").assertIsFocused()
        press(Key.DirectionRight)
        compose.onNode(isFocused()).assert(hasText("Plezy") or hasText("Disney+"))
    }

    @Test
    fun `My apps goes up to Remember when, and opens apps and All apps`() {
        show()
        compose.onNodeWithText("Disney+").requestFocus()
        press(Key.DirectionUp)
        compose.onNode(isFocused()).assert(hasText("Plezy") or hasText("YouTube Kids"))
        press(Key.DirectionUp)
        assertFocused("Watch again")
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        compose.onNodeWithText("All apps").requestFocus()
        press(Key.DirectionCenter)
        assertEquals(2, done.size)
        assertEquals("all apps", done.last())
    }

    @Test
    fun `the Live row goes back up to Row B, scrolled to the top`() {
        show()
        press(Key.DirectionDown, times = 2)
        live("Family").assertIsFocused()
        press(Key.DirectionUp)
        upNext("Avatar").assertIsFocused()
        compose.onNodeWithText("Hi Maya!").assertIsDisplayed()
    }

    @Test
    fun `Back from All apps lands on My apps' All apps`() {
        show()
        focus = FocusRequest(HomeFocus.ALL_APPS, serial = 1)
        compose.waitForIdle()
        assertFocused("All apps")
    }

    @Test
    fun `what would land on the Guide card lands on Play`() {
        show()
        compose.onNodeWithText("Disney+").requestFocus()
        focus = FocusRequest(HomeFocus.GUIDE, serial = 1)
        compose.waitForIdle()
        assertFocused("Play")
        // Back from a Live card's full guide lands on that card.
        focus = FocusRequest(HomeFocus.LIVE, serial = 2, channelId = "cartoons")
        compose.waitForIdle()
        live("Cartoon Club").assertIsFocused()
    }
}
