package com.phonoodlesramen.actioncenter.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.phonoodlesramen.actioncenter.ai.AiBlurbsController
import com.phonoodlesramen.actioncenter.ai.AiBlurbsStatus
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings › AI blurbs' buttons (M7, M10, M11). Clicks go through semantics: tv-material ignores touch. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class AiBlurbsSettingsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()
    private val status = MutableStateFlow(AiBlurbsStatus())

    private val controller = object : AiBlurbsController {
        override val status = this@AiBlurbsSettingsTest.status

        override fun writeNow() {
            calls += "write now"
        }

        override fun forgetKey() {
            calls += "forget key"
        }
    }

    private val actions = object : SettingsActions {
        override fun setAiBlurbs(on: Boolean) {
            calls += "ai $on"
        }

        override fun openAiKeyOnPhone() {
            calls += "key on phone"
        }
    }

    private fun show(state: HomeUiState) = compose.setContent {
        ActionCenterTheme {
            SettingsScreen(state, actions, onBack = {}, initialSection = SettingsSection.AI, aiBlurbs = controller)
        }
    }

    private fun click(text: String) = compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun `off and without a key, turn it on, or add a key on your phone`() {
        show(SampleHomeState.state())
        compose.onNodeWithText("No key yet").assertIsDisplayed()
        compose.onNodeWithText("Not run yet").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Write now").fetchSemanticsNodes().size)

        // The switch: the whole row, found by its detail (the section is called AI blurbs too).
        compose.onNodeWithText("Once a night", substring = true).performSemanticsAction(SemanticsActions.OnClick)
        click("Add a key on your phone")

        assertEquals(listOf("ai true", "key on phone"), calls)
    }

    @Test
    fun `on with a key, write now says it's writing until the run's line changes, and forget key`() {
        status.value = AiBlurbsStatus(keyEnding = "x7Qa", lastRun = "Last night at 4:02 AM: 28 blurbs")
        show(SampleHomeState.state().copy(aiBlurbs = true))
        compose.onNodeWithText("Key ending …x7Qa").assertIsDisplayed()

        click("Write now")
        compose.onNodeWithText("Writing now. This line changes when it's done.").assertIsDisplayed()
        status.value = status.value.copy(lastRun = "Today at 8:20 PM: 27 blurbs")
        compose.onNodeWithText("Today at 8:20 PM: 27 blurbs").assertIsDisplayed()

        click("Forget key")
        assertEquals(listOf("write now", "forget key"), calls)
    }

    @Test
    fun `on without a key says nothing is sent`() {
        show(SampleHomeState.state().copy(aiBlurbs = true))
        compose.onNodeWithText("Waiting for a key. Nothing is sent until there's one.").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Write now").fetchSemanticsNodes().size)
    }
}
