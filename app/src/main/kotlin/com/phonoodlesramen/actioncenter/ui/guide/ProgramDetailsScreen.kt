package com.phonoodlesramen.actioncenter.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.handoff.PlayIn
import com.phonoodlesramen.actioncenter.ui.art.TitleArt
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.PillButton
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import java.time.ZoneId

/**
 * A program's details, full screen (Phase 2.7, plan §2): opened from the full guide, by OK
 * on a later program or Details in a program's quick actions ([HomeActions.openDetails]).
 * It reads only the cache, as the guide does.
 *
 * - **What it shows:** [ProgramDetails], inside the safe area.
 * - **Back** calls [onBack]: HomeActivity returns to the guide with this program focused.
 * - **Screen care** (G10): after the idle minutes it dims and drifts like the guide
 *   ([GuideScreenCare]). The first key only wakes it.
 * - **Gone:** a program that has left the lineup (its channel turned off, the lineup
 *   remade) says so, and Back still works.
 *
 * @param channelId and [startMillis] name the program; it may have left the lineup.
 * @param nowMillis the home's minute tick: the status line and the buttons follow it.
 * @param idleMinutes the idle time before screen care starts; null never starts it.
 * @param lastInputMillis when the remote was last pressed (wall clock).
 */
@Composable
fun ProgramDetailsScreen(
    state: GuideCardState,
    channelId: String,
    startMillis: Long,
    nowMillis: Long,
    actions: HomeActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    lateNight: Boolean = false,
    idleMinutes: Int? = null,
    lastInputMillis: () -> Long = { 0L },
    care: GuideScreenCare = remember { GuideScreenCare() },
    /** Settings › Plex › Play in (Phase 11): Open in Plezy, or Open in Plex. */
    playIn: PlayIn = PlayIn.PLEZY,
) {
    BackHandler(onBack = onBack)
    val channel = (state as? GuideCardState.Ready)?.channels?.firstOrNull { it.id == channelId }
    val slot = channel?.slots?.firstOrNull { it.startMillis == startMillis }
    GuideCaredScreen(care, idleMinutes, lastInputMillis, nowMillis, zone, modifier) { area ->
        if (channel != null && slot != null) {
            // Without a button to focus, the screen holds focus, so keys still reach screen care.
            val idle = !ProgramActions.of(slot, nowMillis).any
            val fallback = rememberFallbackFocus(enabled = idle)
            ProgramDetails(
                channel = channel,
                slot = slot,
                nowMillis = nowMillis,
                onWatch = { actions.tuneIn(channel.id) },
                onStartOver = { actions.startOver(channel.id) },
                onOpenIn = slot.itemKey?.let { key -> { actions.openIn(key, slot.title) } },
                openInLabel = "Open in ${playIn.label}",
                modifier = area.focusRequester(fallback).focusable(enabled = idle),
                zone = zone,
                lateNight = lateNight,
            )
        } else {
            Gone(area)
        }
    }
}

/**
 * The details themselves, shared by [ProgramDetailsScreen] and the player's options
 * (shown over the picture there). They draw no background of their own and fill what
 * they're given:
 *
 * - the channel's number and name, the title, the episode line for an episode, the times,
 *   length and rating, and the status line ([ProgramStatus]: on now with time left, starts
 *   in, ended at, or off air and why in the caution colour);
 * - the art at its own size (spec §6), at the right;
 * - Plex's whole summary, wrapped (smaller type when it's long);
 * - the buttons, the first focused: **Watch** while it's on, **Start over** while a program
 *   with a file is on, **Open in Plezy** (or Plex, [openInLabel]) for a program with a file
 *   (not filler); the player's is "Watch in Plezy", which carries on where the picture is
 *   (Phase 11). A later program says when it starts instead of Watch ("Starts at 9:30"). A
 *   null action leaves its button out (the player passes Watch as null), and so does a time
 *   it doesn't apply to ([ProgramActions]);
 * - the clock, at the buttons' right.
 */
@Composable
fun ProgramDetails(
    channel: GuideChannel,
    slot: GuideSlot,
    nowMillis: Long,
    onWatch: (() -> Unit)?,
    onStartOver: (() -> Unit)?,
    onOpenIn: (() -> Unit)?,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    lateNight: Boolean = false,
    openInLabel: String = "Open in Plezy",
) {
    val can = ProgramActions.of(slot, nowMillis)
    val buttons = listOfNotNull(
        onWatch?.takeIf { can.watch }?.let { DetailsButton(AppIcons.Play, "Watch", it) },
        onStartOver?.takeIf { can.startOver }?.let { DetailsButton(AppIcons.StartOver, "Start over", it) },
        onOpenIn?.takeIf { can.plezyKey != null }?.let { DetailsButton(AppIcons.OpenIn, openInLabel, it) },
    )
    val next = remember(channel, slot) { channel.upNext(slot.startMillis, 1).firstOrNull() }
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f).padding(end = 32.dp)) {
                Eyebrow("${channel.number} · ${channel.name}")
                Text(
                    text = slot.title,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                slot.episode?.let { episode ->
                    Text(episode, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    text = listOfNotNull(GuideTime.range(slot, zone), GuideTime.length(slot), slot.contentRating).joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
                ProgramStatus(slot, nowMillis, zone, Modifier.padding(top = 12.dp).heightIn(min = 20.dp))
            }
            val art = artFor(slot, next)
            Box(Modifier.fillMaxWidth(ART_WIDTH).aspectRatio(16f / 9f).clip(ArtShape).background(Palette.Surface)) {
                TitleArt(id = art.itemKey ?: "${channel.id}:${art.startMillis}", image = art.art, lateNight = lateNight, modifier = Modifier.fillMaxSize())
            }
        }
        Summary(description(slot, next, zone), Modifier.weight(1f).fillMaxWidth().padding(top = 24.dp, bottom = 16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Buttons(
                startsAt = if (slot.startMillis > nowMillis) GuideTime.startsAt(slot.startMillis, nowMillis, zone) else null,
                buttons = buttons,
                modifier = Modifier.weight(1f),
            )
            Text(GuideTime.clockAmPm(nowMillis, zone), style = MaterialTheme.typography.titleMedium, color = Palette.TextDim, maxLines = 1)
        }
    }
}

/**
 * Which of a program's actions apply at a time; the details' buttons and the guide's quick
 * actions share them. Pure, so it's tested without Compose.
 */
@Immutable
internal data class ProgramActions(
    /** Tune in: only while it's on. */
    val watch: Boolean,
    /** Tune in from its start (G11): while a program with a file is on. */
    val startOver: Boolean,
    /** The library title to hand to Plezy (D11): a program with a file, never filler. */
    val plezyKey: String?,
) {
    val any: Boolean get() = watch || startOver || plezyKey != null

    companion object {
        fun of(slot: GuideSlot, nowMillis: Long): ProgramActions {
            val onNow = nowMillis >= slot.startMillis && nowMillis < slot.endMillis
            // A file: a program whose title is still in the library. Filler's trailer is a file too, but not a program.
            val file = slot.itemKey.takeIf { !slot.filler && slot.offAir == null }
            return ProgramActions(watch = onNow, startOver = onNow && file != null, plezyKey = file)
        }
    }
}

private data class DetailsButton(val icon: ImageVector, val label: String, val run: () -> Unit)

/** "Starts at 9:30" for a later program, then the buttons; the first takes focus, and again when the buttons change. */
@Composable
private fun Buttons(startsAt: String?, buttons: List<DetailsButton>, modifier: Modifier = Modifier) {
    val first = remember { FocusRequester() }
    val labels = buttons.map { it.label }
    LaunchedEffect(labels) {
        if (labels.isNotEmpty()) runCatching { first.requestFocus() }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (startsAt != null) StartsAt(startsAt)
        buttons.forEachIndexed { index, button ->
            PillButton(
                text = button.label,
                onClick = button.run,
                icon = button.icon,
                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
}

/** When a later program starts, where Watch would be: a quiet outline, not a button. */
@Composable
private fun StartsAt(text: String) {
    Row(
        modifier = Modifier
            .height(36.dp)
            .border(1.dp, Color.White.copy(alpha = 0.16f), PillShape)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Info, contentDescription = null, tint = LocalAccent.current, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * Plex's summary, whole: in bodyLarge when it fits, else bodyMedium. Only a summary too
 * long even for that (rare, and more so at 115%) ends in an ellipsis, never half a line.
 */
@Composable
private fun Summary(text: String?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        if (text == null) return@BoxWithConstraints
        val measurer = rememberTextMeasurer()
        val large = MaterialTheme.typography.bodyLarge
        val small = MaterialTheme.typography.bodyMedium
        val style = remember(text, constraints, large, small) {
            if (!constraints.hasBoundedHeight) return@remember large
            val height = measurer.measure(text, large, constraints = Constraints(maxWidth = constraints.maxWidth)).size.height
            if (height <= constraints.maxHeight) large else small
        }
        val line = with(LocalDensity.current) { style.lineHeight.toDp() }
        val lines = if (constraints.hasBoundedHeight) (maxHeight / line).toInt() else Int.MAX_VALUE
        if (lines > 0) Text(text, style = style, color = Palette.TextDim, maxLines = lines, overflow = TextOverflow.Ellipsis)
    }
}

/** The program has left the lineup: say so calmly; Back still goes to the guide. */
@Composable
private fun Gone(modifier: Modifier = Modifier) {
    val fallback = rememberFallbackFocus(enabled = true)
    Column(
        modifier.focusRequester(fallback).focusable(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Eyebrow("Plex Guide")
        Text(
            text = "This program is no longer in the guide",
            style = MaterialTheme.typography.titleMedium,
            color = Palette.TextDim,
            textAlign = TextAlign.Center,
        )
    }
}

private val ArtShape = RoundedCornerShape(Layout.TileRadius)
private val PillShape = RoundedCornerShape(50)

/** The art's share of the width: the text beside it keeps the rest. */
private const val ART_WIDTH = 0.42f
