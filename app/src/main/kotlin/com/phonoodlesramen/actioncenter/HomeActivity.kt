package com.phonoodlesramen.actioncenter

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.app.SearchManager
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.SearchEvent
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.apps.LocalAppArtSource
import com.phonoodlesramen.actioncenter.apps.LocalCustomBanners
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.LensMoveOn
import com.phonoodlesramen.actioncenter.content.LensPick
import com.phonoodlesramen.actioncenter.content.Pick
import com.phonoodlesramen.actioncenter.content.QuickItem
import com.phonoodlesramen.actioncenter.content.UpNextItem
import com.phonoodlesramen.actioncenter.context.ContextMode
import com.phonoodlesramen.actioncenter.guide.KidGuide
import com.phonoodlesramen.actioncenter.context.Hours
import com.phonoodlesramen.actioncenter.favorites.FavoriteChange
import com.phonoodlesramen.actioncenter.favorites.FavoriteText
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.handoff.Handoffs
import com.phonoodlesramen.actioncenter.handoff.IntentSpec
import com.phonoodlesramen.actioncenter.handoff.PlayIn
import com.phonoodlesramen.actioncenter.home.ChannelButton
import com.phonoodlesramen.actioncenter.home.HomeState
import com.phonoodlesramen.actioncenter.home.HomeStateLoader
import com.phonoodlesramen.actioncenter.home.HomeViewModel
import com.phonoodlesramen.actioncenter.home.OpenedBy
import com.phonoodlesramen.actioncenter.home.reportLines
import com.phonoodlesramen.actioncenter.lastapp.AppVisit
import com.phonoodlesramen.actioncenter.lastapp.TunedChannel
import com.phonoodlesramen.actioncenter.lastapp.YouTubeTv
import com.phonoodlesramen.actioncenter.nfl.WatchOptions
import com.phonoodlesramen.actioncenter.nfl.WatchWay
import com.phonoodlesramen.actioncenter.player.ExoProbePlayer
import com.phonoodlesramen.actioncenter.player.GuidePlayerActivity
import com.phonoodlesramen.actioncenter.player.PlaybackCheck
import com.phonoodlesramen.actioncenter.player.SavedSpot
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.safety.StartupMode
import com.phonoodlesramen.actioncenter.safety.summary
import com.phonoodlesramen.actioncenter.tvprovider.dumpLines
import com.phonoodlesramen.actioncenter.nfl.WatchApp
import com.phonoodlesramen.actioncenter.search.SearchCheckResult
import com.phonoodlesramen.actioncenter.search.StreamingApp
import com.phonoodlesramen.actioncenter.search.StreamingSearch
import com.phonoodlesramen.actioncenter.setup.AllowRoute
import com.phonoodlesramen.actioncenter.setup.LocalSetup
import com.phonoodlesramen.actioncenter.setup.OpenSetupActivity
import com.phonoodlesramen.actioncenter.setup.SetupPermission
import com.phonoodlesramen.actioncenter.ui.home.myApps
import com.phonoodlesramen.actioncenter.ui.home.rows.appsRowOrder
import com.phonoodlesramen.actioncenter.ui.Toast
import com.phonoodlesramen.actioncenter.ui.ToastMessage
import com.phonoodlesramen.actioncenter.ui.apps.AllAppsScreen
import com.phonoodlesramen.actioncenter.ui.art.LocalArtUrls
import com.phonoodlesramen.actioncenter.ui.art.PlexArtUrls
import com.phonoodlesramen.actioncenter.ui.diagnostics.DiagnosticsActions
import com.phonoodlesramen.actioncenter.ui.diagnostics.DiagnosticsScreen
import com.phonoodlesramen.actioncenter.ui.diagnostics.PlaybackCheckScreen
import com.phonoodlesramen.actioncenter.ui.diagnostics.SearchCheckScreen
import com.phonoodlesramen.actioncenter.ui.guide.GuideScreen
import com.phonoodlesramen.actioncenter.ui.guide.ProgramDetailsScreen
import com.phonoodlesramen.actioncenter.ui.home.FocusRequest
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.HomeFocus
import com.phonoodlesramen.actioncenter.ui.home.HomeScreen
import com.phonoodlesramen.actioncenter.ui.home.backToCardFocus
import com.phonoodlesramen.actioncenter.ui.home.rows.LocalChannelsAndApps
import com.phonoodlesramen.actioncenter.ui.nfl.LocalNflLogos
import com.phonoodlesramen.actioncenter.ui.nfl.NflOpen
import com.phonoodlesramen.actioncenter.ui.nfl.NflScreen
import com.phonoodlesramen.actioncenter.ui.search.SearchScreen
import com.phonoodlesramen.actioncenter.ui.profiles.WhosWatchingScreen
import com.phonoodlesramen.actioncenter.ui.profiles.opensAfterScreenOff
import com.phonoodlesramen.actioncenter.ui.settings.AiKeyScreen
import com.phonoodlesramen.actioncenter.ui.settings.PhoneEditorScreen
import com.phonoodlesramen.actioncenter.ui.settings.SettingsActions
import com.phonoodlesramen.actioncenter.ui.settings.SettingsScreen
import com.phonoodlesramen.actioncenter.ui.settings.SettingsSection
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalWallpaperSources
import com.phonoodlesramen.actioncenter.update.LocalUpdates
import com.phonoodlesramen.actioncenter.weather.Place
import com.phonoodlesramen.actioncenter.weather.TemperatureUnit
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Shield's home screen: receives the HOME intent once tvlauncher is disabled. */
class HomeActivity : ComponentActivity() {
    private enum class Screen { HOME, ALL_APPS, SETTINGS, DIAGNOSTICS, PLAYBACK_CHECK, SEARCH_CHECK, GUIDE, DETAILS, PHONE_EDITOR, AI_KEY, WHOS_WATCHING, NFL, SEARCH }

    /** A guide program: its channel and start. */
    private data class ProgramRef(val channelId: String, val startMillis: Long)

    private val container by lazy { (application as ActionCenterApp).container }

    // The container's, which outlives this screen ([AppContainer.home]); made on first use, so safe mode never touches it.
    private val viewModel: HomeViewModel get() = container.home

    private var safeMode by mutableStateOf(false)
    private var screen by mutableStateOf(Screen.HOME)
    private var homeFocus by mutableStateOf(FocusRequest())
    private var settingsSection by mutableStateOf(SettingsSection.LOOK)

    /**
     * Where Back from "Who's watching?" lands (K1): the top bar's gear, beside the avatar that
     * opened it, or the top of the home after the screen was off.
     */
    private var whosWatchingBack = HomeFocus.PLAY

    /** The screen went off since the home last came back: "Who's watching?" on the way back, if there's a kid (K1). */
    private var screenWentOff = false

    /**
     * Where Back from a player the Back-to card resumed lands (P4): that card, which is the
     * Guide card, or for a kid their Live row's card for that channel. Home clears it (U10).
     */
    private var backFromResume: FocusRequest? = null

    /** Registered while the activity lives: the screen-off broadcast only reaches receivers registered at runtime. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) screenWentOff = true
        }
    }
    private var watchingScreenOff = false

    /**
     * The Live card whose Full guide opened the full guide: the guide opens on its channel,
     * and Back returns to that card (G20 A). Null is the Guide card: the first channel.
     */
    private var guideChannel by mutableStateOf<String?>(null)

    /** The program the details screen shows. */
    private var details by mutableStateOf<ProgramRef?>(null)

    /** Back from the details, the guide opens on their program; null is now. */
    private var guideAt by mutableStateOf<ProgramRef?>(null)

    /** How the NFL screen opened (Phase 13, F3): its page, and the home card Back returns to. */
    private var nflOpen by mutableStateOf(NflOpen())
    private var diagnostics by mutableStateOf(HomeState())
    private var now by mutableStateOf(ZonedDateTime.now())
    private var toast by mutableStateOf<ToastMessage?>(null)
    private var openedBy: OpenedBy? = null
    private var linking: Job? = null
    private var lastKey: String? = null

    /** When the remote was last pressed, for Now showing (wall clock). */
    private var lastInputMillis = System.currentTimeMillis()

    /** Diagnostics › Search check (Phase 15): its lines so far, and its run. */
    private var searchCheck by mutableStateOf(SearchCheckResult())
    private var searchCheckRun: Job? = null

    /** The Playback check's run and the surface it plays into; both end when the screen does. */
    private var playbackCheck: Job? = null
    private var playbackSurface: SurfaceView? = null

    /** Settings › Setup's Allow (setup without a computer): Android's own question, then Setup reads again. */
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        lifecycleScope.launch { container.setup.refresh() }
        container.customBanners.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Decided before anything is composed: after a crash, the art-forward home and
        // its view model never start, so a startup crash can't loop (AGENTS.md).
        safeMode = runCatching { container.crashGuard.startupMode() == StartupMode.SAFE }.getOrDefault(true)
        openedBy = intent.openedBy()
        if (intent.action == Intent.ACTION_ALL_APPS) screen = Screen.ALL_APPS
        if (intent.action == OpenSetupActivity.ACTION_OPEN_SETUP && !safeMode) openSetupScreen()
        savedInstanceState?.let(::restorePlace)
        watchScreenOff()
        setContent {
            Box(Modifier.fillMaxSize().onPreviewKeyEvent { recordKey(it.nativeKeyEvent); false }) {
                if (safeMode) {
                    // The home screen has nowhere to go back to.
                    BackHandler {}
                    val updates by container.updates.status.collectAsState()
                    DiagnosticsScreen(diagnostics, now, diagnosticsActions, focusResetKey = homeFocus.serial, updates = updates)
                } else {
                    Screens()
                }
                Toast(toast, onDone = { toast = null })
            }
        }
        lifecycleScope.launch { container.updates.started()?.let(::say) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    now = ZonedDateTime.now()
                    if (!safeMode) viewModel.tick()
                    delay(MINUTE_MILLIS - System.currentTimeMillis() % MINUTE_MILLIS)
                }
            }
        }
        // Phase 8: while the home is in front (and again on wake), a paired TV follows its
        // guide station's changes, and Phase 13's favorites with them. Unpaired, this waits
        // and does nothing; it never throws.
        if (!safeMode) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch { container.guideStation.keepUp() }
                    launch { container.channelFavorites.keepUp() }
                    // Phase 13: the NFL, while the home (or the NFL screen in it) is in front; not for a kid (F9).
                    launch { container.nfl.keepUp(viewModel.state.map { !it.profile.kid }) }
                }
            }
        }
    }

    @Composable
    private fun Screens() {
        val state by viewModel.state.collectAsState()
        val rows by viewModel.rows.collectAsState()
        val guide by viewModel.guide.collectAsState()
        val spot by viewModel.spot.collectAsState()
        val tv by viewModel.tv.collectAsState()
        val favorites by viewModel.favorites.collectAsState()
        val nfl by viewModel.nfl.collectAsState()
        val wallpaper by viewModel.wallpaper.collectAsState()
        val plexAccess by container.plexAccess.collectAsState(initial = null)
        val setup by container.setup.status.collectAsState()
        val updates by container.updates.status.collectAsState()
        val artUrls = remember(plexAccess) { PlexArtUrls(plexAccess) }
        // Where Watch goes (F6, F7): the Live row's networks, and the apps on this TV by their leanback launch intents.
        val watch = remember(rows.channels, state.allApps) { WatchOptions(rows.channels, state.allApps.mapTo(HashSet()) { it.packageName }) }
        ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
            CompositionLocalProvider(
                LocalAppArtSource provides container.appArt,
                LocalCustomBanners provides container.customBanners,
                LocalArtUrls provides artUrls,
                LocalWallpaperSources provides container.wallpaperSources,
                LocalChannelsAndApps provides container.channelsAndApps,
                LocalNflLogos provides container.nflLogos,
                LocalSetup provides setup,
                LocalUpdates provides updates,
            ) {
                when (screen) {
                    Screen.HOME -> {
                        BackHandler {}
                        HomeScreen(
                            state = state,
                            actions = homeActions,
                            focus = homeFocus,
                            rows = rows,
                            wallpaper = wallpaper,
                            lastInputMillis = { lastInputMillis },
                            guide = guide,
                            spot = spot,
                            favorites = favorites,
                            nfl = nfl,
                            watch = watch,
                            tv = tv,
                        )
                    }
                    // Phase 13 (F3, F4): inside the home like the full guide; Back to the card that opened it.
                    Screen.NFL -> NflScreen(
                        view = nfl,
                        open = nflOpen,
                        nowMillis = state.nowMillis,
                        controller = container.nfl,
                        actions = homeActions,
                        watch = watch,
                        onBack = { goHome(HomeFocus.NFL, nflKey = nflOpen.opener) },
                        idleMinutes = wallpaper.nowShowingMinutes,
                        lastInputMillis = { lastInputMillis },
                    )
                    // Phase 15: Back returns to the magnifier.
                    Screen.SEARCH -> {
                        val kid = state.profile.kid
                        val youTubeTv = !kid || state.profile.apps.orEmpty().any { it.startsWith(WatchApp.YOUTUBE_TV.packageName + "/") }
                        SearchScreen(
                            profile = state.profile,
                            recent = state.recentSearches,
                            search = container.librarySearch,
                            streaming = if (state.searchStreaming) container.streamingSearch else StreamingSearch.None,
                            // The apps whoever's watching may open (a kid's My apps, K9), in the Apps row's order.
                            apps = remember(state.launchpad, state.allApps, state.profile) {
                                if (kid) myApps(state.launchpad, state.allApps, state.profile.apps.orEmpty())
                                else appsRowOrder(state.launchpad, state.allApps, hidden = emptySet())
                            },
                            // A kid sees the channels inside their ceiling, and YouTube TV only when it's one of their apps (K8, K9).
                            guide = if (kid) KidGuide.of(guide, state.profile.ceiling.orEmpty()) else guide,
                            networks = if (youTubeTv) rows.channels else emptyList(),
                            nowMillis = state.nowMillis,
                            lateNight = state.mode == ContextMode.LATE_NIGHT,
                            actions = homeActions,
                            onBack = { goHome(HomeFocus.SEARCH) },
                        )
                    }
                    Screen.GUIDE -> GuideScreen(
                        state = guide,
                        // The home's minute tick: the now line moves with it, no timer of its own.
                        nowMillis = state.nowMillis,
                        actions = homeActions,
                        // Back to the card that opened it: a Live card, or the Guide card.
                        onBack = { goHome(if (guideChannel != null) HomeFocus.LIVE else HomeFocus.GUIDE, guideChannel) },
                        lateNight = state.mode == ContextMode.LATE_NIGHT,
                        plexLinked = state.plex.link.linked,
                        // G10: Now showing's idle time; the guide dims and drifts rather than being covered.
                        idleMinutes = wallpaper.nowShowingMinutes,
                        lastInputMillis = { lastInputMillis },
                        focusChannelId = guideAt?.channelId ?: guideChannel,
                        openAtMillis = guideAt?.startMillis,
                        // K8: a kid sees the channels inside their ceiling; you see them all.
                        ceiling = state.profile.ceiling,
                        playIn = state.playIn,
                        favorites = favorites,
                    )
                    Screen.DETAILS -> details?.let { program ->
                        ProgramDetailsScreen(
                            state = guide,
                            channelId = program.channelId,
                            startMillis = program.startMillis,
                            nowMillis = state.nowMillis,
                            actions = homeActions,
                            // Back to the guide with this program still focused.
                            onBack = {
                                guideAt = program
                                screen = Screen.GUIDE
                            },
                            lateNight = state.mode == ContextMode.LATE_NIGHT,
                            idleMinutes = wallpaper.nowShowingMinutes,
                            lastInputMillis = { lastInputMillis },
                            playIn = state.playIn,
                        )
                    }
                    Screen.PHONE_EDITOR -> PhoneEditorScreen(
                        editor = container.guideEditor,
                        onBack = {
                            settingsSection = SettingsSection.PLEX_GUIDE
                            screen = Screen.SETTINGS
                        },
                    )
                    // Settings › AI blurbs › Add a key on your phone (M10): back to AI blurbs, saved or not.
                    Screen.AI_KEY -> AiKeyScreen(
                        keys = container.aiBlurbs,
                        onDone = { saved ->
                            settingsSection = SettingsSection.AI
                            screen = Screen.SETTINGS
                            if (saved) say("Key saved")
                        },
                    )
                    Screen.PLAYBACK_CHECK -> {
                        val check by container.playbackCheck.state.collectAsState()
                        PlaybackCheckScreen(
                            state = check,
                            onStart = ::startPlaybackCheck,
                            surface = { playbackSurface = it },
                            onBack = {
                                stopPlaybackCheck()
                                settingsSection = SettingsSection.DIAGNOSTICS
                                screen = Screen.DIAGNOSTICS
                            },
                        )
                    }
                    Screen.SEARCH_CHECK -> SearchCheckScreen(
                        state = searchCheck,
                        onRun = ::runSearchCheck,
                        onGoogle = homeActions::searchWithGoogle,
                        // Only the TV shows what a link opens: Android may ask which app.
                        onOpenLink = { url, service -> start(Intent(Intent.ACTION_VIEW, Uri.parse(url)), service) },
                        onBack = {
                            searchCheckRun?.cancel()
                            settingsSection = SettingsSection.DIAGNOSTICS
                            screen = Screen.DIAGNOSTICS
                        },
                    )
                    Screen.WHOS_WATCHING -> WhosWatchingScreen(
                        profiles = state.profiles,
                        currentId = state.profile.id,
                        // Your avatar is Settings › Look's accent, even while a kid's colour is on.
                        ownerColor = Palette.Accents.getOrElse(state.accentIndex) { Palette.DefaultAccent },
                        onPick = ::pickProfile,
                        onBack = { goHome(whosWatchingBack) },
                    )
                    Screen.ALL_APPS -> AllAppsScreen(
                        apps = state.allApps,
                        mostUsed = state.mostUsed,
                        hiddenApps = state.hiddenApps,
                        actions = homeActions,
                        onBack = { goHome(HomeFocus.ALL_APPS) },
                        // K9: a kid's All apps is their My apps.
                        allowlist = state.profile.apps.takeIf { state.profile.kid },
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        state = state,
                        actions = settingsActions,
                        onBack = { goHome(HomeFocus.SETTINGS) },
                        initialSection = settingsSection,
                        version = version,
                        wallpaper = container.wallpaperController,
                        channelsAndApps = container.channelsAndApps,
                        guideEditor = container.guideEditor,
                        aiBlurbs = container.aiBlurbs,
                        profiles = container.profiles,
                        station = container.guideStation,
                        nfl = container.nfl,
                        setup = setup,
                        updates = container.updates,
                    )
                    Screen.DIAGNOSTICS -> {
                        BackHandler {
                            settingsSection = SettingsSection.DIAGNOSTICS
                            screen = Screen.SETTINGS
                        }
                        DiagnosticsScreen(diagnostics, now, diagnosticsActions)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startLinking()
    }

    /**
     * Android can make the Home activity again while the app runs, and does so behind the
     * guide's player: the player rides out a configuration change (an HDMI mode switch, a
     * remote reconnecting), and the home behind it is made again as it comes back. So the
     * full guide and a program's details are kept, with the card that opened the guide, and
     * Back from the player lands on them (G5). The guide keeps its own focus. Other screens
     * open at the home, as before.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (screen != Screen.GUIDE && screen != Screen.DETAILS) return
        outState.putString(SAVED_SCREEN, screen.name)
        outState.putString(SAVED_GUIDE_CHANNEL, guideChannel)
        guideAt?.let { outState.putProgram(SAVED_GUIDE_AT, it) }
        details?.let { outState.putProgram(SAVED_DETAILS, it) }
    }

    /** The full guide or a program's details, as [onSaveInstanceState] kept them; anything else leaves the screen as it is. */
    private fun restorePlace(saved: Bundle) {
        val kept = when (saved.getString(SAVED_SCREEN)) {
            Screen.GUIDE.name -> Screen.GUIDE
            Screen.DETAILS.name -> Screen.DETAILS
            else -> return
        }
        guideChannel = saved.getString(SAVED_GUIDE_CHANNEL)
        guideAt = saved.getProgram(SAVED_GUIDE_AT)
        details = saved.getProgram(SAVED_DETAILS)
        screen = if (kept == Screen.DETAILS && details == null) Screen.GUIDE else kept
    }

    private fun Bundle.putProgram(key: String, program: ProgramRef) {
        putString("$key.channel", program.channelId)
        putLong("$key.start", program.startMillis)
    }

    private fun Bundle.getProgram(key: String): ProgramRef? {
        val channel = getString("$key.channel") ?: return null
        return ProgramRef(channel, getLong("$key.start"))
    }

    /**
     * Screen-off is a protected broadcast only the system sends, so the receiver needs no
     * export flag (API 30 has none anyway). A home screen must start whatever happens: if
     * it can't be registered, "Who's watching?" just doesn't open on waking.
     */
    private fun watchScreenOff() {
        watchingScreenOff = try {
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
            true
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "screen off: ${e.javaClass.simpleName}")
            false
        }
    }

    override fun onDestroy() {
        if (watchingScreenOff) unregisterReceiver(screenOff)
        super.onDestroy()
    }

    override fun onPause() {
        container.foreground.homeInFront = false
        super.onPause()
    }

    override fun onStop() {
        // No sign-in code is polled while the home screen isn't showing.
        linking?.cancel()
        linking = null
        stopPlaybackCheck()
        super.onStop()
    }

    /** Shows a sign-in code, or links the server, until Plex is linked (U14). Restarts with a new code. */
    private fun startLinking() {
        if (safeMode) return
        linking?.cancel()
        linking = lifecycleScope.launch {
            try {
                container.plexSession.link()
                // Linked: keep the cache fresh in the background, and sync now if it's stale.
                // Back home, a minute is stale: Up Next drops what you just finished (the Up
                // Next credits fix). The sync runs in WorkManager; the cards read the cache.
                container.plexSchedule.keepFresh()
                if (container.plexSync.needsSyncBackHome()) container.plexSchedule.refreshNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "plex: ${e.summary()}")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openedBy = intent.openedBy()
        // Home from a resumed player lands at the top, not on the card that resumed it.
        backFromResume = null
        if (intent.action == Intent.ACTION_ALL_APPS) {
            screen = Screen.ALL_APPS
        } else if (intent.action == OpenSetupActivity.ACTION_OPEN_SETUP && !safeMode) {
            openSetupScreen()
        } else {
            // Home pressed while already home: back to the top, Play focused [U10].
            goHome(HomeFocus.PLAY)
        }
    }

    /** Settings › Setup: from the top bar's chip, or HomeWatch's Configure in TV Settings. */
    private fun openSetupScreen() {
        settingsSection = SettingsSection.SETUP
        screen = Screen.SETTINGS
    }

    /**
     * @param channelId the Live card [HomeFocus.LIVE] lands on.
     * @param nflKey the NFL card [HomeFocus.NFL] lands on.
     */
    private fun goHome(focus: HomeFocus, channelId: String? = null, nflKey: String? = null) {
        screen = Screen.HOME
        homeFocus = FocusRequest(focus, homeFocus.serial + 1, channelId, nflKey)
    }

    // Phase 0 step 8: shows which remote buttons reach apps (is the menu button usable
    // for quick actions? U11). Observes only; never consumes the key.
    private fun recordKey(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN) {
            lastInputMillis = System.currentTimeMillis()
            container.foreground.lastTouchMillis = lastInputMillis
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
        val name = KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")
        lastKey = name
        diagnostics = diagnostics.copy(lastKey = name)
        Log.i(LOG_TAG, "key=$name")
    }

    override fun onResume() {
        super.onResume()
        // Install automatically counts 30 untouched minutes from here, or from the last key.
        container.foreground.homeInFront = true
        container.foreground.lastTouchMillis = System.currentTimeMillis()
        // The app list and the stock launcher can change while we're away (Play
        // update, OTA).
        if (!safeMode) {
            viewModel.refresh()
            container.customBanners.refresh()
            // Setup's steps change in TV Settings while we're away.
            lifecycleScope.launch { container.setup.refresh() }
            keepWeatherFresh()
            // Back from a player the Back-to card resumed: that card again (P4).
            backFromResume?.let { goHome(it.target, it.channelId) }
            backFromResume = null
            askWhosWatching()
        }
        refreshDiagnostics()
    }

    /**
     * K1: back after the screen was off, "Who's watching?" opens with whoever was on focused,
     * if there's a kid to pick. On resume rather than start, so the Home press that brought
     * the home back (onNewIntent, between the two) doesn't close it again.
     */
    private fun askWhosWatching() {
        if (!screenWentOff) return
        screenWentOff = false
        if (opensAfterScreenOff(viewModel.state.value.profiles)) {
            whosWatchingBack = HomeFocus.PLAY
            screen = Screen.WHOS_WATCHING
        }
    }

    /** "Who's watching?" or a kid's avatar on a weekend morning (K1, K11): the home, as [profile], from the top. */
    private fun pickProfile(profile: ActiveProfile) {
        if (profile.id != viewModel.state.value.profile.id) {
            viewModel.switchProfile(profile.id)
            say("Now watching as ${profile.name.ifBlank { "you" }}")
        }
        goHome(HomeFocus.PLAY)
    }

    /** Background work only: the screen reads the cache. */
    private fun keepWeatherFresh() = weatherJob {
        container.weatherSchedule.keepFresh()
        if (container.weather.needsRefresh(System.currentTimeMillis())) container.weatherSchedule.refreshNow()
    }

    /** Weather is a nice-to-have: nothing it does is allowed to break the home screen. */
    private fun weatherJob(block: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "weather: ${e.summary()}")
            }
        }
    }

    /** Plays the check's titles in turn on [playbackSurface]; a new run replaces the last. */
    private fun startPlaybackCheck() {
        playbackCheck?.cancel()
        playbackCheck = lifecycleScope.launch {
            try {
                container.playbackCheck.run(ExoProbePlayer(this@HomeActivity, { playbackSurface }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(PlaybackCheck.LOG_TAG, "playback check: ${e.summary()}")
            }
        }
    }

    private fun stopPlaybackCheck() {
        playbackCheck?.cancel()
        playbackCheck = null
    }

    /** @param logTvRows also logs every row other apps published (Device check), for reading with logcat. */
    private fun refreshDiagnostics(logTvRows: Boolean = false) {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { container.homeStateLoader.load() }
            diagnostics = loaded.copy(openedBy = openedBy, lastKey = lastKey)
            loaded.reportLines().forEach { Log.i(LOG_TAG, it) }
            if (logTvRows) loaded.tvProvider?.dumpLines()?.forEach { Log.i(LOG_TAG, it) }
        }
    }

    private val version: String by lazy {
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
    }

    private val homeActions = object : HomeActions {
        override fun play(id: String, title: String) {
            val state = viewModel.state.value
            val server = state.plex.link.server
            if (state.content?.fromPlex != true || server == null) return say(SAMPLE_CANT_PLAY)
            handOff(server.clientIdentifier, id, title)
        }

        override fun openIn(id: String, title: String) {
            val server = viewModel.state.value.plex.link.server ?: return say("Plex isn't linked")
            handOff(server.clientIdentifier, id, title)
        }

        override fun trailer(pick: Pick) {
            val state = viewModel.state.value
            if (state.content?.fromPlex != true) return say(SAMPLE_CANT_PLAY)
            // The trailer extra cached at sync, through Plezy like Play; else a YouTube search.
            val server = state.plex.link.server
            startFirst(Handoffs.trailerFor(pick.title, pick.year, server?.clientIdentifier, pick.trailerKey), "the trailer for ${pick.title}")
        }
        override fun openIntentUri(uri: String, what: String) {
            // Other apps publish these (L8, L9). Keep what they open, never a URI permission grant.
            val intent = runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull()?.apply {
                selector = null
                flags = flags and URI_GRANTS.inv()
            }
            if (intent == null) say("Couldn't open $what") else start(intent, what)
        }

        override fun notTonight() = viewModel.notTonight()
        override fun undo() = viewModel.undo()
        override fun nextLensPick() = viewModel.nextLensPick()
        override fun previousLensPick() = viewModel.previousLensPick()
        override fun lensShown(pick: LensPick) = viewModel.lensShown(pick)
        override fun nextLens() = viewModel.nextLens()

        override fun removeFromUpNext(item: UpNextItem) {
            viewModel.removeFromUpNext(item)
            say("${item.title} is off Up Next until you play it again")
        }

        override fun hideFromQuickWatch(item: QuickItem) {
            viewModel.hideFromQuickWatch(item)
            say("${item.title} is hidden from Quick Watch for 30 days")
        }

        override fun dontShowLensPick() = viewModel.dontShowLensPick()

        override fun moreLikeThis() {
            if (!viewModel.moreLikeThis()) say("Nothing in your library is much like it yet")
        }

        override fun launch(app: LaunchableApp) {
            if (start(app.launchIntent(), app.label)) viewModel.launched(app)
        }

        override fun setHidden(app: LaunchableApp, hidden: Boolean) {
            viewModel.setHidden(app, hidden)
            say(if (hidden) "${app.label} is hidden from the launchpad. It stays in All apps." else "${app.label} is back on the launchpad")
        }

        override fun appInfo(app: LaunchableApp) {
            start(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null)),
                "app info for ${app.label}",
            )
        }

        override fun openAllApps() {
            screen = Screen.ALL_APPS
        }

        override fun openSettings() {
            settingsSection = SettingsSection.LOOK
            screen = Screen.SETTINGS
        }

        override fun openSearch() {
            screen = Screen.SEARCH
        }

        /**
         * Q4: Google's TV search (`com.google.android.katniss`, which both TVs resolve
         * `GLOBAL_SEARCH` to), named so Android never shows a chooser, with the words as
         * `SearchManager.QUERY`. Whether it fills them in is Diagnostics' to find out.
         */
        override fun searchWithGoogle(text: String) {
            start(Intent(GLOBAL_SEARCH).setPackage(GOOGLE_SEARCH).putExtra(SearchManager.QUERY, text), "Google search")
        }

        override fun rememberSearch(text: String) = viewModel.rememberSearch(text)

        override fun watchOn(app: StreamingApp, title: String) {
            // Every link names the app's package: on a Google TV a bare one opens Android's chooser.
            val link = app.url?.let { IntentSpec.ViewUri(it, app.packageName) }
            val spec = container.intentProbe.firstResolvable(listOfNotNull(link, IntentSpec.LaunchApp(app.packageName)))
            val intent = spec?.let(container.intentResolver::toIntent) ?: return say("Couldn't open ${app.label}")
            // The app's home: Android's own toast, since it shows over the other app.
            if (spec !is IntentSpec.ViewUri) {
                android.widget.Toast.makeText(applicationContext, "Search for “$title” in ${app.label}", android.widget.Toast.LENGTH_LONG).show()
            }
            start(intent, "$title in ${app.label}")
        }

        override fun openDiagnostics() {
            settingsSection = SettingsSection.DIAGNOSTICS
            screen = Screen.SETTINGS
        }

        override fun openSetup() = openSetupScreen()

        override fun openUpdate() = container.updates.chipPressed(
            openAbout = {
                settingsSection = SettingsSection.ABOUT
                screen = Screen.SETTINGS
            },
        )

        override fun openPlexSettings() {
            settingsSection = SettingsSection.PLEX
            screen = Screen.SETTINGS
        }

        override fun openProfiles() {
            whosWatchingBack = HomeFocus.PROFILE
            screen = Screen.WHOS_WATCHING
        }

        override fun switchProfile(profileId: String) {
            viewModel.state.value.profiles.firstOrNull { it.id == profileId }?.let(::pickProfile)
        }

        override fun newPlexCode() = startLinking()

        override fun openGuide(channelId: String?) {
            guideChannel = channelId
            guideAt = null
            screen = Screen.GUIDE
        }

        override fun openDetails(channelId: String, startMillis: Long) {
            details = ProgramRef(channelId, startMillis)
            screen = Screen.DETAILS
        }

        // Our own player, in the home's task (no NEW_TASK), so Home closes it (U10).
        override fun tuneIn(channelId: String) = startActivity(GuidePlayerActivity.intent(this@HomeActivity, channelId))

        override fun startOver(channelId: String) =
            startActivity(GuidePlayerActivity.intent(this@HomeActivity, channelId, startOver = true))

        // The player takes the spot over: Home there saves it again, Back leaves none (P3).
        override fun resume(spot: SavedSpot) {
            viewModel.forgetSpot(only = spot)
            backFromResume = backToCardFocus(viewModel.state.value.profile.kid, spot.channelId)
            startActivity(GuidePlayerActivity.resume(this@HomeActivity, spot))
        }

        override fun backToLive(channelId: String) {
            viewModel.forgetSpot()
            tuneIn(channelId)
        }

        override fun forgetSpot() = viewModel.forgetSpot()

        // Back to YouTube TV: a YouTube TV channel opened from the Live row is remembered; others open as before.
        override fun watchNetwork(button: ChannelButton) {
            if (YouTubeTv.isChannel(button)) watchTvChannel(TunedChannel(button.name, button.intentUri, System.currentTimeMillis()))
            else openIntentUri(button.intentUri, button.name)
        }

        override fun watchTvChannel(channel: TunedChannel) {
            val intent = watchIntent(channel.link, YouTubeTv.PACKAGE) ?: return say("Couldn't open ${channel.name}")
            if (start(intent, channel.name)) viewModel.tunedTv(channel.copy(atMillis = System.currentTimeMillis()))
        }

        override fun forgetVisit(visit: AppVisit) = viewModel.forgetVisit(visit)

        override fun setFavorite(channel: GuideChannel, favorite: Boolean) {
            lifecycleScope.launch {
                when (val change = container.channelFavorites.set(channel.id, favorite)) {
                    FavoriteChange.Saved -> say(FavoriteText.changed(channel.name, favorite))
                    is FavoriteChange.Refused -> refuse(change.sentence)
                }
            }
        }

        override fun saveFavorites(order: List<String>, done: (kept: Boolean) -> Unit) {
            lifecycleScope.launch {
                val change = container.channelFavorites.reorder(order)
                if (change is FavoriteChange.Refused) refuse(change.sentence)
                done(change == FavoriteChange.Saved)
            }
        }

        override fun openNfl(open: NflOpen) {
            nflOpen = open
            screen = Screen.NFL
        }

        override fun watch(way: WatchWay, what: String) {
            if (!way.installed) return say(way.missing)
            val intent = way.spec()?.let(container.intentResolver::toIntent) ?: way.link?.let { watchIntent(it, way.app.packageName) }
            if (intent == null) return say("Couldn't open $what in ${way.app.label}")
            // Back to YouTube TV: a YouTube TV channel opened here is remembered, as from the Live row.
            if (start(intent, "$what in ${way.app.label}") && way.app == WatchApp.YOUTUBE_TV && way.link != null && way.channel != null) {
                viewModel.tunedTv(TunedChannel(way.channel, way.link, System.currentTimeMillis()))
            }
        }

        override fun showScore(gameId: String) = container.nfl.showScore(gameId)

        override fun notToday() {
            container.nfl.notToday()
            say("Quick Watch is back until tomorrow. The NFL row stays.")
        }

        override fun say(message: String) = this@HomeActivity.say(message)
    }

    /**
     * The Live row's `intent:` URI for a network (F6), as [HomeActions.openIntentUri] opens it,
     * named for [packageName] when it names no app: on a Google TV a bare link opens Android's
     * chooser (checked 11/02/2026). A `tv.youtube.com` link parses to a view of it.
     */
    private fun watchIntent(uri: String, packageName: String): Intent? =
        runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull()?.apply {
            selector = null
            flags = flags and URI_GRANTS.inv()
            if (`package` == null && component == null) setPackage(packageName)
        }

    private val settingsActions = object : SettingsActions {
        override fun setAccent(index: Int) = viewModel.setAccent(index)
        override fun setLayoutScale(scale: Float) = viewModel.setLayoutScale(scale)
        override fun setShowSampleContent(show: Boolean) = viewModel.setShowSampleContent(show)

        override fun openDeviceCheck() {
            refreshDiagnostics(logTvRows = true)
            screen = Screen.DIAGNOSTICS
        }

        override suspend fun searchPlaces(query: String) = container.weather.search(query)

        override fun choosePlace(place: Place) = weatherJob {
            container.weather.setPlace(place)
            // A first reading now; the worker keeps it fresh after that.
            container.weather.refresh()
        }

        override fun setTemperatureUnit(unit: TemperatureUnit) = weatherJob { container.weather.setUnit(unit) }

        override fun setLensEnabled(lens: Lens, enabled: Boolean) = viewModel.setLensEnabled(lens, enabled)
        override fun setLensRotate(on: Boolean) = viewModel.setLensRotate(on)
        override fun setLensSpeed(notch: Int) = viewModel.setLensSpeed(notch)
        override fun setLensShuffle(on: Boolean) = viewModel.setLensShuffle(on)
        override fun setLensMoveOn(moveOn: LensMoveOn) = viewModel.setLensMoveOn(moveOn)
        override fun setQuickWatchCutoff(cutoff: LocalTime) = viewModel.setQuickWatchCutoff(cutoff)
        override fun setWeekendCutoff(cutoff: LocalTime) = viewModel.setWeekendCutoff(cutoff)
        override fun setModeHours(mode: ContextMode, hours: Hours) = viewModel.setModeHours(mode, hours)
        override fun setAiBlurbs(on: Boolean) = viewModel.setAiBlurbs(on)
        override fun setPlayIn(app: PlayIn) = viewModel.setPlayIn(app)
        override fun setCountGuideViewing(on: Boolean) = viewModel.setCountGuideViewing(on)
        override fun setSearchStreaming(on: Boolean) = viewModel.setSearchStreaming(on)

        override fun openAiKeyOnPhone() {
            screen = Screen.AI_KEY
        }

        override fun signOutOfPlex() {
            lifecycleScope.launch {
                container.plexSession.signOut()
                container.plexSync.clear()
                startLinking()
            }
        }

        override fun choosePlexServer(clientIdentifier: String) = container.plexSession.choose(clientIdentifier)

        override fun syncPlexNow() {
            container.plexSchedule.refreshNow(replace = true)
            say("Syncing with Plex…")
        }

        override fun openPhoneEditor() {
            screen = Screen.PHONE_EDITOR
        }

        override fun allowPermission(permission: SetupPermission) {
            val wanted = permission.permissions(Build.VERSION.SDK_INT)
            lifecycleScope.launch {
                val rationale = wanted.any { shouldShowRequestPermissionRationale(it) }
                when (container.setup.allowRoute(permission, rationale)) {
                    AllowRoute.ASK -> askPermissions.launch(wanted.toTypedArray())
                    AllowRoute.APP_INFO -> {
                        // Android won't ask again: its App info › Permissions can still allow it.
                        say("Android won't ask again: allow it in App info › Permissions")
                        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)), "App info")
                    }
                }
            }
        }

        override fun openHomeWatchSettings() {
            // The SHIELD has no Accessibility screen to link to: TV Settings, and Setup says the way.
            val direct = container.setup.status.value.accessibilityLink
            if (!direct || !start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "Accessibility")) {
                start(Intent(Settings.ACTION_SETTINGS), "Settings")
            }
        }

        override fun openUsageAccess() {
            if (!start(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), "Usage access")) start(Intent(Settings.ACTION_SETTINGS), "Settings")
        }

        override fun setSetupChipShown(shown: Boolean) {
            lifecycleScope.launch { container.setup.setChipHidden(!shown) }
        }

        override fun openInstallUnknownApps() = this@HomeActivity.openInstallUnknownApps()
    }

    private val diagnosticsActions = object : DiagnosticsActions {
        override fun openSettings() {
            start(Intent(Settings.ACTION_SETTINGS), "Settings")
        }

        override fun launchApp(app: LaunchableApp) {
            start(app.launchIntent(), app.label)
        }

        override fun playTestTrailer() =
            startFirst(Handoffs.trailer(HomeStateLoader.TEST_TRAILER_ID), "the test trailer")

        override fun openPlezy() = startFirst(listOf(IntentSpec.LaunchApp(Handoffs.PLEZY)), "Plezy")

        override fun openPlex() = startFirst(listOf(IntentSpec.LaunchApp(Handoffs.PLEX)), "Plex")

        override fun openPlaybackCheck() {
            if (safeMode) return say("The Playback check needs the normal home: leave safe mode first")
            screen = Screen.PLAYBACK_CHECK
        }

        override fun openSearchCheck() {
            if (safeMode) return say("The Search check needs the normal home: leave safe mode first")
            screen = Screen.SEARCH_CHECK
        }

        override fun leaveSafeMode() {
            container.crashGuard.leaveSafeMode()
            safeMode = false
            startLinking()
            goHome(HomeFocus.PLAY)
            refreshDiagnostics()
        }

        override fun forceCrash(): Unit = throw IllegalStateException("Test crash from the device panel")

        override fun checkForUpdates() = container.updates.checkNow()

        override fun installUpdate() = container.updates.install()

        override fun openInstallUnknownApps() = this@HomeActivity.openInstallUnknownApps()
    }

    /**
     * TV Settings' Install unknown apps, at Action Center's own switch where the TV links there;
     * else the list of apps; else TV Settings. Setup reads it again on resume.
     */
    private fun openInstallUnknownApps() {
        val ours = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", packageName, null))
        val opened = runCatching { startActivity(ours.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess ||
            start(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES), "Install unknown apps")
        if (!opened) start(Intent(Settings.ACTION_SETTINGS), "Settings")
    }

    private fun LaunchableApp.launchIntent() = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        .setClassName(packageName, activityName)

    /**
     * A library title handed off (D11, Phase 11): in Plezy or the Plex app by Settings › Plex ›
     * Play in, the other as the fallback. Plezy's link names the profile's Plex user (K3), so
     * Plezy switches to them first. Nothing is written to Plex.
     */
    private fun handOff(serverId: String, ratingKey: String, title: String) {
        val app = viewModel.state.value.playIn
        lifecycleScope.launch {
            val profile = try {
                container.plexViewers.current()?.profile
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val candidates = try {
                Handoffs.play(app, serverId, ratingKey, profile)
            } catch (_: IllegalArgumentException) {
                return@launch say("Couldn't open $title")
            }
            startFirst(candidates, title)
        }
    }

    private fun startFirst(candidates: List<IntentSpec>, what: String) {
        val intent = container.intentProbe.firstResolvable(candidates)?.let(container.intentResolver::toIntent)
        if (intent == null) say("Nothing on this Shield can open $what") else start(intent, what)
    }

    /** A launcher must never crash because another app is missing or refuses to start. */
    private fun start(intent: Intent, what: String): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        say("Couldn't open $what")
        false
    } catch (_: SecurityException) {
        say("Not allowed to open $what")
        false
    }

    /** Something refused, in the caution colour (N5): a favorite while the station can't be reached. */
    private fun refuse(sentence: String) {
        toast = ToastMessage(sentence, caution = true)
    }

    private fun say(message: String) {
        toast = ToastMessage(message)
    }

    private fun Intent.openedBy(): OpenedBy {
        val parts = listOfNotNull(action) + categories.orEmpty().sorted()
        val description = parts.joinToString(" + ") {
            it.removePrefix("android.intent.action.").removePrefix("android.intent.category.")
        }
        return OpenedBy(description.ifEmpty { "unknown" }, System.currentTimeMillis())
    }

    /** Runs the Search check again; each line also goes to logcat under [SEARCH_LOG_TAG] (never a token). */
    private fun runSearchCheck() {
        searchCheckRun?.cancel()
        searchCheck = SearchCheckResult()
        searchCheckRun = lifecycleScope.launch {
            var logged = 0
            container.searchCheck.run { result ->
                searchCheck = result
                result.lines.drop(logged).forEach { Log.i(SEARCH_LOG_TAG, "${it.label}: ${it.result}") }
                logged = result.lines.size
            }
        }
    }

    /**
     * The remote's Search key (Phase 15, Q5): Android asks the activity before it starts
     * Google's search, so Search opens instead, from the home and its screens. Not in safe mode.
     */
    override fun onSearchRequested(searchEvent: SearchEvent?): Boolean {
        if (safeMode) return super.onSearchRequested(searchEvent)
        homeActions.openSearch()
        return true
    }

    override fun onSearchRequested(): Boolean = onSearchRequested(null)

    private companion object {
        /** Google's TV search: both TVs resolve [GLOBAL_SEARCH] to it (read 11/02/2026). */
        const val GOOGLE_SEARCH = "com.google.android.katniss"
        const val GLOBAL_SEARCH = "android.search.action.GLOBAL_SEARCH"
        const val LOG_TAG = "ActionCenterProbe"
        const val SEARCH_LOG_TAG = "ActionCenterSearch"
        const val MINUTE_MILLIS = 60_000L

        /** Where the home was, kept across Android making it again ([onSaveInstanceState]). */
        const val SAVED_SCREEN = "screen"
        const val SAVED_GUIDE_CHANNEL = "guide_channel"
        const val SAVED_GUIDE_AT = "guide_at"
        const val SAVED_DETAILS = "details"
        const val SAMPLE_CANT_PLAY = "Sample content can't play: it's made up for design review"
        const val URI_GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
