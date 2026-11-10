package com.phonoodlesramen.actioncenter.ui.settings

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.guide.editor.EditorServer.State
import com.phonoodlesramen.actioncenter.guide.editor.EditorServer.StopReason
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import io.nayuki.qrcodegen.QrCode
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Edit on your phone at the Shield's 1080p TV configuration, with a fixed address, PIN and
 * QR code: waiting at 90/100/115%, connected, stopped after idling, and without a network;
 * and the QR code's pixels checked against the link. Record with
 * `.\gradlew.bat recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class PhoneEditorScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val waiting = State.Running(
        address = "http://192.168.1.20:8137",
        pin = "123456",
        link = "http://192.168.1.20:8137/?k=sCreEnShOt-k3y_0123456",
        phoneConnected = false,
    )

    private fun show(state: State, layoutScale: Float = 1f) = compose.setContent {
        ActionCenterTheme(layoutScale = layoutScale) {
            PhoneEditorContent(state, onStartAgain = {}, onBack = {})
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun waiting() {
        show(waiting)
        capture("phone_editor_waiting")
    }

    @Test
    fun waitingScale90() {
        show(waiting, layoutScale = 0.9f)
        capture("phone_editor_waiting_scale_90")
    }

    @Test
    fun waitingScale115() {
        show(waiting, layoutScale = 1.15f)
        capture("phone_editor_waiting_scale_115")
    }

    @Test
    fun connected() {
        show(waiting.copy(phoneConnected = true))
        capture("phone_editor_connected")
    }

    @Test
    fun stoppedAfterIdling() {
        show(State.Stopped(StopReason.IDLE))
        capture("phone_editor_stopped")
    }

    @Test
    fun noNetwork() {
        show(State.NoNetwork)
        capture("phone_editor_no_network")
    }

    /** Every module drawn is the link's (none transposed or shifted), and the quiet zone is white. */
    @Test
    fun qrCodeIsTheLink() {
        show(waiting)
        val image = compose.onNodeWithContentDescription("QR code for the phone editor").captureToImage().toPixelMap()
        val code = QrCode.encodeText(waiting.link, QrCode.Ecc.LOW)
        val cell = floor(minOf(image.width, image.height).toFloat() / (code.size + 8))
        val left = floor((image.width - cell * code.size) / 2)
        val top = floor((image.height - cell * code.size) / 2)
        assertTrue("modules of ${cell}px", cell >= 4)
        fun dark(x: Float, y: Float) = image[x.toInt(), y.toInt()].luminance() < 0.5f
        for (y in 0 until code.size) {
            for (x in 0 until code.size) {
                assertEquals("module $x,$y", code.getModule(x, y), dark(left + (x + 0.5f) * cell, top + (y + 0.5f) * cell))
            }
        }
        for (i in 0 until code.size) {
            assertFalse(dark(left - 2 * cell, top + i * cell))
            assertFalse(dark(left + i * cell, top + (code.size + 2) * cell))
        }
    }
}
