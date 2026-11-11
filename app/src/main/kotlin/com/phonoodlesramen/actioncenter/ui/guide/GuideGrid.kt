package com.phonoodlesramen.actioncenter.ui.guide

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.ui.home.onMenuKey
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout as Tokens
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * The full guide's grid (plan §5): channels down the side, then the shared 90-minute
 * window ([GuideFocus.windowStart]) under a time ruler, with a red now line. A program is
 * as wide as its minutes in the window; filler is a slot of its own ("Up next" or a
 * trailer), so it shows as one, and off-air programs say so.
 *
 * Only the window's programs on the [GuideLineup.rows] channels in view are composed: a
 * page is a few dozen text cells, never 24 hours of them (2 GB budget). The D-pad is the
 * grid's own ([GuideLineup.move], by time, not column) and focus follows [focus]; OK is the
 * focused cell's click, [onSelect], and holding OK or Menu is [onMenu].
 *
 * Favorites (Phase 13, C4) lead the lineup: each has a star before its name, and a 2 dp
 * accent line runs under the last one, between it and the first of the rest.
 *
 * @param refocus bumped when focus should come back to the focused cell (a sheet over the grid closed).
 * @param favorites the profile's favorite channels, which [lineup] has first.
 */
@Composable
internal fun GuideGrid(
    lineup: GuideLineup,
    focus: GuideFocus,
    nowMillis: Long,
    zone: ZoneId,
    lateNight: Boolean,
    onMove: (GuideFocus) -> Unit,
    onSelect: (GuideChannel, GuideSlot) -> Unit,
    modifier: Modifier = Modifier,
    onMenu: (GuideChannel, GuideSlot) -> Unit = { _, _ -> },
    refocus: Int = 0,
    favorites: Set<String> = emptySet(),
) {
    val window = focus.windowStart
    // The favorites lead: the line goes under the last of them.
    val lastFavorite = remember(lineup, favorites) { lineup.channels.count { it.id in favorites } - 1 }
    // Bumped if focus lands on any cell but the focused one, so that one takes it back.
    var reclaim by remember { mutableIntStateOf(0) }
    Column(
        modifier
            .nowLine(window, nowMillis)
            .onPreviewKeyEvent { event ->
                val move = event.guideMove() ?: return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    val next = lineup.move(focus, move)
                    if (next != focus) onMove(next)
                }
                // At a limit too: focus never wanders off the grid by geometry.
                true
            }
            .focusRestorer()
            .focusGroup(),
    ) {
        Row(Modifier.fillMaxWidth().weight(GuideLayout.RULER)) {
            Box(Modifier.weight(GuideLayout.CHANNEL_COLUMN).fillMaxHeight().padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                Text(GuideTime.day(window, nowMillis, zone), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
            Spacer(Modifier.weight(GuideLayout.COLUMN_GAP))
            TimeRuler(window, zone, Modifier.weight(GuideLayout.TRACK).fillMaxHeight())
        }
        Spacer(Modifier.weight(GuideLayout.RULER_GAP))
        repeat(lineup.rows) { index ->
            if (index > 0) {
                val under = focus.topRow + index - 1 == lastFavorite && focus.topRow + index < lineup.channels.size
                FavoritesLine(under, Modifier.fillMaxWidth().weight(GuideLayout.ROW_GAP))
            }
            val channel = lineup.channels.getOrNull(focus.topRow + index)
            if (channel == null) {
                Spacer(Modifier.weight(GuideLayout.ROW))
            } else {
                key(channel.id) {
                    ChannelRow(
                        lineup = lineup,
                        channel = channel,
                        favorite = channel.id in favorites,
                        focus = focus,
                        nowMillis = nowMillis,
                        zone = zone,
                        lateNight = lateNight,
                        reclaim = reclaim + refocus,
                        onStray = { reclaim++ },
                        onSelect = onSelect,
                        onMenu = onMenu,
                        modifier = Modifier.fillMaxWidth().weight(GuideLayout.ROW),
                    )
                }
            }
        }
    }
}

/**
 * The guide's sizes in dp of the 960×540 canvas inside the safe area and screen care's
 * room, used as weights: the grid stays proportional to the screen, and the layout scale
 * grows only the text [U4].
 */
internal object GuideLayout {
    const val INFO = 152f
    const val INFO_GAP = 14f

    const val RULER = 22f
    const val RULER_GAP = 4f
    const val ROW = 42f
    const val ROW_GAP = 6f
    const val GRID = RULER + RULER_GAP + GuideLineup.ROWS * ROW + (GuideLineup.ROWS - 1) * ROW_GAP

    const val CHANNEL_COLUMN = 156f
    const val COLUMN_GAP = 8f
    const val TRACK = 684f

    /** Where the track starts, as a fraction of the grid's width. */
    const val TRACK_FROM = (CHANNEL_COLUMN + COLUMN_GAP) / (CHANNEL_COLUMN + COLUMN_GAP + TRACK)
}

private val CellShape = RoundedCornerShape(Tokens.TileRadius)
/** A channel's icon in the grid (I6), and at most this share of its row's height. */
private val MARK_SIZE = 32.dp
private const val MARK_SHARE = 0.77f
private val CellGap = 3.dp

/** The gap between two rows; under the last favorite, a 2 dp accent line across the grid (C4). */
@Composable
private fun FavoritesLine(shown: Boolean, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (shown) Box(Modifier.fillMaxWidth().height(2.dp).background(LocalAccent.current.copy(alpha = 0.6f)))
    }
}

/** The red now line over the track, from a dot on the ruler down through the rows, while now is in the window. */
private fun Modifier.nowLine(window: Long, nowMillis: Long) = drawWithContent {
    drawContent()
    val fraction = (nowMillis - window).toFloat() / GuideLineup.WINDOW_MILLIS
    if (fraction < 0f || fraction >= 1f) return@drawWithContent
    val trackLeft = size.width * GuideLayout.TRACK_FROM
    val x = trackLeft + (size.width - trackLeft) * fraction
    drawLine(NowLine, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
    drawCircle(NowLine, radius = 4.dp.toPx(), center = Offset(x, 4.dp.toPx()))
}

/** The spec's red (`bad`), for the now line. */
private val NowLine = Palette.Bad

private fun KeyEvent.guideMove(): GuideMove? = when (key) {
    Key.DirectionUp -> GuideMove.UP
    Key.DirectionDown -> GuideMove.DOWN
    Key.DirectionLeft -> GuideMove.LEFT
    Key.DirectionRight -> GuideMove.RIGHT
    else -> null
}

/** The window's half hours: "8:30 PM", "9:00", "9:30", each over a faint tick. */
@Composable
private fun TimeRuler(window: Long, zone: ZoneId, modifier: Modifier = Modifier) {
    val marks = (GuideLineup.WINDOW_MILLIS / GuideLineup.STEP_MILLIS).toInt()
    TimeTrack(
        modifier.drawBehind {
            repeat(marks) { k ->
                val x = size.width * k / marks
                drawLine(Color.White.copy(alpha = 0.16f), Offset(x, size.height * 0.2f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            }
        },
    ) {
        repeat(marks) { k ->
            val at = window + k * GuideLineup.STEP_MILLIS
            Box(
                Modifier.layoutId(TrackSpan(k.toFloat() / marks, (k + 1f) / marks)).padding(start = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = GuideTime.mark(at, if (k == 0) null else at - GuideLineup.STEP_MILLIS, zone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextDim,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    lineup: GuideLineup,
    channel: GuideChannel,
    favorite: Boolean,
    focus: GuideFocus,
    nowMillis: Long,
    zone: ZoneId,
    lateNight: Boolean,
    reclaim: Int,
    onStray: () -> Unit,
    onSelect: (GuideChannel, GuideSlot) -> Unit,
    onMenu: (GuideChannel, GuideSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val window = focus.windowStart
    val focusedRow = channel.id == focus.channelId
    Row(modifier) {
        ChannelLabel(channel, focusedRow, favorite, lateNight, Modifier.weight(GuideLayout.CHANNEL_COLUMN).fillMaxHeight())
        Spacer(Modifier.weight(GuideLayout.COLUMN_GAP))
        val slots = lineup.visible(channel, window)
        TimeTrack(Modifier.weight(GuideLayout.TRACK).fillMaxHeight()) {
            if (slots.isEmpty()) {
                Box(Modifier.layoutId(TrackSpan(0f, 1f)).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                    Text("Nothing scheduled", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, maxLines = 1)
                }
            }
            slots.forEach { slot ->
                key(slot.startMillis) {
                    ProgramCell(
                        channel = channel,
                        slot = slot,
                        window = window,
                        nowMillis = nowMillis,
                        zone = zone,
                        selected = focusedRow && slot.startMillis == focus.slotStart,
                        reclaim = reclaim,
                        onStray = onStray,
                        onClick = { onSelect(channel, slot) },
                        onMenu = { onMenu(channel, slot) },
                        modifier = Modifier.layoutId(TrackSpan.of(slot, window)),
                    )
                }
            }
        }
    }
}

/** Number, icon ([ChannelMark]) and name, a favorite's with a star before it. The focused program's row lights up here too. */
@Composable
private fun ChannelLabel(channel: GuideChannel, focused: Boolean, favorite: Boolean, lateNight: Boolean, modifier: Modifier = Modifier) = BoxWithConstraints(modifier) {
    // 32 dp at 100% (I6). A larger layout scale makes the rows shorter in dp, and the icon with them, so the name keeps its room.
    val mark = min(MARK_SIZE, maxHeight * MARK_SHARE)
    Row(
        Modifier
            .fillMaxSize()
            .clip(CellShape)
            .background(if (focused) Palette.Surface2 else Color.Transparent)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = channel.number.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = if (focused) LocalAccent.current else Palette.TextDim,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(22.dp),
        )
        ChannelMark(channel, mark, lateNight, Modifier.padding(start = 8.dp))
        if (favorite) Icon(AppIcons.Star, contentDescription = "Favorite", tint = LocalAccent.current, modifier = Modifier.padding(start = 8.dp).size(12.dp))
        BoxWithConstraints(Modifier.weight(1f).padding(start = if (favorite) 4.dp else 8.dp)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (maxHeight >= TWO_LINES) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One program. Text only: the info panel has the art. Focus is the state's: a cell takes
 * focus when it becomes [selected] (or [reclaim] changes), and any other cell that gets it
 * calls [onStray]. Holding OK or Menu is [onMenu].
 */
@Composable
private fun ProgramCell(
    channel: GuideChannel,
    slot: GuideSlot,
    window: Long,
    nowMillis: Long,
    zone: ZoneId,
    selected: Boolean,
    reclaim: Int,
    onStray: () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(selected, reclaim) {
        if (selected) runCatching { requester.requestFocus() }
    }
    val past = slot.endMillis <= nowMillis
    val container = when {
        slot.filler -> Color.Transparent
        past -> Color.White.copy(alpha = 0.04f)
        slot.startMillis <= nowMillis -> Palette.Surface2
        else -> Palette.Surface
    }
    Surface(
        onClick = onClick,
        onLongClick = onMenu,
        modifier = modifier
            .padding(end = CellGap)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused && !selected) onStray() }
            .onMenuKey(onMenu)
            .semantics { contentDescription = "${channel.name}, ${slot.title}, ${GuideTime.range(slot, zone)}" },
        shape = ClickableSurfaceDefaults.shape(CellShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = if (past || slot.filler) Palette.TextDim else Palette.Text,
            focusedContainerColor = Palette.Surface3,
            focusedContentColor = Palette.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            // Filler is drawn as an outline: a break between programs, honestly shown.
            border = if (slot.filler) Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), shape = CellShape) else Border.None,
            focusedBorder = Border(BorderStroke(2.5.dp, LocalAccent.current), shape = CellShape),
        ),
    ) {
        CellText(slot, window, zone)
    }
}

/** Title, then the episode, "Off air" or the time; one line when the row is short (115%), none on a sliver. */
@Composable
private fun CellText(slot: GuideSlot, window: Long, zone: ZoneId) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        if (maxWidth >= MIN_TEXT_WIDTH) {
            val twoLines = maxHeight >= TWO_LINES
            val second = when {
                slot.offAir != null -> "Off air"
                slot.filler -> null
                else -> slot.episode ?: GuideTime.range(slot, zone)
            }
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                if (slot.startMillis < window) Continues("‹", Modifier.padding(end = 4.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        text = slot.title,
                        style = if (slot.filler) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (second != null && twoLines) {
                        Text(
                            text = second,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (slot.offAir != null) Palette.Caution else LocalContentColor.current.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (slot.endMillis > window + GuideLineup.WINDOW_MILLIS) Continues("›", Modifier.padding(start = 4.dp))
            }
        }
    }
}

/** A program that runs on past the window's edge. */
@Composable
private fun Continues(arrow: String, modifier: Modifier = Modifier) {
    Text(arrow, style = MaterialTheme.typography.titleSmall, color = LocalContentColor.current.copy(alpha = 0.6f), modifier = modifier)
}

/** Room for a title and a second line; below it, cells and channel names keep to one line. */
private val TWO_LINES = 40.dp

/** Narrower than this (a two-minute trailer), a cell is a coloured sliver without text. */
private val MIN_TEXT_WIDTH = 20.dp

/** A cell's place on the track: [start] to [end] as fractions of the window. */
@Immutable
private data class TrackSpan(val start: Float, val end: Float) {
    companion object {
        fun of(slot: GuideSlot, window: Long): TrackSpan {
            val span = GuideLineup.WINDOW_MILLIS.toFloat()
            return TrackSpan(((slot.startMillis - window) / span).coerceIn(0f, 1f), ((slot.endMillis - window) / span).coerceIn(0f, 1f))
        }
    }
}

/** Lays children out across the window by their [TrackSpan], each the track's full height. */
@Composable
private fun TimeTrack(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placed = measurables.map { measurable ->
            val span = measurable.layoutId as? TrackSpan ?: TrackSpan(0f, 1f)
            val left = (span.start * width).roundToInt().coerceIn(0, width)
            val right = (span.end * width).roundToInt().coerceIn(left, width)
            measurable.measure(Constraints.fixed(right - left, height)) to left
        }
        layout(width, height) { placed.forEach { (placeable, x) -> placeable.place(x, 0) } }
    }
}
