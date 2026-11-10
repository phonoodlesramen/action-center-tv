package com.phonoodlesramen.actioncenter.ui.nfl

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.phonoodlesramen.actioncenter.nfl.GameState
import com.phonoodlesramen.actioncenter.nfl.NflLoad
import com.phonoodlesramen.actioncenter.nfl.NflSettings
import com.phonoodlesramen.actioncenter.nfl.NflView
import com.phonoodlesramen.actioncenter.nfl.NflWeek
import com.phonoodlesramen.actioncenter.nfl.WatchWay
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.FocusRequest
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.HomeFocus
import com.phonoodlesramen.actioncenter.ui.home.HomeScreen
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.home.rows.SampleHomeRows
import com.phonoodlesramen.actioncenter.ui.settings.SettingsActions
import com.phonoodlesramen.actioncenter.ui.settings.SettingsScreen
import com.phonoodlesramen.actioncenter.ui.settings.SettingsSection
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The NFL's keys (Phase 13; ui-spec "The NFL", §3's focus table): the game-day card's OK lands on
 * its game in Games (F3), Up reaches the tabs and Right moves along them, ‹ Week and Week ›,
 * Back from details and from a team page, ★ Make it your team, Back to the home's card, and
 * Settings › NFL's grid.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class NflFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var open by mutableStateOf<NflOpen?>(null)
    private var view by mutableStateOf(SampleNfl.view())
    private val done = mutableListOf<String>()
    private var backs = 0

    private val actions = object : HomeActions {
        override fun openNfl(open: NflOpen) {
            this@NflFocusTest.open = open
        }

        override fun watch(way: WatchWay, what: String) {
            done += "watch ${way.label}"
        }

        override fun say(message: String) {
            done += "say $message"
        }
    }

    /** The sample, recording the settings it's asked to keep, with a made-up Week 9 to step to. */
    private inner class Controller : SampleNfl.Controller(SampleNfl.view()) {
        override fun saveSettings(settings: NflSettings, done: (String?) -> Unit) {
            this@NflFocusTest.done += "save ${settings.yourTeams.joinToString(",")}"
            this@NflFocusTest.view = SampleNfl.view(settings)
            done(null)
        }

        override suspend fun week(seasonType: Int, week: Int, seasonYear: Int): NflLoad<NflWeek> {
            this@NflFocusTest.done += "week $seasonType/$week/$seasonYear"
            val game = SampleNfl.monday.copy(id = "w$week", week = week, kickoffMillis = SampleNfl.monday.kickoffMillis + (week - 8) * 7 * DAY, away = "PHI", home = "NYG")
            return NflLoad.Loaded(NflWeek(2026, seasonType, week, "Week $week", listOf(game)))
        }
    }

    private val controller = Controller()

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun assertFocused(description: String) = compose.onNode(isFocused()).assert(hasContentDescription(description))

    private fun focusedText() = compose.onNode(isFocused()).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()

    /** The home, then the NFL screen once something opens it, as HomeActivity does. */
    private fun show(focus: FocusRequest = FocusRequest(), start: NflOpen? = null) {
        open = start
        val state = SampleHomeState.state(time = SampleNfl.sundayNight)
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                val opened = open
                if (opened == null) {
                    HomeScreen(state, actions, focus = focus, rows = SampleHomeRows.rows, nfl = view, watch = SampleNfl.watch)
                } else {
                    NflScreen(view, opened, SampleNfl.nowMillis, controller, actions, SampleNfl.watch, onBack = { backs++ })
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `OK on the game-day card opens the NFL screen on its game in Games`() {
        show()
        compose.onNodeWithContentDescription("Game day: Eagles at Commanders").requestFocus()
        press(Key.DirectionCenter)
        assertEquals(NflOpen(NflPage.Tab(NflTab.GAMES, gameId = SampleNfl.live.id), NflOpen.CARD), open)
        assertFocused("Eagles at Commanders")
        compose.onNodeWithText("2nd & Goal at PHI 7", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the game-day card sits under the lens card and over the Guide card`() {
        show()
        compose.onNodeWithContentDescription("Game day: Eagles at Commanders").requestFocus()
        press(Key.DirectionUp)
        assertTrue(focusedText(), focusedText() in setOf("Watch again", "Next"))
        compose.onNodeWithContentDescription("Game day: Eagles at Commanders").requestFocus()
        press(Key.DirectionDown)
        compose.onNodeWithText("Your library as TV channels").assertIsFocused()
    }

    @Test
    fun `Back from the NFL screen lands on the card that opened it`() {
        show(FocusRequest(HomeFocus.NFL, serial = 1, nflKey = NflOpen.CARD))
        compose.onNodeWithContentDescription("Game day: Eagles at Commanders").assertIsFocused()
    }

    @Test
    fun `Back from the NFL screen lands on the row's game that opened it`() {
        show(FocusRequest(HomeFocus.NFL, serial = 1, nflKey = SampleNfl.monday.id))
        assertFocused("Bears at Seahawks")
    }

    @Test
    fun `Up from the top row reaches the tabs, Right moves along them, and OK opens one`() {
        show(start = NflOpen(NflPage.Tab(NflTab.GAMES, gameId = "401873023")))
        assertFocused("Panthers at Packers")
        press(Key.DirectionUp)
        assertEquals("Games", focusedText())
        press(Key.DirectionRight)
        assertEquals("Standings", focusedText())
        press(Key.DirectionCenter)
        assertEquals("Standings", focusedText())
        compose.onNodeWithText("Division").assertIsDisplayed()
        // Down to the views, Right to Conference, OK: the seeds and the playoff line.
        press(Key.DirectionDown)
        assertEquals("Division", focusedText())
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals("Conference", focusedText())
        compose.onNodeWithText("the dashed line is the playoff line", substring = true).assertIsDisplayed()
    }

    @Test
    fun `Week and Week step through the season's calendar`() {
        show(start = NflOpen(NflPage.Tab(NflTab.GAMES, gameId = SampleNfl.live.id)))
        compose.onNodeWithText("Week 9 ›").requestFocus()
        press(Key.DirectionCenter)
        assertEquals("week 2/9/2026", done.last())
        compose.onNodeWithContentDescription("Eagles at Giants").assertExists()
        compose.onNodeWithText("‹ Week 8").assertIsDisplayed()
        assertEquals("Week 10 ›", focusedText())
        press(Key.DirectionLeft)
        assertEquals("‹ Week 8", focusedText())
        press(Key.DirectionCenter)
        // The copy's own week: nothing to fetch.
        assertEquals(1, done.count { it.startsWith("week") })
        compose.onNodeWithContentDescription("Eagles at Commanders").assertExists()
    }

    @Test
    fun `OK on a game to come opens its details, and Back returns to it, then to the opener`() {
        show(start = NflOpen(NflPage.Tab(NflTab.GAMES, gameId = SampleNfl.monday.id), NflOpen.ALL_GAMES))
        assertFocused("Bears at Seahawks")
        press(Key.DirectionCenter)
        compose.onNodeWithText("TEAM STATS").assertIsDisplayed()
        compose.onNodeWithText("Team stats appear here once it kicks off.").assertIsDisplayed()
        back()
        assertFocused("Bears at Seahawks")
        assertEquals(0, backs)
        back()
        assertEquals(1, backs)
    }

    @Test
    fun `OK on a game that's on watches it in its first way`() {
        show(start = NflOpen(NflPage.Tab(NflTab.GAMES, gameId = SampleNfl.live.id)))
        press(Key.DirectionCenter)
        assertEquals("watch NBC on YouTube TV", done.last())
    }

    @Test
    fun `Back from a team page returns to its row in the standings`() {
        show(start = NflOpen(NflPage.Tab(NflTab.STANDINGS)))
        compose.onNodeWithContentDescription("Philadelphia Eagles").requestFocus()
        press(Key.DirectionCenter)
        assertEquals("Your team", focusedText())
        compose.onNodeWithText("WK 18").assertExists()
        back()
        assertFocused("Philadelphia Eagles")
    }

    @Test
    fun `Make it your team keeps the team, and the button says so`() {
        show(start = NflOpen(NflPage.Team("DAL")))
        assertEquals("Make it your team", focusedText())
        press(Key.DirectionCenter)
        assertEquals("save PHI,DAL", done.first { it.startsWith("save") })
        assertTrue(done.last(), done.last().startsWith("say The Cowboys are your team"))
        compose.onNodeWithText("Your team").assertIsDisplayed()
    }

    @Test
    fun `Settings, NFL's grid stars a team with OK and saves on Back`() {
        val state = SampleHomeState.state(time = SampleNfl.sundayNight)
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                SettingsScreen(state, SettingsActions.None, onBack = {}, initialSection = SettingsSection.NFL, nfl = controller)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Your teams").requestFocus()
        press(Key.DirectionCenter)
        assertFocused("Buffalo Bills")
        press(Key.DirectionRight)
        assertFocused("Miami Dolphins")
        press(Key.DirectionDown)
        assertFocused("Jacksonville Jaguars")
        press(Key.DirectionUp)
        press(Key.DirectionCenter)
        back()
        assertEquals("save PHI,MIA", done.single { it.startsWith("save") })
        compose.onNodeWithText("Your teams").assertIsDisplayed()
    }

    @Test
    fun `nothing yet, the NFL screen says what it waits on and Back still goes home`() {
        view = NflView.None
        show(start = NflOpen())
        compose.onNodeWithText("The NFL appears once the guide station (or ESPN) answers").assertIsDisplayed()
        back()
        assertEquals(1, backs)
    }

    @Test
    fun `a kid's home has neither the card nor the row`() {
        val kid = SampleHomeState.kid(time = SampleNfl.sundayNight)
        compose.setContent {
            ActionCenterTheme(accent = kid.accent, layoutScale = kid.layoutScale) {
                HomeScreen(kid, actions, rows = SampleHomeRows.rows, nfl = view, watch = SampleNfl.watch)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Game day: Eagles at Commanders").assertDoesNotExist()
        compose.onNodeWithContentDescription("All games").assertDoesNotExist()
        assertTrue(SampleNfl.live.state == GameState.LIVE)
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
