package com.phonoodlesramen.actioncenter.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.handoff.PlayIn
import com.phonoodlesramen.actioncenter.player.CreditsCard
import com.phonoodlesramen.actioncenter.player.GuidePlayerState
import com.phonoodlesramen.actioncenter.player.OffAirReason
import com.phonoodlesramen.actioncenter.player.Overlay
import com.phonoodlesramen.actioncenter.player.PickUpCard
import com.phonoodlesramen.actioncenter.player.Picture
import com.phonoodlesramen.actioncenter.ui.art.TitleArt
import com.phonoodlesramen.actioncenter.ui.guide.ChannelMark
import com.phonoodlesramen.actioncenter.ui.guide.NoChannelsYet
import com.phonoodlesramen.actioncenter.ui.guide.ProgramDetails
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.QuickActionsSheet
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.WallpaperTextShadow
import com.phonoodlesramen.actioncenter.ui.theme.safeArea
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.ScreenCare
import java.time.Instant
import java.time.ZoneId

/**
 * The guide player's screen (G5, plan §3): the video full screen and black around it, and
 * over it the banner, the Up next card or the off-air card, and the 2.7 overlays. It draws
 * [state] and nothing else: the keys and the timing are [com.phonoodlesramen.actioncenter.player.GuideTuner]'s.
 *
 * - **The banner** (OK, Info, and every channel change): the channel's number and name, the
 *   program and episode, its times, progress and time left, then the clock and what's next.
 *   Behind live (G11) it says "Started over" or "Paused" and how far behind, and its bar
 *   shows the picture's place with the schedule's fainter behind it. OK adds the control
 *   row (P2, [ControlRow]); holding ⏪ or ⏩ puts the scrub's marker on the bar (P1).
 * - **The Up next card:** filler without a trailer, or a file that ended early: the next
 *   program's art, title and start, with a countdown.
 * - **The off-air card:** why nothing plays, in plain words ("This movie isn't available
 *   right now", "Can't reach Plex", "This one won't play on the Shield", "No channels
 *   yet"), whether it's trying again, and what's next. Never an error code.
 * - **Overlays:** the mini guide (G12), the options sheet and its Audio and Subtitles lists
 *   (G13), and the program's details. Back closes each.
 * - **The credits card** (Phase 11, K5): over an episode's credits, a small card at the top
 *   right, "Next: S2 E5 · Watch in Plezy", with the next episode's art and title. Nothing in
 *   it is focusable: OK picks it (the activity reads the key), Back takes it away.
 *
 * The cards hold still on an OLED for minutes at a time, so they drift a few dp each minute
 * ([ScreenCare]); the screen isn't held on while they show (G10).
 *
 * @param now the wall clock, read only by what's on screen (countdowns, time left).
 * @param video the picture and its subtitles: views on the Shield, nothing in screenshots.
 */
@Composable
fun GuidePlayerScreen(
    state: GuidePlayerState,
    now: () -> Long,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    actions: PlayerActions = PlayerActions.None,
    video: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
        video()
        when (val picture = state.picture) {
            Picture.Tuning -> Text(
                text = "Tuning in…",
                style = MaterialTheme.typography.titleMedium,
                color = Palette.TextDim,
                modifier = Modifier.align(Alignment.Center),
            )
            Picture.Video -> Unit
            Picture.UpNext -> CardScreen(now, zone) { UpNextCard(state.channel, state.next, now(), zone) }
            is Picture.OffAir -> CardScreen(now, zone) { OffAirCard(state, picture, now(), zone) }
        }
        val channel = state.channel
        val slide = with(LocalDensity.current) { OVERLAY_SLIDE.roundToPx() }
        // Back with the control row up takes the row away (P2), before Back can leave.
        BackHandler(enabled = state.showsControls, onBack = actions::hideControls)
        // Back on the pick-up card (P5) takes it away first, and the channel carries on live.
        val pickUp = state.pickUp?.takeIf { state.overlay == Overlay.None }
        BackHandler(enabled = pickUp != null, onBack = actions::dismissPickUp)
        AnimatedVisibility(
            visible = pickUp != null,
            enter = fadeIn(tween(OVERLAY_MILLIS)),
            exit = fadeOut(tween(OVERLAY_MILLIS)),
            modifier = Modifier.fillMaxSize(),
        ) {
            // The last card stays while it fades out.
            val shown = remember { mutableStateOf(pickUp) }
            if (pickUp != null) shown.value = pickUp
            shown.value?.let { PickUpCardView(it, now()) }
        }
        // Back on the credits card (K5) takes the card away first. The pick-up card, when it's up, has the corner.
        val card = state.creditsCard?.takeIf { state.overlay == Overlay.None && pickUp == null }
        BackHandler(enabled = card != null, onBack = actions::dismissCreditsCard)
        AnimatedVisibility(
            visible = card != null,
            enter = fadeIn(tween(OVERLAY_MILLIS)),
            exit = fadeOut(tween(OVERLAY_MILLIS)),
            modifier = Modifier.fillMaxSize(),
        ) {
            // The last card stays while it fades out.
            val shown = remember { mutableStateOf(card) }
            if (card != null) shown.value = card
            shown.value?.let { CreditsCardView(it, actions.playIn()) }
        }
        AnimatedVisibility(
            visible = state.banner && channel != null,
            enter = fadeIn(tween(OVERLAY_MILLIS)) + slideInVertically(tween(OVERLAY_MILLIS)) { slide },
            exit = fadeOut(tween(OVERLAY_MILLIS)),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (channel != null) Banner(state, channel, now(), zone, actions)
        }
        Overlays(state, now, zone, actions)
    }
}

/** What's open over the picture (2.7): one at a time, each closed by Back. */
@Composable
private fun Overlays(state: GuidePlayerState, now: () -> Long, zone: ZoneId, actions: PlayerActions) {
    when (val overlay = state.overlay) {
        Overlay.None -> Unit
        is Overlay.MiniGuide -> {
            BackHandler(onBack = actions::closeOverlay)
            MiniGuide(state.miniGuide, overlay.focusedId, state.channel?.id, now(), zone, favorites = state.favorites.toSet())
        }
        Overlay.Options -> {
            // The same sheet while it's open, so a state change doesn't send focus back to its first row.
            val shape = listOf(
                state.slot?.let(actions::keepWatching)?.label,
                state.canStartOver, state.behind != null, state.tracks.audio.isNotEmpty(), state.tracks.subtitles.isNotEmpty(), state.detailsSlot != null,
                state.favorite,
            )
            val sheet = remember(state.slot?.title, state.channel?.id, shape) { optionsSheet(state, actions) }
            QuickActionsSheet(sheet, onClose = { actions.closeOverlay() })
        }
        is Overlay.Tracks -> TrackSheet(
            kind = overlay.kind,
            program = state.slot?.title.orEmpty(),
            menu = state.tracks,
            onChoose = actions::chooseTrack,
            onClose = actions::closeOverlay,
        )
        Overlay.Details -> DetailsOverlay(state, now, actions)
    }
}

/**
 * The options' Details: [ProgramDetails] over the dimmed picture, which plays on. Watch
 * isn't offered (it's on); Start over is, for the program on screen, and Watch in Plezy (or
 * Plex), which does what the options' first row does (Phase 11).
 */
@Composable
private fun DetailsOverlay(state: GuidePlayerState, now: () -> Long, actions: PlayerActions) {
    val channel = state.channel ?: return
    val slot = state.detailsSlot ?: return
    val keepWatching = actions.keepWatching(slot)
    val focus = remember { FocusRequester() }
    BackHandler(onBack = actions::closeOverlay)
    // Into the details' own buttons: a focus request on a group goes to its first child.
    LaunchedEffect(slot) { runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Background.copy(alpha = DETAILS_SCRIM))
            .safeArea()
            .focusRequester(focus)
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        ProgramDetails(
            channel = channel,
            slot = slot,
            nowMillis = now(),
            onWatch = null,
            onStartOver = if (slot == state.slot && state.canStartOver) ({ actions.startOver() }) else null,
            onOpenIn = keepWatching?.run,
            openInLabel = keepWatching?.label.orEmpty(),
        )
    }
}

/**
 * The credits card (Phase 11, K5): at the top right inside the safe area, clear of the
 * banner, so the credits play on around it. The next episode's art, "NEXT: S2 E5" in the
 * accent, its title, and "OK · Watch in Plezy". It goes after 15 s by itself.
 */
@Composable
private fun CreditsCardView(card: CreditsCard, app: PlayIn) {
    Box(Modifier.fillMaxSize().safeArea(), contentAlignment = Alignment.TopEnd) {
        Row(
            modifier = Modifier
                .width(CREDITS_CARD_WIDTH)
                .clip(CardShape)
                .background(Palette.Surface.copy(alpha = 0.94f))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TitleArt(
                id = card.next.ratingKey,
                image = card.next.art,
                lateNight = false,
                modifier = Modifier.size(CREDITS_ART_WIDTH, CREDITS_ART_WIDTH * 9 / 16).clip(RoundedCornerShape(Layout.TileRadius)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Eyebrow("Next: ${card.next.label}", color = LocalAccent.current)
                Text(card.next.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "OK",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Palette.AccentInk,
                        modifier = Modifier.background(LocalAccent.current, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                    Text("Watch in ${app.label}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * The pick-up card (P5): where the credits card goes, at the top right inside the safe area,
 * while the channel tunes in live. The program's art, "PICK UP WHERE YOU LEFT OFF" in the
 * accent, its title, where and when you left it ([PickUpText]), and "OK · Pick up". It goes
 * after 15 s by itself.
 */
@Composable
private fun PickUpCardView(card: PickUpCard, nowMillis: Long) {
    val slot = card.place.slot
    Box(Modifier.fillMaxSize().safeArea(), contentAlignment = Alignment.TopEnd) {
        Row(
            modifier = Modifier
                .width(CREDITS_CARD_WIDTH)
                .clip(CardShape)
                .background(Palette.Surface.copy(alpha = 0.94f))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TitleArt(
                id = slot.showKey ?: slot.itemKey ?: slot.title,
                image = slot.art,
                lateNight = false,
                modifier = Modifier.size(CREDITS_ART_WIDTH, CREDITS_ART_WIDTH * 9 / 16).clip(RoundedCornerShape(Layout.TileRadius)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Eyebrow("Pick up where you left off", color = LocalAccent.current)
                Text(slot.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = PickUpText.line(card.place, nowMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "OK",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Palette.AccentInk,
                        modifier = Modifier.background(LocalAccent.current, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                    Text("Pick up", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}

/** A card's screen: black over the video, the card a little above the middle so the banner fits under it. */
@Composable
private fun CardScreen(now: () -> Long, zone: ZoneId, card: @Composable () -> Unit) {
    val minute = Instant.ofEpochMilli(now()).atZone(zone).let { it.hour * 60 + it.minute }
    val (x, y) = ScreenCare.drift(minute)
    val drift = with(LocalDensity.current) { IntOffset(x.dp.roundToPx(), y.dp.roundToPx()) }
    Box(Modifier.fillMaxSize().background(Color.Black).safeArea(), contentAlignment = Alignment.Center) {
        Box(Modifier.offset(y = CARD_LIFT).offset { drift }) { card() }
    }
}

@Composable
private fun UpNextCard(channel: GuideChannel?, next: GuideSlot?, now: Long, zone: ZoneId) {
    Row(
        modifier = Modifier
            .width(UP_NEXT_WIDTH)
            .clip(CardShape)
            .background(Palette.Surface)
            .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (next != null) {
            TitleArt(
                id = next.showKey ?: next.itemKey ?: next.title,
                image = next.art,
                lateNight = false,
                modifier = Modifier.size(ART_WIDTH, ART_WIDTH * 9 / 16).clip(RoundedCornerShape(Layout.TileRadius)),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eyebrow(listOfNotNull("Up next", channel?.let { "${it.number} ${it.name}" }).joinToString(" · "), color = LocalAccent.current)
            if (next == null) {
                Text("More soon", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                Text("This channel's schedule is being filled in.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
            } else {
                Text(
                    text = next.title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                PlayerText.meta(next)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    Text(PlayerText.clock(next.startMillis, zone), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "in ${PlayerText.countdown(next.startMillis - now)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = LocalAccent.current,
                    )
                }
            }
        }
    }
}

@Composable
private fun OffAirCard(state: GuidePlayerState, picture: Picture.OffAir, now: Long, zone: ZoneId) {
    val reason = picture.reason
    val channel = state.channel
    Column(
        modifier = Modifier
            .width(OFF_AIR_WIDTH)
            .clip(CardShape)
            .background(Palette.Surface)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val eyebrow = if (reason == OffAirReason.NO_CHANNELS) "Plex Guide" else listOfNotNull("Off air", channel?.let { "${it.number} ${it.name}" }).joinToString(" · ")
        Eyebrow(eyebrow, color = if (reason == OffAirReason.NO_CHANNELS) Palette.TextDim else Palette.Caution)
        Text(
            text = PlayerText.headline(reason, picture.detail, state.slot),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // What should be on, so the card says what's missing.
        state.slot?.takeIf { reason != OffAirReason.NO_CHANNELS }?.let { slot ->
            Text(
                text = listOfNotNull(slot.title, slot.episode).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The player only opens with Plex linked, so an empty lineup waits on the library, not on Plex.
        val waitingOn = state.lineupState?.let { NoChannelsYet.of(it, plexLinked = true) }
        // No error code here: it's in the log (`player: CODE`), for the operator.
        Text(PlayerText.explanation(reason, waitingOn), style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
        val next = state.next
        if (next != null && reason != OffAirReason.NO_CHANNELS) {
            Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
            Eyebrow("Next at ${PlayerText.clock(next.startMillis, zone)} · in ${PlayerText.countdown(next.startMillis - now)}")
            Text(
                text = listOfNotNull(next.title, next.episode).joinToString(" · "),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The banner over the bottom of the picture, on a scrim dark enough for the text at
 * 4.5:1 over any frame (spec §6); the dimmer lines also get the wallpaper's halo. With
 * the control row (P2) the scrim reaches higher, to keep the text above the row on it.
 * While ⏪ or ⏩ is held (P1), the eyebrow gives the speed and where it'll land, the bar has
 * the marker, and beside it is the marker's place in the program ("9:00 in").
 */
@Composable
private fun Banner(state: GuidePlayerState, channel: GuideChannel, now: Long, zone: ZoneId, actions: PlayerActions) {
    val slot = state.slot
    val next = state.next
    val behind = state.behind
    val controls = state.showsControls
    val scrub = state.scrub?.takeIf { state.canSeek }
    val dim = TextStyle(color = Palette.TextDim, shadow = WallpaperTextShadow)
    Box(Modifier.fillMaxSize()) {
        // The scrim runs to the screen's edges; the text stays inside the safe area.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(if (controls) SCRIM_HEIGHT_CONTROLS else SCRIM_HEIGHT)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.4f to Palette.Scrim.copy(alpha = 0.92f),
                        0.6f to Palette.Scrim.copy(alpha = 0.97f),
                        1f to Palette.Scrim.copy(alpha = 0.97f),
                    ),
                ),
        )
        Row(
            modifier = Modifier.fillMaxSize().safeArea().wrapContentHeight(Alignment.Bottom),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.width(CHANNEL_WIDTH)) {
                ChannelMark(channel, CHANNEL_ICON, lateNight = false, Modifier.padding(bottom = 6.dp))
                Text(
                    text = channel.number.toString(),
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    color = LocalAccent.current,
                    maxLines = 1,
                )
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodyMedium.merge(dim),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when {
                    slot == null -> {
                        Eyebrow("Nothing on", color = Palette.Caution)
                        BannerTitle("Nothing scheduled right now")
                    }
                    else -> {
                        when {
                            scrub != null -> Eyebrow(PlayerText.scrubEyebrow(scrub, slot, now), color = LocalAccent.current)
                            behind != null -> Eyebrow(PlayerText.behindEyebrow(behind, now), color = LocalAccent.current)
                            slot.offAir != null -> Eyebrow("Off air", color = Palette.Caution)
                            else -> Eyebrow("On now")
                        }
                        BannerTitle(slot.title)
                        PlayerText.meta(slot)?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium.merge(dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(
                            modifier = Modifier.padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(PlayerText.timeRange(slot.startMillis, slot.endMillis, zone), style = MaterialTheme.typography.bodySmall)
                            val fraction = behind?.progressAt(now) ?: slot.progressAt(now)
                            val live = if (behind != null) slot.progressAt(now) else null
                            if (scrub != null) {
                                val length = (slot.endMillis - slot.startMillis).coerceAtLeast(1)
                                MarkedProgress(fraction, scrub.targetMs.toFloat() / length, Modifier.weight(1f), live = live)
                                Text("${PlayerText.position(scrub.targetMs)} in", style = MaterialTheme.typography.bodySmall)
                            } else {
                                Progress(fraction, Modifier.weight(1f), live = live)
                                Text(behind?.let { PlayerText.left(it, now) } ?: PlayerText.left(slot, now), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (controls) ControlRow(state, actions, Modifier.padding(top = 12.dp))
                    }
                }
            }
            Column(Modifier.width(NEXT_WIDTH), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(PlayerText.clock(now, zone), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                if (next != null) {
                    Eyebrow("Next · ${PlayerText.clock(next.startMillis, zone)}")
                    Text(next.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                    next.episode?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall.merge(dim), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}

@Composable
private fun BannerTitle(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)

/**
 * The program's progress, as the home's cards draw it: an accent bar on a faint track.
 * Behind live, [live] is how far the schedule has got, drawn fainter under the picture's place.
 */
@Composable
private fun Progress(fraction: Float, modifier: Modifier = Modifier, live: Float? = null) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.22f))) {
        if (live != null) Box(Modifier.fillMaxHeight().fillMaxWidth(live.coerceIn(0f, 1f)).background(Color.White.copy(alpha = 0.4f)))
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(LocalAccent.current))
    }
}

private val CardShape = RoundedCornerShape(Layout.CardRadius)

private val SCRIM_HEIGHT = 240.dp

/** The control row adds a 36 dp row of buttons under the bar: the scrim's dark part reaches over the eyebrow above it. */
private val SCRIM_HEIGHT_CONTROLS = 300.dp
private val CHANNEL_WIDTH = 96.dp

/** The channel's icon over its number (I5). */
private val CHANNEL_ICON = 40.dp
private val NEXT_WIDTH = 220.dp
private val UP_NEXT_WIDTH = 600.dp
private val OFF_AIR_WIDTH = 520.dp
private val ART_WIDTH = 208.dp

/** The credits card (K5): small, so the credits show around it. */
private val CREDITS_CARD_WIDTH = 340.dp
private val CREDITS_ART_WIDTH = 96.dp

/** Cards sit this far above the middle, clear of the banner. */
private val CARD_LIFT = (-40).dp

/** Spec §7: overlays slide 40 dp and fade over 200 ms. */
private const val OVERLAY_MILLIS = 200
private val OVERLAY_SLIDE = 40.dp

/** The details cover the picture, which plays on faintly behind them. */
private const val DETAILS_SCRIM = 0.94f
