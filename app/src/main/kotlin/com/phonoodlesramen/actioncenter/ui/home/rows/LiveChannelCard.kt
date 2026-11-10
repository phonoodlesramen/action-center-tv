package com.phonoodlesramen.actioncenter.ui.home.rows

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.ui.art.TitleArt
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.QuickSheet
import com.phonoodlesramen.actioncenter.ui.home.SheetItem
import com.phonoodlesramen.actioncenter.ui.home.cardLift
import com.phonoodlesramen.actioncenter.ui.home.focusRing
import com.phonoodlesramen.actioncenter.ui.home.onMenuKey
import com.phonoodlesramen.actioncenter.ui.home.tileColors
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.onWallpaper
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalFocusedArt
import com.phonoodlesramen.actioncenter.ui.wallpaper.reportsArt
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The home's minute tick, for the Live row and the Guide card: [nowMillis] is [now] on
 * the home's own clock, so a slot reads as that clock's time ("9:30") with no zone of its
 * own. Samples and tests run on UTC, the Shield on its own zone.
 */
@Immutable
data class LiveClock(val now: LocalDateTime, val nowMillis: Long) {
    /** [millis] on the home's clock, "9:30", as Quick Watch's "fits before 10:30". */
    fun timeOf(millis: Long): String = now.plus(millis - nowMillis, ChronoUnit.MILLIS).format(CLOCK)

    private companion object {
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm")
    }
}

/** What a guide channel's card shows at a time. Pure, so it's tested without Compose. */
internal sealed interface LiveNow {
    /** A program is on: how far in, and the whole minutes left. */
    data class OnNow(val slot: GuideSlot, val progress: Float, val minutesLeft: Int) : LiveNow

    /** Filler is on (a trailer or the Up next card, G3), or a gap: the next program and when it starts. */
    data class UpNext(val slot: GuideSlot) : LiveNow

    /**
     * Nothing can play: the slot's file is gone ([why], from the scheduler) or the schedule
     * has run out. [backMillis] is when the next program that can play starts.
     */
    data class OffAir(val why: String?, val backMillis: Long?) : LiveNow

    /** The program the card is about, for its art. */
    val program: GuideSlot? get() = when (this) {
        is OnNow -> slot
        is UpNext -> slot
        is OffAir -> null
    }

    /** The caption's first line. */
    val title: String get() = program?.title ?: "Off air"

    /** The caption's second line: "41m left · S3 E1 · Thaw", "Up next at 9:30", "Back at 2:30". */
    fun detail(clock: LiveClock): String = when (this) {
        is OnNow -> listOfNotNull(timeLeft(minutesLeft), slot.episode).joinToString(" · ")
        is UpNext -> listOfNotNull("Up next at ${clock.timeOf(slot.startMillis)}", slot.episode).joinToString(" · ")
        is OffAir -> backMillis?.let { "Back at ${clock.timeOf(it)}" } ?: "Nothing scheduled yet"
    }

    companion object {
        /** What's on [channel] at [millis]. */
        fun of(channel: GuideChannel, millis: Long): LiveNow {
            val slot = channel.slotAt(millis)
            val next by lazy { channel.slots.firstOrNull { it.startMillis > millis && it.plays } }
            return when {
                slot != null && slot.offAir != null -> OffAir(slot.offAir, next?.startMillis)
                slot != null && !slot.filler -> OnNow(slot, slot.progressAt(millis), slot.minutesLeftAt(millis))
                else -> next?.let(::UpNext) ?: OffAir(why = null, backMillis = null)
            }
        }

        /** A program, not filler, whose file is there. */
        private val GuideSlot.plays get() = !filler && offAir == null
    }
}

/** "41m left", or "1h 44m left" from an hour up: Up Next's time left with the hero's runtimes. */
internal fun timeLeft(minutes: Int): String = when {
    minutes < 60 -> "${minutes}m left"
    minutes % 60 == 0 -> "${minutes / 60}h left"
    else -> "${minutes / 60}h ${minutes % 60}m left"
}

/**
 * A Plex Guide channel in the Live row (plan §6): the program's art at the card's size
 * with the channel's number and name on it and a progress bar, then the title and time
 * left under it. During filler it shows the next program as "Up next at 9:30"; off air,
 * when the channel is back. OK tunes in ([HomeActions.tuneIn]); hold OK or Menu for Tune
 * in and the full guide, which opens on this channel (G20 A).
 *
 * **Favorites** (Phase 13, C2, C3): a favorite has a star before its number. Hold OK adds it
 * to the profile's favorites or removes it, and on a favorite among others, Move picks it up.
 * While it's [CardFavorite.carried] it keeps focus and the D-pad: its caption's second line
 * says "Left or right, then OK" in the accent, Left and Right move it, and OK, Back or Menu
 * put it down.
 *
 * @param self the card's focus, so Back from its full guide can land on it ([LiveRowFocus]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiveChannelCard(
    channel: GuideChannel,
    clock: LiveClock,
    lateNight: Boolean,
    actions: HomeActions,
    openSheet: (QuickSheet) -> Unit,
    self: FocusRequester = remember(channel.id) { FocusRequester() },
    favorite: CardFavorite = CardFavorite.None,
) {
    val now = remember(channel, clock.nowMillis) { LiveNow.of(channel, clock.nowMillis) }
    val tuneIn = { actions.tuneIn(channel.id) }
    val sheet = {
        openSheet(
            QuickSheet(
                title = channel.name,
                returnTo = self,
                items = listOfNotNull(
                    SheetItem(AppIcons.Play, "Tune in") { tuneIn() },
                    SheetItem(AppIcons.Guide, "Full guide") { actions.openGuide(channel.id) },
                    if (favorite.favorite) {
                        SheetItem(AppIcons.Star, "Remove from favorites") { actions.setFavorite(channel, false) }
                    } else {
                        SheetItem(AppIcons.Star, "Add to favorites") { actions.setFavorite(channel, true) }
                    },
                    SheetItem(AppIcons.Move, "Move") { favorite.onPickUp() }.takeIf { favorite.movable },
                    (now as? LiveNow.OffAir)?.why?.let { why -> SheetItem(AppIcons.Info, "Why it's off air") { actions.say(why) } },
                ),
            ),
        )
    }
    val shape = RoundedCornerShape(Layout.TileRadius)
    val program = now.program
    val detail = now.detail(clock)
    val metrics = LocalRowMetrics.current
    val bring = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    // OK went down while it was carried: its release puts it down.
    var pressed by remember { mutableStateOf(false) }
    // Carried, or re-sorted by Add or Remove: the row scrolls to keep it, and the card before it, in view.
    val withNeighbour = with(LocalDensity.current) { Rect(-(metrics.cardWidth + metrics.gap).toPx(), 0f, metrics.cardWidth.toPx(), metrics.cardHeight.toPx()) }
    LaunchedEffect(favorite.position) { if (focused) runCatching { bring.bringIntoView(withNeighbour) } }

    /** While it's carried, every key of the D-pad, OK, Back and Menu is the card's. */
    fun onCarryKey(event: KeyEvent): Boolean {
        if (!favorite.carried) return false
        val down = event.type == KeyEventType.KeyDown
        when (event.key) {
            Key.DirectionLeft -> if (down) favorite.onStep(-1)
            Key.DirectionRight -> if (down) favorite.onStep(1)
            Key.DirectionUp, Key.DirectionDown -> Unit
            Key.Back, Key.Escape, Key.Menu -> if (!down) favorite.onPutDown()
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> when {
                down -> if (event.nativeKeyEvent.repeatCount == 0) pressed = true
                pressed -> {
                    pressed = false
                    favorite.onPutDown()
                }
                // The release of a press made before it was picked up isn't OK.
                else -> Unit
            }
            else -> return false
        }
        return true
    }

    Column(Modifier.width(metrics.cardWidth)) {
        Surface(
            onClick = tuneIn,
            onLongClick = sheet,
            modifier = Modifier
                .rowCard(metrics)
                .bringIntoViewResponder(rememberCaptionRoom())
                .bringIntoViewRequester(bring)
                .reportsArt(LocalFocusedArt.current, program?.art)
                .focusRequester(self)
                .onFocusChanged {
                    focused = it.isFocused
                    // Focus taken away while it's carried (Home): it's put down where it is.
                    if (!it.isFocused && favorite.carried) favorite.onPutDown()
                }
                .onPreviewKeyEvent(::onCarryKey)
                .onMenuKey(sheet)
                .semantics { contentDescription = "${channel.name}, ${now.title}, $detail" },
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = tileColors(),
            scale = cardLift,
            border = focusRing(shape),
        ) {
            // Art at the card's own size (spec §6); the channel's generated art when off air.
            TitleArt(program?.itemKey ?: program?.title ?: channel.id, program?.art, lateNight, Modifier.fillMaxSize())
            if (now is LiveNow.OffAir) Box(Modifier.matchParentSize().background(Palette.Scrim.copy(alpha = 0.6f)))
            // Text: hidden while the row peeks at the screen's foot (TV-OV).
            ShownWhole(Modifier.align(Alignment.TopStart).padding(6.dp)) { ChannelBadge(channel, favorite.favorite, Modifier.widthIn(max = metrics.cardWidth - 12.dp)) }
            if (now is LiveNow.OnNow) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.22f)),
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(now.progress).background(LocalAccent.current))
                }
            }
        }
        ShownWhole {
            Column {
                Text(
                    text = now.title,
                    style = MaterialTheme.typography.titleSmall.onWallpaper(),
                    color = if (now is LiveNow.OffAir) Palette.TextDim else Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = CAPTION_GAP),
                )
                Text(
                    text = if (favorite.carried) "Left or right, then OK" else detail,
                    style = MaterialTheme.typography.bodySmall.onWallpaper(),
                    color = if (favorite.carried) LocalAccent.current else Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Turns a focused card's ask to be seen into an ask for its row's label above it and the
 * captions below it, so the home scrolls far enough to show the captions whole (TV-OV).
 * Every card in a captioned row asks for the same height, captioned or not, so moving
 * along the row never moves the home; on its own a card in the first row would ask for
 * less, and the home would scroll back to leave the captions under the screen's edge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Stable
internal class CaptionRoom(private val above: Float, private val below: Float) : BringIntoViewResponder {
    override fun calculateRectForParent(localRect: Rect) = Rect(localRect.left, localRect.top - above, localRect.right, localRect.bottom + below)

    // Nothing here scrolls: the row and the home do, from the rect above.
    override suspend fun bringChildIntoView(localRect: () -> Rect?) = Unit
}

/** A [CaptionRoom] at this screen's density, type scale and row metrics. */
@Composable
internal fun rememberCaptionRoom(): CaptionRoom {
    val type = MaterialTheme.typography
    val labelGap = LocalRowMetrics.current.labelGap
    val (above, below) = with(LocalDensity.current) {
        (labelGap + type.labelMedium.lineHeight.toDp()).toPx() to
            (CAPTION_GAP + type.titleSmall.lineHeight.toDp() + type.bodySmall.lineHeight.toDp()).toPx()
    }
    return remember(above, below) { CaptionRoom(above, below) }
}

/** The captions' gap under a card. */
private val CAPTION_GAP = 6.dp

/** The channel's number and name, like a station's bug, on a dark pill over the art; a favorite's with a star first. */
@Composable
private fun ChannelBadge(channel: GuideChannel, favorite: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (favorite) Icon(AppIcons.Star, contentDescription = "Favorite", tint = LocalAccent.current, modifier = Modifier.size(12.dp))
        Text(
            text = channel.number.toString(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = Palette.TextDim,
            maxLines = 1,
        )
        Text(
            text = channel.name,
            style = MaterialTheme.typography.labelMedium,
            color = Palette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
