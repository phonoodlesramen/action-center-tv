package com.phonoodlesramen.actioncenter.ui.nfl

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.home.HomeLayout
import com.phonoodlesramen.actioncenter.nfl.GameState
import com.phonoodlesramen.actioncenter.nfl.NflCities
import com.phonoodlesramen.actioncenter.nfl.NflCityImages
import com.phonoodlesramen.actioncenter.nfl.NflView
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.HomeScreen
import com.phonoodlesramen.actioncenter.ui.home.rows.SampleHomeRows
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Two cities (ui-spec "Two cities") behind each matchup, from [SampleNfl]: the home's game-day
 * card, the Games tab's panel live and before kickoff, a game's details live, before kickoff and
 * once it's over, and Marquee's backdrop on game day, with the app's own pictures
 * (`src/main/assets/nfl/cities/`). Coil runs unconfined, so they're decoded before each capture. Record with
 * `./gradlew :app:testDebugUnitTest --tests '*CityClashScreenshotTest' -Proborazzi.test.record=true`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class CityClashScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var cities: NflCityImages

    @Before
    fun setUp() {
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ApplicationProvider.getApplicationContext()).coroutineContext(Dispatchers.Unconfined).build())
        cities = NflCityImages { abbr -> File(ASSETS, NflCities.asset(abbr)).takeIf { it.isFile } }
    }

    @After
    fun resetImages() = SingletonImageLoader.reset()

    private companion object {
        /** The app's assets, from the module's folder, where the tests run. */
        val ASSETS = File("src/main/assets")
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun screen(page: NflPage, view: NflView = SampleNfl.view()) {
        val state = SampleHomeState.state(time = SampleNfl.sundayNight)
        compose.setContent {
            CompositionLocalProvider(LocalNflCities provides cities) {
                ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                    NflScreen(
                        view = view,
                        open = NflOpen(page),
                        nowMillis = SampleNfl.nowMillis,
                        controller = SampleNfl.Controller(view),
                        actions = HomeActions.None,
                        watch = SampleNfl.watch,
                        onBack = {},
                    )
                }
            }
        }
        settle()
    }

    private fun home(layout: HomeLayout = HomeLayout.DASHBOARD, moments: Boolean = false) {
        val state = SampleHomeState.state(time = SampleNfl.sundayNight, homeLayout = layout, moments = moments, profiles = SampleHomeState.family)
        compose.setContent {
            CompositionLocalProvider(LocalNflCities provides cities) {
                ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                    HomeScreen(
                        state,
                        HomeActions.None,
                        rows = SampleHomeRows.rows,
                        guide = SampleGuide.ready(state.nowMillis),
                        favorites = SampleHomeState.favorites,
                        nfl = SampleNfl.view(),
                        watch = SampleNfl.watch,
                    )
                }
            }
        }
        settle()
    }

    @Test
    fun theHomeCard() {
        home()
        capture("nfl_cities_home_card")
    }

    @Test
    fun theGamesPanelWhileItsOn() {
        screen(NflPage.Tab(NflTab.GAMES, gameId = SampleNfl.live.id))
        capture("nfl_cities_games")
    }

    @Test
    fun theGamesPanelBeforeKickoff() {
        screen(NflPage.Tab(NflTab.GAMES, gameId = SampleNfl.monday.id))
        capture("nfl_cities_games_pre")
    }

    @Test
    fun aGameWhileItsOn() {
        screen(NflPage.Game(SampleNfl.live.id))
        capture("nfl_cities_game_live")
    }

    @Test
    fun aGameBeforeKickoff() {
        screen(NflPage.Game(SampleNfl.monday.id))
        capture("nfl_cities_game_pre")
    }

    /** Monday's game over, Chicago 27 Seattle 20: Seattle's city has gone dark. */
    @Test
    fun aFinalDarkensTheLosersCity() {
        val over = SampleNfl.monday.copy(state = GameState.FINAL, awayScore = 27, homeScore = 20, winner = "CHI", detail = "Final")
        val week = SampleNfl.week.copy(games = SampleNfl.week.games.map { if (it.id == over.id) over else it })
        screen(NflPage.Game(over.id), SampleNfl.view().copy(state = SampleNfl.state(week = week)))
        capture("nfl_cities_game_final")
    }

    @Test
    fun marqueeOnGameDay() {
        home(HomeLayout.MARQUEE, moments = true)
        capture("nfl_cities_marquee")
    }
}
