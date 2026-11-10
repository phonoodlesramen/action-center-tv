package com.phonoodlesramen.actioncenter.ui.home

import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.apps.LocalCustomBanners
import com.phonoodlesramen.actioncenter.apps.key
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.Pick
import com.phonoodlesramen.actioncenter.content.UpNextItem
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.home.LensCardState
import com.phonoodlesramen.actioncenter.player.BackTo
import com.phonoodlesramen.actioncenter.ui.apps.AppBanner
import com.phonoodlesramen.actioncenter.ui.art.ArtFilters
import com.phonoodlesramen.actioncenter.ui.art.LogoArt
import com.phonoodlesramen.actioncenter.ui.art.TitleArt
import com.phonoodlesramen.actioncenter.ui.art.rememberLogoBox
import com.phonoodlesramen.actioncenter.ui.home.rows.LiveClock
import com.phonoodlesramen.actioncenter.ui.home.rows.LiveRow
import com.phonoodlesramen.actioncenter.ui.home.rows.appsRowOrder
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.onWallpaper
import com.phonoodlesramen.actioncenter.ui.wallpaper.FocusedArt
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalFocusedArt
import com.phonoodlesramen.actioncenter.ui.wallpaper.reportsArt

/**
 * The kids' layout (U13, K7; spec §1 "Rows (kids)", §2): the same card system, bigger and
 * rounder, in their own colour (their accent, set by the home state).
 */
object KidLayout {
    /** Cards and inner tiles: 18 and 12 dp, for the adult's 10 and 8 (spec §2). */
    val CardRadius = 18.dp
    val TileRadius = 12.dp

    /** Top bar 44, Row A 214, Row B 196 (spec §1), with the adult's 16 dp gaps: the whole safe area. */
    const val TOP_BAR_WEIGHT = 44f
    const val ROW_A_WEIGHT = 214f
    const val ROW_B_WEIGHT = 196f

    /** Up Next's stills: two, about 240×135 dp (spec §4). */
    const val UP_NEXT_SLOTS = 2

    /** My apps: three of their apps, then All apps (K9). */
    const val MY_APPS = 3

    /** A My apps tile's height (the prototype's 74 dp); less when Row B is short at 115%. */
    val AppTileHeight = 74.dp
}

internal val KidShape = RoundedCornerShape(KidLayout.CardRadius)
private val KidTileShape = RoundedCornerShape(KidLayout.TileRadius)

/** The kids' greeting (K7): "Hi Maya!". */
fun kidGreeting(name: String): String = if (name.isBlank()) "Hi!" else "Hi $name!"

/**
 * The kids' lens card's eyebrow (R1): Throwback is "Remember when" for a kid (K7); any other
 * lens reads as on yours, Seasonal by its season.
 */
fun kidLensTitle(state: LensCardState): String = if (state.moreLike == null && state.lens == Lens.THROWBACK) "Remember when" else state.title

/** My apps (K9): the kid's allowlist ([allow], by [LaunchableApp.key]), in the Apps row's order: habit first, then A–Z. */
fun myApps(launchpad: List<LaunchableApp>, allApps: List<LaunchableApp>, allow: Set<String>): List<LaunchableApp> =
    appsRowOrder(launchpad, allApps, hidden = emptySet()).filter { it.key in allow }

/** The kids' dashboard's focus targets: Play [U10], the gear, the lens card's Watch, and All apps for Back. */
internal class KidFocus(
    val play: FocusRequester,
    val gear: FocusRequester,
    val avatar: FocusRequester,
    val side: FocusRequester,
    val allApps: FocusRequester,
    val search: FocusRequester? = null,
)

/**
 * The kids' first screen (K7): the top bar ("Hi Maya!" and the context chip); Row A, their
 * pick and their lens card (R1: "Remember when" leading); Row B, Up Next as two big stills
 * and My apps. Each panel is a focus group that remembers its last child, so the D-pad
 * reaches everything by geometry; only the hero's Up and Right are set, as on yours.
 */
@Composable
internal fun KidDashboard(
    state: HomeUiState,
    actions: HomeActions,
    lateNight: Boolean,
    focusedArt: FocusedArt,
    focus: KidFocus,
    openSheet: (QuickSheet) -> Unit,
    modifier: Modifier = Modifier,
    lensMotion: LensMotion = LensMotion(),
) {
    val content = state.content
    val pick = state.pick
    // Worked out by the home state, as yours (R1): Settings › Lenses, the lead and the rotation.
    val side = state.lens
    val greeted = remember(state) { state.copy(greeting = kidGreeting(state.profile.name), name = state.profile.name) }
    Column(modifier) {
        TopBar(
            state = greeted,
            actions = actions,
            gear = focus.gear,
            modifier = Modifier.fillMaxWidth().weight(KidLayout.TOP_BAR_WEIGHT).focusRestorer().focusGroup(),
            avatar = focus.avatar,
            search = focus.search,
        )
        Spacer(Modifier.weight(Layout.GAP_WEIGHT))
        Row(Modifier.fillMaxWidth().weight(KidLayout.ROW_A_WEIGHT)) {
            val hero = Modifier.weight(Layout.LEFT_WEIGHT).fillMaxSize()
                .reportsArt(focusedArt, pick?.image).focusRestorer().focusGroup()
            if (content == null || pick == null) {
                PlexHero(state.plex, actions, focus.play, hero)
            } else {
                PickHero(
                    pick = pick,
                    undo = state.pickState.undo,
                    note = state.heroNote,
                    lateNight = lateNight,
                    actions = actions,
                    play = focus.play,
                    up = focus.gear,
                    right = if (side != null) focus.side else FocusRequester.Default,
                    openSheet = openSheet,
                    modifier = hero,
                    kid = true,
                )
            }
            Spacer(Modifier.weight(Layout.GAP_WEIGHT))
            val sideSlot = Modifier.weight(Layout.RIGHT_WEIGHT).fillMaxSize()
            if (side == null) {
                PlaceholderCard(
                    when {
                        content != null -> "Old favourites show up here as you watch more"
                        state.plex.link.linked -> "Old favourites arrive with the library"
                        else -> "Old favourites appear once Plex is connected"
                    },
                    sideSlot,
                    radius = KidLayout.CardRadius,
                )
            } else {
                KidLensCard(
                    state = side,
                    lateNight = lateNight,
                    actions = actions,
                    watch = focus.side,
                    openSheet = openSheet,
                    // Coming into the card lands on Watch, not Back above it (L3), unless it remembers otherwise.
                    modifier = sideSlot.reportsArt(focusedArt, side.pick.image).focusRestorer(focus.side).focusGroup(),
                    motion = lensMotion,
                )
            }
        }
        Spacer(Modifier.weight(Layout.GAP_WEIGHT))
        Row(Modifier.fillMaxWidth().weight(KidLayout.ROW_B_WEIGHT)) {
            KidUpNextRow(
                items = content?.upNext,
                lateNight = lateNight,
                actions = actions,
                openSheet = openSheet,
                afterHide = focus.play,
                modifier = Modifier.weight(Layout.LEFT_WEIGHT).fillMaxSize(),
            )
            Spacer(Modifier.weight(Layout.GAP_WEIGHT))
            MyApps(state, actions, focus.allApps, Modifier.weight(Layout.RIGHT_WEIGHT).fillMaxSize())
        }
    }
}

/**
 * Below the kids' dashboard (K7, K8): the Live row alone, with only the kid-safe guide
 * channels in [guide] (already [com.phonoodlesramen.actioncenter.guide.KidGuide]'s). No
 * YouTube TV networks, no Continue in your apps (other apps' Watch Next isn't per kid) and
 * no Apps row: My apps is above. Without kid-safe channels nothing is drawn.
 *
 * [backTo], the guide player's saved spot on one of those channels (P4), is the row's first
 * card: a kid's home has no Guide card to hold it.
 */
@Composable
internal fun KidRows(
    state: HomeUiState,
    guide: GuideCardState,
    lateNight: Boolean,
    actions: HomeActions,
    openSheet: (QuickSheet) -> Unit,
    modifier: Modifier = Modifier,
    backTo: BackTo? = null,
    /** Their own favorites (Phase 13), among their kid-safe channels; hidden ones stay in the list. */
    favorites: List<String> = emptyList(),
) {
    Column(modifier.fillMaxWidth()) {
        LiveRow(emptyList(), guide, LiveClock(state.now, state.nowMillis), lateNight, actions, openSheet, backTo = backTo, favorites = favorites)
    }
}

/**
 * How the kid hero's title and reason share the text column's [budget] (its height less the
 * eyebrow and meta), in dp, given whether the title fits one line in the kid title style
 * ([largeFits], 32/40) and in headlineSmall ([smallFits], 24/32). Line heights are dp too.
 */
internal data class KidHeroFit(val large: Boolean, val titleLines: Int, val reasonLines: Int)

/**
 * The kid title when it and a line of reason fit (spec §2); else headlineSmall, on two lines
 * when one won't hold it and there's room; else one ellipsized line. The reason [REQ] takes
 * two lines when there's room left, so nothing is ever cut by the card's edge.
 *
 * With a [logo] box (Phase 12, [heroLogoFit]) the logo takes the title's place, and the title
 * waits in the box on one line until it loads: the kid style when the box holds its line,
 * else headlineSmall. The reason keeps the lines the box left it.
 */
internal fun kidHeroFit(
    budget: Float,
    largeFits: Boolean,
    smallFits: Boolean,
    largeLine: Float,
    smallLine: Float,
    reasonLine: Float,
    logo: HeroLogoFit? = null,
): KidHeroFit {
    if (logo != null) return KidHeroFit(large = largeFits && logo.height >= largeLine, titleLines = 1, reasonLines = logo.reasonLines)
    fun reasonLines(title: Float) = if (budget - title >= 2 * reasonLine) 2 else 1
    return when {
        largeFits && budget >= largeLine + reasonLine -> KidHeroFit(large = true, titleLines = 1, reasonLines = reasonLines(largeLine))
        smallFits && budget >= smallLine + reasonLine -> KidHeroFit(large = false, titleLines = 1, reasonLines = reasonLines(smallLine))
        !smallFits && budget >= 2 * smallLine + reasonLine -> KidHeroFit(large = false, titleLines = 2, reasonLines = reasonLines(2 * smallLine))
        else -> KidHeroFit(large = false, titleLines = 1, reasonLines = 1)
    }
}

/**
 * The kid hero's text (K7): "Pick for you", the title in the kid hero title style (spec §2:
 * headlineLarge, bold, 32/40), the meta and the reason. The title gives way as [kidHeroFit]
 * says: a long one, or 115%'s bigger text, takes headlineSmall. A logo (Phase 12) takes the
 * title's place, up to [KID_HERO_LOGO] tall, when the column has room for it ([heroLogo]).
 */
@Composable
internal fun KidPickText(pick: Pick, lateNight: Boolean) {
    val large = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold)
    val small = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
    val reason = MaterialTheme.typography.titleMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints {
        val width = constraints.maxWidth
        val logo = heroLogo(pick, max = KID_HERO_LOGO, min = with(density) { small.lineHeight.toDp() })
        val fit = remember(pick.title, width, maxHeight, large, small, reason, density, logo?.fit) {
            fun fits(style: TextStyle) = !constraints.hasBoundedWidth ||
                !measurer.measure(pick.title, style, maxLines = 1, softWrap = false, constraints = Constraints(maxWidth = width)).hasVisualOverflow
            with(density) {
                kidHeroFit(
                    budget = if (constraints.hasBoundedHeight) (maxHeight - HERO_FIXED).value else Float.MAX_VALUE,
                    largeFits = fits(large),
                    smallFits = fits(small),
                    largeLine = large.lineHeight.toDp().value,
                    smallLine = small.lineHeight.toDp().value,
                    reasonLine = reason.lineHeight.toDp().value,
                    logo = logo?.fit,
                )
            }
        }
        val title = @Composable { modifier: Modifier ->
            Text(
                text = pick.title,
                style = if (fit.large) large else small,
                maxLines = fit.titleLines,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier,
            )
        }
        Column {
            Eyebrow("Pick for you")
            if (logo == null) title(Modifier.padding(top = 6.dp, bottom = 2.dp)) else HeroLogoArt(pick, logo, lateNight) { title(Modifier) }
            Text(pick.meta, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1)
            HeroReason(pick.reason, reason, fit.reasonLines)
        }
    }
}

/** The kid hero's logo box (Phase 12): up to 72 dp tall, for yours' 60. */
private val KID_HERO_LOGO = 72.dp

/** The kids' lens card's side padding. */
private val KID_LENS_PADDING = 18.dp

/** Back's circle on the kids' lens card (L6), and the top padding it leaves: 8 + 24, the 16 + 16 the eyebrow had. */
private val KID_LENS_BACK = 24.dp
private val KID_LENS_TOP = 8.dp

/**
 * The kids' lens card (R1; K7 layout): one title through one lens, in that lens's tint and
 * eyebrow as on yours, with its art over the right side; Throwback reads "Remember when".
 * The line says why. One button: Watch again for a title they've seen (their plays, not
 * yours), else Watch; holding OK or Menu opens your lens card's quick actions (Watch,
 * Details, Another lens, Don't show this), whose feedback is theirs. [watch] is where Right
 * from the hero lands. A logo (Phase 12) takes the title's place, in a box its line's height.
 * Like yours (Phase 14, L6) it moves on by itself with [motion], and Back (Up from Watch)
 * brings back the title before.
 */
@Composable
internal fun KidLensCard(
    state: LensCardState,
    lateNight: Boolean,
    actions: HomeActions,
    watch: FocusRequester,
    openSheet: (QuickSheet) -> Unit,
    modifier: Modifier = Modifier,
    motion: LensMotion = LensMotion(),
) {
    val pick = state.pick
    val accent = LocalAccent.current
    val style = state.style(accent)
    val back = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LensClock(state, motion, focused, visible, actions)
    // A kid's card has no Next, so Another lens stays even while shuffling: it's how they move it on.
    val sheet = { openSheet(lensSheet(pick, actions, returnTo = watch, watch = watch)) }
    BoxWithConstraints(
        modifier
            .onFocusChanged { focused = it.hasFocus }
            .onGloballyPositioned { visible = it.mostlyInView() }
            .clip(KidShape)
            .background(style.gradient),
    ) {
        // At 115% the line keeps to one line, so the title and the button keep their room.
        val roomy = maxHeight >= 200.dp
        val titleStyle = MaterialTheme.typography.headlineSmall
        val density = LocalDensity.current
        // Phase 12: the logo in the title's place, as tall as the title's line, so nothing moves.
        val titleLine = with(density) { titleStyle.lineHeight.toDp() }
        val logoWidthPx = constraints.maxWidth - with(density) { (KID_LENS_PADDING * 2).roundToPx() }
        Crossfade(
            targetState = state,
            animationSpec = tween(LENS_FADE_MILLIS),
            label = "kid lens art",
            modifier = Modifier.fillMaxHeight().fillMaxWidth(0.55f).align(Alignment.CenterEnd),
        ) { shownState ->
            val shown = shownState.style(accent)
            Box(Modifier.fillMaxSize()) {
                TitleArt(shownState.pick.id, shownState.pick.image, lateNight, Modifier.fillMaxSize(), if (shown.sepia) ArtFilters.Sepia else null)
                Box(Modifier.matchParentSize().background(shown.scrim))
            }
        }
        Column(Modifier.fillMaxSize().padding(start = KID_LENS_PADDING, end = KID_LENS_PADDING, top = KID_LENS_TOP, bottom = 16.dp)) {
            // L3, L6: Back in the eyebrow's row, in the top padding's room, so nothing below moves.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LensBack(
                    style = style,
                    onClick = actions::previousLensPick,
                    modifier = Modifier.focusRequester(back).focusProperties { down = watch },
                    size = KID_LENS_BACK,
                )
                Crossfade(state, animationSpec = tween(LENS_FADE_MILLIS), label = "kid lens eyebrow") { shownState ->
                    Eyebrow(kidLensTitle(shownState), color = shownState.style(accent).eyebrow)
                }
            }
            Crossfade(state, animationSpec = tween(LENS_FADE_MILLIS), label = "kid lens words") { shownState ->
                val shown = shownState.style(accent)
                val shownPick = shownState.pick
                val logo = rememberLogoBox(shownPick.logo, logoWidthPx, titleLine, titleLine)
                Column {
                    Text(
                        text = shownPick.line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = shown.text.copy(alpha = 0.9f),
                        maxLines = if (roomy) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    val title = @Composable { modifier: Modifier ->
                        Text(text = shownPick.title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
                    }
                    if (logo == null) {
                        title(Modifier.padding(top = 2.dp))
                    } else {
                        LogoArt(
                            logo.url, logo.widthPx, logo.heightPx, shownPick.title, shown.lightest, lateNight,
                            Modifier.padding(top = 2.dp).fillMaxWidth().height(logo.height),
                        ) { title(Modifier) }
                    }
                    Text(shownPick.meta, style = MaterialTheme.typography.bodyMedium, color = shown.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.weight(1f))
            PillButton(
                text = watchLabel(pick),
                icon = AppIcons.Play,
                colors = pillButtonColors(
                    container = style.eyebrow.copy(alpha = 0.14f),
                    content = lerp(style.eyebrow, Color.White, 0.7f),
                    focused = lerp(style.eyebrow, Color.White, 0.15f),
                    focusedInk = style.dark,
                ),
                onClick = { actions.play(pick.id, pick.title) },
                onLongClick = sheet,
                modifier = Modifier.focusRequester(watch).focusProperties { up = back }.onMenuKey(sheet),
            )
        }
    }
}

/**
 * The kids' Up Next (spec §4): two big stills, each with its time left as a chip on it and a
 * progress bar in their colour, the title and episode under it. Focus rings the still. Hold
 * OK for the same quick actions as yours; [afterHide] takes focus once one is removed.
 */
@Composable
internal fun KidUpNextRow(
    items: List<UpNextItem>?,
    lateNight: Boolean,
    actions: HomeActions,
    openSheet: (QuickSheet) -> Unit,
    modifier: Modifier = Modifier,
    afterHide: FocusRequester? = null,
) {
    Column(modifier) {
        Eyebrow("Up next")
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp).focusRestorer().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (items == null) {
                repeat(KidLayout.UP_NEXT_SLOTS) {
                    PlaceholderCard(
                        text = "Your shows in progress",
                        radius = KidLayout.TileRadius,
                        modifier = Modifier.weight(1f).aspectRatio(16f / 9f),
                    )
                }
            } else {
                items.take(KidLayout.UP_NEXT_SLOTS).forEach { item ->
                    KidUpNextCard(item, lateNight, actions, openSheet, Modifier.weight(1f).fillMaxHeight(), afterHide)
                }
                repeat(KidLayout.UP_NEXT_SLOTS - items.size.coerceAtMost(KidLayout.UP_NEXT_SLOTS)) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun KidUpNextCard(
    item: UpNextItem,
    lateNight: Boolean,
    actions: HomeActions,
    openSheet: (QuickSheet) -> Unit,
    modifier: Modifier = Modifier,
    afterHide: FocusRequester? = null,
) {
    val self = remember(item.id) { FocusRequester() }
    val sheet = { openSheet(upNextSheet(item, actions, self, afterHide)) }
    Column(modifier) {
        // 16:9 when Row B has the room; a little shorter at 115%, where the caption keeps its line.
        Surface(
            onClick = { actions.play(item.id, item.title) },
            onLongClick = sheet,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .aspectRatio(16f / 9f)
                .reportsArt(LocalFocusedArt.current, item.image)
                .focusRequester(self)
                .onMenuKey(sheet)
                .semantics { contentDescription = "${item.title}, ${item.episode}, ${item.timeLeft}" },
            shape = ClickableSurfaceDefaults.shape(KidTileShape),
            colors = tileColors(),
            scale = cardLift,
            border = focusRing(KidTileShape),
        ) {
            TitleArt(item.id, item.image, lateNight, Modifier.fillMaxSize())
            Text(
                text = item.timeLeft,
                style = MaterialTheme.typography.labelMedium,
                color = Palette.Text,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Palette.Scrim.copy(alpha = 0.78f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.22f)),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(item.progress.coerceIn(0f, 1f)).background(LocalAccent.current))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium.onWallpaper(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(item.episode, style = MaterialTheme.typography.bodySmall.onWallpaper(), color = Palette.TextDim, maxLines = 1)
        }
    }
}

/**
 * My apps (K9; spec §4 Apps row "Kids"): three apps from the kid's allowlist ([myApps]) and
 * All apps, two by two, as big rounded tiles. OK opens; there are no quick actions, since
 * hiding apps and choosing art are yours. [allApps] is where Back from All apps lands.
 */
@Composable
internal fun MyApps(state: HomeUiState, actions: HomeActions, allApps: FocusRequester, modifier: Modifier = Modifier) {
    val apps = remember(state.launchpad, state.allApps, state.profile.apps) {
        myApps(state.launchpad, state.allApps, state.profile.apps.orEmpty()).take(KidLayout.MY_APPS)
    }
    val banners by LocalCustomBanners.current.state.collectAsState()
    Column(modifier) {
        Eyebrow("My apps")
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp).focusRestorer().focusGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Null is the All apps tile, always last.
            (apps + null).chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).height(KidLayout.AppTileHeight),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { app ->
                        val tile = Modifier.weight(1f).fillMaxHeight()
                        if (app == null) {
                            AllAppsTile(actions::openAllApps, tile.focusRequester(allApps), radius = KidLayout.TileRadius)
                        } else {
                            AppBanner(
                                app = app,
                                custom = banners.bannerFor(app),
                                onClick = { actions.launch(app) },
                                modifier = tile,
                                radius = KidLayout.TileRadius,
                            )
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
