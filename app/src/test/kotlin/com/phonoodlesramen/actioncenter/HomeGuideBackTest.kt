package com.phonoodlesramen.actioncenter

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideSource
import com.phonoodlesramen.actioncenter.player.GuidePlayerActivity
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Back from the guide's player lands on the guide, on the program that was played (G5), even
 * when Android makes the Home activity again while the player is in front. The player rides
 * out a configuration change (an HDMI mode switch, a remote reconnecting); the home behind it
 * is made again, and before this it opened at the top of the home.
 *
 * The names stay short: Robolectric puts a test's name in its data path, and on Windows a
 * path over 259 characters can't open (suggestions.db-journal fails with SQLITE_CANTOPEN).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P, application = SampleGuideApp::class)
class HomeGuideBackTest {
    @get:Rule
    val compose = createAndroidComposeRule<HomeActivity>()

    @Test
    fun `Back from the player lands on the guide when the home is made again`() {
        openGuide()
        press(Key.DirectionDown, times = 2)
        assertFocused("TV Mix, ")
        press(Key.DirectionCenter)
        val player = shadowOf(compose.activity).nextStartedActivity
        assertEquals(GuidePlayerActivity::class.java.name, player.component?.className)
        assertEquals("tv-mix", player.getStringExtra(GuidePlayerActivity.EXTRA_CHANNEL))

        // The player is in front: the home stops, and Android makes it again behind the player.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.recreate()
        // Back from the player.
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()

        assertFocused("TV Mix, ")
        compose.onNodeWithText("Connect your Plex account").assertDoesNotExist()
        // Back from the guide still returns to the Guide card that opened it.
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNode(isFocused()).assert(hasText(GUIDE_CARD))
    }

    @Test
    fun `the details stay when the home is made again, and Back is the guide`() {
        openGuide()
        // Movies' next program but one: later, so OK opens its details.
        press(Key.DirectionRight, times = 2)
        assertFocused("Movies, ")
        val program = compose.onNode(isFocused()).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        press(Key.DirectionCenter)
        compose.onNodeWithText("Starts at", substring = true).assertExists()

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        compose.onNodeWithText("Starts at", substring = true).assertExists()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertFocused(program)
    }

    private fun openGuide() {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(GUIDE_CARD).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasClickAction() and hasText(GUIDE_CARD)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun assertFocused(description: String) =
        compose.onNode(isFocused()).assert(hasContentDescription(description, substring = true))

    private companion object {
        const val GUIDE_CARD = "PLEX GUIDE · 6 CHANNELS"
    }
}

/** The test app with the sample lineup as the Plex Guide's, around the time the test runs. */
class SampleGuideApp : TestActionCenterApp() {
    override val guide = object : GuideSource {
        override val card: Flow<GuideCardState> = flowOf(SampleGuide.ready(System.currentTimeMillis()))
    }
}
