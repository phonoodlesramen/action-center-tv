package com.phonoodlesramen.actioncenter.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.weather.OpenMeteoException
import com.phonoodlesramen.actioncenter.weather.Place
import com.phonoodlesramen.actioncenter.weather.WeatherState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings › Weather's city search (Q2). Clicks go through semantics: tv-material ignores touch. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class WeatherSettingsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val oregon = Place("Portland", "Oregon", "United States", 45.52, -122.68)
    private val maine = Place("Portland", "Maine", "United States", 43.66, -70.26)
    private val chosen = mutableListOf<Place>()
    private var offline = false
    private var leftSettings = false

    private val actions = object : SettingsActions {
        override suspend fun searchPlaces(query: String): List<Place> {
            if (offline) throw OpenMeteoException("offline")
            return if (query == "Portland") listOf(oregon, maine) else emptyList()
        }

        override fun choosePlace(place: Place) {
            chosen += place
        }
    }

    private fun show() {
        val state = SampleHomeState.state(weather = WeatherState())
        compose.setContent {
            ActionCenterTheme {
                SettingsScreen(state, actions, onBack = { leftSettings = true }, initialSection = SettingsSection.WEATHER)
            }
        }
    }

    private fun click(text: String) = compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)

    private fun search(query: String) {
        click("Set location")
        compose.onNode(hasSetTextAction()).performTextInput(query)
        compose.onNode(hasSetTextAction()).performImeAction()
    }

    @Test
    fun `search for a city, pick one, and focus returns to the button`() {
        show()
        search("Portland")

        click("Portland, Maine, United States")

        assertEquals(listOf(maine), chosen)
        compose.onNodeWithText("Set location").assertIsFocused()
    }

    @Test
    fun `no matches says so`() {
        show()
        search("Zzyzx Road")

        compose.onNodeWithText("No places called “Zzyzx Road”. Try another spelling.").assertExists()
    }

    @Test
    fun `offline says so instead of failing`() {
        offline = true
        show()
        search("Portland")

        compose.onNodeWithText("Couldn't reach Open-Meteo", substring = true).assertExists()
    }

    @Test
    fun `Back leaves the search, not Settings, and changes nothing`() {
        show()
        search("Portland")

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithText("Portland, Oregon, United States").assertDoesNotExist()
        compose.onNodeWithText("Set location").assertIsFocused()
        assertEquals(emptyList<Place>(), chosen)
        assertFalse(leftSettings)
    }
}
