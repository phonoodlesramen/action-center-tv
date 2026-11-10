package com.phonoodlesramen.actioncenter.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import com.phonoodlesramen.actioncenter.guide.editor.EditorServer
import com.phonoodlesramen.actioncenter.guide.editor.EditorServer.State
import com.phonoodlesramen.actioncenter.guide.editor.EditorServer.StopReason
import com.phonoodlesramen.actioncenter.guide.editor.EditorSignIn
import com.phonoodlesramen.actioncenter.guide.editor.FakeGuideEditor
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Edit on your phone (G17) around a real server on loopback: opening the screen starts
 * it, and the screen closing, Back or the home stopping stops it; Start again starts a
 * new run.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class PhoneEditorScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val server = EditorServer(
        editor = FakeGuideEditor(),
        page = { "<!doctype html><title>Editor</title>".toByteArray() },
        address = { InetAddress.getLoopbackAddress() },
        ports = listOf(0),
        log = {},
    )
    private var backs = 0
    private var showing by mutableStateOf(true)

    private fun show() = compose.setContent {
        ActionCenterTheme {
            if (showing) PhoneEditorScreen(server, onBack = { backs++ })
        }
    }

    private fun awaitRunning(): State.Running {
        compose.waitUntil(TIMEOUT_MILLIS) { server.state.value is State.Running }
        compose.waitForIdle()
        return server.state.value as State.Running
    }

    @After
    fun stop() = server.stop(StopReason.CLOSED)

    @Test
    fun `opening the screen starts the server and shows its address and PIN`() {
        show()
        val running = awaitRunning()
        compose.onNodeWithText("Edit on your phone").assertIsDisplayed()
        compose.onNodeWithText("Waiting for your phone…").assertIsDisplayed()
        compose.onNodeWithText(running.address, substring = true).assertIsDisplayed()
        compose.onNodeWithText("enter PIN ${EditorSignIn.spaced(running.pin)}", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a phone opening the QR link shows as connected`() {
        show()
        val running = awaitRunning()
        val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        http.newCall(Request.Builder().url(running.link).build()).execute().use { assertEquals(200, it.code) }
        compose.waitUntil(TIMEOUT_MILLIS) { (server.state.value as? State.Running)?.phoneConnected == true }
        compose.onNodeWithText("Your phone is connected").assertIsDisplayed()
    }

    @Test
    fun `the home stopping stops the server, and Start again makes a new run`() {
        show()
        val first = awaitRunning()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(State.Stopped(StopReason.AWAY), server.state.value)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Stopped while you were away").assertIsDisplayed()
        // Start again has focus, so OK on the remote presses it.
        compose.onNode(isFocused()).assert(hasText("Start again"))
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        val second = awaitRunning()
        assertNotEquals(first.link, second.link)
        compose.onNodeWithText("Waiting for your phone…").assertIsDisplayed()
    }

    @Test
    fun `leaving the screen stops the server`() {
        show()
        awaitRunning()
        showing = false
        compose.waitForIdle()
        assertEquals(State.Stopped(StopReason.CLOSED), server.state.value)
    }

    @Test
    fun `Back stops the server and goes back`() {
        show()
        awaitRunning()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, backs)
        assertEquals(State.Stopped(StopReason.CLOSED), server.state.value)
    }

    @Test
    fun `stopped after idling, it offers Start again`() {
        show()
        awaitRunning()
        compose.runOnUiThread { server.stop(StopReason.IDLE) }
        compose.onNodeWithText("Stopped after 15 minutes without use").assertIsDisplayed()
        compose.onNodeWithText("Start again").assertIsDisplayed()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
