package com.phonoodlesramen.actioncenter.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.media.MediaKind
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.PillButton
import com.phonoodlesramen.actioncenter.ui.home.pillButtonColors
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalWallpaperSources
import com.phonoodlesramen.actioncenter.wallpaper.CardStyle
import com.phonoodlesramen.actioncenter.wallpaper.ImageFit
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperController
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperFamily
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperPrefs
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Settings › Wallpaper (L1-L4): the styles by family, the chosen style's own controls,
 * the card style and Now showing. Everything is a press of OK: pills, lists and −/+
 * steps, never a slider (D-pad only).
 */
@Composable
fun ColumnScope.WallpaperSettings(controller: WallpaperController) {
    val prefs by controller.prefs.collectAsState()
    var choosingFolder by remember { mutableStateOf(false) }
    val change = remember { FocusRequester() }
    var refocus by remember { mutableStateOf(false) }
    LaunchedEffect(choosingFolder) {
        // Back from the folder list lands on Change again.
        if (!choosingFolder && refocus) {
            runCatching { change.requestFocus() }
            refocus = false
        }
    }
    if (choosingFolder) {
        FolderChooser(controller, prefs) {
            refocus = true
            choosingFolder = false
        }
        return
    }
    Note("Behind the home screen. Press Back to see it.")
    WallpaperFamily.entries.forEach { family ->
        Eyebrow(family.title, Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WallpaperStyle.entries.filter { it.family == family }.forEach { style ->
                PillButton(
                    text = style.title,
                    icon = if (style == prefs.style) AppIcons.Check else null,
                    onClick = { controller.update { it.copy(style = style) } },
                )
            }
        }
    }
    StyleControls(controller, prefs, change) { choosingFolder = true }
    Eyebrow("Cards", Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CardStyle.entries.forEach { style ->
            PillButton(
                text = if (style == CardStyle.SOLID) "Solid" else "Frosted glass",
                icon = if (style == prefs.cardStyle) AppIcons.Check else null,
                onClick = { controller.update { it.copy(cardStyle = style) } },
            )
        }
    }
    Note("Frosted glass shows the wallpaper through cards without art. Over Midnight and the moving styles it's a plain tint.")
    Eyebrow("Now showing", Modifier.padding(top = 8.dp))
    WallpaperToggleRow(
        title = "Now showing",
        detail = "After ${minutes(prefs.nowShowingMinutes)} without a press, Tonight's Pick fills the screen with the clock and weather. Any key brings the home back.",
        on = prefs.nowShowing,
        onClick = { controller.update { it.copy(nowShowing = !it.nowShowing) } },
    )
    if (prefs.nowShowing) {
        Stepper(
            label = "Wait",
            value = minutes(prefs.nowShowingMinutes),
            onLess = { controller.update { it.copy(nowShowingMinutes = WallpaperSteps.step(WallpaperSteps.IdleMinutes, it.nowShowingMinutes, -1)) } },
            onMore = { controller.update { it.copy(nowShowingMinutes = WallpaperSteps.step(WallpaperSteps.IdleMinutes, it.nowShowingMinutes, 1)) } },
        )
    }
}

/** What the chosen style can change; nothing a style ignores is shown. */
@Composable
private fun ColumnScope.StyleControls(controller: WallpaperController, prefs: WallpaperPrefs, change: FocusRequester, chooseFolder: () -> Unit) {
    val style = prefs.style
    Eyebrow(style.title, Modifier.padding(top = 8.dp), color = LocalAccent.current)
    Note(about(style))
    if (style == WallpaperStyle.YOUR_IMAGES || style == WallpaperStyle.VIDEO_LOOP) {
        val kind = if (style == WallpaperStyle.YOUR_IMAGES) MediaKind.IMAGE else MediaKind.VIDEO
        FolderControls(controller, prefs, kind, change, chooseFolder)
    }
    if (style == WallpaperStyle.YOUR_IMAGES) {
        if (prefs.image == null) {
            Stepper(
                label = "Next image",
                value = "every ${minutes(prefs.slideshowMinutes)}",
                onLess = { controller.update { it.copy(slideshowMinutes = WallpaperSteps.step(WallpaperSteps.SlideshowMinutes, it.slideshowMinutes, -1)) } },
                onMore = { controller.update { it.copy(slideshowMinutes = WallpaperSteps.step(WallpaperSteps.SlideshowMinutes, it.slideshowMinutes, 1)) } },
            )
        }
        Choices(
            label = "Fit",
            options = listOf(ImageFit.FILL to "Fill the screen", ImageFit.FIT to "Show it whole"),
            selected = prefs.fit,
        ) { fit -> controller.update { it.copy(fit = fit) } }
        WallpaperToggleRow(
            title = "Slow pan",
            detail = "A slow drift and zoom across the picture. It holds still when Android's animations are off.",
            on = prefs.slowPan,
            onClick = { controller.update { it.copy(slowPan = !it.slowPan) } },
        )
    }
    if (style == WallpaperStyle.COVER_ART) {
        Choices("Blur", WallpaperSteps.Blur, WallpaperSteps.nearest(WallpaperSteps.Blur, prefs.blur)) { blur ->
            controller.update { it.copy(blur = blur) }
        }
    }
    if (style == WallpaperStyle.YOUR_IMAGES) {
        Choices("Blur", WallpaperSteps.Blur, WallpaperSteps.nearest(WallpaperSteps.Blur, prefs.imageBlur)) { blur ->
            controller.update { it.copy(imageBlur = blur) }
        }
    }
    if (style.animated && style != WallpaperStyle.VIDEO_LOOP) {
        Choices("Speed", WallpaperSteps.Speed, WallpaperSteps.nearest(WallpaperSteps.Speed, prefs.speed)) { speed ->
            controller.update { it.copy(speed = speed) }
        }
    }
    if (style != WallpaperStyle.MIDNIGHT) {
        Stepper(
            label = "Dim",
            value = "${(prefs.dim * 100).roundToInt()}%",
            onLess = { controller.update { it.copy(dim = WallpaperSteps.dim(it.dim, -1)) } },
            onMore = { controller.update { it.copy(dim = WallpaperSteps.dim(it.dim, 1)) } },
        )
    }
}

private fun about(style: WallpaperStyle) = when (style) {
    WallpaperStyle.MIDNIGHT -> "The home screen's own blue-black. The calmest, and the default."
    WallpaperStyle.COVER_ART -> "Tonight's Pick's backdrop, blurred and dimmed. It changes when the pick does."
    WallpaperStyle.YOUR_IMAGES -> "Your pictures from a folder on the Shield: one, or a slideshow."
    WallpaperStyle.ACCENT_GRADIENT -> "A deep glow in your accent colour. Change the colour in Look."
    WallpaperStyle.GLOW_SPIRITS -> "Soft orbs of light drifting slowly, in your accent or the pick's colours."
    WallpaperStyle.VIDEO_LOOP -> "A muted video from a folder on the Shield, looping. It pauses when the home is hidden."
    WallpaperStyle.AMBIENT_LIGHT -> "The colours of the card you're on, washing slowly across the screen."
    WallpaperStyle.POSTER_WALL -> "Posters from your Plex library, drifting faintly behind the cards."
    WallpaperStyle.LIVING_SKY -> "A sky that follows today's sunrise, sunset and weather."
}

/** The folder, the grant it needs (L2), and which file: one image or a slideshow, or which video. */
@Composable
private fun ColumnScope.FolderControls(
    controller: WallpaperController,
    prefs: WallpaperPrefs,
    kind: MediaKind,
    change: FocusRequester,
    chooseFolder: () -> Unit,
) {
    Eyebrow("Folder", Modifier.padding(top = 4.dp))
    if (!controller.canReadFolders()) {
        Text(
            text = "Action Center can't read this TV's folders yet. Settings › Setup › Your pictures allows it. From a " +
                "computer, scripts/grant-permissions.ps1 does too, or",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Caution,
        )
        Text(GRANT_COMMAND, style = MaterialTheme.typography.bodySmall, color = Palette.Text)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(prefs.folder, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
        PillButton(
            text = "Change",
            icon = AppIcons.Search,
            onClick = chooseFolder,
            colors = pillButtonColors(container = Palette.Surface2),
            modifier = Modifier.focusRequester(change),
        )
    }
    // One small directory listing, like the folder chooser's.
    val sources = LocalWallpaperSources.current
    val files = remember(sources, prefs.folder, kind) { sources.files(prefs.folder, kind).map { it.name } }
    if (files.isEmpty()) {
        Note(if (kind == MediaKind.IMAGE) "No pictures in this folder." else "No videos in this folder.")
        return
    }
    Eyebrow(if (kind == MediaKind.IMAGE) "Image" else "Video", Modifier.padding(top = 4.dp))
    val chosen = if (kind == MediaKind.IMAGE) prefs.image else prefs.video
    val first = if (kind == MediaKind.IMAGE) "All ${files.size}, as a slideshow" else "The first in the folder"
    ChoiceRow(first, selected = chosen == null || chosen !in files) {
        controller.update { if (kind == MediaKind.IMAGE) it.copy(image = null) else it.copy(video = null) }
    }
    files.forEach { name ->
        ChoiceRow(name, selected = name == chosen) {
            controller.update { if (kind == MediaKind.IMAGE) it.copy(image = name) else it.copy(video = name) }
        }
    }
}

/** A list of the folders that hold pictures or videos; OK picks one, Back keeps the old one. */
@Composable
private fun ColumnScope.FolderChooser(controller: WallpaperController, prefs: WallpaperPrefs, onDone: () -> Unit) {
    val first = remember { FocusRequester() }
    BackHandler(onBack = onDone)
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val folders = remember { controller.folders() }
    Eyebrow("Choose a folder", Modifier.padding(top = 4.dp))
    if (folders.isEmpty()) {
        Note("No folders under Pictures, Movies or Download hold pictures or videos.")
        PillButton(text = "Back", onClick = onDone, modifier = Modifier.focusRequester(first))
        return
    }
    folders.forEachIndexed { index, folder ->
        ChoiceRow(
            text = folder,
            selected = folder == prefs.folder,
            modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
        ) {
            controller.update { it.copy(folder = folder, image = null, video = null) }
            onDone()
        }
    }
}

/** A row of pills, one per option; the selected one has a check. */
@Composable
private fun <T> Choices(label: String, options: List<Pair<T, String>>, selected: T, onChoose: (T) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ControlLabel(label)
        options.forEach { (value, text) ->
            PillButton(
                text = text,
                icon = if (value == selected) AppIcons.Check else null,
                onClick = { onChoose(value) },
                small = true,
            )
        }
    }
}

/** A number changed one step at a time: − and + either side of it. */
@Composable
private fun Stepper(label: String, value: String, onLess: () -> Unit, onMore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ControlLabel(label)
        PillButton(text = "−", onClick = onLess, small = true)
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 88.dp),
        )
        PillButton(text = "+", onClick = onMore, small = true)
    }
}

@Composable
private fun ControlLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Palette.TextDim, modifier = Modifier.width(96.dp), maxLines = 1)
}

/** A setting that's on or off: the whole row is the button (as in Settings › Lenses). */
@Composable
private fun WallpaperToggleRow(title: String, detail: String, on: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Surface3,
            focusedContentColor = Palette.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
            Spacer(Modifier.width(16.dp))
            val accent = LocalAccent.current
            Box(
                modifier = Modifier.size(width = 44.dp, height = 24.dp).background(if (on) accent else Palette.Surface3, RoundedCornerShape(50)),
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(Modifier.padding(3.dp).size(18.dp).background(if (on) Palette.AccentInk else Palette.TextDim, CircleShape))
            }
        }
    }
}

/** One of a list (a folder, a file): the focused one fills with the accent; the chosen one has a check. */
@Composable
private fun ChoiceRow(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(40.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.Text,
            focusedContainerColor = LocalAccent.current,
            focusedContentColor = Palette.AccentInk,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(18.dp)) {
                if (selected) Icon(AppIcons.Check, contentDescription = "Selected", modifier = Modifier.fillMaxSize())
            }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun minutes(count: Int) = if (count == 1) "1 minute" else "$count minutes"

/** The steps Settings offers, so a remote reaches every value in a few presses. */
internal object WallpaperSteps {
    val Blur = listOf(0f to "Sharp", 0.3f to "Soft", 0.6f to "Blurred", 1f to "Strongest")
    val Speed = listOf(0.2f to "Slow", 0.5f to "Medium", 0.8f to "Fast")
    val SlideshowMinutes = listOf(1, 2, 5, 10, 15, 30, 60)
    val IdleMinutes = listOf(1, 2, 5, 10, 15, 30)

    /** Dim moves in tens of percent, up to [WallpaperPrefs.MAX_DIM]. */
    fun dim(current: Float, direction: Int): Float =
        ((current * 10).roundToInt() + direction).coerceIn(0, (WallpaperPrefs.MAX_DIM * 10).roundToInt()) / 10f

    /** The option nearest [value], so a stored value between steps still shows a check. */
    fun nearest(options: List<Pair<Float, String>>, value: Float): Float = options.minBy { abs(it.first - value) }.first

    /** The next step from [current] in [direction]; a value between steps moves to its neighbour. */
    fun step(steps: List<Int>, current: Int, direction: Int): Int = if (direction > 0) {
        steps.firstOrNull { it > current } ?: steps.last()
    } else {
        steps.lastOrNull { it < current } ?: steps.first()
    }
}

/** For the grant note: the same command `scripts/grant-permissions.ps1` runs. */
private const val GRANT_COMMAND = "adb shell pm grant com.phonoodlesramen.actioncenter android.permission.READ_EXTERNAL_STORAGE"
