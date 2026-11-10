package com.phonoodlesramen.actioncenter.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.home.TopBar
import com.phonoodlesramen.actioncenter.ui.settings.SettingsActions
import com.phonoodlesramen.actioncenter.ui.settings.SettingsScreen
import com.phonoodlesramen.actioncenter.ui.settings.SettingsSection
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.safeArea
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phase 5's profile screens (K1, K3, K10, K11), with two made-up kids, Maya and Leo:
 * Settings › Profiles, a kid's page, the bracket table, "Who's watching?", and your top bar
 * with the kids' avatars on a weekend morning.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class ProfilesScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    private fun settings(state: HomeUiState = SampleProfiles.withKids(SampleHomeState.state())) {
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                SettingsScreen(
                    state, SettingsActions.None, onBack = {}, initialSection = SettingsSection.PROFILES, version = "0.1.0",
                    profiles = FakeProfiles(SampleProfiles.kids),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun open(text: String) {
        compose.onNode(hasText(text) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test
    fun profiles() {
        settings()
        capture("settings_profiles")
    }

    @Test
    fun kidPage() {
        settings()
        open("Maya")
        capture("settings_profiles_kid")
    }

    @Test
    fun kidPageScale90() {
        settings(SampleProfiles.withKids(SampleHomeState.state(layoutScale = 0.9f)))
        open("Maya")
        capture("settings_profiles_kid_scale_90")
    }

    @Test
    fun kidPageScale115() {
        settings(SampleProfiles.withKids(SampleHomeState.state(layoutScale = 1.15f)))
        open("Maya")
        capture("settings_profiles_kid_scale_115")
    }

    @Test
    fun kidPageCeiling() {
        settings()
        open("Maya")
        compose.onNode(hasText("Their own") and hasClickAction()).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithContentDescription("Maya's ceiling: TV-14").performScrollTo()
        compose.waitForIdle()
        capture("settings_profiles_kid_ceiling")
    }

    @Test
    fun pin() {
        settings()
        open("Maya")
        compose.onNode(hasText("Change") and hasClickAction()).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        open("Leo R")
        capture("settings_profiles_pin")
    }

    @Test
    fun brackets() {
        settings()
        open("Ratings by age")
        capture("settings_profiles_brackets")
    }

    private fun whosWatching(scale: Float) = compose.setContent {
        ActionCenterTheme(layoutScale = scale) {
            WhosWatchingScreen(SampleProfiles.everyone(), currentId = "owner", ownerColor = Palette.DefaultAccent, onPick = {}, onBack = {})
        }
    }

    @Test
    fun whosWatching() {
        whosWatching(1f)
        capture("whos_watching")
    }

    @Test
    fun whosWatchingScale90() {
        whosWatching(0.9f)
        capture("whos_watching_scale_90")
    }

    @Test
    fun whosWatchingScale115() {
        whosWatching(1.15f)
        capture("whos_watching_scale_115")
    }

    @Test
    fun topBarWeekendMorning() {
        val state = SampleProfiles.withKids(SampleHomeState.state(time = SampleHomeState.saturdayMorning))
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                Box(Modifier.fillMaxSize().background(Palette.Background).safeArea()) {
                    Column {
                        TopBar(state, HomeActions.None, remember { FocusRequester() }, Modifier.fillMaxWidth().height(44.dp).testTag("top bar"))
                    }
                }
            }
        }
        compose.onNodeWithTag("top bar").captureRoboImage("src/test/screenshots/top_bar_kids_weekend_morning.png")
    }
}
