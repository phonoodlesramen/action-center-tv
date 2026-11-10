package com.phonoodlesramen.actioncenter.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.LensPick
import com.phonoodlesramen.actioncenter.home.LensCardState
import com.phonoodlesramen.actioncenter.ui.art.ArtFilters
import com.phonoodlesramen.actioncenter.ui.art.LogoArt
import com.phonoodlesramen.actioncenter.ui.art.LogoCheck
import com.phonoodlesramen.actioncenter.ui.art.TitleArt
import com.phonoodlesramen.actioncenter.ui.art.rememberLogoBox
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette

/**
 * A lens's colours (spec §4): its gradient runs [light] → [dark], and the eyebrow
 * colour also sets the text and buttons. Throwback and Classic art goes sepia.
 */
data class LensStyle(val light: Color, val dark: Color, val eyebrow: Color, val sepia: Boolean = false) {
    val text: Color get() = lerp(eyebrow, Color.White, 0.55f)
    val meta: Color get() = lerp(eyebrow, Color.White, 0.3f)
    val gradient: Brush
        get() = Brush.linearGradient(0f to light, 0.6f to dark, 1f to Palette.Surface, start = Offset.Zero, end = Offset.Infinite)
    val scrim: Brush get() = Brush.horizontalGradient(listOf(dark, dark.copy(alpha = 0.2f)))

    /** The gradient's lightest stop: what a logo on the card is read against (Phase 12, [LogoCheck]). */
    val lightest: Color get() = listOf(light, dark, Palette.Surface).maxBy(LogoCheck::luminance)
}

fun lensStyle(lens: Lens): LensStyle = when (lens) {
    Lens.THROWBACK -> LensStyle(Palette.WarmLight, Palette.WarmDark, Palette.WarmEyebrow, sepia = true)
    Lens.CLASSIC -> LensStyle(Color(0xFF3D3219), Color(0xFF2A2313), Color(0xFFE5C77A), sepia = true)
    Lens.FAMILY -> LensStyle(Color(0xFF1E3F3A), Color(0xFF142A27), Color(0xFF8FD3C3))
    Lens.ACTION -> LensStyle(Color(0xFF45201F), Color(0xFF2E1516), Color(0xFFF2A08A))
    Lens.HIDDEN_GEM -> LensStyle(Color(0xFF1C2D49), Color(0xFF132036), Color(0xFF9EC1F2))
    Lens.FINISH_SERIES -> LensStyle(Color(0xFF2D2449), Color(0xFF1E1832), Color(0xFFC2B1F0))
    Lens.SEASONAL -> LensStyle(Color(0xFF3F2410), Color(0xFF2A180B), Color(0xFFFFA45C))
    Lens.COMFORT -> LensStyle(Color(0xFF382337), Color(0xFF261825), Color(0xFFE3AFD9))
}

/** More like this (S10) isn't a lens: a neutral surface with the accent as its eyebrow. */
fun moreLikeStyle(accent: Color) = LensStyle(Palette.Surface2, Palette.Surface, accent)

/** The card's colours for what it shows now. */
fun LensCardState.style(accent: Color): LensStyle = if (moreLike != null) moreLikeStyle(accent) else lensStyle(lens)

/** Watch again for a title the profile on now has seen (theirs, for a kid), Watch otherwise. */
internal fun watchLabel(pick: LensPick): String = if (pick.seen) "Watch again" else "Watch"

/**
 * The lens card's quick actions (spec §4, S10), yours and a kid's: Watch (again), Details,
 * Another lens (not while [shuffling], L4: Next already changes lens), and Don't show this,
 * after which the card stays and moves on, so focus comes back to [watch]. Feedback is the
 * profile on now's.
 */
internal fun lensSheet(pick: LensPick, actions: HomeActions, returnTo: FocusRequester, watch: FocusRequester, shuffling: Boolean = false) = QuickSheet(
    title = pick.title,
    returnTo = returnTo,
    items = listOfNotNull(
        SheetItem(AppIcons.Play, watchLabel(pick)) { actions.play(pick.id, pick.title) },
        SheetItem(AppIcons.Info, "Details") { actions.say(NOT_YET) },
        if (shuffling) null else SheetItem(AppIcons.Spark, "Another lens") { actions.nextLens() },
        SheetItem(AppIcons.Hide, "Don't show this", returnTo = watch) { actions.dontShowLensPick() },
    ),
)

/**
 * What moves the lens card by itself (Phase 14): every [rotateSeconds] (L1; null while it
 * doesn't), and whether it's [shuffling] (L4). While something is over the home ([covered]:
 * a sheet, Now showing, the art picker) it neither moves nor counts as shown.
 */
@Immutable
data class LensMotion(val rotateSeconds: Int? = null, val shuffling: Boolean = false, val covered: Boolean = false)

/** On screen this long, a title counts as a day on the card (L5). */
internal const val SHOWN_AFTER_MILLIS = 3_000L

/**
 * The lens card's clock (Phase 14). While it's on screen (the home resumed, the card at
 * least half in view, nothing over it), a title shown for [SHOWN_AFTER_MILLIS] counts as a
 * day on the card (L5), and with rotation on the card moves on (Next) every
 * [LensMotion.rotateSeconds] while focus is elsewhere (L1). A new title, focus leaving or the
 * home coming back starts the wait over, so a title never changes under you.
 */
@Composable
internal fun LensClock(state: LensCardState, motion: LensMotion, focused: Boolean, visible: Boolean, actions: HomeActions) {
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    val onScreen = resumed && visible && !motion.covered
    val latest by rememberUpdatedState(actions)
    LaunchedEffect(state, onScreen) {
        if (!onScreen || state.moreLike != null) return@LaunchedEffect
        delay(SHOWN_AFTER_MILLIS)
        latest.lensShown(state.pick)
    }
    val seconds = motion.rotateSeconds
    LaunchedEffect(state, onScreen, focused, seconds) {
        if (seconds == null || !onScreen || focused) return@LaunchedEffect
        delay(seconds * 1_000L)
        latest.nextLensPick()
    }
}

/** At least half of it inside the window: not scrolled away under the rows. */
internal fun LayoutCoordinates.mostlyInView(): Boolean = size.height > 0 && boundsInWindow().height >= size.height / 2f

/**
 * The lens card's Back (L3): an arrow in a circle at its top left, in the lens's colours, as
 * Watch and Next are. Up from Watch reaches it.
 */
@Composable
internal fun LensBack(style: LensStyle, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = LENS_BACK) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = style.eyebrow.copy(alpha = 0.12f),
            contentColor = lerp(style.eyebrow, Color.White, 0.7f),
            focusedContainerColor = lerp(style.eyebrow, Color.White, 0.15f),
            focusedContentColor = style.dark,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Icon(AppIcons.Back, contentDescription = "Back", modifier = Modifier.size(size * 0.64f).align(Alignment.Center))
    }
}

/** The lens card's Back circle (L3). */
private val LENS_BACK = 22.dp

/** A new title fades in over this long, its art and its words together (L1). */
internal const val LENS_FADE_MILLIS = 400

/** The lens card's logo box (Phase 12): up to 30 dp tall, in the title's place. */
private val LENS_LOGO = 30.dp

/** The lens card's side padding. */
private val LENS_PADDING = 14.dp

/** Above the eyebrow's row: the 12 it had, less what Back's circle (22) takes over the eyebrow (16). */
private val LENS_TOP = 6.dp

/** Besides the title: the padding (6 + 12), the eyebrow's row with Back (22) and the buttons (30). */
private val LENS_FIXED = 70.dp

/** The line (16) and its padding (2), when the card has room for it. */
private val LENS_LINE = 18.dp

/**
 * The lens card (spec §4, U17): one title through one lens, tinted by it. Watch (again)
 * plays; Next shows the lens's next title and then runs on into the next lens; Back (L3,
 * Up from Watch) the title it showed before. With [motion] it moves on by itself (L1).
 * [watch] is where Right from the hero lands. A logo (Phase 12) takes the title's place.
 */
@Composable
fun LensCard(
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
    val next = remember { FocusRequester() }
    val back = remember { FocusRequester() }
    val watchLabel = watchLabel(pick)
    var focused by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LensClock(state, motion, focused, visible, actions)
    fun sheet(returnTo: FocusRequester) = lensSheet(pick, actions, returnTo, watch, motion.shuffling)
    BoxWithConstraints(
        modifier
            .onFocusChanged { focused = it.hasFocus }
            .onGloballyPositioned { visible = it.mostlyInView() }
            .clip(RoundedCornerShape(Layout.CardRadius))
            .background(style.gradient),
    ) {
        // At larger layout scales the card has room for the title or the line, not both.
        val roomForLine = maxHeight >= 112.dp
        val titleStyle = MaterialTheme.typography.titleLarge
        val density = LocalDensity.current
        val logoWidthPx = constraints.maxWidth - with(density) { (LENS_PADDING * 2).roundToPx() }
        // Phase 12: the logo, up to 30 dp tall and never taller than the card leaves the title.
        val logoHeight = minOf(LENS_LOGO, maxHeight - LENS_FIXED - if (roomForLine) LENS_LINE else 0.dp)
        val titleLine = with(density) { titleStyle.lineHeight.toDp() }
        Crossfade(
            targetState = state,
            animationSpec = tween(LENS_FADE_MILLIS),
            label = "lens art",
            modifier = Modifier.fillMaxHeight().fillMaxWidth(0.55f).align(Alignment.CenterEnd),
        ) { shownState ->
            val shown = shownState.style(accent)
            Box(Modifier.fillMaxSize()) {
                TitleArt(shownState.pick.id, shownState.pick.image, lateNight, Modifier.fillMaxSize(), if (shown.sepia) ArtFilters.Sepia else null)
                Box(Modifier.matchParentSize().background(shown.scrim))
            }
        }
        Column(Modifier.fillMaxSize().padding(start = LENS_PADDING, end = LENS_PADDING, top = LENS_TOP, bottom = 12.dp)) {
            // L3: Back sits in the eyebrow's row, in the top padding's room, so nothing below moves.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LensBack(
                    style = style,
                    onClick = actions::previousLensPick,
                    modifier = Modifier.focusRequester(back).focusProperties { down = watch },
                )
                // L1: the words fade with the art, so a title moving on by itself never jumps.
                Crossfade(state, animationSpec = tween(LENS_FADE_MILLIS), label = "lens eyebrow") { shownState ->
                    Eyebrow(shownState.title, color = shownState.style(accent).eyebrow)
                }
            }
            Crossfade(state, animationSpec = tween(LENS_FADE_MILLIS), label = "lens words") { shownState ->
                val shown = shownState.style(accent)
                val shownPick = shownState.pick
                val logo = rememberLogoBox(path = shownPick.logo, widthPx = logoWidthPx, height = logoHeight, min = titleLine)
                Column {
                    if (roomForLine) {
                        Text(
                            text = shownPick.line,
                            style = MaterialTheme.typography.bodySmall,
                            color = shown.text.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    // The title (or its logo) wins the space; the year and runtime give way.
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val title = @Composable { Text(text = shownPick.title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        if (logo == null) {
                            title()
                        } else {
                            LogoArt(logo.url, logo.widthPx, logo.heightPx, shownPick.title, shown.lightest, lateNight, Modifier.height(logo.height), title)
                        }
                        Text(
                            text = shownPick.meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = shown.meta,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).padding(bottom = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            val colors = pillButtonColors(
                container = style.eyebrow.copy(alpha = 0.12f),
                content = lerp(style.eyebrow, Color.White, 0.7f),
                focused = lerp(style.eyebrow, Color.White, 0.15f),
                focusedInk = style.dark,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    text = watchLabel,
                    small = true,
                    colors = colors,
                    onClick = { actions.play(pick.id, pick.title) },
                    onLongClick = { openSheet(sheet(watch)) },
                    modifier = Modifier.focusRequester(watch).focusProperties { up = back }.onMenuKey { openSheet(sheet(watch)) },
                )
                PillButton(
                    text = "Next",
                    small = true,
                    colors = colors,
                    onClick = actions::nextLensPick,
                    onLongClick = { openSheet(sheet(next)) },
                    modifier = Modifier.focusRequester(next).focusProperties { up = back }.onMenuKey { openSheet(sheet(next)) },
                )
            }
        }
    }
}
