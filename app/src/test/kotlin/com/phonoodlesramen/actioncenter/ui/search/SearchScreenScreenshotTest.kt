package com.phonoodlesramen.actioncenter.ui.search

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.home.ChannelButton
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.search.LibraryAnswer
import com.phonoodlesramen.actioncenter.search.LibrarySearch
import com.phonoodlesramen.actioncenter.search.SearchItem
import com.phonoodlesramen.actioncenter.search.StreamingApp
import com.phonoodlesramen.actioncenter.search.StreamingItem
import com.phonoodlesramen.actioncenter.search.StreamingSearch
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.theme.LayoutScale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Search (Phase 15) at the Shield's 1080p, with made-up titles. Record with `recordRoborazziDebug`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class SearchScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val found = listOf(
        SearchItem("heat", "movie", "Heat", "1995 · R"),
        SearchItem("speed", "movie", "Speed", "1994 · R"),
        SearchItem("rock", "movie", "The Rock", "1996 · R", why = "With Nicolas Cage"),
        SearchItem("faceoff", "movie", "Face/Off", "1997 · R", why = "With Nicolas Cage"),
        SearchItem("pointbreak", "movie", "Point Break", "1991 · R"),
        SearchItem("xfiles", "show", "The X-Files", "1993 · TV-14"),
        SearchItem("xfiles-s3e4", "episode", "Clyde Bruckman's Final Repose", "The X-Files · S3E4"),
    )

    private fun show(
        typed: String = "",
        profile: ActiveProfile = ActiveProfile.owner("Alex"),
        offline: Boolean = false,
        items: List<SearchItem> = found,
        layoutScale: Float = 1f,
        recent: List<String> = emptyList(),
        onApps: List<StreamingItem> = emptyList(),
    ) {
        val time = SampleHomeState.weeknight
        compose.setContent {
            ActionCenterTheme(layoutScale = layoutScale) {
                SearchScreen(
                    profile = profile,
                    recent = recent,
                    search = LibrarySearch { _, _ -> LibraryAnswer(items, offline) },
                    streaming = StreamingSearch { _, _, _, _ -> onApps },
                    apps = SampleHomeState.apps,
                    guide = SampleGuide.ready(SampleHomeState.weeknight.toEpochSecond(java.time.ZoneOffset.UTC) * 1_000),
                    networks = listOf(ChannelButton("amc", "AMC", "intent://example")),
                    nowMillis = time.toEpochSecond(java.time.ZoneOffset.UTC) * 1_000,
                    lateNight = false,
                    actions = HomeActions.None,
                    onBack = {},
                )
            }
        }
        if (typed.isNotEmpty()) {
            compose.onNode(hasSetTextAction()).performTextInput(typed)
            compose.mainClock.advanceTimeBy(SEARCH_SETTLE_MILLIS + 50)
            compose.waitForIdle()
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun empty() {
        show(recent = listOf("90s action", "kids movies", "heat"))
        capture("search_empty")
    }

    @Test
    fun results() {
        show("90s action")
        capture("search_results")
    }

    @Test
    fun onYourApps() {
        val netflix = StreamingApp("com.netflix.ninja", "Netflix")
        val prime = StreamingApp("com.amazon.amazonvideo.livingroom", "Prime Video")
        show(
            "heat",
            items = found.take(1),
            onApps = listOf(
                StreamingItem("red-heat", "movie", "Red Heat", 1988, "R", image = null, apps = listOf(netflix, prime)),
                StreamingItem("the-heat", "movie", "The Heat", 2013, "R", image = null, apps = listOf(prime)),
                StreamingItem("heat-show", "show", "Heat", 2023, "TV-MA", image = null, apps = listOf(netflix)),
            ),
        )
        capture("search_on_your_apps")
    }

    @Test
    fun aKidsSearch() {
        show("x files", profile = SampleHomeState.maya, items = found.takeLast(2))
        capture("search_kid")
    }

    @Test
    fun offline() {
        show("heat", offline = true, items = found.take(1))
        capture("search_offline")
    }

    @Test
    fun nothingFound() {
        show("zzz", items = emptyList())
        capture("search_nothing")
    }

    @Test
    fun atLargestScale() {
        show("90s action", layoutScale = LayoutScale.MAX)
        capture("search_results_scale_115")
    }
}
