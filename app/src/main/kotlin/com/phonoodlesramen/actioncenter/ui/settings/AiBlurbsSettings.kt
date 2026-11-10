package com.phonoodlesramen.actioncenter.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.ai.AiBlurbsController
import com.phonoodlesramen.actioncenter.ai.AiBlurbsStatus
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.PillButton
import com.phonoodlesramen.actioncenter.ui.home.pillButtonColors
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Palette

/**
 * Settings › AI blurbs (M7-M11): on/off, the key from your phone, and the last run. All of
 * it works with the D-pad.
 *
 * - **The switch** ([SettingsActions.setAiBlurbs]): off by default. Nothing is sent until
 *   it's on and a key is stored (M7); off clears the blurbs.
 * - **The key:** "Add a key on your phone" ([SettingsActions.openAiKeyOnPhone], M10), or
 *   "Key ending …abcd" with **Forget key**. Never more than its last 4 characters.
 * - **The last run:** "Last night at 4:02 AM: 28 blurbs, 7,530 tokens, about $0.002", or
 *   when and why it failed, in the caution colour. **Write now** while it's on with a key.
 * - **A note** on what's sent (M8) and when a blurb shows (M9).
 */
@Composable
fun ColumnScope.AiBlurbsSettings(state: HomeUiState, actions: SettingsActions, controller: AiBlurbsController) {
    val status by controller.status.collectAsState(initial = AiBlurbsStatus())
    val on = state.aiBlurbs
    val ending = status.keyEnding
    // Write now: "Writing now…" until the run's line changes.
    var writing by remember { mutableStateOf(false) }
    LaunchedEffect(status.lastRun) { writing = false }

    ToggleRow(
        title = "AI blurbs",
        detail = "Once a night, Claude Haiku 5.5 writes a short line on why each pick suits you. Off by default.",
        on = on,
        onClick = { actions.setAiBlurbs(!on) },
    )

    Eyebrow("Claude API key", Modifier.padding(top = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = if (ending == null) "No key yet" else "Key ending …$ending",
            style = MaterialTheme.typography.titleSmall,
            color = if (ending == null) Palette.TextDim else Palette.Text,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (ending == null) {
            PillButton(text = "Add a key on your phone", onClick = actions::openAiKeyOnPhone)
        } else {
            PillButton(text = "Forget key", onClick = controller::forgetKey, colors = pillButtonColors(container = Palette.Surface2))
        }
    }

    Eyebrow("Last run", Modifier.padding(top = 8.dp))
    Text(
        text = when {
            writing -> "Writing now. This line changes when it's done."
            status.lastRun != null -> status.lastRun.orEmpty()
            on && ending != null -> "Not run yet. It writes tonight at about 4 AM."
            on -> "Waiting for a key. Nothing is sent until there's one."
            else -> "Not run yet"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (status.failed && !writing) Palette.Caution else Palette.Text,
    )
    if (on && ending != null) {
        PillButton(
            text = "Write now",
            icon = AppIcons.Restart,
            onClick = {
                writing = true
                controller.writeNow()
            },
            colors = pillButtonColors(container = Palette.Surface2),
        )
    }

    Note(
        "Sent to Anthropic: each pick's title, year, genres, rating and length, and why it was picked. " +
            "Never your name, your Plex server or your account.",
    )
    Note("A blurb shows in place of a pick's reason only while that reason is unchanged. Why this pick? keeps the plain reason.")
}
