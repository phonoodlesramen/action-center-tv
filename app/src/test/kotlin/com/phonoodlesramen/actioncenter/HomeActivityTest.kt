package com.phonoodlesramen.actioncenter

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real activity and container: the art-forward home normally, the probe screen
 * after a crash. Clicks go through semantics: tv-material surfaces ignore touch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class HomeActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<HomeActivity>()

    /** The app's, not the activity's: a test may have closed the activity. */
    private val container get() = ApplicationProvider.getApplicationContext<ActionCenterApp>().container

    private val crashGuard get() = container.crashGuard

    @After
    fun tearDown() = crashGuard.leaveSafeMode()

    @Test
    fun `opens the art-forward home with a plex_tv_link code until Plex is linked`() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(TestActionCenterApp.UnclaimedPlex.CODE).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Connect your Plex account").assertExists()
        compose.onNodeWithText("Get a new code").assertExists()
    }

    @Test
    fun `the gear opens Settings`() {
        compose.onNodeWithContentDescription("Settings").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Accent colour", substring = true, ignoreCase = true).assertExists()
    }

    @Test
    fun `sample content's Trailer says it can't play, like Play`() {
        val settings = (compose.activity.application as ActionCenterApp).container.settings
        runBlocking { settings.setShowSampleContent(true) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Trailer").fetchSemanticsNodes().isNotEmpty() }
        // The toast goes after 2.6 s: hold the clock so it's still there to read.
        compose.mainClock.autoAdvance = false

        compose.onNodeWithText("Trailer").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)

        compose.onNodeWithText("Sample content can't play: it's made up for design review").assertExists()
        compose.mainClock.autoAdvance = true
        runBlocking { settings.setShowSampleContent(false) }
    }

    @Test
    fun `the home outlives its screen, so a new Home screen opens with the cards it had`() {
        runBlocking { container.settings.setShowSampleContent(true) }
        compose.waitUntil(5_000) { container.home.state.value.sampleContent }
        val home = container.home

        // Android made the Home screen again while the app ran (a Google TV waking up).
        compose.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)

        assertTrue("the home keeps going", home.viewModelScope.isActive)
        assertSame(home, container.home)
        assertTrue("with the cards it had", container.home.state.value.sampleContent)
        runBlocking { container.settings.setShowSampleContent(false) }
    }

    @Test
    fun `after a crash it opens safe mode, and leaving it shows the home`() {
        crashGuard.recordCrash(IllegalStateException("boom"))
        compose.activityRule.scenario.recreate()

        compose.onNodeWithText("Safe mode").assertExists()
        compose.onNodeWithText("Get a new code").assertDoesNotExist()

        compose.onNodeWithText("Leave safe mode").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Get a new code").assertExists()
    }
}
