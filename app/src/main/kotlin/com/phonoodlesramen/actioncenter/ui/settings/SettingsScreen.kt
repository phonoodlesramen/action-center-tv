package com.phonoodlesramen.actioncenter.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.ai.AiBlurbsController
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.LensMoveOn
import com.phonoodlesramen.actioncenter.content.LensPrefs
import com.phonoodlesramen.actioncenter.content.LensSchedule
import com.phonoodlesramen.actioncenter.context.ContextMode
import com.phonoodlesramen.actioncenter.context.ContextSchedule
import com.phonoodlesramen.actioncenter.context.Hours
import com.phonoodlesramen.actioncenter.guide.GuideEditor
import com.phonoodlesramen.actioncenter.handoff.PlayIn
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.nfl.NflController
import com.phonoodlesramen.actioncenter.plex.PlexSignIn
import com.phonoodlesramen.actioncenter.profiles.AgeBrackets
import com.phonoodlesramen.actioncenter.profiles.ProfilesController
import com.phonoodlesramen.actioncenter.setup.SetupPermission
import com.phonoodlesramen.actioncenter.setup.SetupStatus
import com.phonoodlesramen.actioncenter.station.GuideStationController
import com.phonoodlesramen.actioncenter.tvprovider.ChannelsAndAppsController
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.PillButton
import com.phonoodlesramen.actioncenter.ui.home.cardLift
import com.phonoodlesramen.actioncenter.ui.home.focusRing
import com.phonoodlesramen.actioncenter.ui.home.pillButtonColors
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.LayoutScale
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.safeArea
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperController
import com.phonoodlesramen.actioncenter.weather.Place
import com.phonoodlesramen.actioncenter.weather.TemperatureUnit
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** What Settings can change. Defaults do nothing (previews, screenshots). */
interface SettingsActions {
    fun setAccent(index: Int) {}
    fun setLayoutScale(scale: Float) {}
    fun setShowSampleContent(show: Boolean) {}

    /** The Phase 0 probe screen: device, handoff probes, Watch Next, safe mode. */
    fun openDeviceCheck() {}

    /** Open-Meteo's city search. Throws [java.io.IOException] when it can't be reached. */
    suspend fun searchPlaces(query: String): List<Place> = emptyList()
    fun choosePlace(place: Place) {}
    fun setTemperatureUnit(unit: TemperatureUnit) {}

    /** Settings › Lenses (U19). Turning off the last lens that's on does nothing. */
    fun setLensEnabled(lens: Lens, enabled: Boolean) {}

    /** Settings › Lenses › Rotate on its own (L1). */
    fun setLensRotate(on: Boolean) {}

    /** Settings › Lenses › Speed (L2): a notch, 1 to 5. */
    fun setLensSpeed(notch: Int) {}

    /** Settings › Lenses › Shuffle (L4). */
    fun setLensShuffle(on: Boolean) {}

    /** Settings › Lenses › Move on from a title (L5). */
    fun setLensMoveOn(moveOn: LensMoveOn) {}

    /** Settings › Time & modes: Quick Watch fits before this (S6). */
    fun setQuickWatchCutoff(cutoff: LocalTime) {}

    /** Settings › Time & modes: Friday and Saturday nights' cutoff (M4). */
    fun setWeekendCutoff(cutoff: LocalTime) {}

    /** Settings › Time & modes (M1). Refused if the schedule would have a [ContextSchedule.problem]. */
    fun setModeHours(mode: ContextMode, hours: Hours) {}

    /** Settings › AI blurbs (M7). */
    fun setAiBlurbs(on: Boolean) {}

    /** Settings › AI blurbs › Add a key on your phone (M10). */
    fun openAiKeyOnPhone() {}

    /** Forgets the Plex account and its tokens on this Shield; the home shows a new code. */
    fun signOutOfPlex() {}

    /** P2: you own more than one server. */
    fun choosePlexServer(clientIdentifier: String) {}

    /** Syncs the Plex cache now, in the background. */
    fun syncPlexNow() {}

    /** Settings › Plex › Play in (Phase 11, K4): where every Play goes on this TV. */
    fun setPlayIn(app: PlayIn) {}

    /** Settings › Plex › Count guide viewing in Plex (Phase 11, K1, K2). */
    fun setCountGuideViewing(on: Boolean) {}

    /** Settings › Search › Include streaming apps (Phase 15, Q3). */
    fun setSearchStreaming(on: Boolean) {}

    /** Settings › Plex Guide › Edit on your phone (G1). */
    fun openPhoneEditor() {}

    /** Settings › Setup's Allow: Android asks, or App info opens once it won't ask again. */
    fun allowPermission(permission: SetupPermission) {}

    /** Settings › Setup › Home button: TV Settings › Accessibility, or TV Settings where it can't link there. */
    fun openHomeWatchSettings() {}

    /** Settings › Setup › Apps row by habit: TV Settings' Usage access. */
    fun openUsageAccess() {}

    /** Settings › Setup › Show in the top bar, on this TV. */
    fun setSetupChipShown(shown: Boolean) {}

    companion object None : SettingsActions
}

/** Settings sections [U9, brief §9]. Later phases fill in the placeholders. */
enum class SettingsSection(val title: String) {
    /** Setup without a computer: Home, permissions, usage access and Plex, a checklist to come back to. */
    SETUP("Setup"),
    LOOK("Look"),
    WALLPAPER("Wallpaper"),
    LENSES("Lenses"),
    PROFILES("Profiles"),
    PLEX("Plex"),
    PLEX_GUIDE("Plex Guide"),

    /** Phase 13: your teams, your area's, Sunday Ticket and Hide scores. Hidden on a kid's profile, as the NFL is. */
    NFL("NFL"),
    CHANNELS_AND_APPS("Channels & apps"),

    /** Phase 15: whether Search adds your streaming apps' titles. */
    SEARCH("Search"),
    TIME("Time & modes"),
    WEATHER("Weather"),
    AI("AI blurbs"),
    DIAGNOSTICS("Diagnostics"),
    ABOUT("About"),
}

/**
 * Settings (spec §4): a section list on the left, the focused section on the right.
 * Opened by the gear; Back returns to it.
 */
@Composable
fun SettingsScreen(
    state: HomeUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialSection: SettingsSection = SettingsSection.LOOK,
    version: String = "",
    wallpaper: WallpaperController = WallpaperController.None,
    channelsAndApps: ChannelsAndAppsController = ChannelsAndAppsController.None,
    guideEditor: GuideEditor? = null,
    aiBlurbs: AiBlurbsController = AiBlurbsController.None,
    profiles: ProfilesController = ProfilesController.None,
    /** Settings › Plex Guide › Guide station (Phase 8); null in previews, which leave its row out. */
    station: GuideStationController? = null,
    /** Settings › NFL (Phase 13); null leaves the section out. */
    nfl: NflController? = null,
    /** Settings › Setup's states; a TV that's all set up by default. */
    setup: SetupStatus = SetupStatus(),
) {
    // A kid's profile has no NFL (F9), so no Settings › NFL either.
    val sections = SettingsSection.entries.filter { it != SettingsSection.NFL || (nfl != null && !state.profile.kid) }
    var section by remember { mutableStateOf(initialSection.takeIf { it in sections } ?: SettingsSection.LOOK) }
    val rows = remember { SettingsSection.entries.associateWith { FocusRequester() } }
    val focusManager = LocalFocusManager.current
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { runCatching { rows.getValue(section).requestFocus() } }

    Row(modifier.fillMaxSize().background(Palette.Background).safeArea()) {
        Column(Modifier.weight(LIST_WEIGHT).fillMaxSize()) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            // Fifteen sections: compact rows, and the list scrolls at larger layout scales.
            Column(
                modifier = Modifier.padding(top = 18.dp).verticalScroll(rememberScrollState()).focusRestorer().focusGroup(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                sections.forEach { entry ->
                    SectionRow(
                        title = entry.title,
                        selected = entry == section,
                        onClick = { focusManager.moveFocus(FocusDirection.Right) },
                        modifier = Modifier
                            .focusRequester(rows.getValue(entry))
                            .onFocusChanged { if (it.isFocused) section = entry },
                    )
                }
            }
        }
        Spacer(Modifier.weight(GAP_WEIGHT))
        Column(
            modifier = Modifier
                .weight(DETAIL_WEIGHT)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 64.dp, bottom = 16.dp)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(section.title, style = MaterialTheme.typography.titleLarge)
            when (section) {
                SettingsSection.SETUP -> SetupSettings(
                    state,
                    setup,
                    actions,
                    onOpenPlex = { runCatching { rows.getValue(SettingsSection.PLEX).requestFocus() } },
                )
                SettingsSection.LOOK -> Look(state, actions)
                SettingsSection.WALLPAPER -> WallpaperSettings(wallpaper)
                SettingsSection.LENSES -> Lenses(state, actions)
                SettingsSection.PROFILES -> ProfilesSettings(state, profiles)
                SettingsSection.PLEX -> PlexSettings(state, actions)
                SettingsSection.PLEX_GUIDE -> {
                    // A channel's Ratings choices are Settings › Profiles' age brackets (R2).
                    val brackets by remember(profiles) { profiles.brackets }.collectAsState(initial = AgeBrackets.DEFAULT)
                    PlexGuideSettings(
                        guideEditor,
                        onEditOnPhone = actions::openPhoneEditor,
                        brackets = brackets,
                        nowMillis = state.nowMillis,
                        station = station,
                    )
                }
                SettingsSection.NFL -> nfl?.let { NflSection(it, state.nowMillis, station) }
                SettingsSection.CHANNELS_AND_APPS -> ChannelsAndAppsSettings(channelsAndApps)
                SettingsSection.SEARCH -> SearchSettings(state, actions)
                SettingsSection.TIME -> TimeAndModesSettings(state, actions)
                SettingsSection.WEATHER -> WeatherSettings(state, actions)
                SettingsSection.AI -> AiBlurbsSettings(state, actions, aiBlurbs)
                SettingsSection.DIAGNOSTICS -> Diagnostics(state, actions)
                SettingsSection.ABOUT -> AboutSettings(version, OPEN_METEO_CREDIT)
            }
        }
    }
}

private const val LIST_WEIGHT = 260f
private const val GAP_WEIGHT = 32f
private const val DETAIL_WEIGHT = 572f

@Composable
private fun SectionRow(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(36.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Palette.Surface2 else Color.Transparent,
            contentColor = if (selected) Palette.Text else Palette.TextDim,
            focusedContainerColor = Palette.Surface3,
            focusedContentColor = Palette.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Box(Modifier.size(width = 3.dp, height = 18.dp).background(LocalAccent.current, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(10.dp))
            }
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
}

@Composable
private fun ColumnScope.Look(state: HomeUiState, actions: SettingsActions) {
    Eyebrow("Accent colour", Modifier.padding(top = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Palette.Accents.forEachIndexed { index, colour ->
            Surface(
                onClick = { actions.setAccent(index) },
                modifier = Modifier.size(40.dp),
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = colour,
                    focusedContainerColor = colour,
                    contentColor = Palette.AccentInk,
                    focusedContentColor = Palette.AccentInk,
                ),
                scale = cardLift,
                border = focusRing(CircleShape),
            ) {
                if (index == state.accentIndex) {
                    Icon(AppIcons.Check, contentDescription = "Selected", modifier = Modifier.size(20.dp).align(Alignment.Center))
                }
            }
        }
    }
    Eyebrow("Layout scale", Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LayoutScale.Options.forEach { scale ->
            val selected = (scale * 100).roundToInt() == (state.layoutScale * 100).roundToInt()
            PillButton(
                text = "${(scale * 100).roundToInt()}%",
                icon = if (selected) AppIcons.Check else null,
                onClick = { actions.setLayoutScale(scale) },
            )
        }
    }
    Note("Text and cards grow or shrink; the grid keeps its shape.")
}

@Composable
private fun ColumnScope.Diagnostics(state: HomeUiState, actions: SettingsActions) {
    ToggleRow(
        title = "Sample content",
        detail = "Made-up titles in the media cards, for design review. Nothing in them plays.",
        on = state.sampleContent,
        onClick = { actions.setShowSampleContent(!state.sampleContent) },
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(if (state.stockLauncherBack) Palette.Bad else Palette.Good, CircleShape))
        Text(
            text = if (state.stockLauncherBack) {
                "The stock launcher is enabled, so Home may open it. Run scripts/set-default-home.ps1."
            } else {
                "The stock launcher is disabled."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    PillButton(
        text = "Open device check",
        icon = AppIcons.Info,
        onClick = actions::openDeviceCheck,
        colors = pillButtonColors(container = Palette.Surface2),
    )
    Note("Device, handoff probes, Watch Next and safe mode: the Phase 0 probe screen.")
}

/**
 * Settings › Lenses: how the card moves (Phase 14, L1-L5), then which lenses it can show (U19).
 * The time of day picks the lead.
 */
@Composable
private fun ColumnScope.Lenses(state: HomeUiState, actions: SettingsActions) {
    Note(
        "The card beside Tonight's Pick shows one title through a lens. The time of day picks " +
            "which lens leads, and Next moves on through the others. It never repeats Tonight's Pick.",
    )
    val prefs = state.lensPrefs
    ToggleRow(
        title = "Rotate on its own",
        detail = "The card moves on to its next title ${every(prefs.seconds).lowercase()}. It waits while you're on it, " +
            "and Back (the arrow at its top left) brings back what it showed.",
        on = prefs.rotate,
        onClick = { actions.setLensRotate(!prefs.rotate) },
    )
    if (prefs.rotate) SpeedNotches(prefs.speed, actions::setLensSpeed)
    ToggleRow(
        title = "Shuffle",
        detail = "The lenses mixed, never the same one twice in a row, and a new order each time round. " +
            "Off, each lens's titles come together, in the order below.",
        on = prefs.shuffle,
        onClick = { actions.setLensShuffle(!prefs.shuffle) },
    )
    Eyebrow("Move on from a title", Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LensMoveOn.entries.forEach { moveOn ->
            PillButton(
                text = moveOn.label,
                icon = if (moveOn == prefs.moveOn) AppIcons.Check else null,
                onClick = { actions.setLensMoveOn(moveOn) },
                small = true,
            )
        }
    }
    Note(
        when (val days = prefs.moveOn.days) {
            null -> "A title stays on its lens until it's watched."
            else -> "A title on the card on ${if (days == 1) "a day" else "$days days"} without being played rests for 30 days, " +
                "and the lens's next title takes its place. Days the TV is off don't count."
        } + " Anything played in the last 2 weeks, or in Up Next, is off every lens.",
    )
    Eyebrow("Lenses", Modifier.padding(top = 8.dp))
    val lastOn = state.enabledLenses.singleOrNull()
    Lens.entries.forEach { lens ->
        val on = lens in state.enabledLenses
        val leads = LensSchedule.leads(lens) ?: "Comes up as you press Next"
        ToggleRow(
            title = lens.title,
            detail = "${lens.rule}. $leads." + if (lens == lastOn) " The last lens that's on stays on." else "",
            on = on,
            onClick = { actions.setLensEnabled(lens, !on) },
        )
    }
}

/** "Every 30 s", "Every 1 min". */
private fun every(seconds: Int) = if (seconds % 60 == 0) "Every ${seconds / 60} min" else "Every $seconds s"

/**
 * Settings › Lenses › Speed (L2): five notches on a line, slow to fast. The notch with focus
 * is the speed (as a tab row), so Left and Right change it and there's no OK to press;
 * coming into the row lands on the chosen one, and Left from the slowest leaves the row.
 */
@Composable
private fun SpeedNotches(speed: Int, onSpeed: (Int) -> Unit) {
    val notches = remember { List(LensPrefs.SPEEDS.size) { FocusRequester() } }
    val chosen = speed.coerceIn(1, notches.size)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Speed", style = MaterialTheme.typography.titleSmall, color = Palette.TextDim, modifier = Modifier.width(72.dp))
        Text("Slow", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        Box(Modifier.weight(1f).height(NOTCH), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().padding(horizontal = NOTCH / 2).height(2.dp).background(Palette.Surface3))
            Row(
                modifier = Modifier.fillMaxWidth().focusRestorer(notches[chosen - 1]).focusGroup(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                notches.forEachIndexed { index, requester ->
                    val notch = index + 1
                    Notch(
                        selected = notch == chosen,
                        description = "Speed $notch of ${notches.size}, ${every(LensPrefs.SPEEDS[index]).lowercase()}",
                        modifier = Modifier.focusRequester(requester),
                    ) { if (notch != chosen) onSpeed(notch) }
                }
            }
        }
        Text("Fast", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        Text(every(LensPrefs.SPEEDS[chosen - 1]), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(96.dp))
    }
}

/** One notch: a dot, the accent's when chosen, ringed while it has focus; focus chooses it. */
@Composable
private fun Notch(selected: Boolean, description: String, modifier: Modifier = Modifier, onFocused: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    Box(
        modifier = modifier
            .size(NOTCH)
            .semantics {
                contentDescription = description
                this.selected = selected
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        if (focused) Box(Modifier.size(NOTCH).border(2.5.dp, accent, CircleShape))
        Box(Modifier.size(if (selected) 14.dp else 10.dp).background(if (selected) accent else Palette.TextFaint, CircleShape))
    }
}

/** A notch's focus target: big enough for the ring around the chosen dot. */
private val NOTCH = 28.dp

/** A setting that's on or off: the whole row is the button. */
@Composable
internal fun ToggleRow(title: String, detail: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
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
            Toggle(on = on)
        }
    }
}

@Composable
private fun Toggle(on: Boolean) {
    val accent = LocalAccent.current
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 24.dp)
            .background(if (on) accent else Palette.Surface3, RoundedCornerShape(50)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .size(18.dp)
                .background(if (on) Palette.AccentInk else Palette.TextDim, CircleShape),
        )
    }
}

/** Open-Meteo's data is CC BY 4.0: the credit shows wherever its weather does (D16, Q3). */
private const val OPEN_METEO_CREDIT = "Weather data by Open-Meteo.com"

@Composable
private fun ColumnScope.WeatherSettings(state: HomeUiState, actions: SettingsActions) {
    var searching by remember { mutableStateOf(false) }
    var refocus by remember { mutableStateOf(false) }
    val change = remember { FocusRequester() }
    LaunchedEffect(searching) {
        if (!searching && refocus) {
            runCatching { change.requestFocus() }
            refocus = false
        }
    }
    val weather = state.weather
    Eyebrow("Location", Modifier.padding(top = 4.dp))
    if (searching) {
        PlaceSearch(actions) {
            refocus = true
            searching = false
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = weather.place?.description ?: "Not set",
                style = MaterialTheme.typography.titleSmall,
                color = if (weather.place == null) Palette.TextDim else Palette.Text,
                modifier = Modifier.weight(1f, fill = false),
            )
            PillButton(
                text = if (weather.place == null) "Set location" else "Change",
                icon = AppIcons.Search,
                onClick = { searching = true },
                colors = pillButtonColors(container = Palette.Surface2),
                modifier = Modifier.focusRequester(change),
            )
        }
    }
    Eyebrow("Units", Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TemperatureUnit.entries.forEach { unit ->
            PillButton(
                text = unit.symbol,
                icon = if (unit == weather.unit) AppIcons.Check else null,
                onClick = { actions.setTemperatureUnit(unit) },
            )
        }
    }
    val now = state.weatherNow
    Note(
        when {
            weather.place == null -> "Pick your city to show the temperature in the top bar."
            now != null -> "${now.temperature}${weather.unit.symbol.removePrefix("°")} and ${now.condition.lowercase()}, " +
                "${now.updated}. It updates every 30 minutes while the Shield is online."
            else -> "Waiting for the first reading. It updates every 30 minutes while the Shield is online."
        },
    )
    Text(OPEN_METEO_CREDIT, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
}

private sealed interface SearchStatus {
    data object Idle : SearchStatus
    data object Searching : SearchStatus
    data object Failed : SearchStatus
    data class Found(val query: String, val places: List<Place>) : SearchStatus
}

/**
 * Type a city with the TV keyboard, then pick from Open-Meteo's matches (Q2). Back
 * or Cancel leaves the location as it was.
 */
@Composable
private fun PlaceSearch(actions: SettingsActions, onDone: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<SearchStatus>(SearchStatus.Idle) }
    var focused by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current
    BackHandler(onBack = onDone)
    LaunchedEffect(Unit) {
        runCatching { field.requestFocus() }
        keyboard?.show()
    }
    fun search() {
        val text = query.trim()
        if (text.isEmpty()) return
        keyboard?.hide()
        status = SearchStatus.Searching
        scope.launch {
            status = try {
                SearchStatus.Found(text, actions.searchPlaces(text))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                SearchStatus.Failed
            }
        }
    }
    BasicTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = Palette.Text),
        cursorBrush = SolidColor(accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { search() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(field)
            .onFocusChanged { focused = it.isFocused }
            // OK on the field brings the TV keyboard back after it was dismissed.
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyUp && it.key == Key.DirectionCenter) keyboard?.show()
                false
            }
            .background(Palette.Surface2, RoundedCornerShape(10.dp))
            .border(2.dp, if (focused) accent else Color.Transparent, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        decorationBox = { inner ->
            Box {
                if (query.isEmpty()) {
                    Text("City name, like Portland", style = MaterialTheme.typography.titleMedium, color = Palette.TextFaint)
                }
                inner()
            }
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(text = "Search", icon = AppIcons.Search, onClick = ::search)
        PillButton(text = "Cancel", onClick = onDone, colors = pillButtonColors(container = Palette.Surface2))
    }
    when (val current = status) {
        SearchStatus.Idle -> Unit
        SearchStatus.Searching -> Note("Searching…")
        SearchStatus.Failed -> Note("Couldn't reach Open-Meteo. Check the Shield is online, then try again.")
        is SearchStatus.Found -> if (current.places.isEmpty()) {
            Note("No places called “${current.query}”. Try another spelling.")
        } else {
            current.places.forEach { place ->
                PlaceRow(place) {
                    actions.choosePlace(place)
                    onDone()
                }
            }
        }
    }
}

/**
 * Settings › Plex: who's signed in, which server and how it's reached (P2, P3), the last
 * sync, where Play goes and whether guide viewing counts (Phase 11), and Sign out.
 */
@Composable
private fun ColumnScope.PlexSettings(state: HomeUiState, actions: SettingsActions) {
    val plex = state.plex
    val now = state.nowMillis
    fun ago(millis: Long): String {
        val minutes = (now - millis) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }
    val account = plex.link.account
    if (account == null) {
        val code = (plex.signIn as? PlexSignIn.ShowingCode)?.code
        Note("Not signed in. On your phone or computer, go to plex.tv/link and enter the code " + if (code == null) "shown on the home screen." else "below.")
        if (code != null) {
            Text(
                text = code,
                style = MaterialTheme.typography.displaySmall.copy(letterSpacing = 8.sp, fontWeight = FontWeight.Bold),
                color = LocalAccent.current,
            )
        }
        return
    }
    Eyebrow("Account", Modifier.padding(top = 4.dp))
    Text(account.title, style = MaterialTheme.typography.titleSmall)
    if (account.username != account.title) Text(account.username, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
    Eyebrow("Server", Modifier.padding(top = 8.dp))
    val server = plex.link.server
    when (val signIn = plex.signIn) {
        is PlexSignIn.ChooseServer -> {
            Note("You own more than one Plex server. Which one should Action Center use?")
            signIn.servers.forEach { choice ->
                ChoiceRow(AppIcons.Tv, choice.name) { actions.choosePlexServer(choice.clientIdentifier) }
            }
        }
        else -> Text(
            text = when {
                server != null -> "${server.name} · ${server.kind.label.lowercase()} connection"
                signIn == PlexSignIn.NoServer -> "This account doesn't own a server. Shared servers aren't used."
                signIn is PlexSignIn.CantReachServer -> "Can't reach ${signIn.name}. Trying again shortly."
                signIn == PlexSignIn.Offline -> "Can't reach Plex. Trying again shortly."
                else -> "Finding your server…"
            },
            style = MaterialTheme.typography.titleSmall,
        )
    }
    if (server != null) {
        Eyebrow("Library", Modifier.padding(top = 8.dp))
        Text(
            text = plex.lastSyncMillis?.let { "Synced ${ago(it)}" } ?: "Not synced yet",
            style = MaterialTheme.typography.titleSmall,
        )
        when {
            plex.offline -> Note("The last sync couldn't reach ${server.name}. The home screen shows what was saved.")
            plex.failure != null -> Text(
                text = "The last sync hit a problem: ${plex.failure}",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Caution,
            )
        }
    }
    PlayInSettings(state, actions)
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (server != null) {
            PillButton(text = "Sync now", icon = AppIcons.Sync, onClick = actions::syncPlexNow)
        }
        PillButton(text = "Sign out", onClick = actions::signOutOfPlex, colors = pillButtonColors(container = Palette.Surface2))
    }
    Note("Signing out forgets this account and its tokens on the Shield. Plezy and the Plex app stay signed in.")
}

/**
 * Settings › Plex › Play in and Count guide viewing in Plex (Phase 11, K4, K1, K2), for this
 * TV: where every Play and the guide's Watch in go, the other app the fallback, and whether
 * a guide program watched to its credits is marked watched in Plex.
 */
@Composable
private fun ColumnScope.PlayInSettings(state: HomeUiState, actions: SettingsActions) {
    Eyebrow("Play in", Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlayIn.entries.forEach { app ->
            PillButton(
                text = app.label,
                icon = if (app == state.playIn) AppIcons.Check else null,
                onClick = { actions.setPlayIn(app) },
            )
        }
    }
    val other = PlayIn.entries.first { it != state.playIn }
    val why = when {
        state.playInChoice != null -> ""
        state.plezyInstalled -> " It's Plezy because Plezy is on this TV."
        else -> " It's Plex because Plezy isn't on this TV."
    }
    val profiles = if (state.playIn == PlayIn.PLEZY) " Plezy switches to whoever's watching." else " Plex may ask who's watching first."
    Note("Every Play, and Watch in from the guide, opens in ${state.playIn.label}, where Plex last had it; ${other.label} if it can't.$why$profiles")
    ToggleRow(
        title = "Count guide viewing in Plex",
        detail = "A program watched to its credits on a guide channel, three quarters of it, is marked watched " +
            "for whoever's watching. Nothing else is sent, and nothing for a kid without a Plex user.",
        on = state.countGuideViewing,
        onClick = { actions.setCountGuideViewing(!state.countGuideViewing) },
    )
}

/**
 * Settings › Search (Phase 15, Q3): whether Search adds titles from your streaming apps after
 * your library, for every profile on this TV.
 */
@Composable
private fun SearchSettings(state: HomeUiState, actions: SettingsActions) {
    ToggleRow(
        title = "Include streaming apps",
        detail = "After your library, Search shows titles from Plex's catalog that stream on the apps on this TV that " +
            "whoever's watching may open: subscription and free, never rent or buy. YouTube is left out; YouTube TV isn't.",
        on = state.searchStreaming,
        onClick = { actions.setSearchStreaming(!state.searchStreaming) },
    )
}

@Composable
private fun PlaceRow(place: Place, onClick: () -> Unit) = ChoiceRow(AppIcons.Place, place.description, onClick)

/** A row in a short list of choices: the focused one fills with the accent, like the quick-actions sheet. */
@Composable
private fun ChoiceRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(44.dp),
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
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
