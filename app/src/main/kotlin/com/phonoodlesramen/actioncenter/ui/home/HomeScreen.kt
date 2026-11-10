package com.phonoodlesramen.actioncenter.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.phonoodlesramen.actioncenter.context.ContextMode
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.KidGuide
import com.phonoodlesramen.actioncenter.home.HomeRowsState
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.nfl.NflGame
import com.phonoodlesramen.actioncenter.nfl.NflHome
import com.phonoodlesramen.actioncenter.nfl.NflView
import com.phonoodlesramen.actioncenter.nfl.WatchOptions
import com.phonoodlesramen.actioncenter.home.ChannelButton
import com.phonoodlesramen.actioncenter.lastapp.BackToTv
import com.phonoodlesramen.actioncenter.lastapp.TvState
import com.phonoodlesramen.actioncenter.lastapp.YouTubeTv
import com.phonoodlesramen.actioncenter.player.BackTo
import com.phonoodlesramen.actioncenter.player.SavedSpot
import com.phonoodlesramen.actioncenter.player.SpotRules
import com.phonoodlesramen.actioncenter.ui.apps.ArtPickerHost
import com.phonoodlesramen.actioncenter.ui.apps.ArtPickerState
import com.phonoodlesramen.actioncenter.ui.apps.LocalArtPicker
import com.phonoodlesramen.actioncenter.ui.guide.GuideCard
import com.phonoodlesramen.actioncenter.ui.home.rows.AppsRowFocus
import com.phonoodlesramen.actioncenter.ui.home.rows.HomeRows
import com.phonoodlesramen.actioncenter.ui.home.rows.LiveClock
import com.phonoodlesramen.actioncenter.ui.home.rows.LiveRowFocus
import com.phonoodlesramen.actioncenter.ui.home.rows.LocalAppsRowFocus
import com.phonoodlesramen.actioncenter.ui.home.rows.LocalLiveRowFocus
import com.phonoodlesramen.actioncenter.ui.home.rows.LocalNflRowFocus
import com.phonoodlesramen.actioncenter.ui.home.rows.LocalRowMetrics
import com.phonoodlesramen.actioncenter.ui.home.rows.NflRowFocus
import com.phonoodlesramen.actioncenter.ui.nfl.GameDayCard
import com.phonoodlesramen.actioncenter.ui.nfl.NflOpen
import com.phonoodlesramen.actioncenter.ui.home.rows.MarginBringIntoViewSpec
import com.phonoodlesramen.actioncenter.ui.home.rows.RowMetrics
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.wallpaper.FocusedArt
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalCardSurface
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalFocusedArt
import com.phonoodlesramen.actioncenter.ui.wallpaper.NowShowingOverlay
import com.phonoodlesramen.actioncenter.ui.wallpaper.NowShowingState
import com.phonoodlesramen.actioncenter.ui.wallpaper.WallpaperLayer
import com.phonoodlesramen.actioncenter.ui.wallpaper.WallpaperScene
import com.phonoodlesramen.actioncenter.ui.wallpaper.rememberCardSurface
import com.phonoodlesramen.actioncenter.ui.wallpaper.reportsArt
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperPrefs
import com.phonoodlesramen.actioncenter.ui.theme.LocalWallpaperTextShadow
import com.phonoodlesramen.actioncenter.ui.theme.WallpaperTextShadow
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperStyle
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Where focus lands when the home screen appears [U10]. */
enum class HomeFocus {
    /** Play in Tonight's Pick (Get a new code before Plex): on launch and on Home. */
    PLAY,

    /** Back from All apps: the All apps card at the end of the apps row. */
    ALL_APPS,

    /** Back from Settings. */
    SETTINGS,

    /** Back from Search (Phase 15): the top bar's magnifier. */
    SEARCH,

    /** Back from "Who's watching?" the avatar opened: the avatar (K1). */
    PROFILE,

    /** Back from the full guide the Guide card opened: the Plex Guide card. */
    GUIDE,

    /**
     * Back from the full guide a Live card's Full guide opened (G20 A): that card
     * ([FocusRequest.channelId]), or the Guide card if its channel has left the row.
     */
    LIVE,

    /**
     * Back from the NFL screen (Phase 13): the card that opened it ([FocusRequest.nflKey]): the
     * game-day card, or the NFL row's All games, division or game card. One that has gone
     * meanwhile lands on the row's first card, else Play.
     */
    NFL,
}

/**
 * A focus request. A new [serial] repeats it, e.g. Home pressed while already home.
 *
 * @param channelId the guide channel whose Live card [HomeFocus.LIVE] focuses.
 * @param nflKey the NFL card [HomeFocus.NFL] focuses ([com.phonoodlesramen.actioncenter.ui.nfl.NflOpen.opener]).
 */
data class FocusRequest(val target: HomeFocus = HomeFocus.PLAY, val serial: Int = 0, val channelId: String? = null, val nflKey: String? = null)

/**
 * Where Back from a player the Back-to card resumed lands (P4): the card it was. That's the
 * Guide card, back to its lineup since the player took the spot; for a [kid], whose card was
 * first in the Live row, the Live card of its channel ([channelId]) in the same place.
 */
fun backToCardFocus(kid: Boolean, channelId: String): FocusRequest =
    if (kid) FocusRequest(HomeFocus.LIVE, channelId = channelId) else FocusRequest(HomeFocus.GUIDE)

/**
 * The art-forward home screen (docs/design/ui-spec.md): top bar, Tonight's Pick,
 * the lens card, Quick Watch, Up Next and the Plex Guide, on the 7/12 + 5/12 grid. Every
 * size is a fraction of the screen, so the layout scale only grows the content [U4].
 *
 * Phase 2.5: the dashboard sits over the wallpaper (L1) with the first row below it
 * peeking into the bottom margin, and the rows (L10) scroll into view as focus moves
 * down ([homeScrollTarget]). Up to the dashboard scrolls back to the top.
 *
 * Phase 5: a kid's home (`state.profile.kid`, K7, U13) is [KidDashboard] on the kids' rows
 * (spec §1) and the Live row alone ([KidRows]), with only the kid-safe guide channels
 * ([KidGuide], K8). Yours is unchanged.
 *
 * Phase 7: while the guide player's saved [spot] holds against the lineup this profile sees
 * ([SpotRules], on the minute tick), the Guide card is "Back to {channel}" (P4); a kid gets
 * it as their Live row's first card instead, only on a channel that's kid-safe for them.
 * Focus still opens on Play (U10).
 *
 * Phase 13: the profile's [favorites] lead the Live row in their order, and the Guide card's
 * Coming up ([com.phonoodlesramen.actioncenter.ui.guide.ComingUp]). The NFL ([nfl]) has the
 * game-day card in Quick Watch's spot while a game is on ([NflHome.card]) and its row
 * ([NflHome.row]), never on a kid's home (F9).
 *
 * Back to YouTube TV: while YouTube TV is the last thing you watched ([tv], [YouTubeTv.backTo], on the
 * minute tick), the Guide card is "Back to YouTube TV", unless the guide player's spot is
 * newer; a kid's home never shows it. The YouTube TV channels opened here come after the
 * favorites in the Live row.
 *
 * @param rows Channels and Continue in your apps; empty rows draw nothing.
 * @param lastInputMillis when the remote was last pressed, for Now showing.
 */
// LocalBringIntoViewSpec is still experimental on Android (foundation 1.12).
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    focus: FocusRequest = FocusRequest(),
    rows: HomeRowsState = HomeRowsState(),
    wallpaper: WallpaperPrefs = WallpaperPrefs(),
    lastInputMillis: () -> Long = { 0L },
    /** The Plex Guide's lineup (Phase 2.6): the Live row and the Row B card. */
    guide: GuideCardState = GuideCardState.NotSetUp,
    /** The guide player's saved spot (P3), whether or not it still holds. */
    spot: SavedSpot? = null,
    /** The profile's favorite channels, in its order (Phase 13): first in the Live row, and on the Guide card. */
    favorites: List<String> = emptyList(),
    /** The NFL's copy (Phase 13): the game-day card and the NFL row. */
    nfl: NflView = NflView.None,
    /** Where Watch can go: the Live row's networks and the apps on this TV (F6, F7). */
    watch: WatchOptions = WatchOptions(),
    /** The last app you watched and the YouTube TV channels opened here. */
    tv: TvState = TvState(),
) {
    val play = remember { FocusRequester() }
    val nflCard = remember { FocusRequester() }
    val nflRowFocus = remember { NflRowFocus() }
    val gear = remember { FocusRequester() }
    val avatar = remember { FocusRequester() }
    val searchButton = remember { FocusRequester() }
    val guideCard = remember { FocusRequester() }
    val lensEntry = remember { FocusRequester() }
    val appsRow = remember { AppsRowFocus() }
    val liveRow = remember { LiveRowFocus() }
    val picker = remember { ArtPickerState() }
    var sheet by remember { mutableStateOf<QuickSheet?>(null) }
    var returnTo by remember { mutableStateOf<FocusRequester?>(null) }
    val openSheet: (QuickSheet) -> Unit = { sheet = it }
    val scroll = rememberScrollState()
    val focusedArt = remember { FocusedArt() }
    val nowShowing = remember { NowShowingState() }
    val kid = state.profile.kid
    val sideEntry = remember { FocusRequester() }
    // K8: a kid's home shows only the channels inside their ceiling.
    val shownGuide = remember(guide, kid, state.profile.ceiling) { if (kid) KidGuide.of(guide, state.profile.ceiling.orEmpty()) else guide }
    // P4: the spot, while it holds against the channels this profile sees, on the minute tick.
    val backTo = remember(spot, shownGuide, state.nowMillis) { SpotRules.backTo(spot, shownGuide, state.nowMillis) }
    // Back to YouTube TV on your home, on the minute tick; the newer of it and the spot has the Guide card.
    val backToTv = remember(tv, state.allApps, state.nowMillis, kid, backTo) {
        YouTubeTv.backTo(tv, state.allApps, state.nowMillis)?.takeIf { !kid && (backTo == null || it.visit.leftAt >= backTo.spot.savedAtMillis) }
    }
    val tvButton = remember(backToTv, rows.channels) { backToTv?.channel?.let { YouTubeTv.buttonFor(it, rows.channels) } }
    // F1, F9: the game-day card and the NFL row, on the minute tick and as scores move.
    val gameDay = remember(nfl, state.nowMillis, kid) { NflHome.card(nfl, state.nowMillis, kid) }
    val nflRow = remember(nfl, state.nowMillis, kid) { NflHome.row(nfl, state.nowMillis, kid) }

    LaunchedEffect(focus) {
        // Home and Back from another screen always land at the top [U10].
        if (focus.target == HomeFocus.PLAY) scroll.scrollTo(0)
        picker.dismiss()
        val live = focus.channelId?.takeIf { focus.target == HomeFocus.LIVE }
        // A kid's home has no Guide card (K7): what would land there lands on Play.
        val guideOrPlay = if (kid) play else guideCard
        val target = when (focus.target) {
            HomeFocus.PLAY -> play
            HomeFocus.ALL_APPS -> appsRow.allApps
            HomeFocus.SETTINGS -> gear
            HomeFocus.SEARCH -> searchButton
            HomeFocus.PROFILE -> avatar
            HomeFocus.GUIDE -> guideOrPlay
            HomeFocus.LIVE -> live?.let(liveRow::card) ?: guideOrPlay
            HomeFocus.NFL -> if (focus.nflKey == NflOpen.CARD) nflCard else play
        }
        val nflKey = focus.nflKey?.takeIf { focus.target == HomeFocus.NFL }
        // An open sheet keeps focus inside it: close it first, then move.
        when {
            sheet != null -> {
                returnTo = target
                sheet = null
            }
            // The All apps card may be scrolled out of its row, so it isn't there to focus yet.
            // A kid's is in My apps, always there.
            focus.target == HomeFocus.ALL_APPS && !kid -> appsRow.focusAllApps()
            // So may a Live card; and its channel may have gone meanwhile, leaving the Guide card.
            live != null -> if (!liveRow.focus(live)) runCatching { guideOrPlay.requestFocus() }
            // Back from the NFL screen: the card that opened it, or the row's first, or Play (the card may have gone).
            nflKey != null && nflKey != NflOpen.CARD -> if (!nflRowFocus.focus(nflKey) && !nflRowFocus.focus(NflOpen.ALL_GAMES)) runCatching { play.requestFocus() }
            nflKey == NflOpen.CARD -> runCatching { nflCard.requestFocus() }.onFailure { runCatching { play.requestFocus() } }
            else -> runCatching { target.requestFocus() }
        }
    }
    // Switching between your home and a kid's swaps every card: Play takes focus, at the top [U10].
    var shownKid by remember { mutableStateOf(kid) }
    LaunchedEffect(kid) {
        if (kid == shownKid) return@LaunchedEffect
        shownKid = kid
        scroll.scrollTo(0)
        runCatching { play.requestFocus() }
    }
    LaunchedEffect(sheet) {
        if (sheet == null) returnTo?.let { runCatching { it.requestFocus() } }
    }

    val lateNight = state.mode == ContextMode.LATE_NIGHT
    // Phase 14 (L1, L5): the lens card moves on, and counts what it shows, only with nothing over the home.
    val lensMotion = LensMotion(
        rotateSeconds = state.lensPrefs.rotateSeconds,
        shuffling = state.lensPrefs.shuffle,
        covered = sheet != null || nowShowing.showing || picker.app != null,
    )
    val scene = WallpaperScene(
        prefs = wallpaper,
        accent = state.accent,
        lateNight = lateNight,
        pickId = state.pick?.id,
        pickTitle = state.pick?.title,
        pickArt = state.pick?.image,
        weather = state.weather,
        now = state.now,
    )
    CompositionLocalProvider(
        LocalFocusedArt provides focusedArt,
        LocalCardSurface provides rememberCardSurface(wallpaper.cardStyle, scene),
        // Labels on a wallpaper get a soft halo; Midnight needs none and stays as it was.
        LocalWallpaperTextShadow provides WallpaperTextShadow.takeIf { wallpaper.style != WallpaperStyle.MIDNIGHT },
        LocalAppsRowFocus provides appsRow,
        LocalLiveRowFocus provides liveRow,
        LocalNflRowFocus provides nflRowFocus,
        LocalArtPicker provides picker,
    ) {
        Box(modifier.fillMaxSize().onPreviewKeyEvent { nowShowing.onKey(it) }) {
            // Once Now showing covers the screen, the wallpaper underneath stops: no
            // animation frames and no video decoder for something nobody can see.
            var covered by remember { mutableStateOf(false) }
            LaunchedEffect(nowShowing.showing) {
                if (nowShowing.showing) delay(NOW_SHOWING_COVERS_MILLIS)
                covered = nowShowing.showing
            }
            if (!covered) WallpaperLayer(scene, Modifier.matchParentSize())
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val viewport = maxHeight
                val metrics = RowMetrics.forWidth(maxWidth)
                val geometry = DashboardGeometry(constraints.maxWidth, constraints.maxHeight, if (kid) DashboardRows.Kid else DashboardRows.Adult)
                val density = LocalDensity.current
                val labelHeight = MaterialTheme.typography.labelMedium.lineHeight
                val stops = remember(geometry, metrics, density, labelHeight) {
                    with(density) {
                        geometry.stops(
                            rowLead = (metrics.spaceAbove + metrics.labelGap).toPx() + labelHeight.toPx(),
                            spaceAbove = metrics.spaceAbove.toPx(),
                            cardHeight = metrics.cardHeight.toPx(),
                            bottomRoom = BOTTOM_ROOM.toPx(),
                        )
                    }
                }
                val homeScroll = remember(scroll, stops) { HomeBringIntoViewSpec(scroll, stops) }
                val rowScroll = remember(metrics, density) { MarginBringIntoViewSpec(with(density) { metrics.margin.toPx() }) }
                // The scroller reads the home's spec; the rows inside it scroll by their own.
                CompositionLocalProvider(LocalBringIntoViewSpec provides homeScroll) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                        CompositionLocalProvider(LocalBringIntoViewSpec provides rowScroll, LocalRowMetrics provides metrics) {
                            if (kid) {
                                KidDashboard(
                                    state = state,
                                    actions = actions,
                                    lateNight = lateNight,
                                    focusedArt = focusedArt,
                                    focus = KidFocus(play = play, gear = gear, avatar = avatar, side = sideEntry, allApps = appsRow.allApps, search = searchButton),
                                    openSheet = openSheet,
                                    modifier = Modifier.fillMaxWidth().dashboardArea(geometry),
                                    lensMotion = lensMotion,
                                )
                                KidRows(state, shownGuide, lateNight, actions, openSheet, backTo = backTo, favorites = favorites)
                            } else {
                                Dashboard(
                                    state = state,
                                    actions = actions,
                                    lateNight = lateNight,
                                    focusedArt = focusedArt,
                                    focus = DashboardFocus(play = play, gear = gear, avatar = avatar, lensEntry = lensEntry, guideCard = guideCard, search = searchButton),
                                    openSheet = openSheet,
                                    guide = guide,
                                    backTo = backTo.takeIf { backToTv == null },
                                    backToTv = backToTv,
                                    tvButton = tvButton,
                                    favorites = favorites,
                                    gameDay = gameDay?.let { GameDayHome(it, nfl, watch, nflCard) },
                                    modifier = Modifier.fillMaxWidth().dashboardArea(geometry),
                                    lensMotion = lensMotion,
                                )
                                HomeRows(
                                    state, rows, lateNight, actions, openSheet,
                                    guide = guide, favorites = favorites, nflRow = nflRow, nfl = nfl, watch = watch, recentTv = tv.channels,
                                )
                            }
                            // Room to scroll the last row up to its stop; nothing is drawn here.
                            Spacer(Modifier.height(viewport))
                        }
                    }
                }
            }
            NowShowingOverlay(scene, nowShowing, lastInputMillis, Modifier.matchParentSize())
            ArtPickerHost(picker)
            sheet?.let { open ->
                QuickActionsSheet(open) { target ->
                    returnTo = target
                    sheet = null
                }
            }
        }
    }
}

/** Room kept under a focused row for its focus ring and lift. */
private val BOTTOM_ROOM = 8.dp

/** The game-day card's game and what it draws with (Phase 13), in Quick Watch's spot. */
private class GameDayHome(val game: NflGame, val nfl: NflView, val watch: WatchOptions, val self: FocusRequester)

/** The dashboard's focus targets: Play [U10], the gear for Back, the lens card's entry. */
private class DashboardFocus(
    val play: FocusRequester,
    val gear: FocusRequester,
    val avatar: FocusRequester,
    val lensEntry: FocusRequester,
    val guideCard: FocusRequester,
    val search: FocusRequester,
)

/**
 * The dashboard's rows on the canvas (spec §1), as weights: the top bar, Row A and Row B,
 * with gaps between. Yours leave 57 dp for the first row below to peek into; the kids'
 * (U13) fill the safe area, so their Live row starts below the screen.
 */
@Immutable
internal data class DashboardRows(val topBar: Float, val rowA: Float, val rowB: Float, val gap: Float = Layout.GAP_WEIGHT) {
    val total: Float get() = topBar + gap + rowA + gap + rowB

    companion object {
        val Adult = DashboardRows(Layout.TOP_BAR_WEIGHT, Layout.ROW_A_WEIGHT, Layout.ROW_B_WEIGHT)
        val Kid = DashboardRows(KidLayout.TOP_BAR_WEIGHT, KidLayout.ROW_A_WEIGHT, KidLayout.ROW_B_WEIGHT)
    }
}

/**
 * The dashboard on a screen of [widthPx]×[viewportPx]: the safe margins at the sides and
 * top, then [rows] of the canvas's height ([Layout.DASHBOARD_WEIGHT] for yours). There's
 * no bottom margin: the first row below starts there and peeks into the screen's last 57 dp.
 */
@Immutable
internal data class DashboardGeometry(val widthPx: Int, val viewportPx: Int, val rows: DashboardRows = DashboardRows.Adult) {
    val side = (widthPx * Layout.SAFE_SIDE / Layout.CANVAS_WIDTH).roundToInt()
    val top = (viewportPx * Layout.SAFE_TOP / Layout.CANVAS_HEIGHT).roundToInt()
    val content = (viewportPx * rows.total / Layout.CANVAS_HEIGHT).roundToInt()

    /** Where the rows start: the dashboard's bottom. */
    val bottom get() = top + content

    private fun at(weight: Float) = top + content * weight / rows.total

    fun stops(rowLead: Float, spaceAbove: Float, cardHeight: Float, bottomRoom: Float) = HomeStops(
        rowsTop = bottom.toFloat(),
        dashboard = listOf(
            // Row A at the top edge: the top bar is gone, Row A's text is padded off the edge.
            at(rows.topBar + rows.gap),
            // Row B a gap below the top edge: Row A is gone.
            at(rows.topBar + rows.gap + rows.rowA),
        ),
        rowLead = rowLead,
        spaceAbove = spaceAbove,
        cardHeight = cardHeight,
        bottomRoom = bottomRoom,
    )
}

private fun Modifier.dashboardArea(geometry: DashboardGeometry) = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth - 2 * geometry.side, geometry.content))
    layout(constraints.maxWidth, geometry.bottom) { placeable.place(geometry.side, geometry.top) }
}

/** Where the home may scroll to, in px of scrolled content. */
@Immutable
internal data class HomeStops(
    /** The dashboard's bottom, where the first row starts. Anything above it scrolls the home to the top. */
    val rowsTop: Float,
    /** Scroll positions inside the dashboard whose top edge cuts no text: Row A's top, then Row B's. */
    val dashboard: List<Float>,
    /** From a row's top to its cards: the space above it, its label and the gap under that. */
    val rowLead: Float,
    /** The space above a row's label. */
    val spaceAbove: Float,
    /** A row card's height: anything taller asking to be seen is a whole row, from its label. */
    val cardHeight: Float,
    /** Room kept under the focused row. */
    val bottomRoom: Float,
)

/**
 * Where the home scrolls to show what has focus, at [top]..[bottom] px of the scrolled
 * content, on a screen [viewport] px tall.
 *
 * - Anything in the dashboard scrolls the home back to the top.
 * - A row is shown whole with [HomeStops.bottomRoom] under it, from the first stop that
 *   fits: Row A at the top edge, Row B a gap below it, the first row's top, else the
 *   focused row's own top. The top edge always lands on a gap or a card's padding, so it
 *   never cuts text (TV-OV), and each row always lands in the same place.
 */
internal fun homeScrollTarget(top: Float, bottom: Float, viewport: Float, stops: HomeStops): Float {
    if (top < stops.rowsTop) return 0f
    val needed = bottom + stops.bottomRoom - viewport
    if (needed <= 0f) return 0f
    // A card has its row's label above it; a whole row asking to be seen starts at its label.
    val rowTop = if (bottom - top > stops.cardHeight + 1f) top - stops.spaceAbove else top - stops.rowLead
    return (stops.dashboard + stops.rowsTop).filter { it >= needed && it <= rowTop }.minOrNull() ?: rowTop
}

/**
 * The home scroller's [BringIntoViewSpec]: it scrolls to [homeScrollTarget], replacing
 * Compose's TV default, which would pivot whatever has focus to 30% of the screen.
 */
internal class HomeBringIntoViewSpec(private val scroll: ScrollState, private val stops: HomeStops) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val current = scroll.value
        val target = homeScrollTarget(offset + current, offset + size + current, containerSize, stops)
            .roundToInt()
            .coerceIn(0, scroll.maxValue)
        return (target - current).toFloat()
    }
}

/** The first screen: the top bar, then Row A and Row B on the 7/12 + 5/12 grid. */
@Composable
private fun Dashboard(
    state: HomeUiState,
    actions: HomeActions,
    lateNight: Boolean,
    focusedArt: FocusedArt,
    focus: DashboardFocus,
    openSheet: (QuickSheet) -> Unit,
    guide: GuideCardState,
    backTo: BackTo?,
    favorites: List<String>,
    modifier: Modifier = Modifier,
    backToTv: BackToTv? = null,
    tvButton: ChannelButton? = null,
    gameDay: GameDayHome? = null,
    lensMotion: LensMotion = LensMotion(),
) {
    val content = state.content
    val play = focus.play
    val gear = focus.gear
    val lensEntry = focus.lensEntry
    val guideCard = focus.guideCard
    Column(modifier) {
        TopBar(
            state = state,
            actions = actions,
            gear = gear,
            modifier = Modifier.fillMaxWidth().weight(Layout.TOP_BAR_WEIGHT).focusRestorer().focusGroup(),
            avatar = focus.avatar,
            search = focus.search,
        )
        Spacer(Modifier.weight(Layout.GAP_WEIGHT))
        Row(Modifier.fillMaxWidth().weight(Layout.ROW_A_WEIGHT)) {
            val hero = Modifier.weight(Layout.LEFT_WEIGHT).fillMaxSize()
                .reportsArt(focusedArt, state.pick?.image).focusRestorer().focusGroup()
            val pick = state.pick
            if (content == null || pick == null) {
                PlexHero(state.plex, actions, play, hero)
            } else {
                PickHero(
                    pick = pick,
                    undo = state.pickState.undo,
                    note = state.heroNote,
                    lateNight = lateNight,
                    actions = actions,
                    play = play,
                    up = gear,
                    right = if (state.lens != null) lensEntry else FocusRequester.Default,
                    openSheet = openSheet,
                    modifier = hero,
                )
            }
            Spacer(Modifier.weight(Layout.GAP_WEIGHT))
            Column(Modifier.weight(Layout.RIGHT_WEIGHT).fillMaxSize()) {
                val lensSlot = Modifier.fillMaxWidth().weight(Layout.LENS_CARD_WEIGHT)
                val lens = state.lens
                if (lens == null) {
                    PlaceholderCard(
                        when {
                            content != null -> "Throwbacks and classics show up as your watch history grows"
                            state.plex.link.linked -> "Throwbacks, classics and more arrive with your library"
                            else -> "Throwbacks, classics and more appear once Plex is connected"
                        },
                        lensSlot,
                    )
                } else {
                    LensCard(
                        state = lens,
                        lateNight = lateNight,
                        actions = actions,
                        watch = lensEntry,
                        openSheet = openSheet,
                        // Coming into the card lands on Watch, not Back above it (L3), unless it remembers otherwise.
                        modifier = lensSlot.reportsArt(focusedArt, lens.pick.image).focusRestorer(lensEntry).focusGroup(),
                        motion = lensMotion,
                    )
                }
                Spacer(Modifier.weight(Layout.GAP_WEIGHT))
                val quickSlot = Modifier.fillMaxWidth().weight(Layout.QUICK_WATCH_WEIGHT)
                if (gameDay != null) {
                    // F1: the game-day card takes Quick Watch's spot while a game is on; Not today gives it back.
                    GameDayCard(
                        game = gameDay.game,
                        view = gameDay.nfl,
                        nowMillis = state.nowMillis,
                        watch = gameDay.watch,
                        actions = actions,
                        openSheet = openSheet,
                        modifier = quickSlot,
                        self = gameDay.self,
                        afterNotToday = play,
                    )
                } else if (content == null || content.quickWatch.isEmpty()) {
                    PlaceholderCard(
                        when {
                            content != null -> "Nothing short enough fits before ${content.quickWatchCutoff}"
                            state.plex.link.linked -> "Quick Watch arrives with your library"
                            else -> "Quick Watch appears once Plex is connected"
                        },
                        quickSlot,
                    )
                } else {
                    QuickWatchCard(content.quickWatchCutoff, content.quickWatch, lateNight, actions, openSheet, quickSlot, afterHide = play)
                }
            }
        }
        Spacer(Modifier.weight(Layout.GAP_WEIGHT))
        Row(Modifier.fillMaxWidth().weight(Layout.ROW_B_WEIGHT)) {
            UpNextRow(
                items = content?.upNext,
                lateNight = lateNight,
                actions = actions,
                openSheet = openSheet,
                afterHide = play,
                modifier = Modifier.weight(Layout.LEFT_WEIGHT).fillMaxSize(),
            )
            Spacer(Modifier.weight(Layout.GAP_WEIGHT))
            // The Plex Guide (plan §6): the lineup and what's coming up, on the minute tick; Back to {channel} while a spot holds (P4).
            GuideCard(
                state = guide,
                clock = LiveClock(state.now, state.nowMillis),
                lateNight = lateNight,
                plexLinked = state.plex.link.linked,
                actions = actions,
                modifier = Modifier.weight(Layout.RIGHT_WEIGHT).fillMaxSize().focusRequester(guideCard),
                backTo = backTo,
                openSheet = openSheet,
                self = guideCard,
                favorites = favorites,
                backToTv = backToTv,
                tvButton = tvButton,
            )
        }
    }
}

/** Now showing fades in over 1.2 s; after that the wallpaper underneath can stop. */
private const val NOW_SHOWING_COVERS_MILLIS = 1_500L
