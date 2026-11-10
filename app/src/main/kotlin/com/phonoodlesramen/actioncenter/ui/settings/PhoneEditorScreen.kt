package com.phonoodlesramen.actioncenter.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.guide.GuideEditor
import com.phonoodlesramen.actioncenter.guide.editor.EditorServer
import com.phonoodlesramen.actioncenter.guide.editor.EditorServer.State
import com.phonoodlesramen.actioncenter.guide.editor.EditorServer.StopReason
import com.phonoodlesramen.actioncenter.guide.editor.EditorSignIn
import com.phonoodlesramen.actioncenter.guide.editor.HomeNetwork
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.safeArea
import io.nayuki.qrcodegen.QrCode
import kotlin.math.floor

/**
 * Settings › Plex Guide › Edit on your phone (G1, G17): a QR code, the address and a PIN.
 * The phone editor ([EditorServer]) is served on the home network only while this screen
 * is open.
 *
 * - **Runs** from when the screen opens. It stops when the screen closes (Back), when the
 *   home stops (Home, another app in front), and after 15 minutes without a request; the
 *   screen then says so, with Start again. Each start is a new code and PIN.
 * - **Screen care (G10):** the Shield's own screensaver, as on the home's cards: nothing
 *   holds the screen on, so the QR code never sits on the OLED for long. That's simpler
 *   than the guide's dimming. If the screensaver stops the home, it stops the editor too,
 *   and Start again makes a new code.
 */
@Composable
fun PhoneEditorScreen(editor: GuideEditor, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val server = remember(editor, context) {
        EditorServer(
            editor = editor,
            page = { context.assets.open(EditorServer.PAGE_ASSET).use { it.readBytes() } },
            address = { HomeNetwork.address(context) },
        )
    }
    PhoneEditorScreen(server, onBack, modifier)
}

/** The screen around [server]: starts it now, stops it with the home or the screen. */
@Composable
internal fun PhoneEditorScreen(server: EditorServer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(server, lifecycle) {
        server.start()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) server.stop(StopReason.AWAY)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            server.stop(StopReason.CLOSED)
        }
    }
    val state by server.state.collectAsState()
    PhoneEditorContent(
        state = state,
        onStartAgain = server::start,
        onBack = {
            server.stop(StopReason.CLOSED)
            onBack()
        },
        modifier = modifier,
    )
}

/** The screen for [state], without the server: what the screenshots draw. */
@Composable
internal fun PhoneEditorContent(state: State, onStartAgain: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val running = state as? State.Running
    val status = statusOf(state)
    val self = remember { FocusRequester() }
    val restart = remember { FocusRequester() }
    // Start again takes focus when it appears; otherwise the screen holds it, so Back always works.
    LaunchedEffect(status.action != null) {
        runCatching { if (status.action != null) restart.requestFocus() else self.requestFocus() }
    }
    Column(modifier.fillMaxSize().background(Palette.Background).safeArea().focusRequester(self).focusable()) {
        Text("Edit on your phone", style = MaterialTheme.typography.headlineMedium)
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(GAP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QrImage(running?.link, Modifier.size(QR_SIZE.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (running != null) {
                    Text("Scan the code with your phone's camera", style = MaterialTheme.typography.titleLarge)
                    Text(address(running), style = MaterialTheme.typography.titleMedium, color = Palette.TextDim)
                }
                StatusLine(status)
                status.action?.let {
                    Button(onClick = onStartAgain, modifier = Modifier.focusRequester(restart)) { Text(it) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your phone needs to be on the same network as the Shield. The editor stops when you press Back, " +
                        "or after 15 minutes without use.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextFaint,
                )
            }
        }
    }
}

/** "Or open http://192.168.1.20:8137 on your phone and enter PIN 123 456", the address and PIN picked out. */
@Composable
internal fun address(running: State.Running) = buildAnnotatedString {
    val strong = SpanStyle(color = LocalAccent.current, fontWeight = FontWeight.SemiBold)
    append("Or open ")
    withStyle(strong) { append(running.address) }
    append(" on your phone and enter PIN ")
    withStyle(strong) { append(EditorSignIn.spaced(running.pin)) }
}

internal class Status(val text: String, val color: Color, val action: String? = null)

internal fun statusOf(state: State): Status = when (state) {
    // Closed only shows for the moment before the screen starts the server.
    State.Starting, State.Stopped(StopReason.CLOSED) -> Status("Starting…", Palette.TextDim)
    is State.Running -> if (state.phoneConnected) Status("Your phone is connected", Palette.Good) else Status("Waiting for your phone…", Palette.TextDim)
    State.NoNetwork -> Status("The Shield isn't on a network. Connect it, then try again.", Palette.Caution, "Try again")
    State.NoPort -> Status("Another app is using the editor's port. Try again in a moment.", Palette.Caution, "Try again")
    State.Stopped(StopReason.IDLE) -> Status("Stopped after 15 minutes without use", Palette.Caution, "Start again")
    is State.Stopped -> Status("Stopped while you were away", Palette.Caution, "Start again")
}

@Composable
internal fun StatusLine(status: Status) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(status.color))
        Text(status.text, style = MaterialTheme.typography.titleMedium, color = status.color)
    }
}

/**
 * [link] as a QR code: dark modules on white with a quiet zone of at least
 * [QUIET_ZONE] modules, each module a whole number of pixels so its edges stay sharp.
 * Low error correction keeps the modules large enough to scan from the couch. Without a
 * link (not running), an empty tile.
 */
@Composable
internal fun QrImage(link: String?, modifier: Modifier = Modifier, description: String = "QR code for the phone editor") {
    val code = remember(link) { link?.let { runCatching { QrCode.encodeText(it, QrCode.Ecc.LOW) }.getOrNull() } }
    val described = if (code != null) modifier.semantics { contentDescription = description } else modifier
    Canvas(described.clip(RoundedCornerShape(8.dp)).background(if (code != null) Color.White else Palette.Surface2)) {
        if (code == null) return@Canvas
        val modules = code.size + 2 * QUIET_ZONE
        val cell = floor(size.minDimension / modules)
        if (cell < 1f) return@Canvas
        val left = floor((size.width - cell * code.size) / 2)
        val top = floor((size.height - cell * code.size) / 2)
        for (y in 0 until code.size) {
            for (x in 0 until code.size) {
                if (code.getModule(x, y)) drawRect(Color.Black, Offset(left + x * cell, top + y * cell), Size(cell, cell))
            }
        }
    }
}

/** About 200 dp: big enough to scan from the couch. */
internal const val QR_SIZE = 200
private const val QUIET_ZONE = 4
internal const val GAP = 40
