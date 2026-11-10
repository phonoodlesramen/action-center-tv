package com.phonoodlesramen.actioncenter

import android.content.Intent
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.profiles.SampleProfiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "Who's watching?" in the real activity (K1): it opens from the avatar, and when the home
 * comes back after the screen was off, but only once there's a kid. Clicks go through
 * semantics: tv-material surfaces ignore touch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class WhosWatchingActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<HomeActivity>()

    private val container get() = (compose.activity.application as ActionCenterApp).container

    private val viewModel get() = container.home

    /** Maya is saved, and the home knows her. */
    private fun hasKid() {
        runBlocking { container.profileStore.saveKid(SampleProfiles.maya) }
        compose.waitUntil(5_000) { viewModel.state.value.profiles.any { it.kid } }
    }

    private fun waitForText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** The screen goes off, then the TV wakes and the home comes back. */
    private fun sleepAndWake() {
        compose.activity.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    @Test
    fun `back after the screen was off, it asks who's watching when there's a kid`() {
        hasKid()

        sleepAndWake()

        compose.onNodeWithText("Who's watching?").assertExists()
    }

    @Test
    fun `with no kid, the home comes back as it was`() {
        compose.waitForIdle()

        sleepAndWake()

        compose.onNodeWithText("Who's watching?").assertDoesNotExist()
    }

    @Test
    fun `the avatar opens it, and picking a kid switches to them`() {
        hasKid()
        compose.onNodeWithContentDescription("Who's watching?", substring = true).performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Maya")

        compose.onNodeWithText("Maya").performSemanticsAction(SemanticsActions.OnClick)

        compose.waitUntil(5_000) { runBlocking { container.profileStore.currentId.first() } == SampleProfiles.maya.id }
        compose.onNodeWithText("Who's watching?").assertDoesNotExist()
    }

    @Test
    fun `back from it, when the avatar opened it, lands on the avatar`() {
        hasKid()
        compose.onNodeWithContentDescription("Who's watching?", substring = true).performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Maya")

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.waitUntil(5_000) { compose.onAllNodes(isFocused() and hasContentDescription("Who's watching?", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `while a kid is on, All apps is their My apps`() {
        hasKid()
        runBlocking { container.profileStore.switchTo(SampleProfiles.maya.id) }
        compose.waitUntil(5_000) { viewModel.state.value.profile.kid }
        waitForText("All apps")

        compose.onAllNodesWithText("All apps")[0].performSemanticsAction(SemanticsActions.OnClick)

        waitForText("Only the apps a grown-up picked for you are shown here.")
    }
}
