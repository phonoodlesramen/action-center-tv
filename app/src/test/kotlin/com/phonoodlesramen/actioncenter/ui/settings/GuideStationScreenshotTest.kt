package com.phonoodlesramen.actioncenter.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.station.GuideStationController
import com.phonoodlesramen.actioncenter.station.StationView
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Plex Guide › Guide station (Phase 8) on the sample weeknight (UTC): not paired,
 * pairing with its code, paired, and paired but unable to reach the station, each at 90, 100
 * and 115%; then Settings › Plex Guide with its station row, editing greyed out while the
 * station can't be reached. Record with `.\gradlew.bat recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class GuideStationScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val zone = TimeZone.getDefault()
    private val now = SampleHomeState.state().nowMillis

    @Before
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun restore() = TimeZone.setDefault(zone)

    private fun show(view: StationView, layoutScale: Float = 1f, open: Boolean = true) {
        val home = SampleHomeState.state(layoutScale = layoutScale)
        compose.setContent {
            ActionCenterTheme(accent = home.accent, layoutScale = layoutScale) {
                SettingsScreen(
                    home, SettingsActions.None, onBack = {}, initialSection = SettingsSection.PLEX_GUIDE,
                    guideEditor = FakeGuideEditor(), station = GuideStationController.Fixed(view),
                )
            }
        }
        if (open) {
            compose.onNodeWithText("Guide station").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun unpaired() {
        show(SampleStation.unpaired())
        capture("settings_guide_station_unpaired")
    }

    @Test
    fun unpairedScale90() {
        show(SampleStation.unpaired(), layoutScale = 0.9f)
        capture("settings_guide_station_unpaired_scale_90")
    }

    @Test
    fun unpairedScale115() {
        show(SampleStation.unpaired(), layoutScale = 1.15f)
        capture("settings_guide_station_unpaired_scale_115")
    }

    @Test
    fun pairing() {
        show(SampleStation.pairing(now))
        capture("settings_guide_station_pairing")
    }

    @Test
    fun pairingScale90() {
        show(SampleStation.pairing(now), layoutScale = 0.9f)
        capture("settings_guide_station_pairing_scale_90")
    }

    @Test
    fun pairingScale115() {
        show(SampleStation.pairing(now), layoutScale = 1.15f)
        capture("settings_guide_station_pairing_scale_115")
    }

    @Test
    fun paired() {
        show(SampleStation.paired(now))
        capture("settings_guide_station_paired")
    }

    @Test
    fun pairedScale90() {
        show(SampleStation.paired(now), layoutScale = 0.9f)
        capture("settings_guide_station_paired_scale_90")
    }

    @Test
    fun pairedScale115() {
        show(SampleStation.paired(now), layoutScale = 1.15f)
        capture("settings_guide_station_paired_scale_115")
    }

    @Test
    fun unreachable() {
        show(SampleStation.unreachable(now))
        capture("settings_guide_station_unreachable")
    }

    @Test
    fun unreachableScale90() {
        show(SampleStation.unreachable(now), layoutScale = 0.9f)
        capture("settings_guide_station_unreachable_scale_90")
    }

    @Test
    fun unreachableScale115() {
        show(SampleStation.unreachable(now), layoutScale = 1.15f)
        capture("settings_guide_station_unreachable_scale_115")
    }

    @Test
    fun plexGuideWhileUnreachable() {
        show(SampleStation.unreachable(now), open = false)
        capture("settings_plex_guide_station_unreachable")
    }

    @Test
    fun plexGuidePaired() {
        show(SampleStation.paired(now), open = false)
        capture("settings_plex_guide_station_paired")
    }
}
