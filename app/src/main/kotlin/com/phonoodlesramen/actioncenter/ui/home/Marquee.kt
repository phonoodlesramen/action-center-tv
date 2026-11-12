package com.phonoodlesramen.actioncenter.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.phonoodlesramen.actioncenter.content.LensPick
import com.phonoodlesramen.actioncenter.content.Pick
import com.phonoodlesramen.actioncenter.home.BadgeTone
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.home.StripBadge
import com.phonoodlesramen.actioncenter.home.StripCard
import com.phonoodlesramen.actioncenter.home.TonightStrip
import com.phonoodlesramen.actioncenter.nfl.GameState
import com.phonoodlesramen.actioncenter.nfl.WatchOptions
import com.phonoodlesramen.actioncenter.ui.art.LocalArtUrls
import com.phonoodlesramen.actioncenter.ui.art.LogoArt
import com.phonoodlesramen.actioncenter.ui.art.TitleArt
import com.phonoodlesramen.actioncenter.ui.art.placeholderArt
import com.phonoodlesramen.actioncenter.ui.art.rememberLogoBox
import com.phonoodlesramen.actioncenter.ui.nfl.NflColors
import com.phonoodlesramen.actioncenter.ui.nfl.NflOpen
import com.phonoodlesramen.actioncenter.ui.nfl.NflPage
import com.phonoodlesramen.actioncenter.ui.nfl.NflTab
import com.phonoodlesramen.actioncenter.ui.nfl.cardSheet
import com.phonoodlesramen.actioncenter.ui.nfl.matchup
import com.phonoodlesramen.actioncenter.ui.nfl.waysFor
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout as Grid
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.onWallpaper
import kotlinx.coroutines.delay

/**
 * Marquee's focus targets: the strip's first card (Play's place, U10), its live card (the
 * Guide card's place, for Back from the guide and the player), and the top bar's.
 */
internal class MarqueeFocus(
    val first: FocusRequester,
    val live: FocusRequester,
    val gear: FocusRequester,
    val avatar: FocusRequester,
    val search: FocusRequester,
    /** The context chip, while a moment makes it focusable (H3). */
    val chip: FocusRequester? = null,
)

/**
 * Which strip card Marquee shows (its backdrop and title), which has focus, and whether focus
 * is in the strip. The home holds it, since the backdrop is drawn behind the scrolling home.
 */
@Stable
internal class MarqueeState {
    var focusedKey by mutableStateOf<String?>(null)
    var shownKey by mutableStateOf<String?>(null)
    var browsing by mutableStateOf(false)

    /** The card resting focus last chose, or the strip's first. */
    fun shown(strip: List<StripCard>): StripCard = strip.firstOrNull { it.key == shownKey } ?: strip.first()
}

/**
 * The Marquee layout's first screen (spec §4, Marquee): the title, its reason and buttons, and
 * the "For tonight" strip, on the dashboard's grid: the top bar, the title where Row A is, the
 * strip where Row B is, so the rows below peek in the same place. The art of the card focus
 * rests on is [MarqueeBackdrop], behind the whole home.
 *
 * - Focus starts on the strip. Up reaches the title's buttons, Down the rows below.
 * - Resting [REST_MILLIS] on a card shows it; while focus is in the strip the top bar fades
 *   back to the clock.
 */
@Composable
internal fun Marquee(
    state: HomeUiState,
    strip: List<StripCard>,
    marquee: MarqueeState,
    actions: HomeActions,
    focus: MarqueeFocus,
    lateNight: Boolean,
    openSheet: (QuickSheet) -> Unit,
    modifier: Modifier = Modifier,
    /** Where Watch can go (F6, F7), for game day's card. */
    watch: WatchOptions = WatchOptions(),
    /** The context chip's quick actions while a moment leads (H3). */
    onChip: (() -> Unit)? = null,
    /** The top bar's round buttons; with the side rail (H4) they're in the rail. */
    circles: Boolean = true,
) {
    // The backdrop waits for focus to rest, so passing over a card decodes nothing.
    LaunchedEffect(marquee.focusedKey) {
        val key = marquee.focusedKey ?: return@LaunchedEffect
        if (key == marquee.shown(strip).key) return@LaunchedEffect
        delay(REST_MILLIS)
        marquee.shownKey = key
    }
    val shown = marquee.shown(strip)
    Column(modifier) {
        TopBar(
            state = state,
            actions = actions,
            gear = focus.gear,
            modifier = Modifier.fillMaxWidth().weight(Grid.TOP_BAR_WEIGHT).focusRestorer().focusGroup(),
            avatar = focus.avatar,
            search = focus.search,
            quiet = marquee.browsing,
            onChip = onChip,
            chip = focus.chip,
            circles = circles,
        )
        Spacer(Modifier.weight(Grid.GAP_WEIGHT))
        MarqueeTitle(shown, state, actions, watch, lateNight, openSheet, Modifier.fillMaxWidth().weight(Grid.ROW_A_WEIGHT))
        Spacer(Modifier.weight(Grid.GAP_WEIGHT))
        StripRow(
            strip = strip,
            focus = focus,
            actions = actions,
            watch = watch,
            lateNight = lateNight,
            openSheet = openSheet,
            onFocused = { marquee.focusedKey = it },
            onBrowsing = { marquee.browsing = it },
            modifier = Modifier.fillMaxWidth().weight(Grid.ROW_B_WEIGHT),
        )
    }
}

/** How long focus rests on a strip card before its backdrop comes in. */
internal const val REST_MILLIS = 200L

/** The backdrop's crossfade (the mockup's 400 ms). */
internal const val CROSSFADE_MILLIS = 400

/** The backdrop's decoded size: half the screen's pixels each way, a quarter of the memory. */
internal const val BACKDROP_WIDTH_PX = 960
internal const val BACKDROP_HEIGHT_PX = 540

/** The title's text column: 470 of the content's 864 dp (the mockup). */
private const val TEXT_WIDTH = 470f / 864f

/** A title logo (Phase 12) over the backdrop is up to this tall. */
private val MARQUEE_LOGO = 72.dp

/** The strip's gap between cards: six fill the content width. */
private val STRIP_GAP = 12.dp

private val TileShape = RoundedCornerShape(Grid.TileRadius)

/**
 * What a backdrop shows: a title's placeholder ([id]) and its Plex art; or, for a game, its two
 * teams' colours washing in from either side ([wash]).
 */
@Immutable
internal data class BackdropArt(val id: String, val image: String?, val wash: Pair<Color, Color>? = null)

/** A strip card's art: a game's team colours, else its title art. */
internal fun StripCard.backdrop(): BackdropArt = when (this) {
    is StripCard.Game -> BackdropArt(artId, null, NflColors.wash(view.team(game.away)) to NflColors.wash(view.team(game.home)))
    else -> BackdropArt(artId, image)
}

/** A game's wash: the away team's colour from the left, the home team's from the right, dark between (the mockup's). */
private fun Modifier.teamWash(wash: Pair<Color, Color>): Modifier = background(Palette.Background).background(
    Brush.horizontalGradient(
        0f to wash.first.copy(alpha = 0.55f),
        0.42f to wash.first.copy(alpha = 0.12f),
        0.58f to wash.second.copy(alpha = 0.12f),
        1f to wash.second.copy(alpha = 0.55f),
    ),
)

/**
 * The shown card's art over the whole screen, and its scrims: dark at the left for the title,
 * at the foot for the strip and the rows' peek, and a little at the top for the top bar. It's
 * decoded at 960×540 px ([BACKDROP_WIDTH_PX]), 2.1 MB, never at the screen's 1920×1080 (8.3 MB),
 * and its crossfade holds at most two ([BackdropCrossfade]).
 *
 * It stays put while the home scrolls, fading out as the rows come up ([faded], 0 to 1), so they
 * sit on the wallpaper as in every layout. Its alpha is applied draw by draw, never through an
 * offscreen copy of the screen.
 */
@Composable
internal fun MarqueeBackdrop(card: StripCard, lateNight: Boolean, faded: () -> Float, modifier: Modifier = Modifier) {
    Box(
        modifier.graphicsLayer {
            alpha = 1f - faded().coerceIn(0f, 1f)
            compositingStrategy = CompositingStrategy.ModulateAlpha
        },
    ) {
        BackdropCrossfade(card.backdrop(), lateNight, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(SideScrim))
        Box(Modifier.fillMaxSize().background(FootScrim))
        Box(Modifier.fillMaxSize().background(TopScrim))
    }
}

/** The mockup's scrim: 90% at the left edge, 55% at 42%, clear from 75%. */
private val SideScrim = Brush.horizontalGradient(
    0f to Palette.Scrim.copy(alpha = 0.90f),
    0.42f to Palette.Scrim.copy(alpha = 0.55f),
    0.75f to Color.Transparent,
)

/** Clear down to 44% of the screen, 86% by 76% and 97% at the foot: the strip and the rows' peek. */
private val FootScrim = Brush.verticalGradient(
    0.44f to Color.Transparent,
    0.76f to Palette.Scrim.copy(alpha = 0.86f),
    1f to Palette.Scrim.copy(alpha = 0.97f),
)

/** The top bar's labels on the right sit on art, so they get a scrim too (spec §6). */
private val TopScrim = Brush.verticalGradient(
    0f to Palette.Scrim.copy(alpha = 0.6f),
    0.2f to Color.Transparent,
)

/**
 * A crossfade that holds at most two backdrops: when a new one comes before the last fade is
 * done, whichever of the two shows more stays underneath, and the other is let go.
 */
@Composable
internal fun BackdropCrossfade(target: BackdropArt, lateNight: Boolean, modifier: Modifier = Modifier) {
    var front by remember { mutableStateOf(target) }
    var back by remember { mutableStateOf<BackdropArt?>(null) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == front) return@LaunchedEffect
        back = if (fade.value >= 0.5f) front else back
        front = target
        fade.snapTo(0f)
        fade.animateTo(1f, tween(CROSSFADE_MILLIS))
        back = null
    }
    Box(modifier) {
        listOfNotNull(back, front).forEach { art ->
            key(art) {
                val layer = if (art == front) {
                    Modifier.graphicsLayer {
                        alpha = fade.value
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                } else {
                    Modifier
                }
                BackdropLayer(art, lateNight, Modifier.fillMaxSize().then(layer))
            }
        }
    }
}

/**
 * One backdrop: the title's placeholder, then its art decoded at [BACKDROP_WIDTH_PX]×
 * [BACKDROP_HEIGHT_PX] and stretched to the screen. Late night darkens it, as it dims all art.
 */
@Composable
private fun BackdropLayer(art: BackdropArt, lateNight: Boolean, modifier: Modifier = Modifier) {
    Box(art.wash?.let { modifier.teamWash(it) } ?: modifier.placeholderArt(art.id)) {
        val url = art.image?.let { LocalArtUrls.current.url(it, BACKDROP_WIDTH_PX, BACKDROP_HEIGHT_PX) }
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .size(BACKDROP_WIDTH_PX, BACKDROP_HEIGHT_PX)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (lateNight) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = LATE_NIGHT_DIM)))
    }
}

/** Late night's art at 75% brightness (spec §2), as a veil over the whole backdrop. */
private const val LATE_NIGHT_DIM = 0.25f

/** The shown card's eyebrow, title (or logo), meta and reason, over its buttons. */
@Composable
private fun MarqueeTitle(
    card: StripCard,
    state: HomeUiState,
    actions: HomeActions,
    watch: WatchOptions,
    lateNight: Boolean,
    openSheet: (QuickSheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth(TEXT_WIDTH).clipToBounds(), contentAlignment = Alignment.BottomStart) {
            Crossfade(targetState = card, animationSpec = tween(HERO_SWAP_MILLIS), label = "marquee text") { shown ->
                MarqueeText(shown, lateNight)
            }
        }
        val pick = (card as? StripCard.Tonight)
        val undo = state.pickState.undo
        val note = if (pick != null && undo != null) "Skipping ${undo.skippedTitle} for 2 weeks" else state.heroNote.takeIf { pick != null }
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        }
        MarqueeButtons(card, state, actions, watch, openSheet, Modifier.padding(top = 14.dp))
    }
}

/** The hero swap's crossfade (spec §7). */
private const val HERO_SWAP_MILLIS = 250

@Composable
private fun MarqueeText(card: StripCard, lateNight: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Eyebrow(card.eyebrow)
        val style = (if (card.title.length > LONG_MARQUEE_TITLE) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium)
            .copy(fontWeight = FontWeight.Bold)
        val title = @Composable { Text(card.title, style = style.onWallpaper(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        val logo = card.logo
        if (logo == null) {
            Box(Modifier.padding(top = 4.dp, bottom = 2.dp)) { title() }
        } else {
            BoxWithConstraints(Modifier.padding(top = 4.dp, bottom = 2.dp).fillMaxWidth()) {
                val box = rememberLogoBox(logo, constraints.maxWidth, MARQUEE_LOGO, min = with(LocalDensity.current) { style.lineHeight.toDp() })
                if (box == null) {
                    title()
                } else {
                    LogoArt(
                        url = box.url,
                        widthPx = box.widthPx,
                        heightPx = box.heightPx,
                        description = card.title,
                        background = Palette.Scrim,
                        lateNight = lateNight,
                        modifier = Modifier.fillMaxWidth().height(box.height),
                        title = title,
                    )
                }
            }
        }
        Text(card.meta, style = MaterialTheme.typography.bodyMedium.onWallpaper(), color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        HeroReason(card.reason, MaterialTheme.typography.titleMedium.onWallpaper(), lines = 2)
    }
}

/** Past this many characters the title steps down a size. */
private const val LONG_MARQUEE_TITLE = 18

/**
 * The shown card's buttons: Play first, then what fits it (Trailer, Not tonight, Next, the
 * full guide). Down goes back to the strip card you left (its focus restorer).
 */
@Composable
private fun MarqueeButtons(
    card: StripCard,
    state: HomeUiState,
    actions: HomeActions,
    watch: WatchOptions,
    openSheet: (QuickSheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = remember { FocusRequester() }
    val first = Modifier.focusRequester(primary)
    Row(modifier.focusRestorer(primary).focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (card) {
            is StripCard.Tonight -> {
                val pick = card.pick
                val undo = state.pickState.undo
                PillButton(pick.playLabel, onClick = { actions.play(pick.id, pick.title) }, modifier = first.onMenuKey { openSheet(stripSheet(card, actions, watch, primary, primary)) }, icon = AppIcons.Play)
                PillButton("Trailer", onClick = { actions.trailer(pick) })
                PillButton(if (undo != null) "Undo" else "Not tonight", onClick = { if (undo != null) actions.undo() else actions.notTonight() })
            }
            is StripCard.LensTitle -> {
                val pick = card.state.pick
                PillButton(watchLabel(pick), onClick = { actions.play(pick.id, pick.title) }, modifier = first, icon = AppIcons.Play)
                PillButton("Trailer", onClick = { actions.trailer(pick.asPick()) })
                // Next moves the lens card on; the next lens's place follows it.
                if (card.key == StripCard.KEY_LENS) PillButton("Next", onClick = actions::nextLensPick)
            }
            is StripCard.UpNext -> PillButton("Resume", onClick = { actions.play(card.item.id, card.item.title) }, modifier = first, icon = AppIcons.Play)
            is StripCard.Quick -> PillButton("Play", onClick = { actions.play(card.item.id, card.item.title) }, modifier = first, icon = AppIcons.Play)
            is StripCard.Live -> {
                PillButton("Watch live", onClick = { actions.tuneIn(card.channel.id) }, modifier = first, icon = AppIcons.Play)
                PillButton("Full guide", onClick = { actions.openGuide(card.channel.id) })
            }
            is StripCard.Paused -> {
                PillButton("Resume", onClick = { actions.resume(card.backTo.spot) }, modifier = first, icon = AppIcons.Play)
                PillButton("Back to live", onClick = { actions.backToLive(card.backTo.channel.id) })
            }
            is StripCard.Game -> {
                // Watch in the first app on this TV that has it (F6, F7); the rest are in its quick actions.
                val way = waysFor(card.game, card.view, watch).firstOrNull { it.installed }.takeIf { card.game.state != GameState.FINAL }
                if (way != null) {
                    PillButton("Watch: ${way.label}", onClick = { actions.watch(way, matchup(card.game, card.view)) }, modifier = first, icon = AppIcons.Play)
                    PillButton("Game details", onClick = { primaryAction(card, actions) })
                } else {
                    PillButton("Game details", onClick = { primaryAction(card, actions) }, modifier = first)
                }
            }
        }
    }
}

/**
 * A lens title as a pick: for Trailer, whose search the year from its meta ("1995 · 2h 50m")
 * narrows, and for a moment's hero (H3), which plays it as Watch (again).
 */
internal fun LensPick.asPick() = Pick(
    id = id,
    title = title,
    meta = meta,
    reason = line,
    playLabel = watchLabel(this),
    image = image,
    year = meta.take(4).toIntOrNull(),
    titleKey = titleKey,
    logo = logo,
)

/** "For tonight" and its six cards. Focus coming back to the strip lands on the card it left. */
@Composable
private fun StripRow(
    strip: List<StripCard>,
    focus: MarqueeFocus,
    actions: HomeActions,
    watch: WatchOptions,
    lateNight: Boolean,
    openSheet: (QuickSheet) -> Unit,
    onFocused: (String) -> Unit,
    onBrowsing: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Eyebrow("For tonight")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .onFocusChanged { onBrowsing(it.hasFocus) }
                .focusRestorer(focus.first)
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(STRIP_GAP),
        ) {
            strip.forEachIndexed { index, card ->
                key(card.key) {
                    val self = remember { FocusRequester() }
                    var tile = Modifier.weight(1f).focusRequester(self)
                    if (index == 0) tile = tile.focusRequester(focus.first)
                    if (card.key == StripCard.KEY_LIVE) tile = tile.focusRequester(focus.live)
                    StripTile(
                        card = card,
                        lateNight = lateNight,
                        onClick = { primaryAction(card, actions) },
                        onMenu = { openSheet(stripSheet(card, actions, watch, self, focus.first)) },
                        onFocused = { onFocused(card.key) },
                        modifier = tile,
                    )
                }
            }
            repeat(TonightStrip.SIZE - strip.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** OK on a strip card: Play, Resume, or the channel live. */
internal fun primaryAction(card: StripCard, actions: HomeActions) = when (card) {
    is StripCard.Tonight -> actions.play(card.pick.id, card.pick.title)
    is StripCard.LensTitle -> actions.play(card.state.pick.id, card.state.pick.title)
    is StripCard.UpNext -> actions.play(card.item.id, card.item.title)
    is StripCard.Quick -> actions.play(card.item.id, card.item.title)
    is StripCard.Live -> actions.tuneIn(card.channel.id)
    is StripCard.Paused -> actions.resume(card.backTo.spot)
    is StripCard.Game -> actions.openNfl(NflOpen(NflPage.Tab(NflTab.GAMES, gameId = card.game.id), NflOpen.CARD))
}

/** A strip card's quick actions (U6): what its card on the dashboard offers. */
private fun stripSheet(card: StripCard, actions: HomeActions, watch: WatchOptions, self: FocusRequester, afterHide: FocusRequester): QuickSheet = when (card) {
    is StripCard.Game -> cardSheet(card.game, card.view, card.nowMillis, watch, actions, returnTo = self, afterNotToday = afterHide)
    is StripCard.Tonight -> QuickSheet(
        title = card.pick.title,
        returnTo = self,
        items = listOf(
            SheetItem(AppIcons.Play, "Play") { actions.play(card.pick.id, card.pick.title) },
            SheetItem(AppIcons.Info, "Why this pick?") { actions.say((listOf(card.pick.because ?: card.pick.reason) + card.pick.evidence).joinToString(" · ")) },
            SheetItem(AppIcons.Tv, "More like this") { actions.moreLikeThis() },
            SheetItem(AppIcons.Hide, "Not tonight") { actions.notTonight() },
        ),
    )
    is StripCard.LensTitle -> if (card.key == StripCard.KEY_LENS) {
        lensSheet(card.state.pick, actions, returnTo = self, watch = self)
    } else {
        QuickSheet(card.state.pick.title, listOf(SheetItem(AppIcons.Play, watchLabel(card.state.pick)) { actions.play(card.state.pick.id, card.state.pick.title) }), self)
    }
    is StripCard.UpNext -> upNextSheet(card.item, actions, self, afterHide)
    is StripCard.Quick -> QuickSheet(
        title = card.item.title,
        returnTo = self,
        items = listOf(
            SheetItem(AppIcons.Play, "Play") { actions.play(card.item.id, card.item.title) },
            SheetItem(AppIcons.Hide, "Hide from Quick Watch", returnTo = afterHide) { actions.hideFromQuickWatch(card.item) },
        ),
    )
    is StripCard.Live -> QuickSheet(
        title = card.slot.title,
        returnTo = self,
        items = listOf(
            SheetItem(AppIcons.Play, "Watch live") { actions.tuneIn(card.channel.id) },
            SheetItem(AppIcons.Tv, "Full guide") { actions.openGuide(card.channel.id) },
        ),
    )
    is StripCard.Paused -> QuickSheet(
        title = card.backTo.slot.title,
        returnTo = self,
        items = listOf(
            SheetItem(AppIcons.Play, "Resume") { actions.resume(card.backTo.spot) },
            SheetItem(AppIcons.Tv, "Back to live") { actions.backToLive(card.backTo.channel.id) },
            SheetItem(AppIcons.Hide, "Forget this", returnTo = afterHide) { actions.forgetSpot() },
        ),
    )
}

/** One strip card: its art with a badge and progress, then its title and caption below. */
@Composable
private fun StripTile(
    card: StripCard,
    lateNight: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Surface(
            onClick = onClick,
            onLongClick = onMenu,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onFocusChanged { if (it.isFocused) onFocused() }
                .onMenuKey(onMenu)
                .semantics { contentDescription = "${card.title}, ${card.caption}" },
            shape = ClickableSurfaceDefaults.shape(TileShape),
            colors = tileColors(),
            scale = cardLift,
            border = focusRing(TileShape),
        ) {
            val wash = card.backdrop().wash
            if (wash != null) Box(Modifier.fillMaxSize().teamWash(wash)) else TitleArt(card.artId, card.image, lateNight, Modifier.fillMaxSize())
            Badge(card.badge, Modifier.align(Alignment.TopStart).padding(6.dp))
            card.progress?.let { progress ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.22f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(LocalAccent.current))
                }
            }
        }
        Text(
            text = card.title,
            style = MaterialTheme.typography.titleSmall.onWallpaper(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(card.caption, style = MaterialTheme.typography.bodySmall.onWallpaper(), color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A strip card's badge: a dark pill, its text in the badge's tone; live has a red dot. */
@Composable
private fun Badge(badge: StripBadge, modifier: Modifier = Modifier) {
    val color = when (badge.tone) {
        BadgeTone.ACCENT -> LocalAccent.current
        BadgeTone.WARM -> Palette.WarmEyebrow
        BadgeTone.LIVE -> LiveRed
        BadgeTone.COOL -> CoolBlue
        BadgeTone.PLAIN -> Palette.Text
    }
    Row(
        modifier = modifier
            .background(Palette.Scrim.copy(alpha = 0.78f), RoundedCornerShape(9.dp))
            .padding(horizontal = 7.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (badge.tone == BadgeTone.LIVE) Box(Modifier.size(6.dp).background(LiveRed, CircleShape))
        Text(badge.text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** The badges' live red and cool blue (the mockup's). */
private val LiveRed = Color(0xFFFF8A80)
private val CoolBlue = Color(0xFF9EC1F2)
