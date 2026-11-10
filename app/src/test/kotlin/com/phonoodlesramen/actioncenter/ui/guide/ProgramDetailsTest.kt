package com.phonoodlesramen.actioncenter.ui.guide

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A program's details (plan §2) from the sample lineup at 8:41 PM, in UTC: a film on now,
 * a later episode, off air, filler, a program that has left the lineup, and the player's
 * Watch left out. Then which actions apply when ([ProgramActions]).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class ProgramDetailsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val done = mutableListOf<String>()
    private var backs = 0
    private val care = GuideScreenCare()

    private val actions = object : HomeActions {
        override fun tuneIn(channelId: String) {
            done += "tune in $channelId"
        }

        override fun startOver(channelId: String) {
            done += "start over $channelId"
        }

        override fun play(id: String, title: String) {
            done += "play $id $title"
        }
    }

    private val guide = SampleGuide.ready()

    private fun slot(channelId: String, title: String, after: Long = 0L): GuideSlot =
        guide.channels.first { it.id == channelId }.slots.first { it.title == title && it.startMillis >= after }

    private fun show(slot: GuideSlot, channelId: String, now: Long = SampleGuide.WEEKNIGHT, state: GuideCardState = guide) =
        compose.setContent {
            ActionCenterTheme {
                ProgramDetailsScreen(
                    state = state,
                    channelId = channelId,
                    startMillis = slot.startMillis,
                    nowMillis = now,
                    actions = actions,
                    onBack = { backs++ },
                    zone = ZoneOffset.UTC,
                    care = care,
                )
            }
        }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `a film on now shows its channel, times, rating, status and whole summary, with Watch focused`() {
        val film = slot("movies", "Quiet Field")
        show(film, "movies")
        compose.onNodeWithText("1 · MOVIES").assertIsDisplayed()
        compose.onNodeWithText("Quiet Field").assertIsDisplayed()
        compose.onNodeWithText("8:30 – 10:41 PM · 2h 11m · R").assertIsDisplayed()
        compose.onNodeWithText("On now · 2h left").assertIsDisplayed()
        compose.onNodeWithText(film.summary!!).assertIsDisplayed()
        compose.onNodeWithText("8:41 PM").assertIsDisplayed()
        compose.onNodeWithText("Watch").assertIsFocused()
        compose.onNodeWithText("Start over").assertIsDisplayed()
        compose.onNodeWithText("Open in Plezy").assertIsDisplayed()
        compose.onNodeWithText("Starts at", substring = true).assertDoesNotExist()

        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(listOf("tune in movies", "start over movies", "play ${film.itemKey} Quiet Field"), done)
    }

    @Test
    fun `a later episode says when it starts instead of Watch, and opens in Plezy`() {
        val episode = slot("tv-mix", "Kitchen Table", after = SampleGuide.WEEKNIGHT)
        show(episode, "tv-mix")
        compose.onNodeWithText("3 · TV MIX").assertIsDisplayed()
        compose.onNodeWithText("S1 E9 · Leftovers").assertIsDisplayed()
        compose.onNodeWithText("9:30 – 9:52 PM · 22m · TV-PG").assertIsDisplayed()
        compose.onNodeWithText("Starts in 49m").assertIsDisplayed()
        compose.onNodeWithText("Starts at 9:30").assertIsDisplayed()
        compose.onNodeWithText("Watch").assertDoesNotExist()
        compose.onNodeWithText("Start over").assertDoesNotExist()
        compose.onNodeWithText("Open in Plezy").assertIsFocused()

        press(Key.DirectionCenter)
        assertEquals(listOf("play ${episode.itemKey} Kitchen Table"), done)
    }

    @Test
    fun `off air, the status says why, and Watch is the only button`() {
        // 12:45 AM: Classics' 12:30 film has left the library.
        val now = SampleGuide.WEEKNIGHT + (4 * 60 + 4) * 60_000L
        val gone = guide.channels.first { it.id == "classics" }.slotAt(now)!!
        show(gone, "classics", now = now)
        compose.onNodeWithText("Off air · This title is no longer in your library").assertIsDisplayed()
        compose.onNodeWithText("Watch").assertIsFocused()
        compose.onNodeWithText("Start over").assertDoesNotExist()
        compose.onNodeWithText("Open in Plezy").assertDoesNotExist()
    }

    @Test
    fun `filler later has nothing to press, and Back still works`() {
        val upNext = slot("tv-mix", "Up next", after = SampleGuide.WEEKNIGHT)
        show(upNext, "tv-mix")
        compose.onNodeWithText("Starts at 9:22").assertIsDisplayed()
        compose.onNodeWithText("A short break. Next: Kitchen Table at 9:30.").assertIsDisplayed()
        compose.onNodeWithText("Open in Plezy").assertDoesNotExist()
        back()
        assertEquals(1, backs)
    }

    @Test
    fun `a program that has left the lineup says so, and Back still works`() {
        show(slot("movies", "Quiet Field"), "movies", state = GuideCardState.Building)
        compose.onNodeWithText("This program is no longer in the guide").assertIsDisplayed()
        back()
        assertEquals(1, backs)
    }

    @Test
    fun `Back calls onBack`() {
        show(slot("movies", "Quiet Field"), "movies")
        back()
        assertEquals(1, backs)
    }

    @Test
    fun `once dimmed, the first key only wakes the details`() {
        show(slot("movies", "Quiet Field"), "movies")
        compose.runOnUiThread { care.show() }
        compose.waitForIdle()
        press(Key.DirectionCenter)
        assertFalse(care.showing)
        assertTrue(done.isEmpty())
        press(Key.DirectionCenter)
        assertEquals(listOf("tune in movies"), done)
    }

    @Test
    fun `in the player, Watch is left out and Start over comes first`() {
        val film = slot("movies", "Quiet Field")
        compose.setContent {
            ActionCenterTheme {
                ProgramDetails(
                    channel = guide.channels.first(),
                    slot = film,
                    nowMillis = SampleGuide.WEEKNIGHT,
                    onWatch = null,
                    onStartOver = { done += "start over" },
                    onOpenIn = { done += "plezy" },
                    zone = ZoneOffset.UTC,
                )
            }
        }
        compose.onNodeWithText("Watch").assertDoesNotExist()
        compose.onNodeWithText("Start over").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("start over"), done)
    }

    @Test
    fun `Watch and Start over only while it's on, and Plezy only for a program with a file`() {
        val film = slot("movies", "Quiet Field")
        val trailer = guide.channels.first().slots.first { it.filler && it.startMillis > SampleGuide.WEEKNIGHT }
        val upNext = slot("tv-mix", "Up next", after = SampleGuide.WEEKNIGHT)

        assertEquals(ProgramActions(watch = true, startOver = true, plezyKey = film.itemKey), ProgramActions.of(film, SampleGuide.WEEKNIGHT))
        assertEquals(ProgramActions(watch = false, startOver = false, plezyKey = film.itemKey), ProgramActions.of(film, film.startMillis - 1))
        assertEquals(ProgramActions(watch = false, startOver = false, plezyKey = film.itemKey), ProgramActions.of(film, film.endMillis))
        // A trailer is a file, but filler: Watch while it's on, nothing else.
        assertEquals(ProgramActions(watch = true, startOver = false, plezyKey = null), ProgramActions.of(trailer, trailer.startMillis))
        assertFalse(ProgramActions.of(upNext, SampleGuide.WEEKNIGHT).any)
        val offAir = film.copy(offAir = "This title is no longer in your library")
        assertEquals(ProgramActions(watch = true, startOver = false, plezyKey = null), ProgramActions.of(offAir, SampleGuide.WEEKNIGHT))
        assertNull(ProgramActions.of(film.copy(itemKey = null), SampleGuide.WEEKNIGHT).plezyKey)
    }
}
