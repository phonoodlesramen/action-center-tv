package com.phonoodlesramen.actioncenter.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette

/**
 * One action. [returnTo] overrides where focus goes after it runs (the item may be gone).
 * [dimmed] greys it while it's unfocused: it can't do what it says here (an app that isn't on
 * this TV, Phase 13), and [run] says why.
 */
data class SheetItem(
    val icon: ImageVector,
    val label: String,
    val returnTo: FocusRequester? = null,
    val dimmed: Boolean = false,
    val run: () -> Unit,
)

/**
 * A quick-actions sheet [U6]: title plus up to five actions. [returnTo] is the item
 * that opened it, where focus goes back when it closes.
 */
data class QuickSheet(val title: String, val items: List<SheetItem>, val returnTo: FocusRequester? = null)

/**
 * The side sheet from the right (spec §4): 336 dp, full height, sliding in 40 dp as it
 * fades (200 ms). Focus stays inside it until an action runs or Back closes it.
 *
 * Holding OK opens it on the press's first repeat, so the rest of that hold, and its
 * release, arrive here after the first action has taken focus. tv-material clicks on a
 * release it never saw pressed, so the sheet keeps them from its actions: only a press
 * made inside the sheet picks one.
 *
 * @param onClose gets where focus should go next.
 */
@Composable
fun QuickActionsSheet(sheet: QuickSheet, onClose: (returnTo: FocusRequester?) -> Unit) {
    val first = remember { FocusRequester() }
    val entrance = remember { Animatable(0f) }
    val presses = remember(sheet) { SheetPresses() }
    BackHandler { onClose(sheet.returnTo) }
    LaunchedEffect(sheet) {
        runCatching { first.requestFocus() }
        entrance.animateTo(1f, tween(durationMillis = 200, easing = FastOutSlowInEasing))
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = entrance.value }) {
        Box(Modifier.fillMaxSize().background(Color(0x9E05070A)))
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .graphicsLayer { translationX = (1f - entrance.value) * 40.dp.toPx() }
                .fillMaxHeight()
                .width(336.dp)
                .background(Palette.SheetBackground)
                .padding(horizontal = 28.dp, vertical = 27.dp)
                .onPreviewKeyEvent(presses::swallow)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
        ) {
            Eyebrow("Quick actions")
            Text(sheet.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sheet.items.forEachIndexed { index, item ->
                    Surface(
                        onClick = {
                            onClose(item.returnTo ?: sheet.returnTo)
                            item.run()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = if (item.dimmed) Palette.TextFaint else Palette.Text,
                            focusedContainerColor = LocalAccent.current,
                            focusedContentColor = Palette.AccentInk,
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    ) {
                        // A long label ("Add Ridge Line Marathon to favorites") takes a second line and the row grows.
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(item.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(item.label, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Back to close", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
        }
    }
}

/**
 * Which OK presses the sheet has seen start. Pure, so it's tested without Compose. True
 * from [swallow] keeps a key from the actions: the repeats and release of a hold that
 * began before the sheet opened.
 */
internal class SheetPresses {
    private val pressed = mutableSetOf<Long>()

    fun swallow(event: KeyEvent): Boolean = swallow(
        keyCode = event.key.keyCode,
        down = event.type == KeyEventType.KeyDown,
        up = event.type == KeyEventType.KeyUp,
        repeat = event.nativeKeyEvent.repeatCount > 0,
    )

    internal fun swallow(keyCode: Long, down: Boolean, up: Boolean, repeat: Boolean): Boolean = when {
        keyCode !in OkKeys -> false
        down && !repeat -> {
            pressed += keyCode
            false
        }
        down -> keyCode !in pressed
        up -> !pressed.remove(keyCode)
        else -> false
    }

    private companion object {
        /** The keys tv-material clicks with. */
        val OkKeys = setOf(Key.DirectionCenter.keyCode, Key.Enter.keyCode, Key.NumPadEnter.keyCode)
    }
}

/**
 * An app's quick actions (spec §4, L7): Open, Hide from launchpad (or Show), Choose art
 * and App info. Hiding removes the card from the apps row, so focus then goes to
 * [afterHide] instead of back to it. Move comes in a later phase.
 *
 * @param chooseArt opens Choose art for the app; null leaves the action out.
 */
fun appSheet(
    app: LaunchableApp,
    actions: HomeActions,
    returnTo: FocusRequester,
    afterHide: FocusRequester? = null,
    hidden: Boolean = false,
    chooseArt: (() -> Unit)? = null,
) = QuickSheet(
    title = app.label,
    returnTo = returnTo,
    items = listOfNotNull(
        SheetItem(AppIcons.Play, "Open") { actions.launch(app) },
        if (hidden) {
            SheetItem(AppIcons.Show, "Show on launchpad") { actions.setHidden(app, false) }
        } else {
            SheetItem(AppIcons.Hide, "Hide from launchpad", returnTo = afterHide) { actions.setHidden(app, true) }
        },
        chooseArt?.let { SheetItem(AppIcons.Art, "Choose art") { it() } },
        SheetItem(AppIcons.Info, "App info") { actions.appInfo(app) },
    ),
)
