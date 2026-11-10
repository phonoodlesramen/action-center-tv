package com.phonoodlesramen.actioncenter.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import com.phonoodlesramen.actioncenter.station.StationView
import com.phonoodlesramen.actioncenter.ui.TV_1080P
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
 * Settings › Plex Guide › Guide station with the D-pad's OK (Phase 8): typing the address
 * and picking a room, then Pair; cancelling a code; approving a TV and leaving, each after
 * its confirm; Settings › Plex Guide greyed out while the station can't be reached; and a
 * 1.1 station's note that it needs an update for favorites (Phase 13).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class GuideStationSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = SampleHomeState.state().nowMillis
    private val editor = FakeGuideEditor()
    private var phoneOpened = false

    private fun show(view: StationView, open: Boolean = true): RecordingStation {
        val station = RecordingStation(view)
        val home = SampleHomeState.state()
        val actions = object : SettingsActions {
            override fun openPhoneEditor() {
                phoneOpened = true
            }
        }
        compose.setContent {
            ActionCenterTheme(accent = home.accent, layoutScale = home.layoutScale) {
                SettingsScreen(home, actions, onBack = {}, initialSection = SettingsSection.PLEX_GUIDE, guideEditor = editor, station = station)
            }
        }
        if (open) click("Guide station")
        return station
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test
    fun `the address and a room are typed with the remote, then Pair`() {
        val station = show(SampleStation.unpaired())
        compose.onNode(hasSetTextAction()).performTextInput("192.168.1.20")
        click("Kitchen")
        click("Pair")
        assertEquals(listOf("pair 192.168.1.20 Kitchen"), station.calls)
    }

    @Test
    fun `a room of its own is typed`() {
        val station = show(SampleStation.unpaired().copy(address = "192.168.1.20"))
        click("Another room")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("Den")
        click("Pair")
        assertEquals(listOf("pair 192.168.1.20 Den"), station.calls)
    }

    @Test
    fun `a code waiting for approval can be cancelled`() {
        val station = show(SampleStation.pairing(now))
        compose.onNodeWithText("7K3QPA").assertExists()
        click("Cancel")
        assertEquals(listOf("cancel"), station.calls)
    }

    @Test
    fun `a waiting TV is approved after its code is checked, and leaving asks first`() {
        val station = show(SampleStation.paired(now))
        click("Bedroom")
        compose.onNodeWithText("Approve Bedroom?").assertExists()
        compose.onNodeWithText("Check that the TV in the bedroom shows the code H4TW2C. If it doesn't, cancel.").assertExists()
        click("Approve")
        click("Kitchen")
        click("Unpair")
        click("Leave the station")
        compose.onNodeWithText("Leave the guide station?").assertExists()
        click("Leave")
        assertEquals(listOf("approve 71", "unpair 70", "leave"), station.calls)
    }

    @Test
    fun `a station that keeps no favorites yet says it needs an update`() {
        show(SampleStation.paired(now).copy(favoritesNeedUpdate = true))
        compose.onNodeWithText("The station needs an update for favorites. Until then, this TV keeps its own.").assertExists()
    }

    @Test
    fun `Back leaves the station's page for Plex Guide`() {
        show(SampleStation.paired(now))
        compose.onNodeWithText("Leave the station").assertExists()
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Comedy").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Leave the station").fetchSemanticsNodes().size)
    }

    @Test
    fun `while the station can't be reached, nothing in Plex Guide can be changed`() {
        show(SampleStation.unreachable(now), open = false)
        compose.onNodeWithText("Can't reach the guide station. Channels can't be changed", substring = true).assertExists()
        click("Comedy")
        click("Edit on your phone")
        click("Remake the starter lineup")
        assertTrue(editor.calls.isEmpty())
        assertEquals(false, phoneOpened)
        compose.onNodeWithText("Remake the starter lineup?").assertDoesNotExist()
    }

    @Test
    fun `once it's back, editing works again`() {
        show(SampleStation.paired(now), open = false)
        click("Comedy")
        assertEquals(1, editor.calls.size)
    }
}
