package com.phonoodlesramen.actioncenter.ui.home.rows

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.phonoodlesramen.actioncenter.favorites.FavoriteOrder
import com.phonoodlesramen.actioncenter.home.ChannelButton
import com.phonoodlesramen.actioncenter.player.BackTo
import com.phonoodlesramen.actioncenter.ui.art.PlaceholderArt
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.QuickSheet
import com.phonoodlesramen.actioncenter.ui.home.SheetItem
import com.phonoodlesramen.actioncenter.ui.home.cardLift
import com.phonoodlesramen.actioncenter.ui.home.focusRing
import com.phonoodlesramen.actioncenter.ui.home.onMenuKey
import com.phonoodlesramen.actioncenter.ui.home.tileColors
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalCardSurface
import com.phonoodlesramen.actioncenter.ui.wallpaper.cardSurface
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.lastapp.TunedChannel
import com.phonoodlesramen.actioncenter.lastapp.YouTubeTv
import java.io.File

/**
 * Live (G7, plan Q4; was Channels): the Plex Guide's channels first, each with what's on
 * now ([LiveChannelCard], OK tunes in), then YouTube TV's networks (L8), whose buttons
 * tune YouTube TV through [HomeActions.openIntentUri] and show the network's banner from
 * the Icons folder, or its name on a tinted tile.
 *
 * The label is LIVE in every state: before the guide has channels the row is YouTube TV's
 * networks alone, which are live too, so the row keeps its name when the guide arrives.
 * It follows the home's minute tick ([clock]) and has no timer of its own. Draws nothing
 * without channels of either kind. Without guide channels it says nothing of them either:
 * the Guide card above says what they wait on ([com.phonoodlesramen.actioncenter.ui.guide.GuideCard]).
 *
 * On a kid's home, [backTo] (the guide player's saved spot, on a channel that's kid-safe for
 * them) is the row's first card ([BackToRowCard], P4): they have no Guide card to hold it.
 *
 * **Favorites** (Phase 13, C3): the profile's [favorites] come first, in its order, each with
 * a star, then the other channels by number. Hold OK on a favorite › Move picks it up
 * ([FavoriteMove]): Left and Right carry it among the favorites, the row scrolling with it,
 * and OK, Back or Menu put it down and save the order ([HomeActions.saveFavorites]).
 *
 * **Recent YouTube TV channels**: the YouTube TV channels you opened here last
 * ([recentTv], newest first, up to [YouTubeTv.RECENT]) come right after the favorites, or
 * first without any; the other channels and networks follow as before. Opening a network
 * goes through [HomeActions.watchNetwork], which remembers YouTube TV's.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveRow(
    channels: List<ChannelButton>,
    guide: GuideCardState,
    clock: LiveClock,
    lateNight: Boolean,
    actions: HomeActions,
    openSheet: (QuickSheet) -> Unit,
    modifier: Modifier = Modifier,
    backTo: BackTo? = null,
    /** The profile's favorite channels, in its order (C3); ones the row doesn't show are skipped. */
    favorites: List<String> = emptyList(),
    /** The YouTube TV channels opened here, newest first. */
    recentTv: List<TunedChannel> = emptyList(),
) {
    val focus = LocalLiveRowFocus.current ?: remember { LiveRowFocus() }
    val lineup = (guide as? GuideCardState.Ready)?.channels.orEmpty()
    var moving by remember { mutableStateOf<FavoriteMove?>(null) }
    val saved by rememberUpdatedState(favorites)
    // While a favorite is carried, and until the saved order shows, the row shows the carried order.
    val order = moving?.order ?: favorites
    val guideChannels = remember(lineup, order) { FavoriteOrder.ordered(lineup, order) }
    val favoriteIds = remember(order) { order.toSet() }
    val leading = remember(lineup, order) { FavoriteOrder.leading(lineup, order) }
    val (recentNetworks, otherNetworks) = remember(channels, recentTv) { YouTubeTv.recentFirst(channels, recentTv) }
    LaunchedEffect(favorites) {
        val current = moving
        if (current != null && current.placed && (current.saved || favorites == current.order)) moving = null
    }
    SideEffect {
        focus.keys = buildList {
            if (backTo != null) add(BACK_TO_KEY)
            guideChannels.take(leading).forEach { add(guideKey(it.id)) }
            recentNetworks.forEach { add(networkKey(it.id)) }
            guideChannels.drop(leading).forEach { add(guideKey(it.id)) }
            otherNetworks.forEach { add(networkKey(it.id)) }
        }
    }

    /** Puts the carried favorite down: saved where it is now, or left alone if it's back where it was. */
    fun putDown() {
        val current = moving?.takeIf { !it.placed } ?: return
        if (current.order == current.from) {
            moving = null
            return
        }
        moving = current.copy(placed = true)
        actions.saveFavorites(current.order) { kept ->
            // Turned down: the row shows the saved order again. Kept: it waits for the saved order to show.
            val placed = moving?.takeIf { it.placed && it.id == current.id } ?: return@saveFavorites
            moving = if (!kept || saved == placed.order) null else placed.copy(saved = true)
        }
    }

    if (channels.isEmpty() && guideChannels.isEmpty() && backTo == null) return
    // Guide channels have captions; with them, a network's card asks for the same room (CaptionRoom).
    val captioned = guideChannels.isNotEmpty() || backTo != null
    OtherAppsRowFrame(label = "Live", modifier = modifier, state = focus.list) {
        if (backTo != null) {
            item(key = BACK_TO_KEY) {
                val next = guideChannels.firstOrNull()?.let { focus.card(it.id) }
                BackToRowCard(backTo, clock.nowMillis, lateNight, actions, openSheet, self = focus.backTo, afterForget = next)
            }
        }
        // Keyed by kind as well: a guide channel and a network may share an id.
        fun LazyListScope.guide(shown: List<GuideChannel>, offset: Int) = itemsIndexed(shown, key = { _, channel -> guideKey(channel.id) }) { at, channel ->
            val index = at + offset
            val favorite = channel.id in favoriteIds
            val card = CardFavorite(
                favorite = favorite,
                movable = favorite && leading > 1,
                carried = moving?.let { !it.placed && it.id == channel.id } == true,
                position = index,
                onPickUp = { if (moving?.placed != false) moving = FavoriteMove(channel.id, order) },
                onStep = { step ->
                    val current = moving
                    if (current != null && !current.placed) {
                        FavoriteOrder.moved(current.order, lineup.mapTo(HashSet()) { it.id }, current.id, step)?.let { moving = current.copy(order = it) }
                    }
                },
                onPutDown = ::putDown,
            )
            LiveChannelCard(channel, clock, lateNight, actions, openSheet, self = focus.card(channel.id), favorite = card)
        }
        guide(guideChannels.take(leading), offset = 0)
        items(recentNetworks, key = { networkKey(it.id) }) { button -> ChannelCard(button, captioned, actions, openSheet) }
        guide(guideChannels.drop(leading), offset = leading)
        items(otherNetworks, key = { networkKey(it.id) }) { button -> ChannelCard(button, captioned, actions, openSheet) }
    }
}

private const val BACK_TO_KEY = "back-to"

private fun guideKey(channelId: String) = "guide:$channelId"

private fun networkKey(id: String) = "network:$id"

/**
 * The Live row's scroll position and its guide channels' cards, held by the home screen so
 * Back from a Live card's Full guide lands on that card, even scrolled out of the row
 * (G20 A), as [AppsRowFocus] does for All apps.
 */
@Stable
class LiveRowFocus {
    val list = LazyListState()
    private val cards = mutableMapOf<String, FocusRequester>()

    /** Every card's key, in the row's order: the kids' Back-to card, guide channels and networks. */
    internal var keys: List<String> = emptyList()

    /** The kids' Back-to card (P4). */
    val backTo = FocusRequester()

    /** [channelId]'s card. */
    fun card(channelId: String): FocusRequester = cards.getOrPut(channelId) { FocusRequester() }

    /**
     * Focuses [channelId]'s card: once the row is laid out (a home that has just come back
     * hasn't been yet), scrolled to the card if it's out of view. False if the channel has
     * left the row (turned off meanwhile) or its card can't take focus.
     */
    suspend fun focus(channelId: String): Boolean {
        withFrameNanos {}
        val index = keys.indexOf(guideKey(channelId)).takeIf { it >= 0 } ?: return false
        if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            list.scrollToItem(index)
            withFrameNanos {}
        }
        return runCatching { card(channelId).requestFocus() }.isSuccess
    }
}

/** The home screen provides one; elsewhere the row keeps its own. */
val LocalLiveRowFocus = staticCompositionLocalOf<LiveRowFocus?> { null }

/**
 * A favorite picked up to move (C3): the profile's favorites in their new order, and as they
 * were ([from]). [placed] once it's put down, and [saved] once it's kept, until the row's
 * favorites show the new order.
 */
@Immutable
internal data class FavoriteMove(
    val id: String,
    val order: List<String>,
    val from: List<String> = order,
    val placed: Boolean = false,
    val saved: Boolean = false,
)

/** What a Live card does with favorites (C2, C3). */
internal class CardFavorite(
    /** It's one of the profile's favorites: a star on its pill, and Remove from favorites. */
    val favorite: Boolean,
    /** Move is offered: it's a favorite, and the row has another. */
    val movable: Boolean,
    /** It's carried: Left and Right move it, OK, Back or Menu put it down, and nothing else is reachable. */
    val carried: Boolean,
    /** Its place in the row, so a carried or re-sorted card keeps itself in view. */
    val position: Int,
    val onPickUp: () -> Unit,
    val onStep: (Int) -> Unit,
    val onPutDown: () -> Unit,
) {
    companion object {
        val None = CardFavorite(favorite = false, movable = false, carried = false, position = 0, onPickUp = {}, onStep = {}, onPutDown = {})
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCard(button: ChannelButton, captioned: Boolean, actions: HomeActions, openSheet: (QuickSheet) -> Unit) {
    val self = remember(button.id) { FocusRequester() }
    val watch = { actions.watchNetwork(button) }
    val sheet = {
        openSheet(
            QuickSheet(
                title = button.name,
                returnTo = self,
                items = listOf(
                    SheetItem(AppIcons.Play, "Watch") { watch() },
                    SheetItem(AppIcons.Info, "Where this button comes from") { actions.say(source(button)) },
                ),
            ),
        )
    }
    val shape = RoundedCornerShape(Layout.TileRadius)
    Surface(
        onClick = watch,
        onLongClick = sheet,
        modifier = Modifier
            .rowCard(LocalRowMetrics.current)
            .then(if (captioned) Modifier.bringIntoViewResponder(rememberCaptionRoom()) else Modifier)
            .focusRequester(self)
            .onMenuKey(sheet)
            .semantics { contentDescription = button.name },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = tileColors(container = Color.Transparent),
        scale = cardLift,
        border = focusRing(shape),
    ) {
        ChannelArt(button, Modifier.fillMaxSize())
    }
}

/**
 * A banner from the Icons folder on its own; other art (a program still from YouTube
 * TV's row) with the name over it; otherwise, or until it loads, the name on a tile
 * tinted from the name, over the card surface (glass when Frosted glass is on, L4).
 */
@Composable
internal fun ChannelArt(button: ChannelButton, modifier: Modifier = Modifier) {
    val spec = remember(button.name) { PlaceholderArt.specFor(button.name) }
    val art = button.art
    // A banner is a file path; a program's art is a URI.
    val banner = art != null && "://" !in art
    var showing by remember(art) { mutableStateOf(false) }
    BoxWithConstraints(
        modifier
            .cardSurface(LocalCardSurface.current, RoundedCornerShape(Layout.TileRadius))
            .background(Brush.linearGradient(listOf(spec.from.copy(alpha = 0.85f), spec.to.copy(alpha = 0.85f)), Offset.Zero, Offset.Infinite)),
    ) {
        if (art != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(if (banner) File(art) else art)
                    .size(constraints.maxWidth, constraints.maxHeight)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { showing = true },
                onError = { showing = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
        when {
            showing && banner -> Unit
            showing -> {
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Palette.Scrim.copy(alpha = 0.85f))))
                ShownWhole(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(button.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            else -> ShownWhole(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 10.dp)) {
                Text(
                    text = button.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun source(button: ChannelButton) = when (button.source) {
    ChannelButton.Source.CHANNEL_LIST -> "${button.name} is in your channels.json. Settings › Channels & apps shows how to change the list."
    ChannelButton.Source.YOUTUBE_TV -> "${button.name} is from YouTube TV's own row. Settings › Channels & apps shows how to pick your own."
}
