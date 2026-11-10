package com.phonoodlesramen.actioncenter.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.LensPick
import com.phonoodlesramen.actioncenter.content.Pick
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.art.LocalArtUrls
import com.phonoodlesramen.actioncenter.ui.art.TestLogos
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import com.phonoodlesramen.actioncenter.ui.home.rows.SampleHomeRows
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Title logos (Phase 12) on Tonight's Pick and the lens card, yours and a kid's, from
 * made-up wordmarks ([TestLogos]): a wide logo and a tall one in each card, dark logos
 * (the title stays), a red one that reads on the hero and not on a warm lens, a logo still
 * loading (the title waits in its box), late night, and 115%. A title with no logo is every other screenshot, unchanged. Record with
 * `.\gradlew.bat recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class LogoScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @OptIn(DelicateCoilApi::class)
    @Before
    fun drawnLogos() = SingletonImageLoader.setUnsafe(TestLogos.loader(ApplicationProvider.getApplicationContext()))

    @After
    fun resetImages() = SingletonImageLoader.reset()

    private fun show(state: HomeUiState) = compose.setContent {
        CompositionLocalProvider(LocalArtUrls provides TestLogos.artUrls) {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                if (state.profile.kid) {
                    HomeScreen(state, HomeActions.None, rows = SampleHomeRows.rows, guide = SampleGuide.withKidChannels(state.nowMillis))
                } else {
                    HomeScreen(state, HomeActions.None)
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    /** Sample content with [hero] as Tonight's Pick and [lens] on the lens card (Throwback on a weeknight). */
    private fun yours(hero: Pick, lens: LensPick, scale: Float = 1f, time: java.time.LocalDateTime = SampleHomeState.weeknight): HomeUiState {
        val state = SampleHomeState.state(time = time, layoutScale = scale)
        return state.copy(pick = hero, lens = state.lens!!.copy(pick = lens))
    }

    private fun assertLogo(title: String) {
        compose.onNodeWithContentDescription(title).assertExists()
        compose.onNodeWithText(title).assertDoesNotExist()
    }

    private fun assertTitle(title: String) {
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithContentDescription(title).assertDoesNotExist()
    }

    @Test
    fun wideHeroTallLens() {
        show(yours(starfall, lanternLens))
        capture("home_logo_wide_hero")
        assertLogo("Starfall Harbor")
        assertLogo("The Lantern Keepers")
    }

    @Test
    fun tallHeroWideLens() {
        show(yours(lanternPick, starfallLens))
        capture("home_logo_tall_hero")
        assertLogo("The Lantern Keepers")
        assertLogo("Starfall Harbor")
    }

    @Test
    fun darkLogosKeepTheTitle() {
        show(yours(nightFerry, ironwoodLens))
        capture("home_logo_dark")
        assertTitle("Night Ferry")
        assertTitle("Ironwood Hollow")
    }

    /** Each card judges a logo against its own background: red reads on the hero's scrim, not on Throwback's warm tint. */
    @Test
    fun redLogoOnTheHeroNotOnAWarmLens() {
        show(yours(emberLine, scarletMileLens))
        capture("home_logo_red")
        assertLogo("Ember Line")
        assertTitle("Scarlet Mile")
    }

    @Test
    fun loadingTitleWaitsInTheLogosBox() {
        show(yours(starfall.copy(logo = TestLogos.SLOW), lanternLens.copy(logo = TestLogos.SLOW)))
        capture("home_logo_loading")
        assertTitle("Starfall Harbor")
        assertTitle("The Lantern Keepers")
    }

    @Test
    fun lateNightDimsTheLogo() {
        show(yours(starfall, lanternLens.copy(line = "Watched four times"), time = SampleHomeState.lateNight))
        capture("home_logo_late_night")
        assertLogo("Starfall Harbor")
    }

    @Test
    fun scale115() {
        show(yours(starfall, lanternLens, scale = 1.15f))
        capture("home_logo_scale_115")
        assertLogo("Starfall Harbor")
        assertLogo("The Lantern Keepers")
    }

    @Test
    fun tallHeroScale115() {
        show(yours(lanternPick, starfallLens, scale = 1.15f))
        capture("home_logo_tall_hero_scale_115")
        assertLogo("The Lantern Keepers")
        assertLogo("Starfall Harbor")
    }

    @Test
    fun kidsHero() {
        show(kid())
        capture("home_kid_logo")
        assertLogo("Pebble & Pip")
        assertLogo("The Lantern Keepers")
    }

    @Test
    fun kidsHeroScale115() {
        show(kid(scale = 1.15f))
        capture("home_kid_logo_scale_115")
        assertLogo("Pebble & Pip")
        assertLogo("The Lantern Keepers")
    }

    /** Maya's weeknight home with a made-up logo on her pick and on "Remember when". */
    private fun kid(scale: Float = 1f): HomeUiState {
        val content = SampleHomeState.kidContent()
        val pick = Pick("pebble", "Pebble & Pip", "2023 · 1h 31m · G", "Because you loved Shaun the Sheep Movie", logo = TestLogos.KID)
        val rememberWhen = LensPick("lantern", "The Lantern Keepers", "2021 · 1h 52m · PG", "A year ago you watched it twice in a week!", seen = true, logo = TestLogos.TALL)
        return SampleHomeState.kid(
            layoutScale = scale,
            content = content.copy(picks = listOf(pick) + content.picks, lenses = content.lenses + (Lens.THROWBACK to listOf(rememberWhen))),
        )
    }

    private companion object {
        val starfall = Pick(
            "starfall", "Starfall Harbor", "2025 · 2h 4m · PG-13", "Because you watched three sea stories this month",
            logo = TestLogos.WIDE,
        )
        val lanternPick = Pick(
            "lantern", "The Lantern Keepers", "Season 1 · 3 episodes left · TV-14", "You're 3 episodes from the end of the season",
            playLabel = "Play S1E6", logo = TestLogos.TALL,
        )
        val nightFerry = Pick("ferry", "Night Ferry", "2019 · 1h 48m · R", "Rated 8.1 · never played", logo = TestLogos.NAVY)
        val emberLine = Pick("ember", "Ember Line", "2024 · 1h 57m · R", "Because you watched two heist films this month", logo = TestLogos.RED)
        val scarletMileLens = LensPick("scarlet", "Scarlet Mile", "2010 · 2h 1m", "One year ago today you watched", seen = true, logo = TestLogos.RED_LENS)
        val lanternLens = LensPick("lantern", "The Lantern Keepers", "2021 · 1h 52m", "One year ago today you watched", seen = true, logo = TestLogos.TALL)
        val starfallLens = LensPick("starfall", "Starfall Harbor", "2025 · 2h 4m", "One year ago today you watched", seen = true, logo = TestLogos.WIDE)
        val ironwoodLens = LensPick("ironwood", "Ironwood Hollow", "2008 · 1h 39m", "One year ago today you watched", seen = true, logo = TestLogos.BLACK)
    }
}
