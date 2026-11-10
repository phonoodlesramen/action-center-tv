package com.phonoodlesramen.actioncenter.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.content.Pick
import com.phonoodlesramen.actioncenter.content.QuickItem
import com.phonoodlesramen.actioncenter.content.UpNextItem
import com.phonoodlesramen.actioncenter.home.PickState
import com.phonoodlesramen.actioncenter.ui.art.LogoArt
import com.phonoodlesramen.actioncenter.ui.art.LogoBox
import com.phonoodlesramen.actioncenter.ui.art.TitleArt
import com.phonoodlesramen.actioncenter.ui.art.rememberLogoBox
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalCardSurface
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalFocusedArt
import com.phonoodlesramen.actioncenter.ui.wallpaper.cardSurface
import com.phonoodlesramen.actioncenter.ui.wallpaper.reportsArt
import com.phonoodlesramen.actioncenter.ui.theme.onWallpaper

/** What a button says when what it does arrives in a later phase. */
const val NOT_YET = "Not yet: this arrives in a later update"

private val CardShape = RoundedCornerShape(Layout.CardRadius)
private val TileShape = RoundedCornerShape(Layout.TileRadius)
/** The hero's text column is 356 dp of the card's 457 dp inside its padding (spec §4). */
internal const val HERO_TEXT_WIDTH = 356f / (497.33f - 40f)
private const val LONG_TITLE = 24

/**
 * Tonight's Pick (spec §4): full-bleed art behind a scrim, the reason, and Play /
 * Trailer / Not tonight. Not tonight swaps the pick and turns into Undo for 8 s [U12].
 *
 * A kid's ([kid], K7, U13) is "Pick for you", with the bigger title and 18 dp corners, and
 * Not now for Not tonight. It has no More like this: their lens card (R1) rotates through
 * the lenses alone.
 *
 * @param up the top bar: geometric search would pick the lens card's buttons, which sit higher.
 * @param right the lens card: geometric search would pick Quick Watch, which lines up better.
 */
@Composable
fun PickHero(
    pick: Pick,
    undo: PickState.Undo?,
    /** The offline note (U15); Undo's note takes its place for 8 s. */
    note: String?,
    lateNight: Boolean,
    actions: HomeActions,
    play: FocusRequester,
    up: FocusRequester,
    right: FocusRequester,
    openSheet: (QuickSheet) -> Unit,
    modifier: Modifier = Modifier,
    kid: Boolean = false,
) {
    val trailer = remember { FocusRequester() }
    val skip = remember { FocusRequester() }
    val skipLabel = if (kid) "Not now" else "Not tonight"
    fun sheet(returnTo: FocusRequester) = QuickSheet(
        title = pick.title,
        returnTo = returnTo,
        items = listOfNotNull(
            SheetItem(AppIcons.Play, "Play") { actions.play(pick.id, pick.title) },
            SheetItem(AppIcons.Info, "Why this pick?") { actions.say((listOf(pick.because ?: pick.reason) + pick.evidence).joinToString(" · ")) },
            SheetItem(AppIcons.StartOver, "Start from the beginning") { actions.say(NOT_YET) },
            // The lens card shows them, so focus goes there (S10).
            if (kid) null else SheetItem(AppIcons.Tv, "More like this", returnTo = right) { actions.moreLikeThis() },
            SheetItem(AppIcons.Hide, skipLabel) { actions.notTonight() },
        ),
    )
    val shape = if (kid) KidShape else CardShape
    Box(modifier.clip(shape).background(Palette.Surface)) {
        Crossfade(targetState = pick, animationSpec = tween(250), label = "pick art") { shown ->
            TitleArt(shown.id, shown.image, lateNight, Modifier.fillMaxSize())
        }
        Box(Modifier.matchParentSize().background(HeroScrim))
        Box(Modifier.matchParentSize().background(BottomScrim))
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Crossfade(
                targetState = pick,
                animationSpec = tween(250),
                label = "pick text",
                modifier = Modifier.weight(1f).fillMaxWidth(HERO_TEXT_WIDTH).clipToBounds(),
            ) { shown -> if (kid) KidPickText(shown, lateNight) else PickText(shown, lateNight) }
            val line = if (undo != null) "Skipping ${undo.skippedTitle} for 2 weeks" else note
            if (line != null) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    text = pick.playLabel,
                    icon = AppIcons.Play,
                    onClick = { actions.play(pick.id, pick.title) },
                    onLongClick = { openSheet(sheet(play)) },
                    modifier = Modifier
                        .focusRequester(play)
                        .focusProperties { this.up = up }
                        .onMenuKey { openSheet(sheet(play)) },
                )
                PillButton(
                    text = "Trailer",
                    onClick = { actions.trailer(pick) },
                    onLongClick = { openSheet(sheet(trailer)) },
                    modifier = Modifier
                        .focusRequester(trailer)
                        .focusProperties { this.up = up }
                        .onMenuKey { openSheet(sheet(trailer)) },
                )
                PillButton(
                    text = if (undo != null) "Undo" else skipLabel,
                    onClick = { if (undo != null) actions.undo() else actions.notTonight() },
                    onLongClick = { openSheet(sheet(skip)) },
                    modifier = Modifier
                        .focusRequester(skip)
                        .focusProperties {
                            this.up = up
                            this.right = right
                        }
                        .onMenuKey { openSheet(sheet(skip)) },
                )
            }
        }
    }
}

/** Spec §4: `#080B0F` at 92% → 70% at 40% → 10% at 80%, plus 60% → 0 over the lower half. */
private val HeroScrim = Brush.horizontalGradient(
    0f to Palette.Scrim.copy(alpha = 0.92f),
    0.4f to Palette.Scrim.copy(alpha = 0.70f),
    0.8f to Palette.Scrim.copy(alpha = 0.10f),
    1f to Palette.Scrim.copy(alpha = 0.10f),
)
private val BottomScrim = Brush.verticalGradient(0.5f to Color.Transparent, 1f to Palette.Scrim.copy(alpha = 0.6f))

/**
 * The hero's text: the eyebrow, the title, the meta and the reason. With a logo (Phase 12),
 * the logo takes the title's place when the column has room for it ([heroLogo]).
 */
@Composable
private fun PickText(pick: Pick, lateNight: Boolean) {
    val long = pick.title.length > LONG_TITLE
    val style = (if (long) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium)
        .copy(fontWeight = FontWeight.Bold)
    val title = @Composable { modifier: Modifier ->
        Text(text = pick.title, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
    }
    if (pick.logo == null) {
        PickColumn(pick, reasonLines = 2) { title(Modifier.padding(top = 6.dp, bottom = 2.dp)) }
        return
    }
    BoxWithConstraints {
        val logo = heroLogo(pick, max = HERO_LOGO, min = with(LocalDensity.current) { style.lineHeight.toDp() })
        PickColumn(pick, reasonLines = logo?.fit?.reasonLines ?: 2) {
            if (logo == null) title(Modifier.padding(top = 6.dp, bottom = 2.dp)) else HeroLogoArt(pick, logo, lateNight) { title(Modifier) }
        }
    }
}

/** Tonight's pick's eyebrow, [title] (the title or its logo), the meta and the reason in up to [reasonLines]. */
@Composable
private fun PickColumn(pick: Pick, reasonLines: Int, title: @Composable () -> Unit) {
    Column {
        Eyebrow("Tonight's pick")
        title()
        Text(pick.meta, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1)
        HeroReason(pick.reason, MaterialTheme.typography.titleMedium, reasonLines)
    }
}

/** The reason line [REQ]: the spark in the accent, then [reason] in up to [lines]. */
@Composable
internal fun HeroReason(reason: String, style: TextStyle, lines: Int) {
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            AppIcons.Spark,
            contentDescription = null,
            tint = LocalAccent.current,
            modifier = Modifier.padding(top = 4.dp).size(16.dp),
        )
        Text(reason, style = style, maxLines = lines, overflow = TextOverflow.Ellipsis)
    }
}

/** The hero's logo box (Phase 12): up to 60 dp tall, the text column's width. */
private val HERO_LOGO = 60.dp

/** The eyebrow (16), the title's padding (6 + 2), the meta (20) and the reason's padding (10). */
internal val HERO_FIXED = 54.dp

/** The reason's spark (16) and its gap (8): the reason's text is the column less these. */
private val REASON_INSET = 24.dp

/** How tall the hero's logo box is, and how many lines its reason keeps (Phase 12). The height is in dp. */
internal data class HeroLogoFit(val height: Float, val reasonLines: Int)

/**
 * The hero's logo box in the text column's [budget] (its height less [HERO_FIXED]), in dp:
 * up to [max] tall, and never under [min], the title's line, since the title waits in the box
 * until the logo loads. The reason [REQ] keeps the lines it [needs] (1 or 2) while the box
 * still fits, else one. Null when not even that fits: the title shows as before.
 */
internal fun heroLogoFit(budget: Float, max: Float, min: Float, reasonLine: Float, needs: Int): HeroLogoFit? =
    (needs.coerceIn(1, 2) downTo 1).firstNotNullOfOrNull { lines ->
        minOf(max, budget - lines * reasonLine).takeIf { it >= min }?.let { HeroLogoFit(it, lines) }
    }

/** A hero's logo: how it shares the column with the reason ([fit]), and its [box]. */
internal data class HeroLogo(val fit: HeroLogoFit, val box: LogoBox)

/**
 * [pick]'s logo in this hero text column ([heroLogoFit], the reason measured at the column's
 * width), or null: no logo, no room for one, or no server to load it from. Then the title
 * shows, as before.
 */
@Composable
internal fun BoxWithConstraintsScope.heroLogo(pick: Pick, max: Dp, min: Dp): HeroLogo? {
    val path = pick.logo ?: return null
    val density = LocalDensity.current
    val reason = MaterialTheme.typography.titleMedium
    val measurer = rememberTextMeasurer()
    val width = constraints.maxWidth
    val bounded = constraints.hasBoundedWidth to constraints.hasBoundedHeight
    val fit = remember(pick.reason, width, maxHeight, bounded, max, min, reason, density) {
        with(density) {
            val needs = if (!bounded.first) {
                1
            } else {
                val textWidth = (width - REASON_INSET.roundToPx()).coerceAtLeast(0)
                measurer.measure(pick.reason, reason, maxLines = 2, constraints = Constraints(maxWidth = textWidth)).lineCount
            }
            val budget = if (bounded.second) (maxHeight - HERO_FIXED).value else Float.MAX_VALUE
            heroLogoFit(budget, max.value, min.value, reason.lineHeight.toDp().value, needs)
        }
    } ?: return null
    val box = rememberLogoBox(path, if (bounded.first) width else 0, fit.height.dp, min) ?: return null
    return HeroLogo(fit, box)
}

/**
 * What a hero's logo is read against (Phase 12, [com.phonoodlesramen.actioncenter.ui.art.LogoCheck]):
 * the scrim it sits on, 92% opaque where the text column starts. Yours and a kid's share it.
 */
internal val HERO_LOGO_BACKGROUND = Palette.Scrim

/** [logo] in the title's place, with the title's padding; [title] waits in the box until it loads. */
@Composable
internal fun HeroLogoArt(pick: Pick, logo: HeroLogo, lateNight: Boolean, title: @Composable () -> Unit) {
    LogoArt(
        url = logo.box.url,
        widthPx = logo.box.widthPx,
        heightPx = logo.box.heightPx,
        description = pick.title,
        background = HERO_LOGO_BACKGROUND,
        lateNight = lateNight,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp).fillMaxWidth().height(logo.box.height),
        title = title,
    )
}

/**
 * Quick Watch (spec §4): two short things that fit before the cutoff. OK plays.
 * [afterHide] takes focus once Hide from Quick Watch takes the row away.
 */
@Composable
fun QuickWatchCard(
    cutoff: String,
    items: List<QuickItem>,
    lateNight: Boolean,
    actions: HomeActions,
    openSheet: (QuickSheet) -> Unit,
    modifier: Modifier = Modifier,
    afterHide: FocusRequester? = null,
) {
    val focusedArt = LocalFocusedArt.current
    Column(
        modifier
            .cardSurface(LocalCardSurface.current, CardShape)
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
    ) {
        Eyebrow("Quick Watch · fits before $cutoff")
        Column(
            modifier = Modifier.weight(1f).padding(top = 6.dp).focusRestorer().focusGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.take(2).forEach { item ->
                val self = remember(item.id) { FocusRequester() }
                val sheet = {
                    openSheet(
                        QuickSheet(
                            title = item.title,
                            returnTo = self,
                            items = listOf(
                                SheetItem(AppIcons.Play, "Play") { actions.play(item.id, item.title) },
                                SheetItem(AppIcons.Info, "Details") { actions.say(NOT_YET) },
                                SheetItem(AppIcons.Hide, "Hide from Quick Watch", returnTo = afterHide) { actions.hideFromQuickWatch(item) },
                            ),
                        ),
                    )
                }
                Surface(
                    onClick = { actions.play(item.id, item.title) },
                    onLongClick = sheet,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .heightIn(max = 28.dp)
                        .reportsArt(focusedArt, item.image)
                        .focusRequester(self)
                        .onMenuKey(sheet),
                    shape = ClickableSurfaceDefaults.shape(TileShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.05f),
                        contentColor = Palette.Text,
                        focusedContainerColor = Palette.Surface3,
                        focusedContentColor = Palette.Text,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TitleArt(
                            id = item.id,
                            image = item.image,
                            lateNight = lateNight,
                            modifier = Modifier.size(width = 36.dp, height = 20.dp).clip(RoundedCornerShape(4.dp)),
                        )
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(item.meta, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Up Next (spec §4): three stills with progress. Focus rings the still, not the caption.
 * [afterHide] takes focus once Remove from Up Next takes the card away.
 */
@Composable
fun UpNextRow(
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
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRestorer().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (items == null) {
                repeat(UP_NEXT_SLOTS) {
                    PlaceholderCard(
                        text = "Your shows in progress",
                        radius = Layout.TileRadius,
                        modifier = Modifier.weight(1f).aspectRatio(16f / 9f),
                    )
                }
            } else {
                items.take(UP_NEXT_SLOTS).forEach { item ->
                    UpNextCard(item, lateNight, actions, openSheet, Modifier.weight(1f), afterHide)
                }
                repeat(UP_NEXT_SLOTS - items.size.coerceAtMost(UP_NEXT_SLOTS)) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val UP_NEXT_SLOTS = 3

/** An Up Next card's quick actions (S10); [afterHide] takes focus once Remove from Up Next takes the card away. */
internal fun upNextSheet(item: UpNextItem, actions: HomeActions, self: FocusRequester, afterHide: FocusRequester?) = QuickSheet(
    title = item.title,
    returnTo = self,
    items = listOf(
        SheetItem(AppIcons.Play, "Resume") { actions.play(item.id, item.title) },
        SheetItem(AppIcons.StartOver, "Start this episode over") { actions.say(NOT_YET) },
        SheetItem(AppIcons.Tv, "Go to show in Plezy") { actions.say(NOT_YET) },
        SheetItem(AppIcons.Check, "Mark as watched") { actions.say(NOT_YET) },
        SheetItem(AppIcons.Hide, "Remove from Up Next", returnTo = afterHide) { actions.removeFromUpNext(item) },
    ),
)

@Composable
private fun UpNextCard(
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
        Surface(
            onClick = { actions.play(item.id, item.title) },
            onLongClick = sheet,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .reportsArt(LocalFocusedArt.current, item.image)
                .focusRequester(self)
                .onMenuKey(sheet)
                .semantics { contentDescription = "${item.title}, ${item.episode}, ${item.timeLeft}" },
            shape = ClickableSurfaceDefaults.shape(TileShape),
            colors = tileColors(),
            scale = cardLift,
            border = focusRing(TileShape),
        ) {
            TitleArt(item.id, item.image, lateNight, Modifier.fillMaxSize())
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
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall.onWallpaper(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = "${item.episode} · ${item.timeLeft}",
            style = MaterialTheme.typography.bodySmall.onWallpaper(),
            color = Palette.TextDim,
            maxLines = 1,
        )
    }
}
