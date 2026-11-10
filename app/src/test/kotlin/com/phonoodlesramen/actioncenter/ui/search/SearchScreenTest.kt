package com.phonoodlesramen.actioncenter.ui.search

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.search.LibraryAnswer
import com.phonoodlesramen.actioncenter.search.LibrarySearch
import com.phonoodlesramen.actioncenter.search.SearchChip
import com.phonoodlesramen.actioncenter.search.SearchItem
import com.phonoodlesramen.actioncenter.search.SearchWords
import com.phonoodlesramen.actioncenter.search.StreamingApp
import com.phonoodlesramen.actioncenter.search.StreamingItem
import com.phonoodlesramen.actioncenter.search.StreamingSearch
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Search (Phase 15) with a made-up library: typing, chips, kids, offline, and opening a title. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class SearchScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val asked = mutableListOf<SearchWords>()
    private val done = mutableListOf<String>()
    private var back = 0
    private var offline = false

    private val library = LibrarySearch { words, _ ->
        asked += words
        LibraryAnswer(
            listOf(
                SearchItem("1", "movie", "Harbor Lights", "1995 · R"),
                SearchItem("2", "movie", "Night Shift", "1997 · PG-13", why = "With Dana Example"),
                SearchItem("3", "show", "Harbor Watch", "2019 · TV-14"),
            ),
            offline = offline,
        )
    }

    private val actions = object : HomeActions {
        override fun play(id: String, title: String) {
            done += "play $title"
        }

        override fun searchWithGoogle(text: String) {
            done += "google $text"
        }

        override fun rememberSearch(text: String) {
            done += "remember $text"
        }

        override fun watchOn(app: StreamingApp, title: String) {
            done += "watch $title on ${app.label}"
        }
    }

    /** What the streaming row was given: your library's titles, and the apps. */
    private var streamingSaw: Pair<List<String>, List<String>>? = null

    private val streaming = StreamingSearch { _, _, apps, library ->
        streamingSaw = library.map { it.title } to apps.map { it.label }
        listOf(
            StreamingItem(
                "c1", "movie", "Harbor Nights", 2001, "PG-13", image = null,
                apps = listOf(StreamingApp("com.netflix.ninja", "Netflix", "https://watch.invalid/1"), StreamingApp("pkg.prime", "Prime Video")),
            ),
        )
    }

    private val tvApps = listOf(LaunchableApp("com.netflix.ninja", "com.netflix.ninja.MainActivity", "Netflix"))

    private fun show(
        profile: ActiveProfile = ActiveProfile.owner("Alex"),
        recent: List<String> = emptyList(),
        streaming: StreamingSearch = StreamingSearch.None,
    ) = compose.setContent {
        ActionCenterTheme {
            SearchScreen(
                profile = profile,
                recent = recent,
                search = library,
                streaming = streaming,
                apps = tvApps,
                guide = GuideCardState.NotSetUp,
                networks = emptyList(),
                nowMillis = 0,
                lateNight = false,
                actions = actions,
                onBack = { back++ },
            )
        }
    }

    private fun field() = compose.onNode(hasSetTextAction())

    /** The words settle before Plex is asked: the test clock moves past that. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(SEARCH_SETTLE_MILLIS + 50)
        compose.waitForIdle()
    }

    private fun click(text: String) {
        compose.onNodeWithText(text, substring = true).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test
    fun `it opens on the field, and Plex is asked once the words settle`() {
        show()
        field().assertIsFocused()
        compose.mainClock.autoAdvance = false
        field().performTextInput("har")
        field().performTextReplacement("harbor")
        compose.mainClock.advanceTimeBy(SEARCH_SETTLE_MILLIS + 100)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertEquals("only the settled words", listOf("harbor"), asked.map { it.text })
        compose.onNodeWithText("TOP MATCH").assertExists()
        compose.onNodeWithText("Harbor Lights").assertExists()
        compose.onNodeWithText("1997 · PG-13 · With Dana Example").assertExists()
        compose.onNodeWithText("IN YOUR LIBRARY · SHOWS").assertExists()
    }

    @Test
    fun `opening a title plays it and keeps the words, and Google is the last row`() {
        show()
        field().performTextInput("harbor")
        settle()

        click("Harbor Lights")
        compose.onNodeWithTag(SEARCH_RESULTS).performScrollToNode(hasText("Search everywhere with Google", substring = true))
        click("Search everywhere with Google")

        assertEquals(listOf("remember harbor", "play Harbor Lights", "remember harbor", "google harbor"), done)
    }

    @Test
    fun `your streaming apps follow your library, badged with where each title streams, and OK opens the first`() {
        show(streaming = streaming)
        field().performTextInput("harbor")
        settle()

        compose.onNodeWithTag(SEARCH_RESULTS).performScrollToNode(hasText("ON YOUR APPS"))
        compose.onNodeWithText("Netflix · Prime Video").assertExists()
        click("Harbor Nights")

        assertEquals(listOf("Harbor Lights", "Night Shift", "Harbor Watch") to listOf("Netflix"), streamingSaw)
        assertEquals(listOf("remember harbor", "watch Harbor Nights on Netflix"), done)
    }

    @Test
    fun `with streaming apps off, there's no streaming row`() {
        show()
        field().performTextInput("harbor")
        settle()

        compose.onNodeWithTag(SEARCH_RESULTS).performScrollToNode(hasText("Search everywhere with Google", substring = true))
        compose.onNodeWithText("ON YOUR APPS").assertDoesNotExist()
    }

    @Test
    fun `a kid has no Google row`() {
        show(ActiveProfile("kid-maya", "Maya", kid = true, ceiling = setOf("G")))
        field().performTextInput("harbor")
        settle()

        compose.onNodeWithText("Harbor Lights").assertExists()
        compose.onNodeWithText("Search everywhere with Google", substring = true).assertDoesNotExist()
    }

    @Test
    fun `words become chips, and taking one off takes its word out`() {
        show()
        field().performTextInput("90s action")
        settle()
        assertEquals(listOf(SearchChip.Decade(1990)), asked.last().chips)

        click("1990s")
        settle()

        assertEquals("action", asked.last().text)
        assertTrue(asked.last().chips.isEmpty())
    }

    @Test
    fun `offline says it's searching what's on this TV`() {
        offline = true
        show()
        field().performTextInput("harbor")
        settle()

        compose.onNodeWithText("Plex can't be reached", substring = true).assertExists()
    }

    @Test
    fun `the empty field offers the last searches, and one picked searches again`() {
        show(recent = listOf("kids movies", "harbor"))

        click("kids movies")
        settle()

        assertEquals("kids movies", asked.last().text)
    }

    @Test
    fun `picking a last search with the D-pad doesn't crash the field`() {
        show(recent = listOf("kids movies", "harbor"))
        compose.onNodeWithText("kids movies").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()

        click("kids movies")
        settle()

        assertEquals("kids movies", asked.last().text)
    }

    @Test
    fun `taking a chip off with the D-pad doesn't crash the field`() {
        show()
        field().performTextInput("90s")
        settle()
        compose.onNodeWithText("1990s", substring = true).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()

        click("1990s")
        settle()

        compose.onNodeWithText("1990s", substring = true).assertDoesNotExist()
    }

    /** Voice typing (11/03/2026, the Shield): the words and the keyboard's Search come together, before the screen catches up. */
    @Test
    fun `words and Search arriving together, from voice typing, don't crash the field`() {
        show(recent = listOf("kids movies"))
        compose.mainClock.autoAdvance = false
        field().performTextInput("heat")
        field().performImeAction()
        compose.mainClock.advanceTimeBy(SEARCH_SETTLE_MILLIS + 100)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertEquals("heat", asked.last().text)
        compose.onNode(isFocused() and hasText("Harbor Lights")).assertExists()
    }

    @Test
    fun `Back leaves`() {
        show()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertEquals(1, back)
    }
}
