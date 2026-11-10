package com.phonoodlesramen.actioncenter.ui.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.tvprovider.ChannelList
import com.phonoodlesramen.actioncenter.tvprovider.ChannelListEntry
import com.phonoodlesramen.actioncenter.tvprovider.ChannelsAndAppsController
import com.phonoodlesramen.actioncenter.tvprovider.ChannelsAndAppsState
import com.phonoodlesramen.actioncenter.tvprovider.WatchNextApp
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Channels & apps (L8, L9), with and without what it needs. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class ChannelsAndAppsSettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: ChannelsAndAppsState) {
        val controller = object : ChannelsAndAppsController {
            override val state: StateFlow<ChannelsAndAppsState> = MutableStateFlow(state)
        }
        val home = SampleHomeState.state()
        compose.setContent {
            ActionCenterTheme(accent = home.accent, layoutScale = home.layoutScale) {
                SettingsScreen(home, SettingsActions.None, onBack = {}, initialSection = SettingsSection.CHANNELS_AND_APPS, channelsAndApps = controller)
            }
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    private val apps = listOf(
        WatchNextApp("com.disney.disneyplus", "Disney+", entries = 13, shown = true),
        WatchNextApp("com.amazon.amazonvideo.livingroom", "Prime Video", entries = 1, shown = true),
        WatchNextApp("com.spotify.tv.android", "Spotify", entries = 3, shown = false),
        WatchNextApp("com.google.android.youtube.tv", "YouTube", entries = 7, shown = true),
    )

    private val loaded = ChannelsAndAppsState(
        loaded = true,
        apps = apps,
        channelList = ChannelList.Loaded(List(6) { ChannelListEntry("Network $it", "https://tv.youtube.com/watch/$it") }),
        channels = 6,
        listChannels = 6,
        youTubeTvChannels = 10,
    )

    @Test
    fun yourChannelList() {
        show(loaded)
        capture("settings_channels_and_apps")
    }

    @Test
    fun appsThatFeedContinueInYourApps() {
        show(loaded)
        compose.onNodeWithText("Spotify").requestFocus()
        capture("settings_channels_and_apps_toggles")
    }

    @Test
    fun withoutTheTvListingsGrantAndABrokenList() {
        show(
            ChannelsAndAppsState(
                loaded = true,
                canReadTvListings = false,
                channelList = ChannelList.Problem("it isn't valid JSON (Unexpected JSON token at offset 52)"),
            ),
        )
        capture("settings_channels_and_apps_not_granted")
    }
}
