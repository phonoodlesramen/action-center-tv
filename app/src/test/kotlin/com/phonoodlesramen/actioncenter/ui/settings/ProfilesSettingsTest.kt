package com.phonoodlesramen.actioncenter.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.phonoodlesramen.actioncenter.profiles.AgeBrackets
import com.phonoodlesramen.actioncenter.profiles.PlexLinkResult
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.profiles.FakeProfiles
import com.phonoodlesramen.actioncenter.ui.profiles.KidColors
import com.phonoodlesramen.actioncenter.ui.profiles.SampleProfiles
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Profiles with the D-pad (K2, K3, K9, K10): adding a kid, their page's fields,
 * the bracket table, removing, and linking their Plex Home user. Clicks go through
 * semantics: tv-material ignores touch. Every name, PIN and id is made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class ProfilesSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    private val maya = SampleProfiles.maya

    private fun show(profiles: FakeProfiles) {
        val state = SampleHomeState.state()
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                SettingsScreen(state, SettingsActions.None, onBack = {}, initialSection = SettingsSection.PROFILES, profiles = profiles)
            }
        }
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.click() {
        performScrollTo()
        performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction())
    private fun described(description: String) = compose.onNodeWithContentDescription(description)

    @Test
    fun `adding a kid saves nothing until they're named, then them with the kid apps that are installed`() {
        val profiles = FakeProfiles()
        show(profiles)

        button("Add a kid").click()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals(emptyList<Any>(), profiles.saved)

        compose.onNode(hasSetTextAction()).performTextInput("Maya")
        compose.waitForIdle()

        val saved = profiles.saved.last()
        assertEquals("Maya", saved.name)
        assertEquals(36, UUID.fromString(saved.id).toString().length)
        assertEquals(KidColors.first(), saved.color)
        // Monday 26 October 2026, less 8 years.
        assertEquals(LocalDate.of(2018, 10, 26).toEpochDay(), saved.birthday)
        assertEquals("Disney+ and Plezy are installed; Netflix isn't a kid app", maya.apps, saved.apps)
        assertEquals(null, saved.ceilingOverride)
    }

    @Test
    fun `a kid's colour, birthday, ceiling and apps are saved as they change`() {
        val profiles = FakeProfiles(listOf(maya))
        show(profiles)

        button("Maya").click()
        described("Purple").click()
        assertEquals(KidColors[2], profiles.saved.last().color)

        described("Year up").click()
        described("Month down").click()
        described("Day up").click()
        assertEquals(LocalDate.of(2018, 2, 15).toEpochDay(), profiles.saved.last().birthday)

        button("Their own").click()
        assertEquals("their bracket's ratings, to start from", AgeBrackets.DEFAULT[0].ratings, split(profiles.saved.last().ceilingOverride))
        described("Maya's ceiling: PG-13").click()
        described("Maya's ceiling: TV-Y").click()
        assertEquals(AgeBrackets.DEFAULT[0].ratings + "PG-13" - "TV-Y", split(profiles.saved.last().ceilingOverride))

        button("Netflix").click()
        button("Plezy").click()
        assertEquals(
            setOf("com.disney.disneyplus/com.disney.disneyplus.MainActivity", "com.netflix.ninja/com.netflix.ninja.MainActivity"),
            split(profiles.saved.last().apps),
        )

        button("Their age's: PG and TV-PG").click()
        assertEquals(null, profiles.saved.last().ceilingOverride)
        assertEquals("every save is the whole kid, with the earlier changes", KidColors[2], profiles.saved.last().color)
    }

    @Test
    fun `the bracket table's ratings are toggles, and quick changes keep each other`() {
        val profiles = FakeProfiles()
        show(profiles)

        button("Ratings by age").click()
        described("Ages 10 to 12: TV-14").click()
        described("Ages 7 to 9: PG").click()

        val table = profiles.bracketSaves.last()
        assertEquals(listOf(7 to 9, 10 to 12), table.map { it.fromAge to it.toAge })
        assertEquals(AgeBrackets.DEFAULT[0].ratings - "PG", table[0].ratings)
        assertEquals(AgeBrackets.DEFAULT[1].ratings + "TV-14", table[1].ratings)
    }

    @Test
    fun `remove asks first, then removes the kid`() {
        val profiles = FakeProfiles(listOf(maya))
        show(profiles)

        button("Maya").click()
        button("Remove Maya").click()
        compose.onNodeWithText("Remove Maya?").assertIsDisplayed()
        assertEquals(emptyList<Any>(), profiles.removed)

        button("Remove").click()

        assertEquals(listOf(maya), profiles.removed)
        button("Add a kid").assertIsFocused()
    }

    @Test
    fun `picking a Plex Home user links the kid`() {
        val profiles = FakeProfiles(listOf(maya.copy(plexUserId = null, plexUserTitle = null)))
        show(profiles)

        button("Maya").click()
        button("Choose a Plex user").click()
        button("Maya R").click()

        assertEquals(listOf(Triple("kid-maya", 1002L, null)), profiles.links)
        compose.onNodeWithText("Maya R").assertIsDisplayed()
        button("Change").assertIsFocused()
    }

    @Test
    fun `a user with a PIN asks for it, and it goes to the link and nowhere else`() {
        val profiles = FakeProfiles(listOf(maya))
        show(profiles)

        button("Maya").click()
        button("Change").click()
        button("Leo R").click()
        compose.onNodeWithText("Leo R has a PIN", substring = true).assertIsDisplayed()
        described("Leo R's PIN").performTextInput("4321x9")
        compose.waitForIdle()
        button("Link").click()

        assertEquals(listOf(Triple("kid-maya", 1003L, "4321")), profiles.links)
        assertTrue("the PIN is never saved with the kid", profiles.saved.none { "4321" in it.toString() })
        compose.onNodeWithText("Leo R").assertIsDisplayed()
    }

    @Test
    fun `a link that fails says why, and the kid stays as they were`() {
        val profiles = FakeProfiles(listOf(maya.copy(plexUserId = null, plexUserTitle = null)))
        profiles.linkResult = { _, _ -> PlexLinkResult.Failed("Maya R can't see your Plex server. Give them access to its libraries in Plex, then try again.") }
        show(profiles)

        button("Maya").click()
        button("Choose a Plex user").click()
        button("Maya R").click()

        compose.onNodeWithText("Maya R can't see your Plex server", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Not linked").assertIsDisplayed()
        assertTrue(profiles.saved.none { it.plexUserId != null })
    }

    private fun split(joined: String?) = joined.orEmpty().split('|').filter(String::isNotEmpty).toSet()
}
