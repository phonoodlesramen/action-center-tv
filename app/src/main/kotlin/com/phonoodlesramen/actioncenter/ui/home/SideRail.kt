package com.phonoodlesramen.actioncenter.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.ui.nfl.NflOpen
import com.phonoodlesramen.actioncenter.ui.profiles.argb
import com.phonoodlesramen.actioncenter.ui.profiles.initialOf
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette

/**
 * The side rail's focus targets: the profile, Search and Settings (the top bar's, which move
 * here), Home, its entry, and NFL, where Back from the NFL screen it opened lands.
 */
internal class RailFocus(
    val avatar: FocusRequester,
    val search: FocusRequester,
    val gear: FocusRequester,
    val home: FocusRequester = FocusRequester(),
    val nfl: FocusRequester = FocusRequester(),
)

/**
 * The side rail (spec §4, Home layouts, H4): tv-material's [ModalNavigationDrawer] beside any
 * layout, with the profile, Search, Home, Guide, the NFL (while it shows), Apps and Settings.
 * Closed, it's a column of icons [RAIL_WIDTH] wide; focus on it opens it, with labels, over the
 * home behind a scrim.
 *
 * - Left from the start of a panel or row opens it on Home ([RailFocus.home]).
 * - Right or Back returns to the card you left: [content] is the home's focus group, whose
 *   restorer remembers it.
 * - OK opens that screen; Home goes back to the top of the home ([onHome]).
 */
@Composable
internal fun SideRail(
    state: HomeUiState,
    actions: HomeActions,
    focus: RailFocus,
    content: FocusRequester,
    guide: GuideCardState,
    nfl: Boolean,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
    home: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // Left into the rail lands on Home, once the move into it is done: moving focus again inside that
    // move would lose the card the home remembers for Right and Back.
    var toHome by remember { mutableStateOf(false) }
    LaunchedEffect(toHome) {
        if (!toHome) return@LaunchedEffect
        runCatching { focus.home.requestFocus() }
        toHome = false
    }
    val focusManager = LocalFocusManager.current
    // Back in the rail returns to the home as Right does; elsewhere, the home's own Back handling stands.
    BackHandler(enabled = open) { if (!focusManager.moveFocus(FocusDirection.Right)) runCatching { content.requestFocus() } }
    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = rememberDrawerState(DrawerValue.Closed),
        scrimBrush = Brush.horizontalGradient(listOf(Palette.Scrim.copy(alpha = 0.75f), Color.Transparent)),
        drawerContent = { _ ->
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(RailBackground)
                    .padding(start = RAIL_START, end = RAIL_END, top = 27.dp, bottom = 27.dp)
                    .onFocusChanged { open = it.hasFocus }
                    .focusProperties {
                        // Left into the rail lands on Home (the mockup); a screen's Back lands on its own item.
                        onEnter = { if (requestedFocusDirection == FocusDirection.Left) toHome = true }
                    }
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RailItems(state, actions, focus, guide, nfl, onHome)
                Spacer(Modifier.weight(1f))
                RailItem(AppIcons.Gear, "Settings", actions::openSettings, Modifier.focusRequester(focus.gear))
            }
        },
        content = home,
    )
}

/** The rail closed: its padding and NavigationDrawerItem's collapsed width; the home sits [RAIL_GAP] beyond it. */
internal val RAIL_START = 28.dp
internal val RAIL_END = 12.dp
internal val RAIL_GAP = 12.dp
internal val RAIL_WIDTH: Dp = RAIL_START + NavigationDrawerItemDefaults.CollapsedDrawerItemWidth + RAIL_END

/** The rail's dark panel, a touch see-through at its edge (the mockup's). */
private val RailBackground = Brush.horizontalGradient(
    0.6f to Color(0xFF0E1318).copy(alpha = 0.99f),
    1f to Color(0xFF0E1318).copy(alpha = 0.94f),
)

@Composable
private fun NavigationDrawerScope.RailItems(
    state: HomeUiState,
    actions: HomeActions,
    focus: RailFocus,
    guide: GuideCardState,
    nfl: Boolean,
    onHome: () -> Unit,
) {
    val profile = state.profile
    NavigationDrawerItem(
        selected = false,
        onClick = actions::openProfiles,
        leadingContent = {
            val color = profile.color?.let(::argb) ?: LocalAccent.current
            Box(Modifier.size(28.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                val initial = initialOf(profile.name)
                if (initial != null) {
                    Text(initial, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = Palette.AccentInk)
                } else {
                    Icon(AppIcons.Person, contentDescription = null, modifier = Modifier.size(18.dp), tint = Palette.AccentInk)
                }
            }
        },
        modifier = Modifier.focusRequester(focus.avatar).semantics { contentDescription = "Who's watching? ${profile.name}".trim() },
        colors = railColors(),
    ) { Label(profile.name.ifBlank { "Who's watching?" }) }
    RailItem(AppIcons.Search, "Search", actions::openSearch, Modifier.focusRequester(focus.search))
    RailItem(AppIcons.Home, "Home", onHome, Modifier.focusRequester(focus.home), selected = true)
    val channels = (guide as? GuideCardState.Ready)?.channels?.size
    RailItem(AppIcons.Guide, "Guide", { actions.openGuide() }, supporting = channels?.let { if (it == 1) "1 channel" else "$it channels" })
    if (nfl) RailItem(AppIcons.Football, "NFL", { actions.openNfl(NflOpen(opener = NflOpen.RAIL)) }, Modifier.focusRequester(focus.nfl))
    RailItem(AppIcons.Grid, "Apps", actions::openAllApps)
}

@Composable
private fun NavigationDrawerScope.RailItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    supporting: String? = null,
) {
    NavigationDrawerItem(
        selected = selected,
        onClick = onClick,
        leadingContent = { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
        supportingContent = supporting?.let { { Text(it, maxLines = 1) } },
        modifier = modifier.semantics { contentDescription = label },
        colors = railColors(),
    ) { Label(label) }
}

/** Focused fills with the accent, as buttons do (spec §3); Home, where you are, is a quiet surface. */
@Composable
private fun railColors() = NavigationDrawerItemDefaults.colors(
    containerColor = Color.Transparent,
    contentColor = Palette.TextDim,
    inactiveContentColor = Palette.TextDim,
    focusedContainerColor = LocalAccent.current,
    focusedContentColor = Palette.AccentInk,
    selectedContainerColor = Palette.Surface2,
    selectedContentColor = Palette.Text,
    focusedSelectedContainerColor = LocalAccent.current,
    focusedSelectedContentColor = Palette.AccentInk,
)

@Composable
private fun Label(text: String) = Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
