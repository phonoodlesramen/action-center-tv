package com.phonoodlesramen.actioncenter.ui.home.rows

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.apps.LocalCustomBanners
import com.phonoodlesramen.actioncenter.apps.key
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.ui.apps.AppBanner
import com.phonoodlesramen.actioncenter.ui.apps.LocalArtPicker
import com.phonoodlesramen.actioncenter.ui.home.AllAppsTile
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.QuickSheet
import com.phonoodlesramen.actioncenter.ui.home.appSheet
import com.phonoodlesramen.actioncenter.ui.home.onMenuKey

/**
 * Apps (L5-L7): every app as a full-size banner, the launchpad's order first, then an
 * All apps card that opens the All apps screen. Banners come from the Icons folder when a
 * name matches (or Choose art picked one), else the app's own. Hold OK for quick actions.
 *
 * The row is never empty: All apps is always there, so every app stays reachable.
 */
@Composable
fun AppsRow(
    state: HomeUiState,
    actions: HomeActions,
    openSheet: (QuickSheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalAppsRowFocus.current ?: remember { AppsRowFocus() }
    val apps = remember(state.launchpad, state.allApps, state.hiddenApps) {
        appsRowOrder(state.launchpad, state.allApps, state.hiddenApps)
    }
    val banners by LocalCustomBanners.current.state.collectAsState()
    val picker = LocalArtPicker.current
    val metrics = LocalRowMetrics.current
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(app: LaunchableApp) = requesters.getOrPut(app.key) { FocusRequester() }
    SideEffect { focus.allAppsIndex = apps.size }

    CardRow("Apps", modifier, focus.list) {
        itemsIndexed(apps, key = { _, app -> app.key }) { index, app ->
            val self = requester(app)
            // Hiding takes the card out of the row: focus goes to the card that takes its place.
            val next = apps.getOrNull(index + 1)?.let(::requester) ?: focus.allApps
            val sheet = {
                openSheet(appSheet(app, actions, returnTo = self, afterHide = next, chooseArt = { picker.open(app, self) }))
            }
            AppBanner(
                app = app,
                custom = banners.bannerFor(app),
                onClick = { actions.launch(app) },
                onLongClick = sheet,
                modifier = Modifier.rowCard(metrics).focusRequester(self).onMenuKey(sheet),
            )
        }
        item(key = ALL_APPS_KEY) {
            AllAppsTile(actions::openAllApps, Modifier.rowCard(metrics).focusRequester(focus.allApps))
        }
    }
}

private const val ALL_APPS_KEY = "all-apps"

/** L6: the launchpad first (most launched, then A–Z), then every other app A–Z. Hidden apps are left out. */
fun appsRowOrder(launchpad: List<LaunchableApp>, allApps: List<LaunchableApp>, hidden: Set<String>): List<LaunchableApp> {
    val first = launchpad.filter { it.key !in hidden }
    val placed = first.mapTo(HashSet()) { it.key }
    return first + allApps.filter { it.key !in hidden && it.key !in placed }
}

/**
 * The apps row's scroll position and its All apps card, held by the home screen so Back
 * from All apps can land on that card even when it's scrolled out of the row [U10].
 */
@Stable
class AppsRowFocus {
    val list = LazyListState()
    val allApps = FocusRequester()

    /** Where the All apps card sits: after every app. */
    internal var allAppsIndex = 0

    /** Scrolls the row to the All apps card, waits for it to be laid out, then focuses it. */
    suspend fun focusAllApps() {
        list.scrollToItem(allAppsIndex)
        withFrameNanos {}
        runCatching { allApps.requestFocus() }
    }
}

/** The home screen provides one; elsewhere the row keeps its own. */
val LocalAppsRowFocus = staticCompositionLocalOf<AppsRowFocus?> { null }
