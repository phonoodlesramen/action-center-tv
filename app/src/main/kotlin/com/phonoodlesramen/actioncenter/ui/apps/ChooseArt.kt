package com.phonoodlesramen.actioncenter.ui.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.apps.BannerChoice
import com.phonoodlesramen.actioncenter.apps.BannerFile
import com.phonoodlesramen.actioncenter.apps.CustomBannersState
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.apps.LocalCustomBanners
import com.phonoodlesramen.actioncenter.media.SharedFolders
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.rows.MarginBringIntoViewSpec
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.safeArea

/** Opens Choose art for an app (L7). Focus goes back to [returnTo] when it closes. */
fun interface ArtPicker {
    fun open(app: LaunchableApp, returnTo: FocusRequester)
}

/** The home screen and All apps provide one; elsewhere Choose art does nothing. */
val LocalArtPicker = staticCompositionLocalOf { ArtPicker { _, _ -> } }

/** Which app's art is being chosen, and the card that opened it. */
@Stable
class ArtPickerState : ArtPicker {
    var app: LaunchableApp? by mutableStateOf(null)
        private set
    private var returnTo: FocusRequester? = null

    override fun open(app: LaunchableApp, returnTo: FocusRequester) {
        this.returnTo = returnTo
        this.app = app
    }

    /** Closes the picker; focus goes back to the card that opened it. */
    fun close() {
        app = null
    }

    /** Closes the picker without moving focus (Home pressed: focus goes to Play). */
    fun dismiss() {
        returnTo = null
        app = null
    }

    internal fun takeReturn(): FocusRequester? = returnTo.also { returnTo = null }
}

/** Draws Choose art over the screen while [picker] is open, and puts focus back after. */
@Composable
fun ArtPickerHost(picker: ArtPickerState) {
    val open = picker.app
    LaunchedEffect(open) {
        if (open == null) picker.takeReturn()?.let { runCatching { it.requestFocus() } }
    }
    if (open != null) {
        val banners = LocalCustomBanners.current
        val state by banners.state.collectAsState()
        ChooseArtScreen(
            app = open,
            banners = state,
            onChoose = { choice ->
                banners.choose(open, choice)
                picker.close()
            },
            onClose = picker::close,
        )
    }
}

/**
 * Choose art (L7): the app's automatic match, its own banner, then every image in the
 * Icons folder, five 16:9 cards across with the file's name under each. OK picks one;
 * Back keeps what was there. The pick is saved per app.
 */
// LocalBringIntoViewSpec is still experimental on Android (foundation 1.12).
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChooseArtScreen(
    app: LaunchableApp,
    banners: CustomBannersState,
    onChoose: (BannerChoice) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val current = banners.choiceFor(app)
    val match = banners.matchFor(app)
    val options = remember(banners, app) {
        listOf(BannerChoice.Automatic, BannerChoice.OwnBanner) + banners.files.map { BannerChoice.Chosen(it.name) }
    }
    val selected = options.indexOf(current).coerceAtLeast(0)
    val first = remember { FocusRequester() }
    // The grid starts at the current pick's row, so its card exists to take focus.
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = if (selected < COLUMNS * 2) 0 else 1 + selected - selected % COLUMNS)
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val margin = with(LocalDensity.current) { 60.dp.toPx() }
    val files = remember(banners) { banners.files.associateBy(BannerFile::name) }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides remember(margin) { MarginBringIntoViewSpec(margin) }) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(COLUMNS),
                state = grid,
                modifier = Modifier.fillMaxSize().safeArea(),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Eyebrow("Choose art")
                        Text(app.label, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 4.dp))
                        Text(
                            text = if (banners.files.isEmpty()) {
                                "No images in ${SharedFolders.ICONS} yet. Copy 16:9 banners there; Action Center " +
                                    "needs Settings › Setup › Your pictures."
                            } else {
                                "Banners from ${SharedFolders.ICONS} on this Shield. Back keeps what's there."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.TextDim,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                items(options, key = { it.toString() }) { option ->
                    val isCurrent = option == current
                    val caption = when (option) {
                        BannerChoice.Automatic -> "Automatic · ${match?.name ?: "no match"}"
                        BannerChoice.OwnBanner -> "The app's own banner"
                        is BannerChoice.Chosen -> option.name
                    }
                    Column {
                        BannerSurface(
                            onClick = { onChoose(option) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .then(if (option == options[selected]) Modifier.focusRequester(first) else Modifier),
                        ) {
                            when (option) {
                                BannerChoice.Automatic -> AppArtContent(app, match)
                                BannerChoice.OwnBanner -> AppArtContent(app, null)
                                is BannerChoice.Chosen -> files[option.name]?.let { FileArt(it) }
                            }
                        }
                        Caption(caption, isCurrent)
                    }
                }
            }
        }
    }
}

private const val COLUMNS = 5

/** A file's banner, with its name until it loads (or if it can't). */
@Composable
private fun FileArt(file: BannerFile) {
    var loaded by remember(file) { mutableStateOf(false) }
    if (!loaded) {
        Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            Text(
                text = file.title,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    BannerImage(file, contentDescription = file.name, onSuccess = { loaded = true }, modifier = Modifier.fillMaxSize())
}

/** The option's name under its card; the one in use has a check in the accent. */
@Composable
private fun Caption(text: String, current: Boolean) {
    Row(
        modifier = Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (current) Icon(AppIcons.Check, contentDescription = "In use", tint = LocalAccent.current, modifier = Modifier.size(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (current) Palette.Text else Palette.TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
