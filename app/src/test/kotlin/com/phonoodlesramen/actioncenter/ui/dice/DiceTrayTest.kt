package com.phonoodlesramen.actioncenter.ui.dice

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.dice.DiceDeck
import com.phonoodlesramen.actioncenter.dice.DiceTable
import com.phonoodlesramen.actioncenter.dice.DieRoll
import com.phonoodlesramen.actioncenter.dice.DieTitle
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.HomeScreen
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import java.time.LocalDate
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The die (spec §4): the top bar's die opens its tray, which throws as it opens, lands, and
 * narrows by its dials; Back goes back to the die. Made-up titles. Record with
 * `./gradlew :app:testDebugUnitTest --tests '*DiceTrayTest' -Proborazzi.test.record=true`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class DiceTrayTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val asked = mutableListOf<String>()
    private val actions = object : HomeActions {
        override fun play(id: String, title: String) {
            asked += "play $id $title"
        }
    }

    private val deck = DiceDeck(
        listOf(
            DieTitle("lanterns", "lanterns", "movie", "Paper Lanterns", 1994, listOf("Comedy", "Romance"), "PG", 7.6, 101 * 60_000L),
            DieTitle("cat", "cat", "movie", "The Lighthouse Keeper's Cat", 2016, listOf("Comedy", "Family"), "PG", 7.1, 94 * 60_000L),
            DieTitle("kettle", "kettle", "movie", "Night Shift at Kettle Lake", 2009, listOf("Thriller", "Horror"), "R", 6.4, 88 * 60_000L),
            DieTitle("granite", "granite", "movie", "Granite & Gold", 1957, listOf("Western", "Drama"), "Approved", 7.9, 112 * 60_000L),
            DieTitle("velvet", "velvet", "movie", "Velvet Overture", 1988, listOf("Music", "Drama"), "PG-13", 7.3, 126 * 60_000L, seen = true, lastSeenYear = 2019),
            DieTitle("floors", "floors", "movie", "Sixteen Floors Down", 1999, listOf("Thriller", "Action"), "R", 6.9, 109 * 60_000L),
            DieTitle(
                "signal", "signal-2-4", "show", "Signal Hill", 2019, listOf("Drama", "Mystery"), "TV-14", 8.2, 52 * 60_000L,
                episode = "S2 · E4", seen = true, picksUp = true,
            ),
            DieTitle("astro", "astro-1-1", "show", "Backyard Astronomers", 2021, listOf("Documentary", "Family"), "TV-G", 8.0, 24 * 60_000L, episode = "S1 · E1"),
        ),
    )

    private fun table(deck: DiceDeck = this.deck) = DiceTable(read = { deck }, random = Random(42), today = { LocalDate.of(2026, 10, 26) })

    private fun library(): HomeUiState =
        SampleHomeState.state(sample = false, plex = SampleHomeState.plexLinked, plexSnapshot = SampleHomeState.engineLibrary())

    private var closed = 0

    /** A new [opened] closes the tray and opens it again. */
    private var opened by mutableIntStateOf(0)

    private fun tray(table: DiceTable = table(), profile: ActiveProfile = ActiveProfile.owner("Alex")) {
        compose.setContent {
            ActionCenterTheme {
                key(opened) {
                    DiceTray(
                        profile = profile,
                        version = 1,
                        table = table,
                        now = SampleHomeState.weeknight,
                        lateNight = false,
                        actions = actions,
                        onLanded = {},
                        onClose = { closed++ },
                    )
                }
            }
        }
        settle()
    }

    private fun home(table: DiceTable = table(), state: HomeUiState = library()) {
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                HomeScreen(state, actions, dice = table)
            }
        }
        settle()
    }

    /** Past a throw's tumble and the tray's entrance. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(TUMBLE_MILLIS + 500L)
        compose.waitForIdle()
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    /** A dial's chip, scrolled into its row first: a row only lays out the chips in view. */
    private fun chip(text: String) {
        val rows = compose.onAllNodes(hasScrollToIndexAction())
        val found = (0 until rows.fetchSemanticsNodes().size).any { runCatching { rows[it].performScrollToNode(hasText(text)) }.isSuccess }
        assertTrue("no chip \"$text\"", found)
        compose.waitForIdle()
        click(text)
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun theDieOnTheTopBar() {
        home()
        compose.onNodeWithContentDescription("Roll the die").requestFocus()
        settle()
        capture("dice_top_bar_focused")
    }

    @Test
    fun noLibraryNoDie() {
        home(state = SampleHomeState.state(sample = false, plex = SampleHomeState.plexLinked))
        compose.onNodeWithContentDescription("Roll the die").assertDoesNotExist()
    }

    @Test
    fun theDieOpensItsTrayThrowsAndLandsOnPlay() {
        home()
        compose.onNodeWithContentDescription("Roll the die").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        compose.onNodeWithText("THE DIE HAS SPOKEN").assertExists()
        // The hero has a Play of its own: the tray's is the one with focus.
        compose.onNode(hasText("Play") and isFocused()).assertExists()
        compose.onNodeWithText("8 on the table").assertExists()
        capture("dice_tray")
    }

    @Test
    fun backClosesTheTrayToTheDie() {
        home()
        compose.onNodeWithContentDescription("Roll the die").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
        compose.onNodeWithText("THE DIE HAS SPOKEN").assertDoesNotExist()
        compose.onNodeWithContentDescription("Roll the die").assertIsFocused()
    }

    @Test
    fun rollAgainMovesOnAndHasMoreToSay() {
        tray()
        val seen = mutableSetOf<String>()
        fun shown() = deck.titles.map { it.title }.single { title -> runCatching { compose.onNodeWithText(title).assertExists() }.isSuccess }
        seen += shown()
        click("Roll again")
        compose.onNodeWithText("ROLLED AGAIN").assertExists()
        seen += shown()
        click("Roll again")
        compose.onNodeWithText("THIRD TIME'S THE CHARM").assertExists()
        seen += shown()
        assertEquals("nothing comes up twice until everything has", 3, seen.size)
    }

    @Test
    fun aDialNarrowsTheTableAndThrowsAtOnce() {
        tray()
        chip("Horror")
        compose.onNodeWithText("Horror").assertIsSelected()
        compose.onNodeWithText("THE ONLY ONE THAT FITS").assertExists()
        compose.onNodeWithText("Night Shift at Kettle Lake").assertExists()
        compose.onNodeWithText("1 of 8 fit").assertExists()
        capture("dice_tray_genre")
    }

    @Test
    fun nothingFitsIsNoDice() {
        tray()
        chip("Shows")
        chip("1990s")
        compose.onNodeWithText("NO DICE").assertExists()
        compose.onNodeWithText("Nothing fits all three").assertExists()
        capture("dice_tray_no_dice")
    }

    @Test
    fun theDialsAreWhereYouLeftThem() {
        tray()
        chip("Movies")
        compose.onNodeWithText("Movies").assertIsSelected()
        opened++
        settle()
        compose.onNodeWithText("Movies").assertIsSelected()
        compose.onNodeWithText("6 of 8 fit").assertExists()
    }

    @Test
    fun playHandsOffWhatPlaysAndCloses() {
        tray(table(DiceDeck(deck.titles.filter { it.ratingKey == "signal" })))
        compose.onNodeWithText("Picks up at S2 · E4 · over by 9:33 PM").assertExists()
        click("Play")
        assertEquals(listOf("play signal-2-4 Signal Hill"), asked)
        assertEquals(1, closed)
    }

    @Test
    fun aKidsTrayHasNoTrailer() {
        tray(profile = SampleHomeState.maya)
        compose.onNodeWithText("Play").assertExists()
        compose.onNodeWithText("Trailer").assertDoesNotExist()
    }

    @Test
    fun nothingToRollYet() {
        tray(DiceTable.None)
        compose.onNodeWithText("Nothing to roll yet").assertExists()
        compose.onNodeWithText("Close").assertIsFocused()
        capture("dice_tray_empty")
    }

    @Test
    fun aThrowLandsSquareOnItsFace() {
        val roll = DieRoll(deck.titles.first(), face = 2, throws = 1, pool = 8)
        val flight = DieFlight.of(roll, from = 5)
        assertEquals(2, flight.faces.last())
        assertTrue(flight.faces.zipWithNext().all { (a, b) -> a != b })
        assertTrue(flight.faces.first() != 5)
        assertEquals(2, Tumble.face(flight, 1f))
        assertEquals(0f, Tumble.turn(flight, 1f) % 90f, 0f)
        assertEquals(0f, Tumble.hop(0f), 0f)
        assertEquals(0f, Tumble.hop(1f), 0f)
        assertEquals(0f, Tumble.squash(1f), 0f)
        assertTrue(Tumble.hop(0.25f) > Tumble.hop(0.64f))
    }
}
