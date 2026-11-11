package com.phonoodlesramen.actioncenter.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.setup.SetupStatus
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.update.UpdateInfo
import com.phonoodlesramen.actioncenter.update.UpdateMode
import com.phonoodlesramen.actioncenter.update.UpdateStatus
import com.phonoodlesramen.actioncenter.update.UpdateWork
import com.phonoodlesramen.actioncenter.update.UpdatesController
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › About's Updates: the line, Check for updates, Install or Allow installs, What's new
 * and the mode. Clicks go through semantics: tv-material ignores touch.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class AboutUpdatesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()

    private inner class FakeUpdates(status: UpdateStatus) : UpdatesController {
        override val status = MutableStateFlow(status)
        override fun checkNow() {
            calls += "check"
        }

        override fun install() {
            calls += "install"
        }

        override fun setMode(mode: UpdateMode) {
            calls += "mode $mode"
            status.value = status.value.copy(mode = mode)
        }
    }

    private val actions = object : SettingsActions {
        override fun openInstallUnknownApps() {
            calls += "allow"
        }
    }

    private val state = SampleHomeState.state()
    private val info = UpdateInfo(
        format = 1,
        versionCode = 200,
        versionName = "1.1.0",
        tag = "v1.1.0",
        apk = "app-release.apk",
        size = 53_572_870,
        sha256 = "c".repeat(64),
        signerSha256 = "a".repeat(64),
        notesUrl = "https://github.com/someone/action-center-tv/releases/tag/v1.1.0",
    )
    private val upToDate = UpdateStatus(repo = "someone/action-center-tv", checkedMillis = state.nowMillis - 2 * HOUR)

    private fun show(status: UpdateStatus, canInstall: Boolean = true): FakeUpdates {
        val updates = FakeUpdates(status)
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                SettingsScreen(
                    state,
                    actions,
                    onBack = {},
                    initialSection = SettingsSection.ABOUT,
                    version = "1.0.0",
                    setup = SetupStatus(installUpdates = canInstall),
                    updates = updates,
                )
            }
        }
        return updates
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun `up to date, Check for updates checks, and the mode pills choose how it checks`() {
        show(upToDate)
        compose.onNodeWithText("Up to date · checked 2 h ago").assertIsDisplayed()
        compose.onNodeWithText("github.com/someone/action-center-tv's releases", substring = true).assertIsDisplayed()
        assertEquals(0, count("Install 1.1.0"))
        capture("settings_about_updates")

        click("Check for updates")
        click("Off")
        compose.waitForIdle()
        compose.onNodeWithText("It never looks by itself", substring = true).assertIsDisplayed()
        click("Ask me")
        assertEquals(listOf("check", "mode OFF", "mode ASK"), calls)
    }

    @Test
    fun `an update offers Install, and What's new shows its notes as a QR code, Back landing on it`() {
        show(upToDate.copy(available = info))
        compose.onNodeWithText("1.1.0 is available · 51 MB").assertIsDisplayed()
        capture("settings_about_update_available")

        click("Install 1.1.0")
        assertEquals(listOf("install"), calls)

        click("What's new")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("QR code for 1.1.0's release notes").assertIsDisplayed()
        capture("settings_about_whats_new")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("What's new").assertIsFocused()
    }

    @Test
    fun `until Install unknown apps is on, Allow installs opens it instead`() {
        show(upToDate.copy(available = info), canInstall = false)
        assertEquals(0, count("Install 1.1.0"))
        compose.onNodeWithText("Android needs Install unknown apps", substring = true).assertIsDisplayed()
        capture("settings_about_update_allow")
        click("Allow installs")
        assertEquals(listOf("allow"), calls)
    }

    @Test
    fun `the line says what it's doing, and a problem in the caution colour`() {
        val updates = show(upToDate.copy(available = info, work = UpdateWork.Downloading(34)))
        compose.onNodeWithText("Downloading 1.1.0… 34%").assertIsDisplayed()
        // The pills stay while it works, so focus has nowhere to fall.
        compose.onNodeWithText("Install 1.1.0").assertIsDisplayed()

        updates.status.value = upToDate.copy(available = info, problem = "The download didn't match the release's SHA-256")
        compose.waitForIdle()
        compose.onNodeWithText("The download didn't match the release's SHA-256", substring = true).assertIsDisplayed()
        capture("settings_about_update_problem")
    }

    @Test
    fun `a debug build says it doesn't update, with nothing to press`() {
        show(UpdateStatus(enabled = false, debugBuild = true))
        compose.onNodeWithText("Debug builds don't update themselves.").assertIsDisplayed()
        assertEquals(0, count("Check for updates"))
        assertEquals(0, count("Ask me"))
    }

    private companion object {
        const val HOUR = 60 * 60_000L
    }
}
