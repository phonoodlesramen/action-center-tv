package com.phonoodlesramen.actioncenter.ui.settings

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.ai.AiKeyApi
import com.phonoodlesramen.actioncenter.ai.AiKeyDrop
import com.phonoodlesramen.actioncenter.guide.editor.EditorServer
import com.phonoodlesramen.actioncenter.guide.editor.EditorServer.State
import com.phonoodlesramen.actioncenter.guide.editor.EditorServer.StopReason
import com.phonoodlesramen.actioncenter.guide.editor.HomeNetwork
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.safeArea
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Settings › AI blurbs › Add a key on your phone (M10): the phone editor's server and
 * sign-in ([EditorServer]: a QR code, or the address and a PIN) serving a page with one
 * field, for the Claude API key.
 *
 * - **Runs** from when the screen opens, on the home network only. It stops on Back, when
 *   the home stops (Home, another app in front, a screensaver that stops it) and after 15
 *   minutes without a request, as the phone editor does.
 * - **A key arrives:** [AiKeyApi] stores it, the phone and the TV say "Key saved", and the
 *   screen closes after a moment ([SAVED_MILLIS]), long enough for the phone's answer to go.
 * - **The key** travels only over the home network, from the phone to the Shield.
 *
 * @param onDone back to Settings › AI blurbs; true when a key was saved.
 */
@Composable
fun AiKeyScreen(keys: AiKeyDrop, onDone: (saved: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val saved = remember { MutableStateFlow<String?>(null) }
    val server = remember(keys, context) {
        EditorServer(
            api = AiKeyApi(keys, onSaved = { saved.value = it }, log = { Log.w(EditorServer.LOG_TAG, it) }),
            page = { context.assets.open(EditorServer.KEY_PAGE_ASSET).use { it.readBytes() } },
            address = { HomeNetwork.address(context) },
        )
    }
    AiKeyScreen(server, saved, onDone, modifier)
}

/** The screen around [server]: starts it now, stops it with the home or the screen, and closes once [saved] has a key's ending. */
@Composable
internal fun AiKeyScreen(server: EditorServer, saved: StateFlow<String?>, onDone: (saved: Boolean) -> Unit, modifier: Modifier = Modifier) {
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
    val ending by saved.collectAsState()
    LaunchedEffect(ending) {
        if (ending == null) return@LaunchedEffect
        delay(SAVED_MILLIS)
        server.stop(StopReason.CLOSED)
        onDone(true)
    }
    AiKeyContent(
        state = state,
        savedEnding = ending,
        onStartAgain = server::start,
        onBack = {
            server.stop(StopReason.CLOSED)
            onDone(ending != null)
        },
        modifier = modifier,
    )
}

/** The screen for [state] without the server, what the screenshots draw. [savedEnding]: a key arrived. */
@Composable
internal fun AiKeyContent(
    state: State,
    savedEnding: String?,
    onStartAgain: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val running = (state as? State.Running)?.takeIf { savedEnding == null }
    val status = if (savedEnding != null) Status("Key saved", Palette.Good) else statusOf(state)
    val self = remember { FocusRequester() }
    val restart = remember { FocusRequester() }
    // Start again takes focus when it appears; otherwise the screen holds it, so Back always works.
    LaunchedEffect(status.action != null) {
        runCatching { if (status.action != null) restart.requestFocus() else self.requestFocus() }
    }
    Column(modifier.fillMaxSize().background(Palette.Background).safeArea().focusRequester(self).focusable()) {
        Text("Add a key on your phone", style = MaterialTheme.typography.headlineMedium)
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(GAP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QrImage(running?.link, Modifier.size(QR_SIZE.dp), description = "QR code for the key page")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (running != null) {
                    Text("Scan the code with your phone's camera", style = MaterialTheme.typography.titleLarge)
                    Text(address(running), style = MaterialTheme.typography.titleMedium, color = Palette.TextDim)
                }
                StatusLine(status)
                if (savedEnding != null) {
                    Text("Settings shows it as Key ending …$savedEnding.", style = MaterialTheme.typography.titleMedium, color = Palette.TextDim)
                }
                status.action?.let {
                    Button(onClick = onStartAgain, modifier = Modifier.focusRequester(restart)) { Text(it) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Paste your Claude API key on the page that opens. It goes only to the Shield, over your home network, " +
                        "and is stored encrypted there. The page stops when you press Back, or after 15 minutes without use.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextFaint,
                )
            }
        }
    }
}

/** How long "Key saved" shows before the screen closes. */
internal const val SAVED_MILLIS = 1_500L
