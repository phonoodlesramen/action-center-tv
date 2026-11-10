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
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › About: GPL-3.0's notice, Open-source licences, and each licence's text from the
 * APK's assets. Clicks go through semantics: tv-material ignores touch.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class AboutSettingsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show() = compose.setContent {
        val state = SampleHomeState.state()
        ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
            SettingsScreen(state, SettingsActions.None, onBack = {}, initialSection = SettingsSection.ABOUT, version = "0.1.0")
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun asset(licence: Licence) = compose.activity.assets.open(licence.asset).bufferedReader().use { it.readText() }

    @Test
    fun `every licence the list names is in the APK, and the app's is the repository's LICENSE`() {
        Licence.entries.forEach { assertTrue("${it.asset} is empty", asset(it).isNotBlank()) }
        assertTrue(OpenSource.PARTS.map { it.licence }.toSet().containsAll(Licence.entries))
        // The working directory is the app module; the licence is the repository's own, line endings aside.
        val repository = File("../LICENSE").readText().replace("\r\n", "\n")
        assertEquals(repository, asset(Licence.GPL_3).replace("\r\n", "\n"))
    }

    @Test
    fun `About says it's free software under the GPL, with no warranty, and where the source is`() {
        show()
        compose.onNodeWithText("Copyright © 2026").assertIsDisplayed()
        compose.onNodeWithText(OpenSource.AUTHOR).assertIsDisplayed()
        compose.onNodeWithText("GNU General Public License, version 3 or later", substring = true).assertIsDisplayed()
        compose.onNodeWithText("no warranty", substring = true).assertIsDisplayed()
        compose.onNodeWithText(OpenSource.SOURCE, substring = true).assertIsDisplayed()
        capture("settings_about")
    }

    @Test
    fun `the copyright's name shows the maintainer's GitHub profile as a QR code, and Back lands on it`() {
        show()
        click(OpenSource.AUTHOR)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("QR code for ${OpenSource.AUTHOR}'s GitHub profile").assertIsDisplayed()
        compose.onNodeWithText("github.com/${OpenSource.AUTHOR}", substring = true).assertIsDisplayed()
        capture("settings_about_github")

        back()
        compose.waitForIdle()
        compose.onNodeWithText(OpenSource.AUTHOR).assertIsFocused()
    }

    @Test
    fun `Open-source licences lists every part, a licence opens its text, and Back goes a page at a time`() {
        show()
        click("Open-source licences")
        compose.waitForIdle()
        compose.onNodeWithText("Action Center").assertIsFocused()
        OpenSource.PARTS.forEach { compose.onNodeWithText(it.name).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText("Action Center").performScrollTo()
        capture("settings_about_licences")

        click("NextLib")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("GNU GENERAL PUBLIC LICENSE", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("GNU General Public License v3.0, the full text").assertIsFocused()
        capture("settings_about_licence_text")

        back()
        compose.waitForIdle()
        compose.onNodeWithText("NextLib").assertIsDisplayed()
        back()
        compose.waitForIdle()
        compose.onNodeWithText("Open-source licences").assertIsFocused()
    }
}
