package com.phonoodlesramen.actioncenter.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.player.BackTo
import com.phonoodlesramen.actioncenter.player.SavedSpot
import com.phonoodlesramen.actioncenter.ui.art.TitleArt
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.QuickSheet
import com.phonoodlesramen.actioncenter.ui.home.SheetItem
import com.phonoodlesramen.actioncenter.ui.player.PlayerIcons
import com.phonoodlesramen.actioncenter.ui.player.PlayerText
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette

/** The Back-to card's words (P4), apart from Compose so they're unit-tested. */
internal object BackToText {
    /** "Back to TV Mix". */
    fun title(backTo: BackTo): String = "Back to ${backTo.channel.name}"

    /**
     * "Paused 1:12:30 in · 9 min ago": where in the program, and when it was left. Left on
     * another TV (Phase 8, N6), it names the room instead: "Paused in the living room · 9 min ago".
     */
    fun line(spot: SavedSpot, nowMillis: Long): String {
        val where = spot.room?.let { "in ${inRoom(it)}" } ?: "${PlayerText.position(spot.positionMs)} in"
        return "Paused $where · ${ago(nowMillis - spot.savedAtMillis)}"
    }

    /**
     * A room as a sentence says it: "the living room" for "Living room", "the TV room",
     * "Maya's room" (no "the" before a name's), and "the den" for "The den".
     */
    fun inRoom(room: String): String {
        val name = room.trim().replace(Regex("\\s+"), " ")
        if (name.startsWith("the ", ignoreCase = true)) return "the " + name.substring(4)
        // A name's room ("Maya's room") is the name's, without "the".
        if (Regex("^\\S+['’]s\\b").containsMatchIn(name)) return name
        val first = name.substringBefore(' ')
        // An acronym ("TV room") keeps its capitals.
        val lowered = if (first.length > 1 && first == first.uppercase()) name else name.replaceFirstChar { it.lowercase() }
        return "the $lowered"
    }

    /** "just now", "9 min ago", "1 hr ago" or "1 hr 5 min ago", on the home's minute tick. */
    fun ago(millis: Long): String {
        val minutes = millis.coerceAtLeast(0) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes % 60 == 0L -> "${minutes / 60} hr ago"
            else -> "${minutes / 60} hr ${minutes % 60} min ago"
        }
    }
}

/**
 * The Back-to card's quick actions (P4), the home's side sheet: Resume, Back to live (the
 * channel live, the spot forgotten) and Forget this (the card goes back to what it showed).
 * Focus returns to [returnTo], or after Forget this to [afterForget], since a kid's card goes.
 */
internal fun backToSheet(backTo: BackTo, actions: HomeActions, returnTo: FocusRequester?, afterForget: FocusRequester? = returnTo) = QuickSheet(
    title = backTo.slot.title,
    returnTo = returnTo,
    items = listOf(
        SheetItem(AppIcons.Play, "Resume") { actions.resume(backTo.spot) },
        SheetItem(PlayerIcons.Live, "Back to live") { actions.backToLive(backTo.channel.id) },
        SheetItem(AppIcons.Close, "Forget this", returnTo = afterForget) { actions.forgetSpot() },
    ),
)

/**
 * The Plex Guide card while a spot is saved (P4): "BACK TO TV MIX" in the accent, the
 * program's 16:9 art at its own size with the paused place on its bar, the title, its
 * episode and rating, and "Paused 1:12:30 in · 9 min ago" on the home's minute tick.
 */
@Composable
internal fun ColumnScope.BackToFace(backTo: BackTo, nowMillis: Long, lateNight: Boolean) {
    val slot = backTo.slot
    val accent = LocalAccent.current
    Eyebrow(BackToText.title(backTo), color = accent)
    Row(
        modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(Layout.TileRadius))) {
            TitleArt(id = slot.itemKey ?: slot.title, image = slot.art, lateNight = lateNight, modifier = Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.22f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(backTo.progress).background(accent))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = slot.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            PlayerText.meta(slot)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Text(
        text = BackToText.line(backTo.spot, nowMillis),
        style = MaterialTheme.typography.bodySmall,
        color = Palette.TextDim,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 8.dp),
    )
}
