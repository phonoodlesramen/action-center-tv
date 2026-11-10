package com.phonoodlesramen.actioncenter.ui.home.rows

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.HomeScreen
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Favorite channels on the Live row (Phase 13, C2, C3), on the sample home at 8:41 PM with
 * Comedy and TV Mix as favorites: they lead with a star, hold OK adds or removes one, and
 * Move carries a favorite along the row, keeping focus and the D-pad until OK, Back or Menu
 * put it down. A kid's hidden favorite keeps its place.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class LiveRowFavoritesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val done = mutableListOf<String>()
    private var favorites by mutableStateOf(SampleHomeState.favorites)

    /** Whether a Move's save is kept; until it is, the row shows the carried order. */
    private var keep = true

    private val actions = object : HomeActions {
        override fun tuneIn(channelId: String) {
            done += "tune in $channelId"
        }

        override fun setFavorite(channel: GuideChannel, favorite: Boolean) {
            done += "favorite ${channel.id} $favorite"
        }

        override fun saveFavorites(order: List<String>, done: (kept: Boolean) -> Unit) {
            this@LiveRowFavoritesTest.done += "save ${order.joinToString()}"
            done(keep)
        }
    }

    private fun show(state: HomeUiState = SampleHomeState.state(), guide: GuideCardState = SampleGuide.ready()) {
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                CompositionLocalProvider(LocalAppBadges provides SampleHomeRows.badges) {
                    HomeScreen(state, actions, rows = SampleHomeRows.rows, guide = guide, favorites = favorites)
                }
            }
        }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun card(description: String) = compose.onNodeWithContentDescription(description, substring = true)

    private fun assertFocused(description: String) = compose.onNode(isFocused()).assert(hasContentDescription(description, substring = true))

    private fun left(description: String) = card(description).getBoundsInRoot().left

    /** [names] are in this order along the row. */
    private fun assertOrder(vararg names: String) {
        val lefts = names.map(::left)
        assertTrue("${names.toList()} at $lefts", lefts.zipWithNext().all { (a, b) -> a < b })
    }

    /** Hold OK › Move on [name]. */
    private fun pickUp(name: String) {
        card(name).requestFocus()
        press(Key.Menu)
        compose.onNodeWithText("Move").assertExists()
        press(Key.DirectionDown, times = 3)
        compose.onNodeWithText("Move").assertIsFocused()
        press(Key.DirectionCenter)
        assertFocused(name)
    }

    @Test
    fun `favorites lead the row in your order, each with a star, then the rest by number`() {
        show()
        card("Comedy").requestFocus()
        assertOrder("Comedy", "TV Mix", "Movies", "Sci-Fi")
        card("Comedy").assert(hasContentDescription("Favorite"))
        card("TV Mix").assert(hasContentDescription("Favorite"))
        card("Movies").assert(hasContentDescription("Favorite").not())
    }

    @Test
    fun `hold OK adds a channel to favorites, and removes a favorite, which offers Move`() {
        show()
        card("Movies").requestFocus()
        press(Key.Menu)
        compose.onNodeWithText("Move").assertDoesNotExist()
        press(Key.DirectionDown, times = 2)
        compose.onNodeWithText("Add to favorites").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("favorite movies true"), done)

        card("Comedy").requestFocus()
        press(Key.Menu)
        compose.onNodeWithText("Move").assertExists()
        press(Key.DirectionDown, times = 2)
        compose.onNodeWithText("Remove from favorites").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals("favorite comedy false", done.last())
        assertFocused("Comedy")
    }

    @Test
    fun `a lone favorite has nothing to move past`() {
        favorites = listOf("scifi")
        show()
        card("Sci-Fi").requestFocus()
        press(Key.Menu)
        compose.onNodeWithText("Remove from favorites").assertExists()
        compose.onNodeWithText("Move").assertDoesNotExist()
    }

    @Test
    fun `Move keeps focus and the D-pad, Right carries it, the end goes no further, and Back puts it down`() {
        show()
        pickUp("Comedy")
        compose.onNodeWithText("Left or right, then OK").assertExists()

        press(Key.DirectionRight)
        assertFocused("Comedy")
        assertOrder("TV Mix", "Comedy", "Movies")
        // The last favorite: it goes no further, and focus doesn't leave it, Up and Down included.
        press(Key.DirectionRight)
        press(Key.DirectionUp)
        press(Key.DirectionDown)
        assertFocused("Comedy")
        assertOrder("TV Mix", "Comedy", "Movies")
        assertTrue(done.isEmpty())

        press(Key.Back)
        assertEquals(listOf("save tv-mix, comedy"), done)
        assertFocused("Comedy")
        compose.onNodeWithText("Left or right, then OK").assertDoesNotExist()
        // Kept: the row shows the new order until the saved favorites do.
        assertOrder("TV Mix", "Comedy", "Movies")
        favorites = listOf("tv-mix", "comedy")
        compose.waitForIdle()
        assertOrder("TV Mix", "Comedy", "Movies")

        // Free again: Right goes on to Movies.
        press(Key.DirectionRight)
        assertFocused("Movies")
    }

    @Test
    fun `OK puts it down too, and the first favorite goes no further left`() {
        show()
        pickUp("TV Mix")
        press(Key.DirectionLeft)
        press(Key.DirectionLeft)
        assertFocused("TV Mix")
        assertOrder("TV Mix", "Comedy", "Movies")
        press(Key.DirectionCenter)
        assertEquals(listOf("save tv-mix, comedy"), done)
        assertTrue("OK put it down without tuning in", done.none { it.startsWith("tune in") })
        compose.onNodeWithText("Left or right, then OK").assertDoesNotExist()
    }

    @Test
    fun `put back where it was, nothing is saved, and a save turned down shows the saved order again`() {
        show()
        pickUp("Comedy")
        press(Key.DirectionRight)
        press(Key.DirectionLeft)
        press(Key.Menu)
        assertTrue(done.isEmpty())
        assertOrder("Comedy", "TV Mix")

        keep = false
        pickUp("Comedy")
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(listOf("save tv-mix, comedy"), done)
        assertOrder("Comedy", "TV Mix")
        assertFocused("Comedy")
    }

    @Test
    fun `a kid's favorites are theirs among their channels, a hidden one keeping its place`() {
        favorites = SampleHomeState.mayaFavorites
        show(SampleHomeState.kid(), SampleGuide.withKidChannels())
        card("Family, ").requestFocus()
        // Comedy is in Maya's list but isn't kid-safe: Cartoon Club, then Family, lead.
        assertOrder("Cartoon Club, ", "Family, ")
        card("Comedy, ").assertDoesNotExist()

        press(Key.Menu)
        press(Key.DirectionDown, times = 3)
        compose.onNodeWithText("Move").assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        assertEquals(listOf("save family, comedy, cartoons"), done)
        assertOrder("Family, ", "Cartoon Club, ")
    }
}
