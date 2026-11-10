package com.phonoodlesramen.actioncenter.ui.settings

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.phonoodlesramen.actioncenter.profiles.AgeBracket
import com.phonoodlesramen.actioncenter.profiles.KidEntity
import com.phonoodlesramen.actioncenter.profiles.ProfilesController
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import java.util.TimeZone
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Plex Guide with the D-pad alone: OK turns a channel on or off, hold OK or Menu
 * picks it up to move, Remake asks first, and a change the editor turns down shows its
 * sentence. A fake editor stands in for guide.db.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class PlexGuideSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    private val editor = FakeGuideEditor()
    private var phone = 0
    private val zone = TimeZone.getDefault()

    @Before
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun restore() = TimeZone.setDefault(zone)

    private fun show(profiles: ProfilesController = ProfilesController.None) {
        val home = SampleHomeState.state()
        val actions = object : SettingsActions {
            override fun openPhoneEditor() {
                phone++
            }
        }
        compose.setContent {
            ActionCenterTheme(accent = home.accent, layoutScale = home.layoutScale) {
                SettingsScreen(
                    home, actions, onBack = {}, initialSection = SettingsSection.PLEX_GUIDE, guideEditor = editor, profiles = profiles,
                )
            }
        }
    }

    /** The focused node: a channel's Ratings once Right has reached it. */
    private fun focused() = compose.onNode(isFocused())

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun focus(text: String) {
        compose.onNodeWithText(text).requestFocus()
        compose.waitForIdle()
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /** Where a row is in the list, scrolled out of view or not. */
    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    @Test
    fun `the status line counts the channels and says how far ahead they're scheduled`() {
        show()
        compose.onNodeWithText("18 channels, 2 off · scheduled until 9:30 PM tomorrow").assertExists()
    }

    @Test
    fun `OK turns a channel off and on again`() {
        show()
        focus("Movies")
        press(Key.DirectionCenter)
        press(Key.DirectionCenter)
        assertEquals(listOf("setEnabled movies false", "setEnabled movies true"), editor.calls)
    }

    @Test
    fun `holding OK picks a channel up without turning it off, and OK puts it down`() {
        show()
        focus("Comedy")
        compose.onRoot().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(700)
            keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        compose.onNodeWithText(CARRIED).assertExists()
        assertEquals(emptyList<String>(), editor.calls)

        press(Key.DirectionDown, times = 2)
        press(Key.DirectionCenter)
        assertEquals(listOf("move comedy 4"), editor.calls)
        compose.onNodeWithText(CARRIED).assertDoesNotExist()
        assertTrue(top("Sci-Fi") < top("Comedy") && top("Comedy") < top("Thriller"))
        compose.onNodeWithText("Comedy").assertIsFocused()
    }

    @Test
    fun `Menu picks a channel up, Up moves it, and Back puts it down`() {
        show()
        focus("Drama")
        press(Key.Menu)
        press(Key.DirectionUp, times = 2)
        assertTrue("it moves before it's saved", top("Drama") < top("Action"))
        // Left and Right don't leave it while it's carried.
        press(Key.DirectionLeft)
        compose.onNodeWithText("Drama").assertIsFocused()

        press(Key.Back)
        assertEquals(listOf("move drama 1"), editor.calls)
        assertEquals(listOf("movies", "drama", "action"), editor.channels.value.take(3).map { it.id })
        compose.onNodeWithText("Drama").assertIsFocused()
    }

    @Test
    fun `a channel put back where it was isn't saved`() {
        show()
        focus("Drama")
        press(Key.Menu)
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        press(Key.Menu)
        assertEquals(emptyList<String>(), editor.calls)
        compose.onNodeWithText(CARRIED).assertDoesNotExist()
    }

    @Test
    fun `a change the editor turns down shows its sentence until one works`() {
        show()
        editor.reject = "Channel 12 is taken by Classics."
        focus("Movies")
        press(Key.DirectionCenter)
        compose.onNodeWithText("Channel 12 is taken by Classics.").assertExists()

        press(Key.DirectionCenter)
        compose.onNodeWithText("Channel 12 is taken by Classics.").assertDoesNotExist()
        assertEquals(listOf("setEnabled movies false"), editor.calls)
    }

    @Test
    fun `Remake the starter lineup asks first, with Cancel focused`() {
        show()
        click("Remake the starter lineup")
        compose.onNodeWithText("Remake the starter lineup?").assertExists()
        compose.onNodeWithText("Cancel").assertIsFocused()
        press(Key.DirectionCenter)
        compose.onNodeWithText("Remake the starter lineup?").assertDoesNotExist()
        compose.onNodeWithText("Remake the starter lineup").assertIsFocused()
        assertEquals(emptyList<String>(), editor.calls)

        click("Remake the starter lineup")
        click("Remake")
        assertEquals(listOf("remake"), editor.calls)

        editor.reject = "Plex hasn't synced your library yet, so there's nothing to make a lineup from."
        click("Remake the starter lineup")
        click("Remake")
        compose.onNodeWithText("Plex hasn't synced your library yet, so there's nothing to make a lineup from.").assertExists()
    }

    @Test
    fun `Edit on your phone opens the phone editor`() {
        show()
        click("Edit on your phone")
        assertEquals(1, phone)
    }

    @Test
    fun `Right reaches a channel's Ratings, and OK steps through the age brackets, saving each`() {
        show()
        focus("Movies")
        press(Key.DirectionRight)
        focused().assertTextContains("Ratings: Any").assertTextContains("No limit")

        press(Key.DirectionCenter)
        focused().assertTextContains("Ratings: Ages 7–9").assertTextContains("Up to PG and TV-PG")
        press(Key.DirectionCenter)
        focused().assertTextContains("Ratings: Ages 10–12").assertTextContains("Up to PG-13 and TV-PG")
        press(Key.DirectionCenter)
        focused().assertTextContains("Ratings: Any")
        assertEquals(
            listOf(
                "update movies [G, PG, TV-Y, TV-Y7, TV-G, TV-PG]",
                "update movies [G, PG, PG-13, TV-Y, TV-Y7, TV-G, TV-PG]",
                "update movies []",
            ),
            editor.calls,
        )
        assertEquals(emptyList<String>(), editor.channels.value.first { it.id == "movies" }.draft.rule.contentRatings)

        // Left is the channel again, and its OK is still on/off.
        press(Key.DirectionLeft)
        compose.onNodeWithText("Movies").assertIsFocused()
    }

    @Test
    fun `a channel's Ratings say which bracket its rule's ratings are, and Custom for a list that's none`() {
        show()
        focus("Comedy")
        press(Key.DirectionRight)
        focused().assertTextContains("Ratings: Ages 10–12")

        focus("Drama")
        press(Key.DirectionRight)
        focused().assertTextContains("Ratings: Custom").assertTextContains("PG-13, R")
        // From Custom, OK goes to the youngest bracket's.
        press(Key.DirectionCenter)
        focused().assertTextContains("Ratings: Ages 7–9")
        assertEquals(listOf("update drama [G, PG, TV-Y, TV-Y7, TV-G, TV-PG]"), editor.calls)
    }

    @Test
    fun `a Ratings change the editor turns down shows its sentence and the saved ratings again`() {
        show()
        editor.reject = "Couldn't save the channel. Try again."
        focus("Movies")
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        focused().assertTextContains("Ratings: Any")
        compose.onNodeWithText("Couldn't save the channel. Try again.").assertExists()
        assertEquals(emptyList<String>(), editor.calls)
    }

    @Test
    fun `the Ratings choices are Settings › Profiles' age brackets`() {
        val profiles = object : ProfilesController {
            override val kids = flowOf(emptyList<KidEntity>())
            override val brackets = flowOf(
                listOf(
                    AgeBracket(9, 12, setOf("G", "PG", "TV-Y", "TV-Y7", "TV-G", "TV-PG")),
                    AgeBracket(5, 8, setOf("G", "TV-Y", "TV-Y7")),
                ),
            )
        }
        show(profiles)
        focus("Movies")
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        focused().assertTextContains("Ratings: Ages 5–8").assertTextContains("Up to G and TV-Y7")
        assertEquals(listOf("update movies [G, TV-Y, TV-Y7]"), editor.calls)

        // The default 10-12 bracket's ratings are no bracket's here.
        focus("Comedy")
        press(Key.DirectionRight)
        focused().assertTextContains("Ratings: Custom")
    }

    private companion object {
        const val CARRIED = "Up or down to move it, then OK"
    }
}
