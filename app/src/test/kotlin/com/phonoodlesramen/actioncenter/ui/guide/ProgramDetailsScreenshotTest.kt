package com.phonoodlesramen.actioncenter.ui.guide

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A program's details (plan §2) at the Shield's 1080p TV configuration, from the sample
 * lineup (UTC): a film on now at 90/100/115%, a later episode at 100 and 115%, and a film
 * that has left the library, on now. Plex art doesn't load here, so the art is generated.
 * Record with `.\gradlew.bat recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class ProgramDetailsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val guide = SampleGuide.ready()

    private fun show(channelId: String, slot: GuideSlot, now: Long = SampleGuide.WEEKNIGHT, layoutScale: Float = 1f) = compose.setContent {
        ActionCenterTheme(layoutScale = layoutScale) {
            ProgramDetailsScreen(
                state = guide,
                channelId = channelId,
                startMillis = slot.startMillis,
                nowMillis = now,
                actions = HomeActions.None,
                onBack = {},
                zone = ZoneOffset.UTC,
            )
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    private val film get() = guide.channels.first { it.id == "movies" }.slotAt(SampleGuide.WEEKNIGHT)!!

    private val episode get() = guide.channels.first { it.id == "tv-mix" }.slots.first { it.title == "Kitchen Table" && it.startMillis > SampleGuide.WEEKNIGHT }

    @Test
    fun onNow() {
        show("movies", film)
        capture("details_on_now")
    }

    @Test
    fun onNowScale90() {
        show("movies", film, layoutScale = 0.9f)
        capture("details_on_now_scale_90")
    }

    @Test
    fun onNowScale115() {
        show("movies", film, layoutScale = 1.15f)
        capture("details_on_now_scale_115")
    }

    /** Kitchen Table at 9:30: "Starts at 9:30" where Watch would be, Open in Plezy focused. */
    @Test
    fun laterEpisode() {
        show("tv-mix", episode)
        capture("details_later")
    }

    @Test
    fun laterEpisodeScale115() {
        show("tv-mix", episode, layoutScale = 1.15f)
        capture("details_later_scale_115")
    }

    /** 12:45 AM: Classics' film has left the library, so it's off air; Watch tunes in to the channel. */
    @Test
    fun offAir() {
        val now = SampleGuide.WEEKNIGHT + (4 * 60 + 4) * 60_000L
        show("classics", guide.channels.first { it.id == "classics" }.slotAt(now)!!, now = now)
        capture("details_off_air")
    }
}
