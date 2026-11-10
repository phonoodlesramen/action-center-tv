package com.phonoodlesramen.actioncenter.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.ui.art.TitleArt
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import java.time.ZoneId

/**
 * The full guide's info panel (plan §5), for the focused program: its art at the panel's
 * exact size (the screen's one image, spec §6), then the channel, title, episode, time,
 * length and rating, what's happening now with what OK does, and Plex's summary.
 */
@Composable
internal fun GuideInfo(
    channel: GuideChannel?,
    slot: GuideSlot?,
    nowMillis: Long,
    zone: ZoneId,
    lateNight: Boolean,
    modifier: Modifier = Modifier,
) {
    val next = if (channel != null && slot != null) channel.upNext(slot.startMillis, 1).firstOrNull() else null
    val art = slot?.let { artFor(it, next) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(ArtShape).background(Palette.Surface)) {
            if (channel != null && art != null) {
                TitleArt(id = art.itemKey ?: "${channel.id}:${art.startMillis}", image = art.art, lateNight = lateNight, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(channel?.let { "${it.number} · ${it.name}" }.orEmpty(), Modifier.weight(1f))
                Text(GuideTime.clockAmPm(nowMillis, zone), style = MaterialTheme.typography.titleMedium, color = Palette.TextDim, maxLines = 1)
            }
            if (channel != null && slot != null) {
                Text(
                    text = slot.title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = listOfNotNull(slot.episode, GuideTime.range(slot, zone), GuideTime.length(slot), slot.contentRating).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ProgramStatus(
                    slot = slot,
                    nowMillis = nowMillis,
                    zone = zone,
                    // What OK does here: tune in while it's on, its details before it starts.
                    onNowHint = "OK to watch",
                    laterHint = "OK for details",
                    modifier = Modifier.padding(top = 8.dp).height(20.dp),
                )
                Description(description(slot, next, zone), Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp))
            }
        }
    }
}

private val ArtShape = RoundedCornerShape(Layout.TileRadius)

/** The art to show for [slot]: filler has none of its own, so an "Up next" shows what's coming ([next]). */
internal fun artFor(slot: GuideSlot, next: GuideSlot?): GuideSlot = if (slot.filler && slot.art == null && next != null) next else slot

/**
 * The line that changes with the time, for the info panel and a program's details: off
 * air; on now with its progress; later, how long until it starts; or when it ended. The
 * hints say what OK does, faintly, after the line.
 */
@Composable
internal fun ProgramStatus(
    slot: GuideSlot,
    nowMillis: Long,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    onNowHint: String? = null,
    laterHint: String? = null,
) {
    val label = MaterialTheme.typography.labelLarge
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            slot.offAir != null -> {
                Icon(AppIcons.Warning, contentDescription = null, tint = Palette.Caution, modifier = Modifier.size(16.dp))
                Text("Off air · ${slot.offAir}", style = label, color = Palette.Caution, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            slot.startMillis > nowMillis -> {
                Text(GuideTime.startsIn(slot.startMillis, nowMillis), style = label, color = Palette.TextDim, maxLines = 1)
                if (laterHint != null) Text(laterHint, style = label, color = Palette.TextFaint, maxLines = 1)
            }
            slot.endMillis <= nowMillis -> Text(GuideTime.endedAt(slot.endMillis, zone), style = label, color = Palette.TextDim, maxLines = 1)
            else -> {
                Box(Modifier.width(64.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.22f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(slot.progressAt(nowMillis)).background(LocalAccent.current))
                }
                Text("On now · ${GuideTime.left(slot, nowMillis)}", style = label, maxLines = 1)
                if (onNowHint != null) Text(onNowHint, style = label, color = Palette.TextFaint, maxLines = 1)
            }
        }
    }
}

/** Plex's summary; for filler, what it is and what follows. */
internal fun description(slot: GuideSlot, next: GuideSlot?, zone: ZoneId): String? = when {
    slot.summary != null -> slot.summary
    !slot.filler || next == null -> null
    slot.itemKey != null -> "A trailer between programs. Next: ${next.title} at ${GuideTime.clock(next.startMillis, zone)}."
    else -> "A short break. Next: ${next.title} at ${GuideTime.clock(next.startMillis, zone)}."
}

/** As many whole lines as the panel has room for (three at most): fewer at 115%, never half a line. */
@Composable
private fun Description(text: String?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val style = MaterialTheme.typography.bodyMedium
        val line = with(LocalDensity.current) { style.lineHeight.toDp() }
        val lines = (maxHeight / line).toInt().coerceAtMost(MAX_LINES)
        if (text != null && lines > 0) {
            Text(text, style = style, color = Palette.TextDim, maxLines = lines, overflow = TextOverflow.Ellipsis)
        }
    }
}

private const val MAX_LINES = 3
