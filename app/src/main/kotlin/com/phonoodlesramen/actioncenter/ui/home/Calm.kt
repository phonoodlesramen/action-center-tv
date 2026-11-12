package com.phonoodlesramen.actioncenter.ui.home

import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.home.StripCard
import com.phonoodlesramen.actioncenter.nfl.GameState
import com.phonoodlesramen.actioncenter.nfl.WatchOptions
import com.phonoodlesramen.actioncenter.ui.nfl.matchup
import com.phonoodlesramen.actioncenter.ui.nfl.waysFor
import com.phonoodlesramen.actioncenter.ui.art.TitleArt
import com.phonoodlesramen.actioncenter.ui.profiles.argb
import com.phonoodlesramen.actioncenter.ui.profiles.initialOf
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.onWallpaper
import com.phonoodlesramen.actioncenter.ui.theme.safeArea
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.ScreenCare
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Calm's focus targets: Play (U10), and the avatar, Search and Guide, where Back from their screens lands. */
internal class CalmFocus(
    val play: FocusRequester,
    val avatar: FocusRequester?,
    val search: FocusRequester?,
    val guide: FocusRequester,
)

private val ClockFormat = DateTimeFormatter.ofPattern("h:mm")
private val AmPmFormat = DateTimeFormatter.ofPattern("a")
private val DateFormat = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)

/** How many of your apps the dock holds, in the Apps row's order (M5), before Guide and Search. */
internal const val DOCK_APPS = 5

/**
 * The Calm layout (spec §4, Home layouts, H6): quiet until you ask. The wallpaper, a big clock
 * with the date and weather, one line for tonight with Play ([line]: Tonight's Pick, or a
 * moment's lead, H3), and a dock of your most-used apps plus Guide and Search. Down from the
 * dock, or OK on More for tonight, brings the dashboard up as a sheet ([onMore]); Back drops it.
 *
 * The clock and date drift a few dp each minute for OLED care ([ScreenCare]).
 */
@Composable
internal fun Calm(
    state: HomeUiState,
    line: StripCard,
    apps: List<LaunchableApp>,
    actions: HomeActions,
    focus: CalmFocus,
    lateNight: Boolean,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    watch: WatchOptions = WatchOptions(),
) {
    val drift by with(LocalDensity.current) {
        val (x, y) = ScreenCare.drift(state.now.hour * 60 + state.now.minute)
        animateIntOffsetAsState(IntOffset(x.dp.roundToPx(), y.dp.roundToPx()), tween(DRIFT_MILLIS), label = "calm drift")
    }
    Box(modifier.fillMaxSize().safeArea()) {
        focus.avatar?.let { avatar -> CalmAvatar(state, actions, avatar, Modifier.align(Alignment.TopEnd)) }
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(CLOCK_TOP))
            Row(Modifier.offset { drift }, verticalAlignment = Alignment.Bottom) {
                Text(
                    text = state.now.format(ClockFormat),
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 112.sp, lineHeight = 112.sp, fontWeight = FontWeight.Light, letterSpacing = (-3).sp).onWallpaper(),
                )
                Text(
                    text = state.now.format(AmPmFormat),
                    style = MaterialTheme.typography.headlineMedium.onWallpaper(),
                    color = Palette.TextDim,
                    modifier = Modifier.padding(start = 10.dp, bottom = 14.dp),
                )
            }
            DateLine(state, Modifier.offset { drift }.padding(start = 4.dp, top = 6.dp))
            Spacer(Modifier.height(24.dp))
            TonightLine(line, lateNight, actions, focus.play, watch)
            Spacer(Modifier.height(28.dp))
            Dock(apps, actions, focus)
            Spacer(Modifier.weight(1f))
            MoreForTonight(onMore, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** The clock's top, below the safe margin (the mockup's 96 dp less 27). */
private val CLOCK_TOP = 40.dp

/** The drift moves smoothly to its new spot, as Now showing's does. */
private const val DRIFT_MILLIS = 1_500

/** Play's label on the one line: the pick's own ("Play S2E9"), Watch again, or the game's Watch. */
private fun playLabel(line: StripCard, watch: WatchOptions): String = when (line) {
    is StripCard.Tonight -> line.pick.playLabel
    is StripCard.LensTitle -> watchLabel(line.state.pick)
    is StripCard.Game -> if (gameWay(line, watch) != null) "Watch" else "Game details"
    is StripCard.UpNext, is StripCard.Paused -> "Resume"
    else -> "Play"
}

/** OK on Play: the title plays; a game opens in the first app that has it, else its details. */
private fun primaryAction(line: StripCard, actions: HomeActions, watch: WatchOptions) {
    val way = (line as? StripCard.Game)?.let { gameWay(it, watch) }
    if (line is StripCard.Game && way != null) actions.watch(way, matchup(line.game, line.view)) else primaryAction(line, actions)
}

private fun gameWay(line: StripCard.Game, watch: WatchOptions) =
    waysFor(line.game, line.view, watch).firstOrNull { it.installed }.takeIf { line.game.state != GameState.FINAL }

/** "Wednesday, November 11 · ☁ 54°, Overcast" */
@Composable
private fun DateLine(state: HomeUiState, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val style = MaterialTheme.typography.titleMedium.onWallpaper()
        Text(state.now.format(DateFormat), style = style, color = Palette.TextDim, maxLines = 1)
        state.weatherNow?.let { weather ->
            Text("·", style = style, color = Palette.TextDim)
            Icon(weatherIcon(weather.sky, weather.isDay), contentDescription = null, modifier = Modifier.size(20.dp), tint = Palette.TextDim)
            Text("${weather.temperature}, ${weather.condition.lowercase(Locale.US)}", style = style, color = Palette.TextDim, maxLines = 1)
        }
    }
}

/**
 * Tonight's one line: a frosted strip with the title's art, "Tonight" (or the moment's
 * eyebrow), the title and its reason, and Play on the right, where focus starts.
 */
@Composable
private fun TonightLine(line: StripCard, lateNight: Boolean, actions: HomeActions, play: FocusRequester, watch: WatchOptions) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .widthIn(max = LINE_WIDTH)
            .fillMaxWidth()
            .height(LINE_HEIGHT)
            .clip(shape)
            .background(Palette.Surface.copy(alpha = 0.72f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TitleArt(line.artId, line.image, lateNight, Modifier.size(width = 108.dp, height = 61.dp).clip(RoundedCornerShape(Layout.TileRadius)))
        Column(Modifier.weight(1f)) {
            Eyebrow(if (line is StripCard.Tonight) "Tonight" else line.eyebrow)
            Text(line.title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line.reason, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PillButton(
            text = playLabel(line, watch),
            onClick = { primaryAction(line, actions, watch) },
            icon = AppIcons.Play,
            modifier = Modifier.focusRequester(play),
        )
    }
}

/** The one line's width and height (the mockup's 620 × 86). */
private val LINE_WIDTH = 620.dp
private val LINE_HEIGHT = 86.dp

/** The dock: your five most-used apps in the Apps row's order, then Guide and Search. */
@Composable
private fun Dock(apps: List<LaunchableApp>, actions: HomeActions, focus: CalmFocus) {
    Row(Modifier.focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        apps.take(DOCK_APPS).forEach { app ->
            AppTile(app, onClick = { actions.launch(app) }, modifier = Modifier.size(width = 112.dp, height = 63.dp).semantics { contentDescription = app.label })
        }
        DockIcon(AppIcons.Guide, "Guide", { actions.openGuide() }, Modifier.focusRequester(focus.guide))
        DockIcon(AppIcons.Search, "Search", actions::openSearch, focus.search?.let { Modifier.focusRequester(it) } ?: Modifier)
    }
}

@Composable
private fun DockIcon(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.size(63.dp).semantics { contentDescription = label },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = tileColors(Palette.Surface2.copy(alpha = 0.75f)),
        scale = cardLift,
        border = focusRing(shape),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/**
 * "More for tonight": focus coming to it (Down from the dock) or OK brings the dashboard up as a
 * sheet, so two Downs from Play reach it, as the mockup has.
 */
@Composable
private fun MoreForTonight(onMore: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onMore,
        modifier = modifier.height(30.dp).onFocusChanged { if (it.isFocused) onMore() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.TextDim,
            focusedContainerColor = Palette.Surface3,
            focusedContentColor = Palette.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Back's arrow, turned to point down: Down brings the sheet up.
            Icon(AppIcons.Back, contentDescription = null, modifier = Modifier.size(16.dp).rotate(-90f))
            Text("More for tonight", style = MaterialTheme.typography.labelLarge.onWallpaper())
        }
    }
}

/** Whoever is on, at the top right: OK opens "Who's watching?" (K1). */
@Composable
private fun CalmAvatar(state: HomeUiState, actions: HomeActions, self: FocusRequester, modifier: Modifier = Modifier) {
    val color = state.profile.color?.let(::argb) ?: LocalAccent.current
    Surface(
        onClick = actions::openProfiles,
        modifier = modifier.size(36.dp).focusRequester(self).semantics { contentDescription = "Who's watching? ${state.profile.name}".trim() },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = color, contentColor = Palette.AccentInk, focusedContainerColor = color, focusedContentColor = Palette.AccentInk),
        scale = cardLift,
        border = focusRing(CircleShape),
    ) {
        initialOf(state.profile.name)?.let {
            Text(it, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = Palette.AccentInk, modifier = Modifier.align(Alignment.Center))
        } ?: Icon(AppIcons.Person, contentDescription = null, modifier = Modifier.size(20.dp).align(Alignment.Center), tint = Palette.AccentInk)
    }
}
