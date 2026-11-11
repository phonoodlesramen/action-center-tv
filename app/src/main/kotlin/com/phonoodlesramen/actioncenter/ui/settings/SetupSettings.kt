package com.phonoodlesramen.actioncenter.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.setup.HomeRoute
import com.phonoodlesramen.actioncenter.setup.SetupPermission
import com.phonoodlesramen.actioncenter.setup.SetupStatus
import com.phonoodlesramen.actioncenter.ui.home.PillButton
import com.phonoodlesramen.actioncenter.ui.home.pillButtonColors
import com.phonoodlesramen.actioncenter.ui.theme.Palette

/**
 * Settings › Setup (setup without a computer): a checklist you can come back to. Each step
 * says what it's for and has one button; every step is optional. The states are read again
 * on every resume, so coming back from TV Settings shows the change.
 *
 * A step's button goes once the step is done. If it had focus (it opened Android's question
 * or TV Settings, and the step got done there), focus moves to the next step's button, or to
 * Show in the top bar, so the remote never lands nowhere.
 */
@Composable
internal fun ColumnScope.SetupSettings(
    state: HomeUiState,
    setup: SetupStatus,
    actions: SettingsActions,
    onOpenPlex: () -> Unit,
    /** This build updates itself from Settings › About, so it shows Updates from the app. */
    updates: Boolean = false,
) {
    val done = buildMap {
        put(SetupStep.HOME, setup.homeDone)
        put(SetupStep.TV_LISTINGS, setup.tvListings)
        put(SetupStep.PICTURES, setup.pictures)
        put(SetupStep.USAGE, setup.usageAccess)
        if (updates) put(SetupStep.UPDATES, setup.installUpdates)
        put(SetupStep.PLEX, state.plex.link.linked)
    }
    val buttons = remember { SetupStep.entries.associateWith { FocusRequester() } }
    val toggle = remember { FocusRequester() }
    // The step whose button has focus now, or had it when Android or TV Settings took over.
    var focused by remember { mutableStateOf<SetupStep?>(null) }
    LaunchedEffect(done) {
        val step = focused ?: return@LaunchedEffect
        if (done[step] != true) return@LaunchedEffect
        focused = null
        val next = SetupFocus.next(step, done)
        runCatching { (next?.let(buttons::getValue) ?: toggle).requestFocus() }
    }
    fun Modifier.button(step: SetupStep) =
        focusRequester(buttons.getValue(step)).onFocusChanged { if (it.isFocused) focused = step }

    Note("Each step is optional, and you can come back here any time. Nothing here sends anything off this TV.")

    val homeOn = setup.homeDone
    Step(
        title = "Home button",
        done = homeOn,
        warning = setup.home == HomeRoute.WENT_OFF,
        stateLabel = when (setup.home) {
            HomeRoute.HOME -> "Done"
            HomeRoute.HOMEWATCH -> "Done, with HomeWatch"
            HomeRoute.OFF -> "Off"
            HomeRoute.WENT_OFF -> "Turned off by Android"
        },
        detail = when (setup.home) {
            HomeRoute.HOME -> "Home opens Action Center: the TV's own launcher is turned off."
            HomeRoute.HOMEWATCH ->
                "HomeWatch sends the Home button here. You may see the TV's own home for a moment first."
            HomeRoute.OFF ->
                "Home opens the TV's own home screen. HomeWatch, an accessibility service, can send it here " +
                    "instead. It only looks for the Home button and which app is on screen, and sends nothing anywhere."
            HomeRoute.WENT_OFF ->
                "Android turned HomeWatch off (an update, or the TV's own settings), so Home opens the TV's own " +
                    "home screen again."
        },
        button = if (homeOn) null else "Turn on HomeWatch",
        onClick = actions::openHomeWatchSettings,
        buttonModifier = Modifier.button(SetupStep.HOME),
    )
    if (!homeOn) {
        Note(
            if (setup.accessibilityLink) {
                "It opens Accessibility: choose Action Center Home button, then turn it on."
            } else {
                "It opens Settings. Then choose Device Preferences (or System) › Accessibility › Action Center " +
                    "Home button, and turn it on."
            },
        )
    }
    if (setup.home != HomeRoute.HOME) {
        Note("With a computer, the TV's own launcher can be turned off instead, for the cleanest Home: see docs/install.md.")
    }

    Step(
        title = "Continue in your apps",
        done = setup.tvListings,
        detail = "What you were watching in other apps, from Android's Watch Next.",
        button = if (setup.tvListings) null else "Allow",
        onClick = { actions.allowPermission(SetupPermission.TV_LISTINGS) },
        buttonModifier = Modifier.button(SetupStep.TV_LISTINGS),
    )
    Step(
        title = "Your pictures",
        done = setup.pictures,
        detail = "Your own wallpapers and app banners, from Pictures/Wallpapers and Pictures/Icons on this TV.",
        button = if (setup.pictures) null else "Allow",
        onClick = { actions.allowPermission(SetupPermission.PICTURES) },
        buttonModifier = Modifier.button(SetupStep.PICTURES),
    )
    Step(
        title = "Apps row by habit",
        done = setup.usageAccess,
        detail = "Orders the Apps row by what you open at this time of day. Android counts it, on this TV.",
        button = if (setup.usageAccess) null else "Turn on",
        onClick = actions::openUsageAccess,
        buttonModifier = Modifier.button(SetupStep.USAGE),
    )
    if (!setup.usageAccess) Note("It opens Usage access: choose Action Center, then allow it.")
    if (updates) {
        Step(
            title = "Updates from the app",
            done = setup.installUpdates,
            detail = "Lets Settings › About install Action Center's own updates. Android calls it Install unknown apps; " +
                "Action Center uses it for nothing else.",
            button = if (setup.installUpdates) null else "Allow",
            onClick = actions::openInstallUnknownApps,
            buttonModifier = Modifier.button(SetupStep.UPDATES),
        )
        if (!setup.installUpdates) Note("It opens Install unknown apps: turn it on for Action Center.")
    }
    Step(
        title = "Plex",
        done = state.plex.link.linked,
        detail = "Your library: Tonight's Pick, Up Next and the Plex Guide.",
        button = if (state.plex.link.linked) null else "Open Plex settings",
        onClick = onOpenPlex,
        buttonModifier = Modifier.button(SetupStep.PLEX),
    )

    ToggleRow(
        title = "Show in the top bar",
        detail = "\"Finish setup\" stays in the top bar until the steps above are done. Turn it off to hide it on this TV.",
        on = !setup.chipHidden,
        onClick = { actions.setSetupChipShown(setup.chipHidden) },
        modifier = Modifier.focusRequester(toggle).onFocusChanged { if (it.isFocused) focused = null },
    )
}

/** Settings › Setup's steps, in their order on screen. */
internal enum class SetupStep { HOME, TV_LISTINGS, PICTURES, USAGE, UPDATES, PLEX }

internal object SetupFocus {
    /**
     * Where focus goes when [step]'s button goes: the next step still to do, wrapping round
     * to the first; null (Show in the top bar) when every step is done.
     */
    fun next(step: SetupStep, done: Map<SetupStep, Boolean>): SetupStep? {
        val order = SetupStep.entries
        val after = order.drop(order.indexOf(step) + 1) + order.take(order.indexOf(step))
        return after.firstOrNull { done[it] == false }
    }
}

/** One step: a dot and its state, what it's for, and its button while it isn't done. */
@Composable
private fun Step(
    title: String,
    done: Boolean,
    stateLabel: String = if (done) "Done" else "Off",
    detail: String,
    button: String?,
    onClick: () -> Unit,
    warning: Boolean = false,
    buttonModifier: Modifier = Modifier,
) {
    val dot: Color = when {
        done -> Palette.Good
        warning -> Palette.Caution
        else -> Palette.TextDim
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(stateLabel, style = MaterialTheme.typography.labelMedium, color = if (warning) Palette.Caution else Palette.TextDim)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        if (button != null) {
            Spacer(Modifier.width(16.dp))
            PillButton(
                text = button,
                onClick = onClick,
                modifier = buttonModifier,
                small = true,
                colors = pillButtonColors(container = Palette.Surface2),
            )
        }
    }
}
