package com.phonoodlesramen.actioncenter.ui.settings

import android.app.AppOpsManager
import android.app.Application
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Channels & apps' Apps row line (M5), with and without usage access. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class UsageAccessSettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun usageAccess(mode: Int) =
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, mode)

    private fun show() {
        val controller = object : ChannelsAndAppsController {
            override val state: StateFlow<ChannelsAndAppsState> = MutableStateFlow(
                ChannelsAndAppsState(
                    loaded = true,
                    apps = listOf(WatchNextApp("com.google.android.youtube.tv", "YouTube", entries = 7, shown = true)),
                    channels = 10,
                    youTubeTvChannels = 10,
                ),
            )
        }
        val home = SampleHomeState.state()
        compose.setContent {
            ActionCenterTheme(accent = home.accent, layoutScale = home.layoutScale) {
                SettingsScreen(home, SettingsActions.None, onBack = {}, initialSection = SettingsSection.CHANNELS_AND_APPS, channelsAndApps = controller)
            }
        }
    }

    @Test
    fun withUsageAccess() {
        usageAccess(AppOpsManager.MODE_ALLOWED)
        show()

        compose.onNodeWithText("The Apps row puts what you usually open at this time of day first.").assertIsDisplayed()
    }

    @Test
    fun withoutUsageAccess() {
        usageAccess(AppOpsManager.MODE_IGNORED)
        show()

        compose.onNodeWithText("Settings › Setup › Apps row by habit turns it on", substring = true).assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/settings_channels_and_apps_no_usage_access.png")
    }
}
