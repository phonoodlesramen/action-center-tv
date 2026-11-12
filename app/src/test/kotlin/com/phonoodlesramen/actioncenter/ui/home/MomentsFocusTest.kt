package com.phonoodlesramen.actioncenter.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.phonoodlesramen.actioncenter.home.HomeLayout
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.nfl.NflView
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import com.phonoodlesramen.actioncenter.ui.nfl.SampleNfl
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Moments (H3) on the home: when the moment changes, where Play lands, the chip, and who has none. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class MomentsFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var state: HomeUiState by mutableStateOf(moment(SampleHomeState.weeknight))
    private var focus by mutableStateOf(FocusRequest())
    private var nfl by mutableStateOf(NflView.None)
    private val asked = mutableListOf<String>()

    private val actions = object : HomeActions {
        override fun keepUsualLayout() {
            asked += "keep"
        }

        override fun switchProfile(profileId: String) {
            asked += "switch $profileId"
        }
    }

    private fun moment(time: LocalDateTime, moments: Boolean = true, layout: HomeLayout = HomeLayout.DASHBOARD) =
        SampleHomeState.state(time = time, homeLayout = layout, moments = moments, profiles = SampleHomeState.family)

    private fun show(initial: HomeUiState) {
        state = initial
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                HomeScreen(state, actions, focus = focus, guide = SampleGuide.ready(state.nowMillis), nfl = nfl, watch = SampleNfl.watch)
            }
        }
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    @Test
    fun theMomentChangesOnlyWhenYouComeBackHome() {
        show(moment(SampleHomeState.weeknight))
        compose.onNodeWithText("QUICK WATCH · FITS BEFORE 10:30").assertExists()

        // Late night comes while you're on the home: nothing moves under your focus.
        state = moment(SampleHomeState.lateNight)
        settle()
        compose.onNodeWithText("TONIGHT'S PICK").assertExists()
        compose.onNodeWithText("FITS IN THE NEXT 45 MINUTES").assertDoesNotExist()

        // Back Home, it's late night's: a comfort rewatch, and what fits in the next 45 minutes.
        focus = FocusRequest(HomeFocus.PLAY, serial = 1)
        settle()
        compose.onNodeWithText("FITS IN THE NEXT 45 MINUTES").assertExists()
        compose.onNodeWithText("TONIGHT'S PICK").assertDoesNotExist()
        compose.onNodeWithText("Watch again").assertIsFocused()
    }

    @Test
    fun weekendMorningLeadsWithTheFamilyLensAndTheKids() {
        show(moment(SampleHomeState.saturdayMorning))
        compose.onNodeWithText("FOR THE FAMILY").assertExists()
        compose.onNodeWithText("Watch again").assertIsFocused()
        compose.onNodeWithContentDescription("Switch to Maya").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("switch kid-maya"), asked)
    }

    @Test
    fun gameDayLeadsWithTheGameAndAroundTheLeague() {
        nfl = SampleNfl.view()
        show(moment(SampleNfl.sundayNight))
        compose.onNodeWithContentDescription("Game day: Eagles at Commanders").assertIsFocused()
        compose.onNodeWithText("AROUND THE LEAGUE").assertExists()
    }

    @Test
    fun theChipKeepsTheUsualLayout() {
        show(moment(SampleHomeState.weeknight))
        click("Weeknight evening")
        click("Keep the usual layout")
        assertEquals(listOf("keep"), asked)

        // As the view model brings it back: held for this evening.
        state = state.copy(momentsHeld = true)
        settle()
        compose.onNodeWithText("THROWBACK").assertExists()
        compose.onNodeWithText("QUICK WATCH · FITS BEFORE 10:30").assertExists()
    }

    @Test
    fun withMomentsOffLateNightIsTheUsualHome() {
        show(moment(SampleHomeState.lateNight, moments = false))
        compose.onNodeWithText("TONIGHT'S PICK").assertExists()
        compose.onNodeWithText("FITS IN THE NEXT 45 MINUTES").assertDoesNotExist()
    }

    @Test
    fun aKidsHomeHasNoMoments() {
        show(SampleHomeState.kid(time = SampleHomeState.saturdayMorning).copy(moments = true))
        compose.onNodeWithText("WHO'S WATCHING?").assertDoesNotExist()
    }

    @Test
    fun marqueesStripLeadsWithTheMoment() {
        show(moment(SampleHomeState.saturdayMorning, layout = HomeLayout.MARQUEE))
        compose.onNodeWithContentDescription("Paddington 2, For the family lens").assertIsFocused()
        compose.onNodeWithText("FOR THE FAMILY").assertExists()
    }
}
