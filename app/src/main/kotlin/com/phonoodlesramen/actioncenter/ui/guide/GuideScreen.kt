package com.phonoodlesramen.actioncenter.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.favorites.FavoriteOrder
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.handoff.PlayIn
import com.phonoodlesramen.actioncenter.guide.KidGuide
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.QuickActionsSheet
import com.phonoodlesramen.actioncenter.ui.home.QuickSheet
import com.phonoodlesramen.actioncenter.ui.home.SheetItem
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.IdlePolicy
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.IdleTakeover
import java.time.ZoneId

/**
 * The full guide (Phase 2.6, Q3, plan §5): an info panel for the focused program over a
 * grid of channels down the side and time across, 90 minutes at a time over the next 24
 * hours, with a red now line ([GuideGrid]). It reads only the cache, so it opens at once,
 * offline too.
 *
 * - **Opens** at [openAtMillis] (now, normally), on [focusChannelId] or the first channel.
 * - **OK** on something on now tunes in ([HomeActions.tuneIn]); on a later program, it
 *   opens the program's details ([HomeActions.openDetails], 2.7).
 * - **Hold OK or Menu** on any program: quick actions ([programSheet]): Watch and Start
 *   over while it's on, Details, and Open in Plezy.
 * - **Back** calls [onBack]: HomeActivity returns to the card that opened the guide (the
 *   Guide card, or a Live card's Full guide). Home is HomeActivity's, as everywhere (U10).
 * - **Screen care** (G10): after the idle minutes the guide dims and drifts a few dp each
 *   minute, on Now showing's idle rule ([IdlePolicy], [IdleTakeover]). The first key only
 *   wakes it.
 * - **No channels yet:** what they wait on ([NoChannelsYet]).
 * - **For a kid** (K8): only the channels whose rule stays inside their [ceiling] ([KidGuide]).
 * - **Favorites** (Phase 13, C4): the profile's [favorites] first, in its order, each with a
 *   star and a 2 dp accent line under the last; then the rest by number. Hold OK on a program
 *   adds its channel to them, or removes it.
 *
 * @param state the lineup and its slots, from the cache; never waits on the network.
 * @param ceiling a kid's ratings ([com.phonoodlesramen.actioncenter.profiles.ActiveProfile.ceiling]); null, an adult's, shows every channel.
 * @param nowMillis the home's minute tick: the now line and what's on now move with it.
 * @param openAtMillis where the window opens; null is now.
 * @param plexLinked whether a server is linked: before the library syncs, the channels wait on it or on Plex.
 * @param idleMinutes the idle time before screen care starts; null never starts it.
 * @param lastInputMillis when the remote was last pressed (wall clock).
 */
@Composable
fun GuideScreen(
    state: GuideCardState,
    nowMillis: Long,
    actions: HomeActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    focusChannelId: String? = null,
    openAtMillis: Long? = null,
    lateNight: Boolean = false,
    plexLinked: Boolean = true,
    idleMinutes: Int? = null,
    lastInputMillis: () -> Long = { 0L },
    care: GuideScreenCare = remember { GuideScreenCare() },
    ceiling: Set<String>? = null,
    /** Settings › Plex › Play in (Phase 11): the quick actions' Open in Plezy, or Open in Plex. */
    playIn: PlayIn = PlayIn.PLEZY,
    /** The profile's favorite channels, in its order (Phase 13, C4). */
    favorites: List<String> = emptyList(),
) {
    BackHandler(onBack = onBack)
    val shown = remember(state, ceiling) { KidGuide.of(state, ceiling) }
    val lineup = (shown as? GuideCardState.Ready)?.channels.orEmpty()
    val channels = remember(lineup, favorites) { FavoriteOrder.ordered(lineup, favorites) }
    val favoriteIds = remember(favorites) { favorites.toSet() }
    var sheet by remember { mutableStateOf<QuickSheet?>(null) }
    // Bumped as the sheet closes, so the focused program takes focus back.
    var refocus by remember { mutableIntStateOf(0) }
    GuideCaredScreen(
        care = care,
        idleMinutes = idleMinutes,
        lastInputMillis = lastInputMillis,
        nowMillis = nowMillis,
        zone = zone,
        modifier = modifier,
        overlay = {
            sheet?.let { open ->
                QuickActionsSheet(open) {
                    sheet = null
                    refocus++
                }
            }
        },
    ) { area ->
        if (channels.isEmpty()) {
            NoChannels(NoChannelsYet.of(shown, plexLinked) ?: NoChannelsYet.NOT_SYNCED, area)
        } else {
            Guide(
                channels = channels,
                nowMillis = nowMillis,
                zone = zone,
                focusChannelId = focusChannelId,
                openAtMillis = openAtMillis,
                lateNight = lateNight,
                actions = actions,
                onMenu = { channel, slot -> sheet = programSheet(channel, slot, nowMillis, actions, playIn, favorite = channel.id in favoriteIds) },
                refocus = refocus,
                favorites = favoriteIds,
                modifier = area,
            )
        }
    }
}

/**
 * A program's quick actions in the full guide (hold OK or Menu): Watch and Start over while
 * it's on, its Details, Open in Plezy (or Plex, by [playIn]) for a program with a file
 * ([ProgramActions]), and Add {channel} to favorites, or Remove (Phase 13, C2): five at most.
 */
internal fun programSheet(
    channel: GuideChannel,
    slot: GuideSlot,
    nowMillis: Long,
    actions: HomeActions,
    playIn: PlayIn = PlayIn.PLEZY,
    favorite: Boolean = false,
): QuickSheet {
    val can = ProgramActions.of(slot, nowMillis)
    return QuickSheet(
        title = slot.title,
        items = listOfNotNull(
            if (can.watch) SheetItem(AppIcons.Play, "Watch") { actions.tuneIn(channel.id) } else null,
            if (can.startOver) SheetItem(AppIcons.StartOver, "Start over") { actions.startOver(channel.id) } else null,
            SheetItem(AppIcons.Info, "Details") { actions.openDetails(channel.id, slot.startMillis) },
            can.plezyKey?.let { key -> SheetItem(AppIcons.OpenIn, "Open in ${playIn.label}") { actions.openIn(key, slot.title) } },
            SheetItem(AppIcons.Star, favoriteLabel(channel.name, favorite)) { actions.setFavorite(channel, !favorite) },
        ),
    )
}

/** "Add Sci-Fi to favorites", or "Remove Sci-Fi from favorites": the guide's and the player's row (C2). */
fun favoriteLabel(channel: String, favorite: Boolean): String = if (favorite) "Remove $channel from favorites" else "Add $channel to favorites"

@Composable
private fun Guide(
    channels: List<GuideChannel>,
    nowMillis: Long,
    zone: ZoneId,
    focusChannelId: String?,
    openAtMillis: Long?,
    lateNight: Boolean,
    actions: HomeActions,
    onMenu: (GuideChannel, GuideSlot) -> Unit,
    refocus: Int,
    favorites: Set<String>,
    modifier: Modifier = Modifier,
) {
    val lineup = remember(channels, nowMillis, zone) { GuideLineup(channels, nowMillis, zone) }
    var focus by remember { mutableStateOf(lineup.open(openAtMillis ?: nowMillis, focusChannelId)) }
    val shown = lineup.resolve(focus)
    val focused = lineup.focused(shown)
    val fallback = rememberFallbackFocus(enabled = focused == null)
    Column(modifier.focusRequester(fallback).focusable(enabled = focused == null)) {
        GuideInfo(
            channel = focused?.first,
            slot = focused?.second,
            nowMillis = nowMillis,
            zone = zone,
            lateNight = lateNight,
            modifier = Modifier.fillMaxWidth().weight(GuideLayout.INFO),
        )
        Spacer(Modifier.weight(GuideLayout.INFO_GAP))
        GuideGrid(
            lineup = lineup,
            focus = shown,
            nowMillis = nowMillis,
            zone = zone,
            lateNight = lateNight,
            onMove = { focus = it },
            onSelect = { channel, slot ->
                when {
                    nowMillis < slot.startMillis -> actions.openDetails(channel.id, slot.startMillis)
                    nowMillis < slot.endMillis -> actions.tuneIn(channel.id)
                    // Over already: the info panel says when it ended, and holding OK still has its details.
                }
            },
            onMenu = onMenu,
            refocus = refocus,
            favorites = favorites,
            modifier = Modifier.fillMaxWidth().weight(GuideLayout.GRID),
        )
    }
}

/** No lineup yet: what it waits on, calmly, and Back still goes home. */
@Composable
private fun NoChannels(why: NoChannelsYet, modifier: Modifier = Modifier) {
    val fallback = rememberFallbackFocus(enabled = true)
    Column(
        modifier.focusRequester(fallback).focusable(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Eyebrow("Plex Guide")
        Text(
            text = why.guide,
            style = MaterialTheme.typography.titleMedium,
            color = Palette.TextDim,
            textAlign = TextAlign.Center,
        )
    }
}
