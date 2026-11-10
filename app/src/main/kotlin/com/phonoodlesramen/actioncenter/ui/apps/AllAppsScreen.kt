package com.phonoodlesramen.actioncenter.ui.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.apps.LocalCustomBanners
import com.phonoodlesramen.actioncenter.apps.key
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.KidLayout
import com.phonoodlesramen.actioncenter.ui.home.QuickActionsSheet
import com.phonoodlesramen.actioncenter.ui.home.QuickSheet
import com.phonoodlesramen.actioncenter.ui.home.appSheet
import com.phonoodlesramen.actioncenter.ui.home.onMenuKey
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.safeArea

/**
 * All apps [U8]: a Most used row, then every TV app A–Z, five 16:9 banners across, each
 * with its custom banner when one matches (L7). Back returns to the home screen's All apps
 * card.
 *
 * For a kid ([allowlist], K9) it's "My apps": only the apps a grown-up picked for them, A–Z,
 * in rounder tiles (U13), with no Most used and no quick actions (hiding apps and choosing
 * art are yours). Interface only (D12).
 *
 * @param allowlist a kid's My apps, by [LaunchableApp.key]
 *   ([com.phonoodlesramen.actioncenter.profiles.ActiveProfile.apps]); null, an adult's, shows every app.
 */
@Composable
fun AllAppsScreen(
    apps: List<LaunchableApp>,
    mostUsed: List<LaunchableApp>,
    hiddenApps: Set<String>,
    actions: HomeActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    allowlist: Set<String>? = null,
) {
    val kid = allowlist != null
    val shown = remember(apps, allowlist) { if (allowlist == null) apps else apps.filter { it.key in allowlist } }
    val first = remember { FocusRequester() }
    val picker = remember { ArtPickerState() }
    val banners by LocalCustomBanners.current.state.collectAsState()
    var sheet by remember { mutableStateOf<QuickSheet?>(null) }
    var returnTo by remember { mutableStateOf<FocusRequester?>(null) }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LaunchedEffect(sheet) {
        if (sheet == null) returnTo?.let { runCatching { it.requestFocus() } }
    }

    Box(modifier.fillMaxSize().background(Palette.Background)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            modifier = Modifier.fillMaxSize().safeArea(),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            fullWidth("title") {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (kid) "My apps" else "All apps", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = "${shown.size}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Palette.TextDim,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            val sections = listOfNotNull(
                mostUsed.takeIf { it.isNotEmpty() && !kid }?.let { "Most used" to it },
                "A–Z" to shown,
            )
            sections.forEachIndexed { sectionIndex, (title, list) ->
                fullWidth("eyebrow:$title") { Eyebrow(title, Modifier.padding(top = 6.dp)) }
                items(list, key = { "$title:${it.key}" }) { app ->
                    val self = remember { FocusRequester() }
                    val open = {
                        sheet = appSheet(
                            app,
                            actions,
                            returnTo = self,
                            hidden = app.key in hiddenApps,
                            chooseArt = { picker.open(app, self) },
                        )
                    }
                    val isFirst = sectionIndex == 0 && app == list.first()
                    AppBanner(
                        app = app,
                        custom = banners.bannerFor(app),
                        onClick = { actions.launch(app) },
                        onLongClick = if (kid) null else open,
                        modifier = Modifier
                            .aspectRatio(16f / 9f)
                            .focusRequester(self)
                            .then(if (isFirst) Modifier.focusRequester(first) else Modifier)
                            .then(if (kid) Modifier else Modifier.onMenuKey(open)),
                        radius = if (kid) KidLayout.TileRadius else Layout.TileRadius,
                    )
                }
            }
            fullWidth("hint") {
                Text(
                    text = if (kid) KID_NOTE else "Hold OK on an app to hide it from the launchpad, choose its art or open its settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextDim,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        ArtPickerHost(picker)
        sheet?.let { open ->
            QuickActionsSheet(open) { target ->
                returnTo = target
                sheet = null
            }
        }
    }
}

private const val COLUMNS = 5

/** Under a kid's My apps (prototype). */
internal const val KID_NOTE ="Only the apps a grown-up picked for you are shown here."

private fun LazyGridScope.fullWidth(key: String, content: @Composable LazyGridItemScope.() -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }, content = content)
