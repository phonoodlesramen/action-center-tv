package com.phonoodlesramen.actioncenter.home

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonoodlesramen.actioncenter.apps.AppUsage
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.apps.LaunchpadOrder
import com.phonoodlesramen.actioncenter.apps.UsageCounts
import com.phonoodlesramen.actioncenter.apps.UsageScore
import com.phonoodlesramen.actioncenter.apps.key
import com.phonoodlesramen.actioncenter.content.EmptyContentSource
import com.phonoodlesramen.actioncenter.content.HomeContent
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.LensCursor
import com.phonoodlesramen.actioncenter.content.LensDeck
import com.phonoodlesramen.actioncenter.content.LensMoveOn
import com.phonoodlesramen.actioncenter.content.LensPick
import com.phonoodlesramen.actioncenter.content.LensPrefs
import com.phonoodlesramen.actioncenter.content.LensRotation
import com.phonoodlesramen.actioncenter.content.LensSchedule
import com.phonoodlesramen.actioncenter.content.MoreLike
import com.phonoodlesramen.actioncenter.content.Pick
import com.phonoodlesramen.actioncenter.content.QuickItem
import com.phonoodlesramen.actioncenter.content.SampleContentSource
import com.phonoodlesramen.actioncenter.content.Season
import com.phonoodlesramen.actioncenter.content.UpNextItem
import com.phonoodlesramen.actioncenter.context.ContextMode
import com.phonoodlesramen.actioncenter.context.ContextSchedule
import com.phonoodlesramen.actioncenter.context.Hours
import com.phonoodlesramen.actioncenter.context.greetingAt
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.lastapp.AppVisit
import com.phonoodlesramen.actioncenter.lastapp.RecentApps
import com.phonoodlesramen.actioncenter.lastapp.TunedChannel
import com.phonoodlesramen.actioncenter.lastapp.TvChannelStore
import com.phonoodlesramen.actioncenter.lastapp.TvState
import com.phonoodlesramen.actioncenter.nfl.NflView
import com.phonoodlesramen.actioncenter.player.SavedSpot
import com.phonoodlesramen.actioncenter.player.SpotStore
import com.phonoodlesramen.actioncenter.plex.PlexLink
import com.phonoodlesramen.actioncenter.plex.PlexSignIn
import com.phonoodlesramen.actioncenter.plex.PlexSyncStatus
import com.phonoodlesramen.actioncenter.plex.cache.PlexCards
import com.phonoodlesramen.actioncenter.plex.cache.PlexSnapshot
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.profiles.ProfileStore
import com.phonoodlesramen.actioncenter.handoff.Handoffs
import com.phonoodlesramen.actioncenter.handoff.PlayIn
import com.phonoodlesramen.actioncenter.settings.Settings
import com.phonoodlesramen.actioncenter.settings.SettingsRepository
import com.phonoodlesramen.actioncenter.suggest.FeedbackKind
import com.phonoodlesramen.actioncenter.suggest.FeedbackUndo
import com.phonoodlesramen.actioncenter.suggest.QuickWatch
import com.phonoodlesramen.actioncenter.suggest.SuggestionFeedback
import com.phonoodlesramen.actioncenter.system.PackageState
import com.phonoodlesramen.actioncenter.ui.theme.LayoutScale
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperPrefs
import com.phonoodlesramen.actioncenter.weather.WeatherDisplay
import com.phonoodlesramen.actioncenter.weather.WeatherState
import com.phonoodlesramen.actioncenter.weather.display
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PlexStatus(val label: String) {
    CONNECTED("Plex"),
    OFFLINE("Plex offline"),
    /** The server answered, but the last sync hit an error: Settings › Plex says which. */
    SYNC_FAILED("Plex sync failed"),
    NOT_CONNECTED("Plex not connected"),
}

/** Everything the art-forward home screen renders (docs/design/ui-spec.md). */
data class HomeUiState(
    val now: LocalDateTime,
    /** [now] as epoch millis, in the view model's clock zone (for "synced 3 h ago"). */
    val nowMillis: Long,
    val greeting: String,
    /** Settings name, for the avatar's initial. Blank shows a person icon. */
    val name: String,
    val mode: ContextMode,
    /** Settings › Look; [accent] is this colour, dimmed in late night. */
    val accentIndex: Int,
    val accent: Color,
    val layoutScale: Float,
    /** Null: nothing to show yet, the first-run state [U14]. */
    val content: HomeContent?,
    val pick: Pick?,
    val pickState: PickState,
    /** The lens card (U17); null when no lens has a title to show. */
    val lens: LensCardState?,
    /** Settings › Lenses (U19). */
    val enabledLenses: Set<Lens>,
    val launchpad: List<LaunchableApp>,
    val allApps: List<LaunchableApp>,
    val mostUsed: List<LaunchableApp>,
    /** Apps hidden from the launchpad, by [key]. */
    val hiddenApps: Set<String>,
    val plexStatus: PlexStatus,
    val stockLauncherBack: Boolean,
    val sampleContent: Boolean,
    /** Location, unit and last reading, for Settings › Weather. */
    val weather: WeatherState,
    /** What the top bar shows; null hides the weather. */
    val weatherNow: WeatherDisplay?,
    /** Sign-in and the linked server: the first-run hero and Settings › Plex. */
    val plex: PlexHome,
    /** Under the hero's text: "Saved pick · Plex is offline, updated 2 h ago" (U15). */
    val heroNote: String?,
    /** Settings › Time & modes: Quick Watch fits before this (S6), every night but Friday's and Saturday's. */
    val quickWatchCutoff: LocalTime = QuickWatch.DEFAULT_CUTOFF,
    /** Settings › Time & modes: Quick Watch's cutoff on Friday and Saturday nights (M4). */
    val weekendCutoff: LocalTime = QuickWatch.WEEKEND_CUTOFF,
    /** Settings › Time & modes: when each context mode runs (M1, M2); [mode] is now's. */
    val schedule: ContextSchedule = ContextSchedule.DEFAULT,
    /** Settings › AI blurbs (M7). */
    val aiBlurbs: Boolean = false,
    /** Settings › Plex › Play in as it stands (Phase 11, K4): what's chosen, or Plezy when it's installed. */
    val playIn: PlayIn = PlayIn.PLEZY,
    /** Settings › Plex › Play in as chosen; null: not chosen, so [playIn] follows whether Plezy is installed. */
    val playInChoice: PlayIn? = null,
    /** Plezy is on this TV: Settings › Plex says why Play goes where it does. */
    val plezyInstalled: Boolean = true,
    /** Settings › Plex › Count guide viewing in Plex (Phase 11, K1, K2). */
    val countGuideViewing: Boolean = true,
    /** Who's watching (Phase 5): you, or a kid with their ceiling, apps and colour. */
    val profile: ActiveProfile = ActiveProfile.owner(""),
    /** You, then the kids: "Who's watching?" and Settings › Profiles. */
    val profiles: List<ActiveProfile> = listOf(profile),
    /** Settings › Lenses (Phase 14): rotating, its speed, Shuffle, and when a title moves on. */
    val lensPrefs: LensPrefs = LensPrefs(),
    /** Search's last searches for the profile on now (Phase 15), newest first. */
    val recentSearches: List<String> = emptyList(),
    /** Settings › Search › Include streaming apps (Phase 15, Q3). */
    val searchStreaming: Boolean = true,
    /** Settings › Look › Layout: yours; a kid's home keeps its own whatever this is. */
    val homeLayout: HomeLayout = HomeLayout.DEFAULT,
    /** Settings › Look › Change with the time of day (Moments, H3). */
    val moments: Boolean = false,
    /** The chip's Keep the usual layout holds for now's mode and evening: no moment until it moves on. */
    val momentsHeld: Boolean = false,
)

/** The Plex part of the home screen. Never holds a token. */
data class PlexHome(
    val link: PlexLink = PlexLink(),
    val signIn: PlexSignIn? = null,
    /** The last sync couldn't reach the server: the cards are the cache (U15). */
    val offline: Boolean = false,
    /** The last sync reached the server but a request failed: what and why. */
    val failure: String? = null,
    val lastSyncMillis: Long? = null,
    val lastAttemptMillis: Long? = null,
    /** A sync is running, for a while now ([Updating]): the top bar says "Updating". */
    val updating: Boolean = false,
)

/**
 * What the lens card shows: the title, its lens, and the season for Seasonal. [moreLike]
 * names the title More like this is about (S10): the card then shows its titles instead.
 */
data class LensCardState(val lens: Lens, val pick: LensPick, val season: Season?, val moreLike: String? = null) {
    /** The eyebrow: the lens's name, the season's for Seasonal, or "More like Heat". */
    val title: String get() = moreLike?.let { "More like $it" } ?: season?.title ?: lens.title
}

/** What the view model needs from the system, so tests can fake it. */
interface HomeSystem {
    suspend fun launchableApps(): List<LaunchableApp>
    fun stockLauncherState(): PackageState
}

class HomeViewModel(
    private val settingsRepository: SettingsRepository,
    private val system: HomeSystem,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Where [state] is worked out: off the main thread, so a slow TV draws the home and takes keys meanwhile. */
    compute: CoroutineDispatcher = io,
    /** The weather cache ([com.phonoodlesramen.actioncenter.weather.WeatherRepository.state]). */
    weather: Flow<WeatherState> = flowOf(WeatherState()),
    plexLink: Flow<PlexLink> = flowOf(PlexLink()),
    plexSignIn: Flow<PlexSignIn?> = flowOf(null),
    plexSync: Flow<PlexSyncStatus> = flowOf(PlexSyncStatus()),
    /** True while a Plex sync runs ([com.phonoodlesramen.actioncenter.plex.cache.PlexSync.running]): the top bar's [Updating]. */
    plexSyncing: Flow<Boolean> = flowOf(false),
    /** The Plex cache, re-read when it changes or the day turns. */
    plexSnapshots: (days: Flow<LocalDate>) -> Flow<PlexSnapshot?> = { flowOf(null) },
    /** Settings › Wallpaper (L1-L4). */
    wallpaperPrefs: Flow<WallpaperPrefs> = flowOf(WallpaperPrefs()),
    /** Channels and Continue in your apps (L8, L9). */
    homeRows: Flow<HomeRowsState> = flowOf(HomeRowsState()),
    /** The Plex Guide's lineup (Phase 2.6), for the Live row, the Guide card and the full guide. */
    guideLineup: Flow<GuideCardState> = flowOf(GuideCardState.NotSetUp),
    /** Not tonight and the hides go to the suggestion engine (Phase 3: S1, S10). */
    private val suggestions: SuggestionFeedback = SuggestionFeedback.None,
    /** Which apps you open, and when (M5): the Apps row's order. */
    private val appUsage: AppUsage = AppUsage.None,
    /** Kids, the age brackets and who's watching (Phase 5). */
    private val profileStore: ProfileStore = ProfileStore.None,
    /** Where the guide player was left (P3): the Back-to card (P4). */
    private val spots: SpotStore = SpotStore.None,
    /** The current profile's favorite channels, in its order (Phase 13). */
    favorites: Flow<List<String>> = flowOf(emptyList()),
    /** The NFL's copy (Phase 13): the game-day card, the NFL row and the NFL screen. */
    nfl: Flow<NflView> = flowOf(NflView.None),
    /** The last app you watched, read on every resume (Back to YouTube TV). */
    private val recentApps: RecentApps = RecentApps.None,
    /** The YouTube TV channels opened here, and the visit forgotten. */
    private val tvChannels: TvChannelStore = TvChannelStore.None,
) : ViewModel() {
    private val now = MutableStateFlow(LocalDateTime.now(clock))
    private val apps = MutableStateFlow<List<LaunchableApp>>(emptyList())
    private val stockLauncher = MutableStateFlow(PackageState.DISABLED)
    private val pickState = MutableStateFlow(PickState())
    private val lensCursor = MutableStateFlow(LensCursor())
    private val lastVisit = MutableStateFlow<AppVisit?>(null)

    /** What the lens card showed, newest last, for Back (L3). Cleared with the card (a profile switch). */
    private val lensHistory = ArrayDeque<LensCursor>()

    /** The profiles and titles counted as on screen on [shownOn] (L5), so a title going round all evening is written once. */
    private val shownToday = HashSet<Pair<String, String>>()
    private var shownOn: LocalDate? = null

    val state: StateFlow<HomeUiState> = combine(
        settingsRepository.settings, now, apps, stockLauncher,
        combine(
            pickState,
            lensCursor,
            weather,
            combine(plexLink, plexSignIn, plexSync, Updating.shown(plexSyncing)) { link, signIn, sync, updating ->
                PlexHome(link, signIn, sync.offline, sync.failure, sync.lastSuccessMillis, sync.lastAttemptMillis, updating = updating && link.linked)
            },
            combine(plexSnapshots(now.map { it.toLocalDate() }), appUsage.counts, people(), ::Triple),
        ) { picks, cursor, weatherState, plex, (snapshot, usage, people) -> Extras(picks, cursor, weatherState, plex, snapshot, usage, people) },
    ) { settings, time, installed, stock, extras ->
        homeUiState(
            settings, time, installed, stock, extras.picks, extras.cursor, extras.weather, time.millis(), extras.plex, extras.snapshot,
            extras.usage, extras.people.first, extras.people.second,
        )
    }.flowOn(compute).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        homeUiState(Settings(), now.value, emptyList(), PackageState.DISABLED, PickState(), LensCursor(), WeatherState(), clock.millis(), PlexHome()),
    )

    /** Apart from [state], so a wallpaper change or a new row doesn't rebuild the dashboard. */
    val wallpaper: StateFlow<WallpaperPrefs> = wallpaperPrefs.stateIn(viewModelScope, SharingStarted.Eagerly, WallpaperPrefs())
    val rows: StateFlow<HomeRowsState> = homeRows.stateIn(viewModelScope, SharingStarted.Eagerly, HomeRowsState())
    val guide: StateFlow<GuideCardState> = guideLineup.stateIn(viewModelScope, SharingStarted.Eagerly, GuideCardState.NotSetUp)

    /** The profile's favorite channels (Phase 13): the Live row, the Guide card and the full guide put them first. */
    val favorites: StateFlow<List<String>> = favorites.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The NFL (Phase 13), apart from [state] so a score moving doesn't rebuild the dashboard's other cards. */
    val nfl: StateFlow<NflView> = nfl.stateIn(viewModelScope, SharingStarted.Eagerly, NflView.None)

    /**
     * The guide player's saved spot (P3), whether or not it still holds: the home checks it
     * against the lineup it shows and its minute tick ([com.phonoodlesramen.actioncenter.player.SpotRules]).
     */
    val spot: StateFlow<SavedSpot?> = spots.spot.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The Back-to card's Resume, Back to live and Forget this (P4): the spot is gone; with [only], only while it's that one. */
    fun forgetSpot(only: SavedSpot? = null) = background { spots.clear(only) }

    /**
     * Back to YouTube TV: the last app you watched, the YouTube TV channels opened
     * here and the visit forgotten. The home works out the card from it on its minute tick
     * ([com.phonoodlesramen.actioncenter.lastapp.YouTubeTv.backTo]).
     */
    val tv: StateFlow<TvState> = combine(lastVisit, tvChannels.channels, tvChannels.forgotten, ::TvState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, TvState())

    /** Back to YouTube TV › Forget this: [visit]'s card goes. */
    fun forgetVisit(visit: AppVisit) = background { tvChannels.forget(visit) }

    /** A YouTube TV channel was opened here: it leads the Live row's recent channels and the card's sheet. */
    fun tunedTv(channel: TunedChannel) = background { tvChannels.tuned(channel) }

    private fun LocalDateTime.millis() = atZone(clock.zone).toInstant().toEpochMilli()

    private data class Extras(
        val picks: PickState,
        val cursor: LensCursor,
        val weather: WeatherState,
        val plex: PlexHome,
        val snapshot: PlexSnapshot?,
        val usage: UsageCounts,
        val people: Pair<ActiveProfile, List<ActiveProfile>>,
    )

    /** Who's watching and everyone there is, from the store; the owner's name is Settings'. */
    private fun people(): Flow<Pair<ActiveProfile, List<ActiveProfile>>> {
        val name = settingsRepository.settings.map { it.name }.distinctUntilChanged()
        val today = now.map { it.toLocalDate() }.distinctUntilChanged()
        return combine(profileStore.current(name, today), profileStore.profiles(name, today), ::Pair)
    }

    /** "Who's watching?" (K1): [profileId] is on from now; the cards follow its picks. */
    fun switchProfile(profileId: String) {
        pickState.value = PickState()
        lensCursor.value = LensCursor()
        lensHistory.clear()
        viewModelScope.launch { profileStore.switchTo(profileId) }
    }

    init {
        refresh()
    }

    /**
     * Moves the clock on. The activity calls it once a minute while the screen is
     * started, so nothing ticks in the background.
     */
    fun tick() {
        now.value = LocalDateTime.now(clock)
    }

    /**
     * On every resume: the app list and the stock launcher can change while we're away,
     * and a new day runs the suggestion engine again.
     */
    fun refresh() {
        tick()
        viewModelScope.launch {
            apps.value = withContext(io) { system.launchableApps() }
            stockLauncher.value = withContext(io) { system.stockLauncherState() }
            // Back to YouTube TV: what you were watching before the home came up.
            lastVisit.value = withContext(io) { recentApps.last(apps.value) }
        }
        background { suggestions.refresh() }
        background { appUsage.refresh() }
    }

    /** The last Not tonight's feedback, while its Undo window is open. */
    private var notTonightFeedback: Deferred<FeedbackUndo?>? = null

    fun notTonight() {
        val content = currentContent()
        val shown = state.value.pick
        pickState.update { it.notTonight(content?.picks.orEmpty(), clock.millis()) }
        notTonightFeedback = if (content?.fromPlex == true && shown != null) {
            viewModelScope.async(io) { feedback { suggestions.record(shown.titleKey, FeedbackKind.NOT_TONIGHT) } }
        } else {
            null
        }
        viewModelScope.launch {
            delay(PickState.UNDO_WINDOW_MILLIS)
            pickState.update { it.expire(clock.millis()) }
        }
    }

    fun undo() {
        val window = pickState.value.undo
        pickState.update { it.undo(clock.millis()) }
        val pending = notTonightFeedback
        notTonightFeedback = null
        if (window != null && clock.millis() < window.untilMillis && pending != null) {
            background { pending.await()?.let { suggestions.undo(it) } }
        }
    }

    /** Up Next › Remove from Up Next (S10): until you play it again, and it counts as abandoned. */
    fun removeFromUpNext(item: UpNextItem) = record(item.titleKey, FeedbackKind.UP_NEXT_REMOVED)

    /** Quick Watch › Hide from Quick Watch (S10): for 30 days. */
    fun hideFromQuickWatch(item: QuickItem) = record(item.titleKey, FeedbackKind.QUICK_WATCH_HIDDEN)

    /** The lens card › Don't show this (S10): off that lens for 6 months, and the card moves on. Back never returns to it. */
    fun dontShowLensPick() {
        val shown = state.value.lens
        if (shown != null && shown.moreLike == null) record(shown.pick.titleKey, FeedbackKind.LENS_HIDDEN, scope = shown.lens.name)
        advanceLens(remember = false)
    }

    /**
     * Tonight's Pick › More like this (S10): the lens card shows titles like the pick until
     * the time picks a different lead or Next runs past the last. False when there are none.
     */
    fun moreLikeThis(): Boolean {
        val current = state.value
        val pick = current.pick ?: return false
        if (current.content?.moreLike?.get(pick.id).isNullOrEmpty()) return false
        rememberLensCard()
        lensCursor.value = LensCursor(lead = LensSchedule.lead(current.now, current.schedule), moreLike = MoreLike(pick.id, pick.title))
        return true
    }

    /**
     * Next, and the card moving on by itself (L1): the lens's next title, running on into the
     * next lens; in More like this, its next title, then back to the lenses.
     */
    fun nextLensPick() = advanceLens(remember = true)

    private fun advanceLens(remember: Boolean) {
        if (remember) rememberLensCard()
        val more = lensCursor.value.moreLike ?: return moveLens(LensCursor::next)
        val titles = state.value.content?.moreLike?.get(more.sourceId).orEmpty()
        lensCursor.update { if (more.index + 1 < titles.size) it.copy(moreLike = more.copy(index = more.index + 1)) else LensCursor() }
    }

    /** What the lens card shows now, by its title, onto [lensHistory] for Back (L3). */
    private fun rememberLensCard() {
        val current = state.value
        val shown = current.lens ?: return
        val cursor = lensCursor.value
        val at = if (shown.moreLike != null) {
            cursor
        } else {
            LensCursor(LensSchedule.lead(current.now, current.schedule), shown.lens, shown.pick.id, pass = cursor.pass)
        }
        if (lensHistory.lastOrNull() == at) return
        lensHistory.addLast(at)
        if (lensHistory.size > LENS_HISTORY) lensHistory.removeFirst()
    }

    /**
     * The lens card's Back (L3): the title it showed before, as it showed it, skipping one
     * that has left (hidden, watched, or More like this the time has moved past); past what
     * it remembers, the rotation's title before.
     */
    fun previousLensPick() {
        val current = state.value
        val lead = LensSchedule.lead(current.now, current.schedule)
        val deck = current.lensDeck()
        while (lensHistory.isNotEmpty()) {
            val back = lensHistory.removeLast()
            val more = back.moreLike
            val restored = if (more != null) back else back.copy(lead = lead)
            val stillThere = if (more != null) {
                back.lead == lead && current.content?.moreLike?.get(more.sourceId).orEmpty().size > more.index
            } else {
                deck.slots(back.pass).any { it.lens == back.lens && it.pick.id == back.id }
            }
            if (stillThere && restored != lensCursor.value) {
                lensCursor.value = restored
                return
            }
        }
        lensCursor.update { it.previous(deck, lead) }
    }

    /**
     * The lens card showed [pick] for a few seconds while the home was on screen (L5): once a
     * day a title, it counts as a day on screen for the profile on now. Sample content and
     * More like this don't count.
     */
    fun lensShown(pick: LensPick) {
        if (currentContent()?.fromPlex != true) return
        val day = LocalDate.now(clock)
        if (shownOn != day) {
            shownOn = day
            shownToday.clear()
        }
        if (!shownToday.add(state.value.profile.id to pick.titleKey)) return
        background { suggestions.lensShown(pick.titleKey) }
    }

    private fun record(titleKey: String, kind: FeedbackKind, scope: String = "") {
        if (currentContent()?.fromPlex != true) return
        background { suggestions.record(titleKey, kind, scope) }
    }

    /** Feedback is a nice-to-have: nothing it does is allowed to break the home screen. */
    private fun background(block: suspend () -> Unit) {
        viewModelScope.launch(io) { feedback(block) }
    }

    private suspend fun <T> feedback(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Quick actions › Another lens. Lasts until the time picks a different lead. */
    fun nextLens() {
        rememberLensCard()
        moveLens(LensCursor::nextLens)
    }

    fun setLensEnabled(lens: Lens, enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setLensEnabled(lens, enabled) }
    }

    /** Search (Phase 15): [text] was searched and something opened, so it leads the profile on now's last searches. */
    fun rememberSearch(text: String) {
        val profile = state.value.profile.id
        viewModelScope.launch { settingsRepository.rememberSearch(profile, text) }
    }

    /** Settings › Search › Include streaming apps (Q3). */
    fun setSearchStreaming(on: Boolean) {
        viewModelScope.launch { settingsRepository.setSearchStreaming(on) }
    }

    /** Settings › Lenses › Rotate on its own (L1). */
    fun setLensRotate(on: Boolean) {
        viewModelScope.launch { settingsRepository.setLensRotate(on) }
    }

    /** Settings › Lenses › Speed (L2): a notch, 1 to 5. */
    fun setLensSpeed(notch: Int) {
        viewModelScope.launch { settingsRepository.setLensSpeed(notch) }
    }

    /** Settings › Lenses › Shuffle (L4). */
    fun setLensShuffle(on: Boolean) {
        viewModelScope.launch { settingsRepository.setLensShuffle(on) }
    }

    /** Settings › Lenses › Move on from a title (L5): the engine reads it on its next run. */
    fun setLensMoveOn(moveOn: LensMoveOn) {
        viewModelScope.launch { settingsRepository.setLensMoveOn(moveOn) }
    }

    private fun moveLens(move: LensCursor.(LensDeck, Lens) -> LensCursor) {
        val current = state.value
        lensCursor.update { it.move(current.lensDeck(), LensSchedule.lead(current.now, current.schedule)) }
    }

    fun launched(app: LaunchableApp) {
        viewModelScope.launch { settingsRepository.recordLaunch(app.key) }
    }

    fun setHidden(app: LaunchableApp, hidden: Boolean) {
        viewModelScope.launch { settingsRepository.setHidden(app.key, hidden) }
    }

    fun setAccent(index: Int) {
        viewModelScope.launch { settingsRepository.setAccent(index) }
    }

    fun setLayoutScale(scale: Float) {
        viewModelScope.launch { settingsRepository.setLayoutScale(scale) }
    }

    /** Settings › Look › Layout. */
    fun setHomeLayout(layout: HomeLayout) {
        viewModelScope.launch { settingsRepository.setHomeLayout(layout) }
    }

    /** Settings › Look › Change with the time of day (Moments). */
    fun setMoments(on: Boolean) {
        viewModelScope.launch { settingsRepository.setMoments(on) }
    }

    /** The context chip's Keep the usual layout: no moment until the mode, or the evening, moves on. */
    fun keepUsualLayout() {
        val now = LocalDateTime.now(clock)
        val mode = state.value.schedule.modeAt(now)
        viewModelScope.launch { settingsRepository.holdMoments(Moments.held(mode, now)) }
    }

    /** Settings › Time & modes: Quick Watch fits before this (S6). */
    fun setQuickWatchCutoff(cutoff: LocalTime) {
        viewModelScope.launch { settingsRepository.setQuickWatchCutoff(cutoff) }
    }

    /** Settings › Time & modes: Friday and Saturday nights' cutoff (M4). */
    fun setWeekendCutoff(cutoff: LocalTime) {
        viewModelScope.launch { settingsRepository.setWeekendCutoff(cutoff) }
    }

    /** Settings › Time & modes (M1). Hours that would clash are refused: check [ContextSchedule.problem] first to say why. */
    fun setModeHours(mode: ContextMode, hours: Hours) {
        viewModelScope.launch { settingsRepository.setModeHours(mode, hours) }
    }

    /** Settings › AI blurbs (M7). */
    fun setAiBlurbs(on: Boolean) {
        viewModelScope.launch { settingsRepository.setAiBlurbs(on) }
    }

    /** Settings › Plex › Play in (Phase 11, K4). */
    fun setPlayIn(app: PlayIn) {
        viewModelScope.launch { settingsRepository.setPlayIn(app) }
    }

    /** Settings › Plex › Count guide viewing in Plex (Phase 11). */
    fun setCountGuideViewing(on: Boolean) {
        viewModelScope.launch { settingsRepository.setCountGuideViewing(on) }
    }

    fun setShowSampleContent(show: Boolean) {
        pickState.value = PickState()
        viewModelScope.launch { settingsRepository.setShowSampleContent(show) }
    }

    private fun currentContent(): HomeContent? = state.value.content
}

/** Builds the screen state from settings, the clock and the system. Pure, so samples and tests reuse it. */
fun homeUiState(
    settings: Settings,
    time: LocalDateTime,
    installed: List<LaunchableApp>,
    stock: PackageState,
    picks: PickState,
    cursor: LensCursor,
    weather: WeatherState,
    nowMillis: Long,
    plex: PlexHome = PlexHome(),
    plexSnapshot: PlexSnapshot? = null,
    usage: UsageCounts = UsageCounts(),
    profile: ActiveProfile = ActiveProfile.owner(settings.name),
    profiles: List<ActiveProfile> = listOf(profile),
): HomeUiState {
    val schedule = settings.contextSchedule
    val mode = schedule.modeAt(time)
    // Sample content wins for design review; otherwise the Plex cache once a server is linked.
    val content = when {
        settings.showSampleContent -> SampleContentSource.content()
        plex.link.linked && plexSnapshot != null -> PlexCards.build(
            plexSnapshot, time, shown = picks, schedule = schedule, cutoff = settings.quickWatchCutoff, weekendCutoff = settings.weekendCutoff,
        )
        else -> EmptyContentSource.content()
    }
    // A kid's own colour is their accent (U13).
    val accent = profile.color?.let { Color(it.toInt()) } ?: Palette.Accents[settings.accentIndex]
    val scores = UsageScore.at(usage, time)
    val greeting = greetingAt(time.toLocalTime())
    val pick = content?.picks?.let { picks.current(it) }
    val plezyInstalled = installed.any { it.packageName == Handoffs.PLEZY }
    return HomeUiState(
        now = time,
        nowMillis = nowMillis,
        greeting = if (settings.name.isBlank()) greeting else "$greeting, ${settings.name}",
        name = settings.name,
        mode = mode,
        accentIndex = settings.accentIndex,
        accent = if (mode == ContextMode.LATE_NIGHT) Palette.lateNight(accent) else accent,
        layoutScale = LayoutScale.clamp(settings.layoutScale),
        content = content,
        pick = pick,
        pickState = picks,
        lens = lensCard(content, settings.enabledLenses, time, pick, schedule, cursor, profile, settings.lensPrefs.shuffle),
        enabledLenses = settings.enabledLenses,
        launchpad = LaunchpadOrder.launchpad(installed, settings.launchCounts, settings.hiddenApps, scores.now),
        allApps = installed,
        mostUsed = LaunchpadOrder.mostUsed(installed, settings.launchCounts, scores.allDay),
        hiddenApps = settings.hiddenApps,
        plexStatus = when {
            !plex.link.linked -> PlexStatus.NOT_CONNECTED
            plex.offline -> PlexStatus.OFFLINE
            plex.failure != null -> PlexStatus.SYNC_FAILED
            else -> PlexStatus.CONNECTED
        },
        stockLauncherBack = stock == PackageState.ENABLED,
        sampleContent = settings.showSampleContent,
        weather = weather,
        weatherNow = weather.display(nowMillis),
        plex = plex,
        heroNote = if (content?.fromPlex == true && plex.offline) offlineNote(plex.lastSyncMillis, nowMillis) else null,
        quickWatchCutoff = settings.quickWatchCutoff,
        weekendCutoff = settings.weekendCutoff,
        schedule = schedule,
        aiBlurbs = settings.aiBlurbs,
        playIn = PlayIn.of(settings.playIn, plezyInstalled),
        playInChoice = settings.playIn,
        plezyInstalled = plezyInstalled,
        countGuideViewing = settings.countGuideViewing,
        profile = profile,
        profiles = profiles,
        lensPrefs = settings.lensPrefs,
        recentSearches = settings.recentSearches[profile.id].orEmpty(),
        searchStreaming = settings.searchStreaming,
        homeLayout = settings.homeLayout,
        moments = settings.moments,
        momentsHeld = settings.momentsHeld == Moments.held(mode, time),
    )
}

/** How many titles the lens card's Back (L3) can go back through. */
private const val LENS_HISTORY = 30

private fun offlineNote(lastSync: Long?, now: Long): String {
    val minutes = lastSync?.let { (now - it) / 60_000 }
    val age = when {
        minutes == null -> ""
        minutes < 60 -> ", updated $minutes min ago"
        minutes < 48 * 60 -> ", updated ${minutes / 60} h ago"
        else -> ", updated ${minutes / (24 * 60)} days ago"
    }
    return "Saved pick · Plex is offline$age"
}

/**
 * The lens card's rotation now, never Tonight's Pick (U20): led by the time of day, or for a
 * [kid] by "Remember when" (their Throwback) whenever they have one (K7, R1). Settings ›
 * Lenses' on/off, order and Shuffle (L4: a new mix each day and each time round) apply to
 * everyone.
 */
private fun lensDeck(
    content: HomeContent?,
    enabled: Set<Lens>,
    time: LocalDateTime,
    pick: Pick?,
    schedule: ContextSchedule,
    kid: Boolean,
    shuffle: Boolean,
): LensDeck {
    if (content == null) return LensDeck { emptyList() }
    val lead = lensLead(content, enabled, time, pick, schedule, kid)
    val season = Season.at(time.toLocalDate())
    return LensDeck { pass ->
        LensRotation.slots(content.lenses, enabled, lead, season, pick?.id, LensRotation.seed(time.toLocalDate(), pass).takeIf { shuffle })
    }
}

/** The lens card's rotation for this state's profile: what Next, Back and Another lens move through. */
internal fun HomeUiState.lensDeck(): LensDeck = lensDeck(content, enabledLenses, now, pick, schedule, profile.kid, lensPrefs.shuffle)

/**
 * Which lens leads the card: the time's ([LensSchedule.lead]), as for you, except that a
 * [kid]'s "Remember when" leads whenever Throwback is on and has a title that isn't
 * Tonight's Pick (K7). A lens picked by hand still lasts until the time moves on.
 */
internal fun lensLead(content: HomeContent?, enabled: Set<Lens>, time: LocalDateTime, pick: Pick?, schedule: ContextSchedule, kid: Boolean): Lens {
    val byTime = LensSchedule.lead(time, schedule)
    if (!kid || Lens.THROWBACK !in enabled) return byTime
    val rememberWhen = content?.lenses?.get(Lens.THROWBACK).orEmpty().any { it.id != pick?.id }
    return if (rememberWhen) Lens.THROWBACK else byTime
}

/**
 * The lens card for [profile]: the [cursor]'s place in the rotation, or More like this
 * (S10) while it lasts. The cursor is keyed by the time's lead, so a lens picked by hand,
 * or More like this, lasts until the time picks another, for a kid too.
 */
internal fun lensCard(
    content: HomeContent?,
    enabled: Set<Lens>,
    time: LocalDateTime,
    pick: Pick?,
    schedule: ContextSchedule,
    cursor: LensCursor,
    profile: ActiveProfile,
    shuffle: Boolean = false,
): LensCardState? {
    val lead = LensSchedule.lead(time, schedule)
    val lensSlot = cursor.current(lensDeck(content, enabled, time, pick, schedule, profile.kid, shuffle), lead)
    // More like this (S10) holds the card until the time picks another lead.
    val moreLike = cursor.moreLike?.takeIf { cursor.lead == lead }?.let { more ->
        content?.moreLike?.get(more.sourceId).orEmpty().filter { it.id != pick?.id }.getOrNull(more.index)
            ?.let { LensCardState(lead, it, season = null, moreLike = more.sourceTitle) }
    }
    return moreLike ?: lensSlot?.let { LensCardState(it.lens, it.pick, if (it.lens == Lens.SEASONAL) Season.at(time.toLocalDate()) else null) }
}
