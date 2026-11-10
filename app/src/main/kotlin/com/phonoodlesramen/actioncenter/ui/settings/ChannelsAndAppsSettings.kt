package com.phonoodlesramen.actioncenter.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.apps.UsageAccess
import com.phonoodlesramen.actioncenter.tvprovider.ChannelList
import com.phonoodlesramen.actioncenter.tvprovider.ChannelListFile
import com.phonoodlesramen.actioncenter.tvprovider.ChannelsAndAppsController
import com.phonoodlesramen.actioncenter.tvprovider.ChannelsAndAppsState
import com.phonoodlesramen.actioncenter.tvprovider.WatchNextApp
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.PillButton
import com.phonoodlesramen.actioncenter.ui.home.pillButtonColors
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette

/**
 * Settings › Channels & apps (L8, L9): where the channel buttons come from and how to
 * set your own, how the Apps row orders (M5), and which apps feed Continue in your apps.
 * Every control works with the remote; the channel list is edited on a PC and copied over,
 * since typing links with a remote is no fun (Q3).
 */
@Composable
fun ColumnScope.ChannelsAndAppsSettings(controller: ChannelsAndAppsController) {
    val state by controller.state.collectAsState()
    if (state.loaded && !state.canReadTvListings) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(AppIcons.Warning, contentDescription = null, tint = Palette.Caution, modifier = Modifier.padding(top = 2.dp).size(18.dp))
            Text(
                "Action Center can't read other apps' rows yet, so Continue in your apps and YouTube TV's channels stay empty. " +
                    "Settings › Setup › Continue in your apps allows it (or scripts/grant-permissions.ps1 from a computer).",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Caution,
            )
        }
    }

    Eyebrow("Channels", Modifier.padding(top = 4.dp))
    Text(channelSource(state), style = MaterialTheme.typography.titleSmall)
    channelListProblem(state)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Caution) }
    Hint(
        "YouTube TV's networks show up by themselves, with banners from Pictures/Icons. To pick your own, write " +
            "channels.json on your PC and copy it over. It replaces YouTube TV's networks; to add to them instead, " +
            "wrap the list in {\"includeYouTubeTv\": true, \"channels\": […]}.",
    )
    Code("""[{"name": "ESPN", "link": "https://tv.youtube.com/watch/…", "banner": "ESPN"}]""")
    Code("adb push channels.json ${state.channelListPath.substringBeforeLast('/')}/")
    PillButton(
        text = "Reload channel list",
        icon = AppIcons.Restart,
        onClick = controller::reload,
        colors = pillButtonColors(container = Palette.Surface2),
    )

    // Above Continue in your apps: text after the last switch would never scroll into view.
    Eyebrow("Apps row", Modifier.padding(top = 8.dp))
    val context = LocalContext.current
    val usageAccess = remember { UsageAccess.granted(context) }
    if (usageAccess) {
        Hint("The Apps row puts what you usually open at this time of day first.")
    } else {
        Text(
            "Usage access is off, so the Apps row goes by what you open from here. Settings › Setup › Apps row by habit turns it on.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Caution,
        )
    }

    Eyebrow("Continue in your apps", Modifier.padding(top = 8.dp))
    Hint("Where you left off in other apps, newest first. Plex and Plezy stay out: Up Next has them.")
    when {
        state.apps.isNotEmpty() -> state.apps.forEach { app ->
            AppToggle(app) { controller.setAppShown(app.packageName, !app.shown) }
        }
        !state.loaded -> Hint("Looking for apps…")
        else -> Hint("No apps have anything to continue right now. Watch something in Disney+ or YouTube and it shows up here.")
    }
}

private fun channelSource(state: ChannelsAndAppsState): String {
    val includes = (state.channelList as? ChannelList.Loaded)?.includeYouTubeTv == true
    return when {
        state.listChannels > 0 && includes -> "From YouTube TV's row and your channel list: ${count(state.channels, "channel")}"
        state.listChannels > 0 -> "From your channel list: ${count(state.channels, "channel")}"
        state.youTubeTvChannels > 0 -> "From YouTube TV's row: ${count(state.channels, "channel")}"
        !state.loaded -> "Looking for channels…"
        state.canReadTvListings -> "No channels yet: YouTube TV's row is empty, and there's no channel list."
        else -> "No channels yet"
    }
}

private fun channelListProblem(state: ChannelsAndAppsState): String? {
    val fallback = if (state.youTubeTvChannels > 0) " Showing YouTube TV's row instead." else ""
    return when (val list = state.channelList) {
        ChannelList.Missing -> null
        is ChannelList.Problem -> "${ChannelListFile.NAME} can't be used: ${list.reason}.$fallback"
        is ChannelList.Loaded -> when {
            list.entries.isEmpty() -> "${ChannelListFile.NAME} has no channels with a name and a link.$fallback"
            list.skipped > 0 -> "${count(list.skipped, "entry", "entries")} skipped: each needs a name and a link."
            else -> null
        }
    }
}

private fun count(n: Int, one: String, many: String = "${one}s") = "$n ${if (n == 1) one else many}"

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
}

/** A path, command or example to copy: monospaced, on a dark strip. */
@Composable
private fun Code(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = Palette.Text,
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** An app that feeds the row: the whole row is the switch. */
@Composable
private fun AppToggle(app: WatchNextApp, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Surface3,
            focusedContentColor = Palette.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(app.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = when {
                        !app.shown -> "Hidden from the row" + if (app.entries > 0) " (${count(app.entries, "entry", "entries")})" else ""
                        app.entries == 0 -> "Nothing to continue right now"
                        else -> "${count(app.entries, "entry", "entries")} in the row"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(on = app.shown)
        }
    }
}

@Composable
private fun Switch(on: Boolean) {
    val accent = LocalAccent.current
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 24.dp)
            // Off stays visible on the focused row's Surface3 too.
            .background(if (on) accent else Color.White.copy(alpha = 0.14f), RoundedCornerShape(50)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .size(18.dp)
                .background(if (on) Palette.AccentInk else Palette.TextDim, CircleShape),
        )
    }
}
