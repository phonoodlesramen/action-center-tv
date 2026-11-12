package com.phonoodlesramen.actioncenter.ui.home

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.home.StripCard
import com.phonoodlesramen.actioncenter.ui.art.TitleArt
import com.phonoodlesramen.actioncenter.ui.home.rows.LiveClock
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.onWallpaper
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalCardSurface
import com.phonoodlesramen.actioncenter.ui.wallpaper.cardSurface

private val CardShape = RoundedCornerShape(Layout.CardRadius)
private val BlockShape = RoundedCornerShape(6.dp)

/**
 * On Now's Row B: the pick's card, then Up Next. The mockup's 280 + 568 dp left Up Next's
 * captions no room at 115%, so it's the dashboard's two columns the other way round: Up Next
 * keeps its dashboard size.
 */
private const val PICK_WEIGHT = Layout.RIGHT_WEIGHT
private const val UP_NEXT_WEIGHT = Layout.LEFT_WEIGHT

/**
 * The On Now layout's first screen (spec §4, Home layouts, H5), on the dashboard's grid: the top
 * bar; a big tile back to the last channel ([live]: the guide player's spot while it holds, else
 * what's on your first favorite) and a four-channel mini guide; then Tonight's Pick and Up Next.
 * It redraws on the home's minute tick, never per frame. [lead] is a moment's lead (H3), which
 * takes the big tile's place.
 */
@Composable
internal fun OnNow(
    state: HomeUiState,
    guide: GuideCardState.Ready,
    favorites: List<String>,
    live: StripCard?,
    actions: HomeActions,
    tile: FocusRequester,
    lateNight: Boolean,
    openSheet: (QuickSheet) -> Unit,
    topBar: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    lead: (@Composable (Modifier) -> Unit)? = null,
) {
    val clock = LiveClock(state.now, state.nowMillis)
    Column(modifier) {
        topBar(Modifier.fillMaxWidth().weight(Layout.TOP_BAR_WEIGHT))
        Spacer(Modifier.weight(Layout.GAP_WEIGHT))
        Row(Modifier.fillMaxWidth().weight(Layout.ROW_A_WEIGHT)) {
            val big = Modifier.weight(Layout.LEFT_WEIGHT).fillMaxSize()
            when {
                lead != null -> lead(big)
                live != null -> LiveTile(live, clock, lateNight, actions, openSheet, tile, big)
                else -> PlaceholderCard("Nothing's on your guide channels right now", big)
            }
            Spacer(Modifier.weight(Layout.GAP_WEIGHT))
            MiniGuide(guide, favorites, clock, actions, Modifier.weight(Layout.RIGHT_WEIGHT).fillMaxSize())
        }
        Spacer(Modifier.weight(Layout.GAP_WEIGHT))
        Row(Modifier.fillMaxWidth().weight(Layout.ROW_B_WEIGHT)) {
            state.pick?.let { PickCard(it, lateNight, actions, Modifier.weight(PICK_WEIGHT).fillMaxSize()) }
            Spacer(Modifier.weight(Layout.GAP_WEIGHT))
            UpNextRow(
                items = state.content?.upNext,
                lateNight = lateNight,
                actions = actions,
                openSheet = openSheet,
                afterHide = tile,
                modifier = Modifier.weight(UP_NEXT_WEIGHT).fillMaxSize(),
            )
        }
    }
}

/**
 * The big tile: the program's art, its channel's badge, "Back to 2 Sci-Fi" or "On now · 5 Comedy",
 * the title, its time and what's left, and a bar for how far through. OK resumes the spot or tunes
 * in; hold OK or Menu for the rest.
 */
@Composable
private fun LiveTile(
    card: StripCard,
    clock: LiveClock,
    lateNight: Boolean,
    actions: HomeActions,
    openSheet: (QuickSheet) -> Unit,
    self: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val (channel, slot) = when (card) {
        is StripCard.Paused -> card.backTo.channel to card.backTo.slot
        is StripCard.Live -> card.channel to card.slot
        else -> return
    }
    val play = { if (card is StripCard.Paused) actions.resume(card.backTo.spot) else actions.tuneIn(channel.id) }
    val sheet = {
        openSheet(
            QuickSheet(
                title = slot.title,
                returnTo = self,
                items = listOfNotNull(
                    SheetItem(AppIcons.Play, if (card is StripCard.Paused) "Resume" else "Watch live") { play() },
                    if (card is StripCard.Paused) SheetItem(AppIcons.Tv, "Back to live") { actions.backToLive(channel.id) } else null,
                    SheetItem(AppIcons.Guide, "Full guide") { actions.openGuide(channel.id) },
                    if (card is StripCard.Paused) SheetItem(AppIcons.Hide, "Forget this") { actions.forgetSpot() } else null,
                ),
            ),
        )
    }
    Surface(
        onClick = play,
        onLongClick = sheet,
        modifier = modifier
            .focusRequester(self)
            .onMenuKey(sheet)
            .semantics { contentDescription = "${card.eyebrow}: ${slot.title}" },
        shape = ClickableSurfaceDefaults.shape(CardShape),
        colors = tileColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = focusRing(CardShape),
    ) {
        TitleArt(card.artId, card.image, lateNight, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(TileScrim))
        TileBadge(card, channel, Modifier.align(Alignment.TopStart).padding(14.dp))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Eyebrow(card.eyebrow)
            Text(slot.title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = listOfNotNull("${clock.timeOf(slot.startMillis)}–${clock.timeOf(slot.endMillis)}", leftOf(card, slot, clock)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextDim,
                maxLines = 1,
            )
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.22f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(card.progress ?: 0f).background(LocalAccent.current))
            }
        }
    }
}

/** "18 min left", or "Paused" for the spot. */
private fun leftOf(card: StripCard, slot: GuideSlot, clock: LiveClock): String =
    if (card is StripCard.Paused) "Paused" else "${slot.minutesLeftAt(clock.nowMillis)} min left"

/** Clear at the top, dark at the foot under the text. */
private val TileScrim = Brush.verticalGradient(
    0.25f to Color.Transparent,
    0.55f to Palette.Scrim.copy(alpha = 0.5f),
    1f to Palette.Scrim.copy(alpha = 0.92f),
)

/** "● Live · 2 Sci-Fi", or "Paused · 2 Sci-Fi". */
@Composable
private fun TileBadge(card: StripCard, channel: GuideChannel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.background(Palette.Scrim.copy(alpha = 0.78f), RoundedCornerShape(9.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).background(LIVE_RED, CircleShape))
        Text("${card.badge.text} · ${channel.number} ${channel.name}", style = MaterialTheme.typography.labelSmall, color = LIVE_RED, maxLines = 1)
    }
}

private val LIVE_RED = Color(0xFFFF8A80)

/** Row B's pick: its art, "Tonight's pick", the title, its reason, and Play. */
@Composable
private fun PickCard(pick: com.phonoodlesramen.actioncenter.content.Pick, lateNight: Boolean, actions: HomeActions, modifier: Modifier = Modifier) {
    Box(modifier.clip(CardShape).background(Palette.Surface)) {
        TitleArt(pick.id, pick.image, lateNight, Modifier.fillMaxSize())
        Box(Modifier.matchParentSize().background(PickScrim))
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Eyebrow("Tonight's pick")
            Text(pick.title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pick.reason, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            PillButton(pick.playLabel, onClick = { actions.play(pick.id, pick.title) }, icon = AppIcons.Play, small = true)
        }
    }
}

private val PickScrim = Brush.horizontalGradient(
    0f to Palette.Scrim.copy(alpha = 0.92f),
    0.6f to Palette.Scrim.copy(alpha = 0.6f),
    1f to Palette.Scrim.copy(alpha = 0.3f),
)

/** The mini guide's window: from the half hour now is in, [MiniGuideWindow.MINUTES] long. Pure, so it's tested. */
internal data class MiniGuideWindow(val startMillis: Long) {
    val endMillis: Long get() = startMillis + MINUTES * MINUTE

    /** Where [millis] falls across the window, 0 to 1. */
    fun fraction(millis: Long): Float = ((millis - startMillis).toFloat() / (endMillis - startMillis)).coerceIn(0f, 1f)

    companion object {
        const val MINUTES = 90
        private const val MINUTE = 60_000L

        /** The window [clock] is in: it rolls over on the hour and the half hour. */
        fun at(clock: LiveClock): MiniGuideWindow =
            MiniGuideWindow(clock.nowMillis - ((clock.now.minute % 30) * 60L + clock.now.second) * 1_000L - clock.now.nano / 1_000_000L)
    }
}

/** The mini guide's four channels: your favorites first, in your order, then the lineup's. */
internal fun miniGuideChannels(guide: GuideCardState.Ready, favorites: List<String>): List<GuideChannel> {
    val byId = guide.channels.associateBy { it.id }
    return (favorites.mapNotNull(byId::get) + guide.channels.filter { it.id !in favorites }).take(MINI_GUIDE_CHANNELS)
}

private const val MINI_GUIDE_CHANNELS = 4

/** The narrowest program the mini guide draws. */
private val MIN_BLOCK = 16.dp

/** The channels' column: number, name and ★ favorite. */
private val CHANNEL_COLUMN = 64.dp

/**
 * A home-sized guide (H5): four channels over 90 minutes from the half hour, the program on now
 * lit, and the now line, which moves on the minute tick. Up and Down change channel, Right goes
 * later. OK on what's on tunes in; on what's later, its details.
 */
@Composable
private fun MiniGuide(guide: GuideCardState.Ready, favorites: List<String>, clock: LiveClock, actions: HomeActions, modifier: Modifier = Modifier) {
    val window = MiniGuideWindow.at(clock)
    val channels = remember(guide, favorites) { miniGuideChannels(guide, favorites) }
    Column(modifier.cardSurface(LocalCardSurface.current, CardShape).padding(horizontal = 12.dp, vertical = 10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(20.dp)) {
            val span = maxWidth - CHANNEL_COLUMN
            Eyebrow("On now")
            (0 until MiniGuideWindow.MINUTES step 30).forEach { minutes ->
                Text(
                    text = clock.timeOf(window.startMillis + minutes * 60_000L),
                    style = MaterialTheme.typography.labelSmall.onWallpaper(),
                    color = Palette.TextDim,
                    modifier = Modifier.offset(x = CHANNEL_COLUMN + span * (minutes / MiniGuideWindow.MINUTES.toFloat())),
                )
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
            val span = maxWidth - CHANNEL_COLUMN
            Column(Modifier.fillMaxSize().focusRestorer().focusGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                channels.forEach { channel ->
                    ChannelRow(channel, channel.id in favorites, window, span, clock, actions, Modifier.weight(1f).fillMaxWidth())
                }
            }
            // The now line, over the rows; it moves once a minute, with the home's clock.
            Box(
                Modifier
                    .zIndex(1f)
                    .offset(x = CHANNEL_COLUMN + span * window.fraction(clock.nowMillis) - 1.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(LocalAccent.current),
            )
        }
    }
}

@Composable
private fun ChannelRow(
    channel: GuideChannel,
    favorite: Boolean,
    window: MiniGuideWindow,
    span: Dp,
    clock: LiveClock,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Column(Modifier.width(CHANNEL_COLUMN).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text(channel.number.toString(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1)
            Text(
                text = if (favorite) "★ ${channel.name}" else channel.name,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = if (favorite) LocalAccent.current else Palette.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.offset(x = CHANNEL_COLUMN).width(span).fillMaxHeight().clipToBounds()) {
            // Programs only: filler (trailers, the Up next card) is a gap here, as it's nothing to tune to.
            channel.slots.filter { !it.filler && it.endMillis > window.startMillis && it.startMillis < window.endMillis }.forEach { slot ->
                val from = window.fraction(slot.startMillis)
                val to = window.fraction(slot.endMillis)
                // A sliver at the window's edge is too small to see focus on: it's left out.
                if (span * (to - from) < MIN_BLOCK) return@forEach
                ProgramBlock(
                    slot = slot,
                    description = "${slot.title}, ${channel.number} ${channel.name}, ${clock.timeOf(slot.startMillis)}",
                    on = clock.nowMillis >= slot.startMillis && clock.nowMillis < slot.endMillis,
                    onClick = {
                        if (clock.nowMillis >= slot.startMillis && clock.nowMillis < slot.endMillis) actions.tuneIn(channel.id) else actions.openDetails(channel.id, slot.startMillis)
                    },
                    modifier = Modifier.offset(x = span * from).width((span * (to - from) - 3.dp).coerceAtLeast(4.dp)).fillMaxHeight(),
                )
            }
        }
    }
}

/** A program in the mini guide: lit while it's on; focus fills it with the accent. */
@Composable
private fun ProgramBlock(slot: GuideSlot, description: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(BlockShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (on) Palette.Surface3 else Palette.Surface2,
            contentColor = if (on && !slot.filler) Palette.Text else Palette.TextDim,
            focusedContainerColor = LocalAccent.current,
            focusedContentColor = Palette.AccentInk,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(
            text = slot.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp),
        )
    }
}
