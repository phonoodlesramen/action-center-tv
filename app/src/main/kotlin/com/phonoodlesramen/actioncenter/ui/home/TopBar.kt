package com.phonoodlesramen.actioncenter.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.phonoodlesramen.actioncenter.context.ContextMode
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.home.PlexStatus
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.setup.LocalSetup
import com.phonoodlesramen.actioncenter.setup.SetupChip
import com.phonoodlesramen.actioncenter.ui.profiles.argb
import com.phonoodlesramen.actioncenter.ui.profiles.initialOf
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalCardSurface
import com.phonoodlesramen.actioncenter.ui.wallpaper.cardSurface
import com.phonoodlesramen.actioncenter.update.LocalUpdates
import com.phonoodlesramen.actioncenter.update.UpdateChip
import com.phonoodlesramen.actioncenter.weather.Sky
import com.phonoodlesramen.actioncenter.weather.WeatherDisplay
import com.phonoodlesramen.actioncenter.ui.theme.onWallpaper
import java.time.format.DateTimeFormatter

private val ClockFormat = DateTimeFormatter.ofPattern("h:mm")
private val AmPmFormat = DateTimeFormatter.ofPattern("a")

/**
 * Clock, greeting, context chip, weather, Plex status, gear and avatar (spec §4). When the
 * stock launcher is back, a red chip replaces the context chip. While Plex syncs, a spinner
 * and "Updating" follow the Plex status ([com.phonoodlesramen.actioncenter.home.Updating]).
 *
 * The magnifier (Phase 15) opens Search.
 *
 * The avatar is whoever is on (Phase 5): their initial on their colour, and OK opens "Who's
 * watching?" (K1). On weekend mornings, while you're on, the kids' avatars sit small before
 * the gear, each a one-press switch (K11).
 */
@Composable
fun TopBar(
    state: HomeUiState,
    actions: HomeActions,
    gear: FocusRequester,
    modifier: Modifier = Modifier,
    avatar: FocusRequester? = null,
    search: FocusRequester? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.now.format(ClockFormat), style = MaterialTheme.typography.titleLarge.onWallpaper())
            Text(
                text = state.now.format(AmPmFormat),
                style = MaterialTheme.typography.titleSmall.onWallpaper(),
                color = Palette.TextDim,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        Text(
            text = state.greeting,
            style = MaterialTheme.typography.titleMedium.onWallpaper(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val setupChip = LocalSetup.current.chip(kid = state.profile.kid)
        val updateChip = UpdateChip.of(LocalUpdates.current, kid = state.profile.kid)
        when {
            state.stockLauncherBack -> StockLauncherChip(onClick = actions::openDiagnostics)
            updateChip != null -> UpdateChipButton(updateChip, onClick = actions::openUpdate)
            setupChip != null -> SetupChipButton(setupChip, onClick = actions::openSetup)
            else -> Chip(dot = LocalAccent.current, label = state.mode.label)
        }
        state.weatherNow?.let { WeatherLabel(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlexStatusLabel(state.plexStatus)
            // It slides in and out, so the chips to its left move rather than jump.
            AnimatedVisibility(
                visible = state.plex.updating,
                enter = fadeIn(tween(UPDATING_FADE_MILLIS)) + expandHorizontally(tween(UPDATING_FADE_MILLIS)),
                exit = fadeOut(tween(UPDATING_FADE_MILLIS)) + shrinkHorizontally(tween(UPDATING_FADE_MILLIS)),
            ) {
                UpdatingLabel(Modifier.padding(start = 12.dp))
            }
        }
        val kids = quickSwitch(state)
        if (kids.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kids.forEach { kid -> KidAvatar(kid, onClick = { actions.switchProfile(kid.id) }) }
            }
        }
        // Phase 15 (Q5): Search, the gear's size, just before it; Back from Search comes back here.
        CircleButton(onClick = actions::openSearch, modifier = search?.let { Modifier.focusRequester(it) } ?: Modifier) {
            Icon(AppIcons.Search, contentDescription = "Search", modifier = Modifier.size(18.dp), tint = Palette.TextDim)
        }
        CircleButton(onClick = actions::openSettings, modifier = Modifier.focusRequester(gear)) {
            Icon(AppIcons.Gear, contentDescription = "Settings", modifier = Modifier.size(18.dp), tint = Palette.TextDim)
        }
        Avatar(
            profile = state.profile,
            // A kid's own colour; yours is the accent, dimmed late at night like the rest.
            color = state.profile.color?.let(::argb) ?: LocalAccent.current,
            onClick = actions::openProfiles,
            modifier = avatar?.let { Modifier.focusRequester(it) } ?: Modifier,
        )
    }
}

/** K11: the kids to show beside your avatar, on weekend mornings while you're on; none otherwise. */
fun quickSwitch(state: HomeUiState): List<ActiveProfile> =
    if (state.mode == ContextMode.WEEKEND_MORNING && !state.profile.kid) state.profiles.filter { it.kid } else emptyList()

@Composable
private fun Chip(dot: Color, label: String) {
    Row(
        modifier = Modifier
            .height(28.dp)
            .cardSurface(LocalCardSurface.current, RoundedCornerShape(50), Palette.Surface2)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).background(dot, CircleShape))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Spec §4 [PROP]: replaces the context chip and opens Settings › Diagnostics. */
@Composable
private fun StockLauncherChip(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.height(28.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Warning,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Bad,
            focusedContentColor = Palette.AccentInk,
        ),
        scale = cardLift,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp).align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(AppIcons.Warning, contentDescription = null, modifier = Modifier.size(14.dp))
            Text("Stock launcher is back", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * Setup without a computer: "Finish setup · 2 left", or "Home button is off" when Android
 * turned HomeWatch off. In the context chip's place, like the stock launcher's chip, and
 * OK opens Settings › Setup. The warning is in the caution colour; the reminder isn't.
 */
@Composable
private fun SetupChipButton(chip: SetupChip, onClick: () -> Unit) {
    val warning = chip is SetupChip.HomeButtonOff
    Surface(
        onClick = onClick,
        modifier = Modifier.height(28.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (warning) Palette.Warning else Palette.Surface2,
            contentColor = Palette.Text,
            focusedContainerColor = if (warning) Palette.Bad else LocalAccent.current,
            focusedContentColor = Palette.AccentInk,
        ),
        scale = cardLift,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp).align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(if (warning) AppIcons.Warning else AppIcons.Gear, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(chip.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * In-app updates (A6): "Update available · 1.1.0" or "Update ready · 1.1.0", in the context
 * chip's place, below the stock launcher's chip and above Finish setup. OK opens Settings ›
 * About, or installs a downloaded one.
 */
@Composable
private fun UpdateChipButton(chip: UpdateChip, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.height(28.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface2,
            contentColor = Palette.Text,
            focusedContainerColor = LocalAccent.current,
            focusedContentColor = Palette.AccentInk,
        ),
        scale = cardLift,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp).align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(AppIcons.Sync, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(chip.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Icon and temperature; not focusable, nothing to do here (spec §4). */
@Composable
private fun WeatherLabel(weather: WeatherDisplay) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(weatherIcon(weather.sky, weather.isDay), contentDescription = weather.condition, modifier = Modifier.size(20.dp), tint = Palette.TextDim)
        Text(weather.temperature, style = MaterialTheme.typography.titleMedium.onWallpaper(), maxLines = 1)
    }
}

fun weatherIcon(sky: Sky, isDay: Boolean): ImageVector = when (sky) {
    Sky.CLEAR -> if (isDay) AppIcons.Sun else AppIcons.Moon
    Sky.PARTLY_CLOUDY -> if (isDay) AppIcons.PartlyCloudy else AppIcons.PartlyCloudyNight
    Sky.CLOUDY -> AppIcons.Cloud
    Sky.FOG -> AppIcons.Fog
    Sky.DRIZZLE -> AppIcons.Drizzle
    Sky.RAIN -> AppIcons.Rain
    Sky.SNOW -> AppIcons.Snow
    Sky.THUNDER -> AppIcons.Thunder
}

@Composable
private fun PlexStatusLabel(status: PlexStatus) {
    val dot = when (status) {
        PlexStatus.CONNECTED -> Palette.Good
        PlexStatus.OFFLINE -> Palette.Bad
        PlexStatus.SYNC_FAILED -> Palette.Caution
        PlexStatus.NOT_CONNECTED -> Palette.TextFaint
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(status.label, style = MaterialTheme.typography.labelLarge.onWallpaper(), color = Palette.TextDim, maxLines = 1)
    }
}

/** A sync is running: a small spinner in the accent and "Updating", dim like the Plex status. */
@Composable
private fun UpdatingLabel(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Spinner(color = LocalAccent.current, modifier = Modifier.size(12.dp))
        Text("Updating", style = MaterialTheme.typography.labelLarge.onWallpaper(), color = Palette.TextDim, maxLines = 1)
    }
}

/** A quarter arc that turns once a second on a faint ring. The turn is drawn in the layer, so it never recomposes. */
@Composable
private fun Spinner(color: Color, modifier: Modifier = Modifier) {
    val turn by rememberInfiniteTransition(label = "updating").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(SPIN_MILLIS, easing = LinearEasing)),
        label = "turn",
    )
    Canvas(modifier.graphicsLayer { rotationZ = turn }) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2
        val ring = Size(size.width - stroke.width, size.height - stroke.width)
        drawArc(Palette.TextFaint.copy(alpha = 0.4f), 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = ring, style = stroke)
        drawArc(color, -90f, 90f, useCenter = false, topLeft = Offset(inset, inset), size = ring, style = stroke)
    }
}

private const val SPIN_MILLIS = 1_000
private const val UPDATING_FADE_MILLIS = 200

@Composable
private fun CircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Palette.Surface2,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(36.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = tileColors(container),
        scale = cardLift,
        border = focusRing(CircleShape),
    ) {
        Box(Modifier.align(Alignment.Center)) { content() }
    }
}

/** Whoever is on: their initial on [color], or a person when there's no name. OK opens "Who's watching?". */
@Composable
private fun Avatar(profile: ActiveProfile, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val initial = initialOf(profile.name)
    CircleButton(
        onClick = onClick,
        container = color,
        modifier = modifier.semantics { contentDescription = "Who's watching? ${profile.name}".trim() },
    ) {
        if (initial != null) {
            Text(initial, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = Palette.AccentInk)
        } else {
            Icon(AppIcons.Person, contentDescription = null, modifier = Modifier.size(20.dp), tint = Palette.AccentInk)
        }
    }
}

/** A kid's avatar on a weekend morning (K11): 28 dp, a little faded until focused; OK switches to them. */
@Composable
private fun KidAvatar(kid: ActiveProfile, onClick: () -> Unit) {
    val color = kid.color?.let(::argb) ?: Palette.Surface3
    Surface(
        onClick = onClick,
        modifier = Modifier.size(28.dp).semantics { contentDescription = "Switch to ${kid.name}" },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = color.copy(alpha = 0.85f),
            contentColor = Palette.AccentInk,
            focusedContainerColor = color,
            focusedContentColor = Palette.AccentInk,
        ),
        scale = cardLift,
        border = focusRing(CircleShape),
    ) {
        initialOf(kid.name)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = Palette.AccentInk,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
