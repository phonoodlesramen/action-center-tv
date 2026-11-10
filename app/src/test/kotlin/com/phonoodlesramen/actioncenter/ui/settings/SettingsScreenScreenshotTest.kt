package com.phonoodlesramen.actioncenter.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.LensMoveOn
import com.phonoodlesramen.actioncenter.content.LensPrefs
import com.phonoodlesramen.actioncenter.handoff.PlayIn
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.plex.PlexSignIn
import com.phonoodlesramen.actioncenter.plex.ServerChoice
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.theme.LayoutScale
import com.phonoodlesramen.actioncenter.weather.Place
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class SettingsScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(
        section: SettingsSection,
        actions: SettingsActions = SettingsActions.None,
        state: HomeUiState = SampleHomeState.state(),
    ) {
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                SettingsScreen(state, actions, onBack = {}, initialSection = section, version = "0.1.0")
            }
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun look() {
        show(SettingsSection.LOOK)
        capture("settings_look")
    }

    @Test
    fun diagnostics() {
        show(SettingsSection.DIAGNOSTICS)
        capture("settings_diagnostics")
    }

    @Test
    fun timeAndModes() {
        show(SettingsSection.TIME)
        capture("settings_time")
    }

    /** Phase 15: Include streaming apps, on as it starts. */
    @Test
    fun search() {
        show(SettingsSection.SEARCH)
        capture("settings_search")
    }

    @Test
    fun lenses() {
        show(SettingsSection.LENSES, state = SampleHomeState.state(disabledLenses = setOf(Lens.HIDDEN_GEM)))
        capture("settings_lenses")
    }

    /** Phase 14: the speed's notches with focus on the chosen one, and Move on after a week. */
    @Test
    fun lensesSpeedFocused() {
        show(SettingsSection.LENSES, state = SampleHomeState.state(lensPrefs = LensPrefs(speed = 4, moveOn = LensMoveOn.WEEK)))
        compose.onNodeWithContentDescription("Speed 4 of 5", substring = true).requestFocus()
        capture("settings_lenses_speed")
    }

    @Test
    fun plexSignedIn() {
        show(SettingsSection.PLEX, state = SampleHomeState.state(plex = SampleHomeState.plexLinked))
        capture("settings_plex")
    }

    /** Phase 11: Play in Plex chosen, and guide viewing not counted. */
    @Test
    fun plexPlayInPlex() {
        show(SettingsSection.PLEX, state = SampleHomeState.state(plex = SampleHomeState.plexLinked).copy(playIn = PlayIn.PLEX, playInChoice = PlayIn.PLEX, countGuideViewing = false))
        // Scrolled down to the toggle, as moving focus onto it would.
        compose.onNodeWithText("Count guide viewing in Plex").performScrollTo()
        capture("settings_plex_play_in_plex")
    }

    @Test
    fun plexAtLargestScale() {
        show(SettingsSection.PLEX, state = SampleHomeState.state(plex = SampleHomeState.plexLinked, layoutScale = LayoutScale.MAX))
        compose.onNodeWithText("Count guide viewing in Plex").performScrollTo()
        capture("settings_plex_scale_115")
    }

    @Test
    fun plexSyncFailed() {
        val synced = SampleHomeState.weeknight.toEpochSecond(java.time.ZoneOffset.UTC) * 1_000 - 3 * 3_600_000
        val failed = SampleHomeState.plexLinked.copy(failure = "/hubs/continueWatching answered 404", lastSyncMillis = synced)
        show(SettingsSection.PLEX, state = SampleHomeState.state(plex = failed))
        capture("settings_plex_sync_failed")
    }

    @Test
    fun plexChooseServer() {
        val choose = SampleHomeState.plexLinked.copy(
            link = SampleHomeState.plexLinked.link.copy(server = null),
            signIn = PlexSignIn.ChooseServer(listOf(ServerChoice("Basement", "aaaa1111"), ServerChoice("Attic", "bbbb2222"))),
        )
        show(SettingsSection.PLEX, state = SampleHomeState.state(plex = choose))
        capture("settings_plex_choose")
    }

    @Test
    fun weather() {
        show(SettingsSection.WEATHER)
        capture("settings_weather")
    }

    @Test
    fun weatherSearch() {
        val results = object : SettingsActions {
            override suspend fun searchPlaces(query: String) = listOf(
                Place("Portland", "Oregon", "United States", 45.52, -122.68),
                Place("Portland", "Maine", "United States", 43.66, -70.26),
                Place("Portland", "Victoria", "Australia", -38.34, 141.6),
            )
        }
        show(SettingsSection.WEATHER, results)
        compose.onNodeWithText("Change").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(hasSetTextAction()).performTextInput("Portland")
        compose.onNode(hasSetTextAction()).performImeAction()
        capture("settings_weather_search")
    }
}
