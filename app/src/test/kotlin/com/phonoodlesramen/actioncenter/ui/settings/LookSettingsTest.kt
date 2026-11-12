package com.phonoodlesramen.actioncenter.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.phonoodlesramen.actioncenter.home.HomeLayout
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings › Look › Layout (Home layouts): the choice, ticked, and what it says about it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class LookSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    private var layout by mutableStateOf(HomeLayout.DEFAULT)
    private val chosen = mutableListOf<HomeLayout>()

    private var moments by mutableStateOf(false)

    private val actions = object : SettingsActions {
        override fun setHomeLayout(layout: HomeLayout) {
            chosen += layout
            this@LookSettingsTest.layout = layout
        }

        override fun setMoments(on: Boolean) {
            moments = on
        }
    }

    private val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)

    private fun show() = compose.setContent {
        ActionCenterTheme {
            SettingsScreen(SampleHomeState.state(homeLayout = layout, moments = moments), actions, onBack = {}, initialSection = SettingsSection.LOOK)
        }
    }

    @Test
    fun theDashboardIsChosenUntilAnotherLayoutIs() {
        show()
        compose.onNode(hasText("Dashboard") and selected).assertExists()
        compose.onNodeWithText("Marquee").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertEquals(listOf(HomeLayout.MARQUEE), chosen)
        compose.onNodeWithText("Marquee").assert(selected)
        compose.onNodeWithText("fills the screen", substring = true).assertExists()
    }

    @Test
    fun changeWithTheTimeOfDaySwitchesMomentsOn() {
        show()
        compose.onNodeWithText("Change with the time of day").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(true, moments)
    }
}
