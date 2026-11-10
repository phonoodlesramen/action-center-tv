package com.phonoodlesramen.actioncenter.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.home.rows.LiveClock
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What Maya, 9, sees of the guide (K8), in her coral: the Guide card and the full guide
 * with only the kid-safe channels (Family and Cartoon Club of the sample's nine), and the
 * card with none for her. Record with `.\gradlew.bat recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class KidGuideScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val maya = SampleHomeState.kid()

    /** The card at the size of Row B's right-hand spot (350.67×156 dp), captured alone. */
    private fun card(state: GuideCardState, name: String) {
        compose.setContent {
            ActionCenterTheme(accent = maya.accent) {
                Box(Modifier.fillMaxSize().background(Palette.Background).padding(24.dp)) {
                    GuideCard(
                        state = state,
                        clock = LiveClock(maya.now, maya.nowMillis),
                        lateNight = false,
                        plexLinked = true,
                        actions = HomeActions.None,
                        modifier = Modifier.size(350.67.dp, 156.dp),
                        ceiling = maya.profile.ceiling,
                    )
                }
            }
        }
        compose.onNode(hasClickAction()).captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun guideCard() = card(SampleGuide.withKidChannels(maya.nowMillis), "guide_card_kid")

    @Test
    fun guideCardNoneForHer() = card(SampleGuide.ready(maya.nowMillis), "guide_card_kid_none")

    @Test
    fun fullGuide() {
        compose.setContent {
            ActionCenterTheme(accent = maya.accent) {
                GuideScreen(
                    state = SampleGuide.withKidChannels(maya.nowMillis),
                    nowMillis = maya.nowMillis,
                    actions = HomeActions.None,
                    onBack = {},
                    zone = ZoneOffset.UTC,
                    ceiling = maya.profile.ceiling,
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/guide_kid.png")
    }
}
