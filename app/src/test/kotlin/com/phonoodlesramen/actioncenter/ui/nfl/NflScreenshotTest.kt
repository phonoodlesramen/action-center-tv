package com.phonoodlesramen.actioncenter.ui.nfl

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.nfl.NflController
import com.phonoodlesramen.actioncenter.nfl.NflSettings
import com.phonoodlesramen.actioncenter.nfl.NflView
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.HomeScreen
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.home.rows.SampleHomeRows
import com.phonoodlesramen.actioncenter.ui.settings.SettingsActions
import com.phonoodlesramen.actioncenter.ui.settings.SettingsScreen
import com.phonoodlesramen.actioncenter.ui.settings.SettingsSection
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The NFL (Phase 13) at the Shield's 1080p TV configuration, from [SampleNfl]: the home with the
 * game-day card and scrolled to the NFL row, each tab and view of the NFL screen, a live game's
 * details, a team's page, Settings › NFL and its grid, scores hidden, and 115%. Logos are the
 * stand-ins (no cache in tests). Record with `.\gradlew.bat recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class NflScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    private fun home(view: NflView = SampleNfl.view(), scale: Float = 1f) {
        val state = SampleHomeState.state(time = SampleNfl.sundayNight, layoutScale = scale)
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                HomeScreen(state, HomeActions.None, rows = SampleHomeRows.rows, nfl = view, watch = SampleNfl.watch)
            }
        }
    }

    private fun screen(page: NflPage, view: NflView = SampleNfl.view(), scale: Float = 1f) {
        val state = SampleHomeState.state(time = SampleNfl.sundayNight, layoutScale = scale)
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                NflScreen(
                    view = view,
                    open = NflOpen(page),
                    nowMillis = SampleNfl.nowMillis,
                    controller = SampleNfl.Controller(view),
                    actions = HomeActions.None,
                    watch = SampleNfl.watch,
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun homeWithTheCard() {
        home()
        capture("nfl_home_card")
    }

    @Test
    fun homeWithTheCardFocused() {
        home()
        compose.onNodeWithContentDescription("Game day: Eagles at Commanders").requestFocus()
        capture("nfl_home_card_focused")
    }

    @Test
    fun homeScrolledToTheRow() {
        home()
        compose.onNodeWithContentDescription("Eagles at Commanders").requestFocus()
        compose.waitForIdle()
        capture("nfl_home_row")
    }

    @Test
    fun homeWithScoresHidden() {
        home(SampleNfl.view(SampleNfl.settings.copy(hideScores = true)))
        capture("nfl_home_card_scores_hidden")
    }

    @Test
    fun homeAt115() {
        home(scale = 1.15f)
        capture("nfl_home_card_scale_115")
    }

    @Test
    fun games() {
        screen(NflPage.Tab(NflTab.GAMES, gameId = SampleNfl.live.id))
        capture("nfl_games")
    }

    @Test
    fun gamesAt115() {
        screen(NflPage.Tab(NflTab.GAMES, gameId = SampleNfl.live.id), scale = 1.15f)
        capture("nfl_games_scale_115")
    }

    @Test
    fun standingsByDivision() {
        screen(NflPage.Tab(NflTab.STANDINGS, standings = StandingsView.DIVISION))
        capture("nfl_standings_division")
    }

    @Test
    fun standingsByConference() {
        screen(NflPage.Tab(NflTab.STANDINGS, standings = StandingsView.CONFERENCE))
        capture("nfl_standings_conference")
    }

    @Test
    fun standingsOfTheLeague() {
        screen(NflPage.Tab(NflTab.STANDINGS, standings = StandingsView.LEAGUE))
        capture("nfl_standings_league")
    }

    @Test
    fun playoffsInTheSeason() {
        screen(NflPage.Tab(NflTab.PLAYOFFS))
        capture("nfl_playoffs")
    }

    @Test
    fun liveGameDetails() {
        screen(NflPage.Game(SampleNfl.live.id))
        capture("nfl_game_live")
    }

    @Test
    fun teamPage() {
        screen(NflPage.Team("PHI"))
        capture("nfl_team")
    }

    private fun settings(nfl: NflController = SampleNfl.Controller()) {
        val state = SampleHomeState.state(time = SampleNfl.sundayNight)
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                SettingsScreen(state, SettingsActions.None, onBack = {}, initialSection = SettingsSection.NFL, nfl = nfl)
            }
        }
        compose.waitForIdle()
    }

    /** A new house (O6): Show the NFL is off, so the section is the switch and its note, and nothing else. */
    @Test
    fun settingsNflOff() {
        val saved = mutableListOf<NflSettings>()
        settings(object : NflController.Fixed(NflView()) {
            override fun saveSettings(settings: NflSettings, done: (String?) -> Unit) {
                saved += settings
                done(null)
            }
        })
        capture("settings_nfl_off")
        assertEquals(0, compose.onAllNodesWithText("Your teams").fetchSemanticsNodes().size)
        compose.onNodeWithText("Show the NFL").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(NflSettings(show = true)), saved)
    }

    @Test
    fun settingsNfl() {
        settings()
        capture("settings_nfl")
    }

    @Test
    fun settingsNflTeamGrid() {
        settings()
        compose.onNodeWithText("Your teams").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        capture("settings_nfl_grid")
    }
}
