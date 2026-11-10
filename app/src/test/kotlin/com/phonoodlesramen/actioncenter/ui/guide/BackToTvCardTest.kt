package com.phonoodlesramen.actioncenter.ui.guide

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.home.ChannelButton
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.lastapp.AppVisit
import com.phonoodlesramen.actioncenter.lastapp.TunedChannel
import com.phonoodlesramen.actioncenter.lastapp.TvState
import com.phonoodlesramen.actioncenter.lastapp.YouTubeTv
import com.phonoodlesramen.actioncenter.player.SavedSpot
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.FocusRequest
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.HomeFocus
import com.phonoodlesramen.actioncenter.ui.home.HomeScreen
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.home.rows.LocalAppBadges
import com.phonoodlesramen.actioncenter.ui.home.rows.SampleHomeRows
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Back to YouTube TV on the sample home at 8:41 PM: YouTube TV opened on NBC from
 * the Live row 40 minutes ago and left 9 minutes ago. The Guide card's face, OK, its quick
 * actions, Forget this, the Plex Back-to card when its spot is newer, a kid's home, and the
 * recent channels after the favorites in the Live row.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class BackToTvCardTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val minute = 60_000L
    private val youTubeTv = LaunchableApp(YouTubeTv.PACKAGE, "com.google.android.apps.youtube.tvunplugged.activity.MainActivity", "YouTube TV")
    private val yours = SampleHomeState.state().let { it.copy(allApps = it.allApps + youTubeTv) }
    private val now = yours.nowMillis
    private val visit = AppVisit(YouTubeTv.PACKAGE, frontAt = now - 40 * minute, leftAt = now - 9 * minute)
    private val nbc = TunedChannel("NBC", "https://tv.youtube.com/watch/sample3", now - 40 * minute - 3_000)
    private val espn = TunedChannel("ESPN", "https://tv.youtube.com/watch/sample0", now - 2 * 60 * minute)

    private var tv by mutableStateOf(TvState(visit, listOf(nbc, espn)))
    private var spot by mutableStateOf<SavedSpot?>(null)
    private var focus by mutableStateOf(FocusRequest())
    private var home by mutableStateOf(yours)
    private val done = mutableListOf<String>()

    private val actions = object : HomeActions {
        override fun launch(app: LaunchableApp) {
            done += "launch ${app.label}"
        }

        override fun watchTvChannel(channel: TunedChannel) {
            done += "watch ${channel.name}"
        }

        override fun forgetVisit(visit: AppVisit) {
            done += "forget"
            tv = tv.copy(forgotten = visit.frontAt)
        }

        override fun watchNetwork(button: ChannelButton) {
            done += "network ${button.name}"
        }

        override fun openGuide(channelId: String?) {
            done += "guide"
        }
    }

    private fun show(state: HomeUiState = yours, favorites: List<String> = emptyList()) {
        home = state
        compose.setContent {
            ActionCenterTheme(accent = home.accent, layoutScale = home.layoutScale) {
                CompositionLocalProvider(LocalAppBadges provides SampleHomeRows.badges) {
                    HomeScreen(
                        home, actions, focus = focus, rows = SampleHomeRows.rows, guide = SampleGuide.ready(),
                        spot = spot, favorites = favorites, tv = tv,
                    )
                }
            }
        }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun text(text: String) = compose.onNodeWithText(text)

    private fun card() = text("BACK TO YOUTUBE TV")

    private fun lineupCard() = compose.onNodeWithText("Coming up at", substring = true)

    @Test
    fun `while YouTube TV was last the Guide card is Back to YouTube TV, and Play keeps focus`() {
        show()
        text("Play S2E9").assertIsFocused()
        card().assertIsDisplayed()
        text("Started on NBC").assertIsDisplayed()
        text("Hold OK for channels").assertIsDisplayed()
        text("Left 9 min ago").assertIsDisplayed()
        lineupCard().assertDoesNotExist()
    }

    @Test
    fun `OK on it brings YouTube TV back, as its app icon does`() {
        show()
        card().requestFocus()
        press(Key.DirectionCenter)
        assertEquals(listOf("launch YouTube TV"), done)
    }

    @Test
    fun `its quick actions are Resume, the recent channels and Forget this`() {
        show()
        card().requestFocus()
        press(Key.Menu)
        text("QUICK ACTIONS").assertExists()
        text("Resume").assertIsFocused()
        press(Key.DirectionDown)
        text("Watch NBC").assertIsFocused()
        press(Key.DirectionDown)
        text("Watch ESPN").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("watch ESPN"), done)
    }

    @Test
    fun `Forget this puts the card back to its lineup, with focus still on it`() {
        show()
        card().requestFocus()
        press(Key.Menu)
        press(Key.DirectionDown, times = 3)
        text("Forget this").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("forget"), done)
        card().assertDoesNotExist()
        lineupCard().assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("forget", "guide"), done)
    }

    @Test
    fun `without a channel opened here it's Live TV, and it goes 4 hours after you left`() {
        tv = TvState(visit)
        show()
        text("Live TV").assertIsDisplayed()
        compose.onNodeWithText("Started on", substring = true).assertDoesNotExist()
        tv = TvState(visit.copy(leftAt = now - 4 * 60 * minute))
        compose.waitForIdle()
        card().assertDoesNotExist()
        lineupCard().assertExists()
    }

    /** The Ninth Moon on Sci-Fi, paused 1:12:30 in, saved [minutesAgo] minutes ago. */
    private fun ninthMoon(minutesAgo: Long): SavedSpot {
        val slot = SampleGuide.ready().channels.single { it.id == "scifi" }.slotAt(SampleGuide.WEEKNIGHT - 60 * minute)!!
        return SavedSpot("scifi", slot.startMillis, slot.itemKey!!, 72 * minute + 30_000, now - minutesAgo * minute)
    }

    @Test
    fun `the newer of a saved spot and YouTube TV has the card`() {
        spot = ninthMoon(minutesAgo = 5)
        show()
        text("BACK TO SCI-FI").assertExists()
        card().assertDoesNotExist()
        spot = ninthMoon(minutesAgo = 20)
        compose.waitForIdle()
        card().assertExists()
        text("BACK TO SCI-FI").assertDoesNotExist()
    }

    @Test
    fun `a kid's home never shows it`() {
        show(SampleHomeState.kid().let { it.copy(allApps = it.allApps + youTubeTv) })
        card().assertDoesNotExist()
    }

    @Test
    fun `another app last, or YouTube TV not on this TV, leaves the Guide card as it was`() {
        tv = TvState(visit.copy(packageName = "com.netflix.ninja"))
        show()
        card().assertDoesNotExist()
        tv = TvState(visit)
        compose.waitForIdle()
        card().assertExists()
        home = SampleHomeState.state()
        compose.waitForIdle()
        card().assertDoesNotExist()
    }

    // The Live row.

    private fun left(description: String, substring: Boolean = false) =
        compose.onNodeWithContentDescription(description, substring = substring).getBoundsInRoot().left

    @Test
    fun `the recent YouTube TV channels come right after your favorites, newest first`() {
        show(favorites = SampleHomeState.favorites)
        compose.onNodeWithContentDescription("Comedy,", substring = true).requestFocus()
        val order = listOf(left("Comedy,", true), left("TV Mix,", true), left("NBC"), left("ESPN"), left("Movies,", true))
        assertTrue("$order", order.zipWithNext().all { (a, b) -> a < b })
        compose.onNodeWithContentDescription("NBC").requestFocus()
        press(Key.DirectionCenter)
        assertEquals(listOf("network NBC"), done)
    }

    @Test
    fun `Back from the full guide still lands on its channel's Live card`() {
        show(favorites = SampleHomeState.favorites)
        focus = FocusRequest(HomeFocus.LIVE, serial = 1, channelId = "movies")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Movies,", substring = true).assertIsFocused()
    }
}
