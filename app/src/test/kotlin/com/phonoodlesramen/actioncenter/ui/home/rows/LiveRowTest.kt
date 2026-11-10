package com.phonoodlesramen.actioncenter.ui.home.rows

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
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
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.home.HomeRowsState
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.guide.GuideScreen
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import com.phonoodlesramen.actioncenter.ui.home.FocusRequest
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.HomeFocus
import com.phonoodlesramen.actioncenter.ui.home.HomeScreen
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Live row (G7, plan §6) and the Guide card (Q3) on the sample home at 8:41 PM, with
 * the sample lineup: what each guide channel shows, OK and quick actions, the D-pad into
 * and out of the row, a Live card's full guide and Back to that card (G20 A), and the
 * card's states before there are channels (G20 B).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class LiveRowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val done = mutableListOf<String>()
    private val actions = object : HomeActions {
        override fun tuneIn(channelId: String) {
            done += "tune in $channelId"
        }

        override fun openGuide(channelId: String?) {
            done += if (channelId == null) "guide" else "guide $channelId"
        }

        override fun openIntentUri(uri: String, what: String) {
            done += "open $what"
        }

        override fun say(message: String) {
            done += "say $message"
        }
    }

    private var state by mutableStateOf(SampleHomeState.state())

    private var lineup by mutableStateOf<GuideCardState>(SampleGuide.ready())
    private var guideOpen by mutableStateOf(false)
    private var homeFocus by mutableStateOf(FocusRequest())
    private var guideFrom: String? = null

    /** Opening the guide and Back from it, as HomeActivity routes them (G20 A). */
    private val routes = object : HomeActions {
        override fun openGuide(channelId: String?) {
            guideFrom = channelId
            guideOpen = true
        }
    }

    /** The home and the full guide in turn, each new as it opens, as in HomeActivity. */
    private fun showWithGuide() = compose.setContent {
        ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
            CompositionLocalProvider(LocalAppBadges provides SampleHomeRows.badges) {
                if (guideOpen) {
                    GuideScreen(
                        state = lineup,
                        nowMillis = state.nowMillis,
                        actions = routes,
                        onBack = {
                            guideOpen = false
                            homeFocus = FocusRequest(if (guideFrom != null) HomeFocus.LIVE else HomeFocus.GUIDE, homeFocus.serial + 1, guideFrom)
                        },
                        zone = ZoneOffset.UTC,
                        focusChannelId = guideFrom,
                    )
                } else {
                    HomeScreen(state, routes, focus = homeFocus, rows = SampleHomeRows.rows, guide = lineup)
                }
            }
        }
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun show(
        initial: HomeUiState = SampleHomeState.state(),
        guide: GuideCardState = SampleGuide.ready(),
        rows: HomeRowsState = SampleHomeRows.rows,
    ) {
        state = initial
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                CompositionLocalProvider(LocalAppBadges provides SampleHomeRows.badges) {
                    HomeScreen(state, actions, rows = rows, guide = guide)
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

    private fun guideCard() = compose.onNodeWithText("Coming up at", substring = true)

    /** Wholly inside the screen: nothing of it under an edge. */
    private fun SemanticsNodeInteraction.assertOnScreen() = apply {
        val bounds = getBoundsInRoot()
        val screen = compose.onRoot().getBoundsInRoot()
        assertTrue("$bounds is outside $screen", bounds.top >= screen.top && bounds.bottom <= screen.bottom)
    }

    @Test
    fun `each guide channel shows what's on now, time left or what's up next`() {
        show()
        card("Movies, Quiet Field, 2h left").assertExists()
        card("Sci-Fi, Orbit of Glass, Up next at 9:00").assertExists()
        card("TV Mix, Ridge Line, 41m left · S3 E1 · Thaw").assertExists()
        card("Classics, The Silver Coast, 1h 44m left").assertExists()
        card("Comedy, Open House, 11m left · S4 E2 · The Showing").assertExists()
        card("Ridge Line Marathon, Ridge Line, 41m left · S1 E9").assertExists()
    }

    @Test
    fun `an off-air channel says when it's back`() {
        show(SampleHomeState.state(time = SampleHomeState.weeknight.plusHours(4).plusMinutes(4)))
        card("Classics, Off air, Back at 2:30").assertExists()
    }

    @Test
    fun `the cards follow the home's minute tick`() {
        show()
        state = SampleHomeState.state(time = SampleHomeState.weeknight.plusMinutes(19))
        compose.waitForIdle()
        card("Movies, Quiet Field, 1h 41m left").assertExists()
        card("Sci-Fi, Orbit of Glass, 2h 22m left").assertExists()
        compose.onNodeWithText("Coming up at 9:30").assertExists()
    }

    @Test
    fun `OK on a guide channel tunes in to it`() {
        show()
        card("TV Mix").requestFocus()
        press(Key.DirectionCenter)
        assertEquals(listOf("tune in tv-mix"), done)
    }

    @Test
    fun `YouTube TV's networks follow the guide channels and still open YouTube TV`() {
        show()
        card("Ridge Line Marathon").requestFocus()
        press(Key.DirectionRight)
        assertFocused("ESPN")
        press(Key.DirectionCenter)
        assertEquals(listOf("open ESPN"), done)
    }

    @Test
    fun `quick actions tune in or open the full guide, and say why a channel is off air`() {
        show(SampleHomeState.state(time = SampleHomeState.weeknight.plusHours(4).plusMinutes(4)))
        card("Classics").requestFocus()
        press(Key.Menu)
        compose.onNodeWithText("Tune in").assertIsFocused()
        compose.onNodeWithText("Full guide").assertExists()
        // Past Add to favorites (Phase 13).
        press(Key.DirectionDown, times = 3)
        press(Key.DirectionCenter)
        assertEquals(listOf("say This title is no longer in your library"), done)
        assertFocused("Classics")

        press(Key.Menu)
        press(Key.DirectionCenter)
        assertEquals("tune in classics", done.last())
    }

    @Test
    fun `a Live card's Full guide opens the guide on its channel`() {
        show()
        card("Comedy").requestFocus()
        press(Key.Menu)
        press(Key.DirectionDown)
        compose.onNodeWithText("Full guide").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("guide comedy"), done)
    }

    @Test
    fun `Back from a Live card's full guide lands on that card, even one the row had scrolled past`() {
        showWithGuide()
        card("Ridge Line Marathon").requestFocus()
        press(Key.Menu)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        // The guide opens on that channel.
        compose.onNode(isFocused()).assert(hasContentDescription("Ridge Line Marathon, Ridge Line", substring = true))

        back()
        assertFocused("Ridge Line Marathon")
        card("Ridge Line Marathon").assertOnScreen()
        compose.onNodeWithText("LIVE").assertOnScreen()
    }

    @Test
    fun `Back from the Guide card's full guide still lands on the Guide card`() {
        showWithGuide()
        guideCard().requestFocus()
        press(Key.DirectionCenter)
        compose.onNode(isFocused()).assert(hasContentDescription("Movies, Quiet Field", substring = true))

        back()
        guideCard().assertIsFocused()
    }

    @Test
    fun `if the card's channel left the lineup meanwhile, Back lands on the Guide card`() {
        showWithGuide()
        card("Comedy").requestFocus()
        press(Key.Menu)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        lineup = SampleGuide.ready().let { it.copy(channels = it.channels.filter { channel -> channel.id != "comedy" }) }
        compose.waitForIdle()

        back()
        guideCard().assertIsFocused()
    }

    @Test
    fun `down from Up next reaches the first guide channel, and up comes back`() {
        show()
        card("Slow Horses").requestFocus()
        press(Key.DirectionDown)
        assertFocused("Movies")
        compose.onNodeWithText("LIVE").assertIsDisplayed()
        press(Key.DirectionDown)
        assertFocused("Bluey")
        press(Key.DirectionUp)
        assertFocused("Movies")
        press(Key.DirectionUp)
        card("Slow Horses").assertIsFocused()
    }

    @Test
    fun `a focused Live card shows its captions whole, and moving along the row never moves the home`() {
        show()
        card("Slow Horses").requestFocus()
        press(Key.DirectionDown)
        assertFocused("Movies")
        compose.onNodeWithText("LIVE").assertOnScreen()
        compose.onNodeWithText("2h left").assertOnScreen()
        val label = compose.onNodeWithText("LIVE").getBoundsInRoot().top
        press(Key.DirectionRight, times = 6)
        assertFocused("ESPN")
        assertEquals(label, compose.onNodeWithText("LIVE").getBoundsInRoot().top)
        press(Key.DirectionLeft)
        compose.onNodeWithText("41m left · S1 E9").assertOnScreen()
        assertEquals(label, compose.onNodeWithText("LIVE").getBoundsInRoot().top)
        // Down to Continue in your apps and back: the row lands where it did.
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        assertFocused("Ridge Line Marathon")
        assertEquals(label, compose.onNodeWithText("LIVE").getBoundsInRoot().top)
    }

    @Test
    fun `YouTube TV's row alone lands in the same place coming down from the dashboard or up from the next row`() {
        show(guide = GuideCardState.NotSetUp)
        card("Slow Horses").requestFocus()
        press(Key.DirectionDown)
        assertFocused("ESPN")
        compose.onNodeWithText("LIVE").assertOnScreen()
        val label = compose.onNodeWithText("LIVE").getBoundsInRoot().top
        press(Key.DirectionDown)
        assertFocused("Bluey")
        press(Key.DirectionUp)
        assertFocused("ESPN")
        assertEquals(label, compose.onNodeWithText("LIVE").getBoundsInRoot().top)
    }

    @Test
    fun `the Live row remembers its card, guide channel or network`() {
        show()
        card("Comedy").requestFocus()
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        assertFocused("Comedy")
        press(Key.DirectionRight, times = 3)
        assertFocused("FOX Sports 1")
        press(Key.DirectionUp)
        press(Key.DirectionDown)
        assertFocused("FOX Sports 1")
    }

    @Test
    fun `the Guide card goes down into the Live row below it`() {
        show()
        guideCard().requestFocus()
        press(Key.DirectionDown)
        compose.onNode(isFocused()).assert(hasContentDescription("Classics", substring = true) or hasContentDescription("Comedy", substring = true))
    }

    @Test
    fun `without guide channels the row is YouTube TV's networks alone, still Live`() {
        show(guide = GuideCardState.NotSetUp)
        card("Slow Horses").requestFocus()
        press(Key.DirectionDown)
        assertFocused("ESPN")
        compose.onNodeWithText("LIVE").assertIsDisplayed()
        card("Movies").assertDoesNotExist()
    }

    @Test
    fun `with no networks either, the guide channels make the row`() {
        show(rows = SampleHomeRows.rows.copy(channels = emptyList()))
        card("Slow Horses").requestFocus()
        press(Key.DirectionDown)
        assertFocused("Movies")
        card("Ridge Line Marathon").requestFocus()
        press(Key.DirectionRight)
        assertFocused("Ridge Line Marathon")
    }

    @Test
    fun `the Guide card counts the channels and shows two programs coming up`() {
        show()
        // The card is one focusable surface, so its texts are one node's.
        guideCard()
            .assert(hasText("PLEX GUIDE · 6 CHANNELS"))
            .assert(hasText("Coming up at 9:00"))
            .assert(hasText("Orbit of Glass") and hasText("Sci-Fi"))
            .assert(hasText("Open House") and hasText("Comedy"))
    }

    @Test
    fun `OK on the Guide card opens the full guide`() {
        show()
        guideCard().requestFocus()
        press(Key.DirectionCenter)
        assertEquals(listOf("guide"), done)
        guideCard().assertIsFocused()
    }

    @Test
    fun `before there are channels the Guide card says what they wait on, and OK still opens the guide`() {
        show(guide = GuideCardState.NotSetUp)
        compose.onNodeWithText("They appear here once Plex is connected").assertExists()
        compose.onNodeWithText("Your library as TV channels").requestFocus()
        press(Key.DirectionCenter)
        assertEquals(listOf("guide"), done)
    }

    @Test
    fun `with Plex linked, the empty Guide card waits on the library`() {
        show(SampleHomeState.state(plex = SampleHomeState.plexLinked), guide = GuideCardState.NotSetUp)
        compose.onNodeWithText("They appear here after your library syncs").assertExists()
    }

    @Test
    fun `while the lineup is being made, the Guide card says so, and the row is YouTube TV's alone`() {
        show(SampleHomeState.state(plex = SampleHomeState.plexLinked), guide = GuideCardState.Building)
        compose.onNodeWithText("They're being made from your library now").assertExists()
        card("Movies").assertDoesNotExist()
        compose.onNodeWithText("Your library as TV channels").requestFocus()
        press(Key.DirectionCenter)
        assertEquals(listOf("guide"), done)
    }

    @Test
    fun `when nothing in the library fills a channel, the Guide card says so`() {
        show(SampleHomeState.state(plex = SampleHomeState.plexLinked), guide = GuideCardState.NothingToFill)
        compose.onNodeWithText("Not enough in your library to fill a channel yet").assertExists()
        card("Slow Horses").requestFocus()
        press(Key.DirectionDown)
        assertFocused("ESPN")
    }

    @Test
    fun `a lineup with no channels reads as not set up`() {
        show(guide = GuideCardState.Ready(channels = emptyList(), updatedAtMillis = null))
        compose.onNodeWithText("Your library as TV channels").assertExists()
        card("Slow Horses").requestFocus()
        press(Key.DirectionDown)
        assertFocused("ESPN")
    }
}
