package com.phonoodlesramen.actioncenter.ui.home.rows

import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.phonoodlesramen.actioncenter.tvprovider.ChannelsAndAppsController
import com.phonoodlesramen.actioncenter.tvprovider.TvProgram
import com.phonoodlesramen.actioncenter.ui.art.ArtFilters
import com.phonoodlesramen.actioncenter.ui.art.titleArt
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.QuickSheet
import com.phonoodlesramen.actioncenter.ui.home.SheetItem
import com.phonoodlesramen.actioncenter.ui.home.cardLift
import com.phonoodlesramen.actioncenter.ui.home.focusRing
import com.phonoodlesramen.actioncenter.ui.home.onMenuKey
import com.phonoodlesramen.actioncenter.ui.home.tileColors
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.onWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Continue in your apps (L9): other apps' Watch Next entries, most recent first, each
 * with its app's badge and progress. OK opens the entry's own intent URI through
 * [HomeActions.openIntentUri]; hold OK or Menu for Open and Hide this app from the row.
 * Draws nothing when there's nothing to continue.
 */
@Composable
fun ContinueInAppsRow(
    programs: List<TvProgram>,
    lateNight: Boolean,
    actions: HomeActions,
    openSheet: (QuickSheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = LocalChannelsAndApps.current
    val badges = rememberAppBadges()
    val focusManager = LocalFocusManager.current
    // Hidden here at once, before Settings' store catches up, so focus can stay in the row.
    var hiddenHere by remember(programs) { mutableStateOf(emptySet<String>()) }
    var hiding by remember { mutableStateOf<Hiding?>(null) }
    val shown = programs.filter { it.packageName !in hiddenHere }
    // Cards are slots by position: when an app's cards go, the card that took the place keeps focus.
    val slots = remember { SlotRequesters() }

    LaunchedEffect(hiding) {
        val request = hiding ?: return@LaunchedEffect
        if (controller === ChannelsAndAppsController.None) {
            actions.say("Turn ${request.appName} off in Settings › Channels & apps")
            hiding = null
            return@LaunchedEffect
        }
        // The quick-actions sheet hands focus back to the card first.
        withFrameNanos {}
        val remaining = shown.filter { it.packageName != request.packageName }
        if (remaining.isEmpty()) focusManager.moveFocus(FocusDirection.Up)
        hiddenHere = hiddenHere + request.packageName
        controller.setAppShown(request.packageName, false)
        actions.say("${request.appName} is hidden from Continue in your apps. Settings › Channels & apps brings it back.")
        if (remaining.isNotEmpty()) {
            withFrameNanos {}
            val next = shown.take(request.index).count { it.packageName != request.packageName }
            runCatching { slots[next.coerceAtMost(remaining.lastIndex)].requestFocus() }
        }
        hiding = null
    }

    if (shown.isEmpty()) return
    OtherAppsRowFrame(label = "Continue in your apps", modifier = modifier) {
        items(shown.size) { index ->
            val program = shown[index]
            val appName = badges.name(program.packageName)
            ContinueCard(
                program = program,
                appName = appName,
                lateNight = lateNight,
                badges = badges,
                actions = actions,
                openSheet = openSheet,
                self = slots[index],
                onHide = { hiding = Hiding(program.packageName, appName, index) },
            )
        }
    }
}

/** Settings › Channels & apps, for the Hide quick action. The activity provides the app's. */
val LocalChannelsAndApps = staticCompositionLocalOf<ChannelsAndAppsController> { ChannelsAndAppsController.None }

private data class Hiding(val packageName: String, val appName: String, val index: Int)

/** One focus requester per card position, made as the row grows. */
private class SlotRequesters {
    private val requesters = ArrayList<FocusRequester>()

    operator fun get(index: Int): FocusRequester {
        while (requesters.size <= index) requesters += FocusRequester()
        return requesters[index]
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(
    program: TvProgram,
    appName: String,
    lateNight: Boolean,
    badges: AppBadges,
    actions: HomeActions,
    openSheet: (QuickSheet) -> Unit,
    self: FocusRequester,
    onHide: () -> Unit,
) {
    val open = { program.intentUri?.let { actions.openIntentUri(it, program.title) } ?: Unit }
    val sheet = {
        openSheet(
            QuickSheet(
                title = program.title,
                returnTo = self,
                items = listOf(
                    SheetItem(AppIcons.Play, "Open") { open() },
                    SheetItem(AppIcons.Hide, "Hide this app from the row") { onHide() },
                ),
            ),
        )
    }
    val shape = RoundedCornerShape(Layout.TileRadius)
    val detail = episodeLine(program) ?: appName
    val metrics = LocalRowMetrics.current
    Column(Modifier.width(metrics.cardWidth)) {
        Surface(
            onClick = open,
            onLongClick = sheet,
            modifier = Modifier
                .rowCard(metrics)
                // Asks to be seen with the row's label and its own captions, as the row does.
                .bringIntoViewResponder(rememberCaptionRoom())
                .focusRequester(self)
                .onMenuKey(sheet)
                .semantics { contentDescription = "${program.title}, $detail, in $appName" },
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = tileColors(),
            scale = cardLift,
            border = focusRing(shape),
        ) {
            ProgramArt(program, lateNight, Modifier.fillMaxSize())
            AppBadge(program.packageName, appName, badges, Modifier.align(Alignment.TopStart).padding(6.dp))
            val progress = progress(program)
            if (progress != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.22f)),
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(LocalAccent.current))
                }
            }
        }
        ShownWhole {
            Column {
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.titleSmall.onWallpaper(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall.onWallpaper(),
                    color = Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The entry's poster, then its thumbnail if the poster won't load, over generated art.
 * Other apps' `content://` art is only readable when their provider allows it; when it
 * doesn't, the generated art stays.
 */
@Composable
private fun ProgramArt(program: TvProgram, lateNight: Boolean, modifier: Modifier = Modifier) {
    val candidates = remember(program) { listOfNotNull(program.posterArtUri, program.thumbnailUri).distinct() }
    var tried by remember(program) { mutableIntStateOf(0) }
    BoxWithConstraints(modifier.titleArt("${program.packageName}/${program.id}", lateNight)) {
        val art = candidates.getOrNull(tried) ?: return@BoxWithConstraints
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(art)
                .size(constraints.maxWidth, constraints.maxHeight)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = if (lateNight) ColorFilter.colorMatrix(ArtFilters.LateNight) else null,
            onError = { tried++ },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun AppBadge(packageName: String, appName: String, badges: AppBadges, modifier: Modifier = Modifier) {
    val icon by produceState(badges.cachedIcon(packageName), packageName) { value = badges.icon(packageName) }
    Box(
        modifier = modifier.size(22.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = icon
        if (loaded != null) {
            Image(loaded, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Text(appName.take(1).uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.Text)
        }
    }
}

/** "S2E26 · Sleepytime", or whichever half the app gave; null when it gave neither. */
internal fun episodeLine(program: TvProgram): String? {
    fun number(prefix: String, value: String?) = value?.trim()?.ifEmpty { null }?.let { if (it.all(Char::isDigit)) "$prefix$it" else it }
    val numbers = listOfNotNull(number("S", program.season), number("E", program.episode)).joinToString("").ifEmpty { null }
    return listOfNotNull(numbers, program.episodeTitle?.trim()?.ifEmpty { null }).joinToString(" · ").ifEmpty { null }
}

/** How far in, from 0 to 1; null when the app didn't say. */
internal fun progress(program: TvProgram): Float? {
    val duration = program.durationMillis?.takeIf { it > 0 } ?: return null
    val position = program.positionMillis?.takeIf { it > 0 } ?: return null
    return (position.toFloat() / duration).coerceIn(0f, 1f)
}

/** An app's name and icon for the badge and quick actions. */
interface AppBadges {
    fun name(packageName: String): String

    /** Already loaded, so a card that comes back doesn't flash its letter. */
    fun cachedIcon(packageName: String): ImageBitmap? = null
    suspend fun icon(packageName: String): ImageBitmap? = null
}

/** The installed apps' (from PackageManager) by default; screenshots provide their own. */
val LocalAppBadges = staticCompositionLocalOf<AppBadges?> { null }

@Composable
private fun rememberAppBadges(): AppBadges {
    val provided = LocalAppBadges.current
    val packageManager = LocalContext.current.packageManager
    return provided ?: remember(packageManager) { PackageManagerBadges(packageManager) }
}

/** Icons are decoded once at 48 px and kept for the process (a few KB each). */
private class PackageManagerBadges(private val packageManager: PackageManager) : AppBadges {
    override fun name(packageName: String): String = names.get(packageName) ?: try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }.also { names.put(packageName, it) }

    override fun cachedIcon(packageName: String): ImageBitmap? = icons.get(packageName)

    override suspend fun icon(packageName: String): ImageBitmap? = icons.get(packageName) ?: withContext(Dispatchers.IO) {
        try {
            packageManager.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX).asImageBitmap().also { icons.put(packageName, it) }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private companion object {
        const val ICON_PX = 48
        val names = LruCache<String, String>(64)
        val icons = LruCache<String, ImageBitmap>(32)
    }
}

/**
 * The row anatomy Live, Continue in your apps and Apps share ([RowMetrics], L5, L10): 16 dp
 * above the row, an eyebrow, 8 dp below it a Foundation [LazyRow] of 160×90 dp cards 16 dp
 * apart inside the 48 dp side margins. Those are sizes on the 960 dp canvas, so the rows
 * line up with the dashboard and the apps row at every layout scale while text grows [U4].
 *
 * Focus anywhere in the row asks the home to show all of it, from its label down to the
 * captions, which is what its cards ask too ([CaptionRoom]), so the row lands in one place.
 * The ask never starts above the label (G20 C): for the first row below the dashboard that
 * would be the dashboard's own space, and the home would scroll back to the top.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OtherAppsRowFrame(
    label: String,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val metrics = LocalRowMetrics.current
    val whole = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    Column(
        modifier
            .fillMaxWidth()
            .onSizeChanged { size = it }
            .bringIntoViewRequester(whole)
            .onFocusChanged { focus ->
                if (focus.hasFocus) {
                    scope.launch {
                        // From the label's top: the space above it belongs to whatever is above.
                        val labelTop = with(density) { metrics.spaceAbove.toPx() }
                        whole.bringIntoView(Rect(0f, labelTop, size.width.toFloat(), size.height.toFloat()))
                    }
                }
            }
            .padding(top = metrics.spaceAbove),
    ) {
        ShownWhole(Modifier.padding(horizontal = metrics.margin)) { Eyebrow(label) }
        LazyRow(
            state = state,
            // A LazyRow is a focus group already: focusRestorer() must reach that one. A
            // focusGroup() of our own in between would be what it restores, so it'd forget the card.
            modifier = Modifier.padding(top = metrics.labelGap).fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(horizontal = metrics.margin),
            horizontalArrangement = Arrangement.spacedBy(metrics.gap),
            content = content,
        )
    }
}

/**
 * Text near the screen's edge shows only when all of it is on screen: TV guidance allows
 * art to peek, never text cut off by the edge.
 */
@Composable
internal fun ShownWhole(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var whole by remember { mutableStateOf(true) }
    Box(modifier.onGloballyPositioned { whole = it.isWhollyOnScreen() }.graphicsLayer { alpha = if (whole) 1f else 0f }) {
        content()
    }
}

/** [boundsInWindow] is clipped by the scrolling parents and the window, so a cut-off box comes out smaller. */
private fun LayoutCoordinates.isWhollyOnScreen(): Boolean {
    val visible = boundsInWindow()
    return visible.width >= size.width - 1f && visible.height >= size.height - 1f
}
