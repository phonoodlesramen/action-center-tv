package com.phonoodlesramen.actioncenter.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.player.GuideTuning
import com.phonoodlesramen.actioncenter.ui.art.TitleArt
import com.phonoodlesramen.actioncenter.ui.guide.ChannelMark
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.rows.LiveClock
import com.phonoodlesramen.actioncenter.ui.home.rows.LiveNow
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.safeArea
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Which channels the mini guide shows. Pure, so it's unit-tested. */
internal object MiniGuideRows {
    /** Rows in the strip: the channel picked out, with two above and two below. */
    const val COUNT = 5

    /**
     * [count] channels around [focusedId] in lineup order, wrapping at both ends, so the one
     * picked out stays in the middle; the whole lineup, in order, when it's no longer.
     */
    fun around(lineup: List<GuideChannel>, focusedId: String, count: Int = COUNT): List<GuideChannel> {
        if (lineup.size <= count) return lineup
        val index = lineup.indexOfFirst { it.id == focusedId }.coerceAtLeast(0)
        val above = count / 2
        return (-above until count - above).map { lineup[(index + it).mod(lineup.size)] }
    }
}

/**
 * The mini guide (G12): a strip over the bottom third of the picture, the video playing on
 * above it. The channels in lineup order around the one picked out, each with what's on
 * now and its time left (as the Live row says it) and what's next; the picked channel's
 * program art at its exact size. Nothing in it is focusable: the activity moves the pick
 * (Up/Down) and tunes in (OK).
 *
 * Favorites (Phase 13, C4): [lineup] has the profile's [favorites] first, each with a star,
 * and a 2 dp accent line runs under the last of them.
 *
 * @param playingId the channel on screen, marked with a play sign.
 */
@Composable
internal fun MiniGuide(
    lineup: List<GuideChannel>,
    focusedId: String,
    playingId: String?,
    now: Long,
    zone: ZoneId,
    favorites: Set<String> = emptySet(),
) {
    val clock = LiveClock(LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone), now)
    val picked = lineup.firstOrNull { it.id == focusedId }?.let { LiveNow.of(it, now) }?.program
    // The favorites lead: the line goes under the last, unless every channel is one.
    val lastFavorite = lineup.lastOrNull { it.id in favorites }?.id?.takeIf { it != lineup.last().id }
    Box(Modifier.fillMaxSize()) {
        // The scrim runs to the screen's edges; the strip stays inside the safe area.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(SCRIM_HEIGHT)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.3f to Palette.Scrim.copy(alpha = 0.9f),
                        1f to Palette.Scrim.copy(alpha = 0.97f),
                    ),
                ),
        )
        Row(
            modifier = Modifier.fillMaxSize().safeArea().wrapContentHeight(Alignment.Bottom),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TitleArt(
                id = picked?.showKey ?: picked?.itemKey ?: picked?.title ?: focusedId,
                image = picked?.art,
                lateNight = false,
                modifier = Modifier.size(ART_WIDTH, ART_WIDTH * 9 / 16).clip(RoundedCornerShape(Layout.TileRadius)),
            )
            Column(Modifier.weight(1f)) {
                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Channels")
                    Spacer(Modifier.weight(1f))
                    Text("OK to watch · Back to close", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
                }
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                    val rows = MiniGuideRows.around(lineup, focusedId)
                    rows.forEachIndexed { index, channel ->
                        ChannelRow(
                            channel,
                            picked = channel.id == focusedId,
                            playing = channel.id == playingId,
                            favorite = channel.id in favorites,
                            lineUnder = channel.id == lastFavorite && index < rows.lastIndex,
                            now = now,
                            clock = clock,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One channel: number, icon, name (a favorite's starred), what's on and its time left, and what's
 * next. The pick fills with the accent, as a sheet's row. [lineUnder] draws the favorites'
 * line in the gap below it.
 */
@Composable
private fun ChannelRow(channel: GuideChannel, picked: Boolean, playing: Boolean, favorite: Boolean, lineUnder: Boolean, now: Long, clock: LiveClock) {
    val live = LiveNow.of(channel, now)
    val next = GuideTuning.nextProgram(channel, live.program?.endMillis ?: now)
    val accent = LocalAccent.current
    val ink = if (picked) Palette.AccentInk else Palette.Text
    val dim = if (picked) Palette.AccentInk else Palette.TextDim
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .drawBehind {
                if (lineUnder) drawRect(accent.copy(alpha = 0.6f), topLeft = Offset(0f, size.height), size = Size(size.width, ROW_GAP.toPx()))
            }
            .clip(RoundedCornerShape(Layout.TileRadius))
            .background(if (picked) accent else Color.Transparent)
            .semantics(mergeDescendants = true) { selected = picked }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.width(NUMBER_WIDTH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (playing) {
                Icon(AppIcons.Play, contentDescription = "Playing", tint = if (picked) ink else accent, modifier = Modifier.size(12.dp))
            } else {
                Spacer(Modifier.size(12.dp))
            }
            Text(
                text = channel.number.toString(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = if (playing && !picked) accent else ink,
                maxLines = 1,
            )
        }
        ChannelMark(channel, ICON_SIZE, lateNight = false)
        Row(Modifier.width(NAME_WIDTH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (favorite) Icon(AppIcons.Star, contentDescription = "Favorite", tint = if (picked) ink else accent, modifier = Modifier.size(12.dp))
            Text(channel.name, style = MaterialTheme.typography.bodyMedium, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // The title first; its time left and episode get what room is left.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = live.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (live is LiveNow.OffAir && !picked) Palette.Caution else ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = live.detail(clock),
                style = MaterialTheme.typography.bodySmall,
                color = dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Text(
            text = next?.let { "${clock.timeOf(it.startMillis)}  ${it.title}" }.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(NEXT_WIDTH),
        )
    }
}

private val SCRIM_HEIGHT = 240.dp
private val ROW_HEIGHT = 28.dp
private val ROW_GAP = 2.dp
private val ART_WIDTH = 128.dp
private val NUMBER_WIDTH = 40.dp
private val NAME_WIDTH = 132.dp

/** The channel's icon (I5), between its number and its name. */
private val ICON_SIZE = 20.dp
private val NEXT_WIDTH = 190.dp
