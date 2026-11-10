package com.phonoodlesramen.actioncenter.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.home.PlexHome
import com.phonoodlesramen.actioncenter.plex.PlexSignIn
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalCardSurface
import com.phonoodlesramen.actioncenter.ui.wallpaper.cardSurface

/**
 * The hero until Plex has something to show (U14): the plex.tv/link code while signing
 * in, then what's happening (finding the server, offline, choose a server). [focus]
 * goes on its first button, where Home lands (spec §3).
 */
@Composable
fun PlexHero(plex: PlexHome, actions: HomeActions, focus: FocusRequester, modifier: Modifier = Modifier) {
    val server = plex.link.server
    BoxWithConstraints(modifier.cardSurface(LocalCardSurface.current, RoundedCornerShape(Layout.CardRadius))) {
        // At larger layout scales the code and the buttons need the waiting line's room.
        val roomy = maxHeight >= 220.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Eyebrow("Set up")
            when (val signIn = plex.signIn) {
                null, PlexSignIn.GettingCode, is PlexSignIn.ShowingCode -> if (plex.link.signedIn) {
                    Status("Signed in", "Finding your Plex server…")
                } else {
                    CodePrompt((signIn as? PlexSignIn.ShowingCode)?.code, waitingLine = roomy)
                }
                PlexSignIn.Connecting -> Status("Signed in", "Finding your Plex server…")
                PlexSignIn.Offline -> Status("Can't reach Plex", "Check the Shield is online. Trying again shortly.")
                PlexSignIn.NoServer -> Status(
                    "No server of your own",
                    "This Plex account doesn't own a server, and shared servers aren't used. Sign in with the account that owns your library in Settings › Plex.",
                )
                is PlexSignIn.ChooseServer -> Status("Which server?", "You own more than one Plex server. Choose one in Settings › Plex.")
                is PlexSignIn.CantReachServer -> Status(
                    "Can't reach ${signIn.name}",
                    "Check the server is running and on the network. Trying again shortly.",
                )
                PlexSignIn.Linked -> Status(
                    "Connected to ${server?.name ?: "Plex"}",
                    "Up Next and your picks arrive with the next update. Your apps already work.",
                )
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val showingCode = !plex.link.signedIn
                PillButton(
                    text = if (showingCode) "Get a new code" else "Plex settings",
                    icon = if (showingCode) AppIcons.Restart else AppIcons.Gear,
                    onClick = if (showingCode) actions::newPlexCode else actions::openPlexSettings,
                    modifier = Modifier.focusRequester(focus),
                )
                PillButton(text = "Browse all apps", icon = AppIcons.Grid, onClick = actions::openAllApps)
            }
        }
    }
}

@Composable
private fun CodePrompt(code: String?, waitingLine: Boolean) {
    Text(
        text = "Connect your Plex account",
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
    Text(
        text = buildAnnotatedString {
            append("On your phone or computer, go to ")
            withStyle(SpanStyle(color = Palette.Text, fontWeight = FontWeight.Bold)) { append("plex.tv/link") }
            append(" and enter:")
        },
        style = MaterialTheme.typography.bodyMedium,
        color = Palette.TextDim,
    )
    Text(
        text = code ?: "····",
        style = MaterialTheme.typography.displaySmall.copy(fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = 10.sp, fontWeight = FontWeight.Bold),
        color = if (code == null) Palette.TextFaint else LocalAccent.current,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (waitingLine) {
        Text(
            text = if (code == null) "Getting a code…" else "Waiting for Plex… Your apps below already work.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextDim,
        )
    }
}

@Composable
private fun Status(title: String, detail: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
    )
    Text(detail, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, modifier = Modifier.fillMaxWidth(HERO_TEXT_WIDTH))
}
