package com.phonoodlesramen.actioncenter

import android.app.Application
import android.app.SearchManager
import android.content.Context
import android.os.storage.StorageManager
import android.os.Build
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.work.WorkManager
import com.phonoodlesramen.actioncenter.ai.AiKeyVault
import com.phonoodlesramen.actioncenter.ai.BlurbRuns
import com.phonoodlesramen.actioncenter.ai.BlurbWriter
import com.phonoodlesramen.actioncenter.ai.ClaudeBlurbs
import com.phonoodlesramen.actioncenter.ai.StoredAiBlurbs
import com.phonoodlesramen.actioncenter.ai.WorkBlurbSchedule
import com.phonoodlesramen.actioncenter.apps.AndroidAppArtSource
import com.phonoodlesramen.actioncenter.apps.AndroidLauncherActivitySource
import com.phonoodlesramen.actioncenter.apps.AppUsage
import com.phonoodlesramen.actioncenter.apps.CustomBanners
import com.phonoodlesramen.actioncenter.apps.LaunchableAppsRepository
import com.phonoodlesramen.actioncenter.apps.StoredCustomBanners
import com.phonoodlesramen.actioncenter.apps.usage.AndroidAppUsage
import com.phonoodlesramen.actioncenter.apps.usage.AndroidUsageEvents
import com.phonoodlesramen.actioncenter.dice.Dice
import com.phonoodlesramen.actioncenter.dice.DiceTable
import com.phonoodlesramen.actioncenter.lastapp.DataStoreTvChannels
import com.phonoodlesramen.actioncenter.lastapp.TvChannelStore
import com.phonoodlesramen.actioncenter.lastapp.UsageRecentApps
import com.phonoodlesramen.actioncenter.favorites.ChannelFavorites
import com.phonoodlesramen.actioncenter.favorites.DataStoreFavorites
import com.phonoodlesramen.actioncenter.guide.ChannelLooks
import com.phonoodlesramen.actioncenter.guide.GuideDatabase
import com.phonoodlesramen.actioncenter.guide.GuideEditor
import com.phonoodlesramen.actioncenter.guide.GuideEngine
import com.phonoodlesramen.actioncenter.guide.GuideSource
import com.phonoodlesramen.actioncenter.guide.GuideWorker
import com.phonoodlesramen.actioncenter.guide.RoomGuideEditor
import com.phonoodlesramen.actioncenter.guide.SharedPrefsLineupServer
import com.phonoodlesramen.actioncenter.handoff.AndroidIntentResolver
import com.phonoodlesramen.actioncenter.handoff.Handoffs
import com.phonoodlesramen.actioncenter.handoff.IntentProbe
import com.phonoodlesramen.actioncenter.handoff.IntentSpec
import com.phonoodlesramen.actioncenter.handoff.PlayIn
import com.phonoodlesramen.actioncenter.home.HomeStateLoader
import com.phonoodlesramen.actioncenter.home.HomeSystem
import com.phonoodlesramen.actioncenter.home.HomeViewModel
import com.phonoodlesramen.actioncenter.media.SharedFolders
import com.phonoodlesramen.actioncenter.nfl.DataStoreNfl
import com.phonoodlesramen.actioncenter.nfl.EspnClient
import com.phonoodlesramen.actioncenter.nfl.NflLogos
import com.phonoodlesramen.actioncenter.nfl.NflSource
import com.phonoodlesramen.actioncenter.player.DataStoreSpots
import com.phonoodlesramen.actioncenter.player.GuidePlexWrites
import com.phonoodlesramen.actioncenter.player.NextEpisodes
import com.phonoodlesramen.actioncenter.player.PlaybackCheck
import com.phonoodlesramen.actioncenter.player.PlexPlayables
import com.phonoodlesramen.actioncenter.player.PlexSlotCredits
import com.phonoodlesramen.actioncenter.player.SpotStore
import com.phonoodlesramen.actioncenter.plex.KeystoreSecretBox
import com.phonoodlesramen.actioncenter.plex.PlexCatalog
import com.phonoodlesramen.actioncenter.plex.PlexCatalogApi
import com.phonoodlesramen.actioncenter.plex.PlexAccounts
import com.phonoodlesramen.actioncenter.plex.PlexHeaders
import com.phonoodlesramen.actioncenter.plex.PlexHome
import com.phonoodlesramen.actioncenter.plex.PlexHomeCheck
import com.phonoodlesramen.actioncenter.plex.PlexLibrary
import com.phonoodlesramen.actioncenter.plex.PlexServerReach
import com.phonoodlesramen.actioncenter.plex.PlexSession
import com.phonoodlesramen.actioncenter.plex.PlexStore
import com.phonoodlesramen.actioncenter.plex.PlexSyncStatus
import com.phonoodlesramen.actioncenter.plex.PlexTv
import com.phonoodlesramen.actioncenter.plex.PlexWatchState
import com.phonoodlesramen.actioncenter.plex.SHIELD_PLEX_DEVICE
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import com.phonoodlesramen.actioncenter.plex.cache.PlexDatabase
import com.phonoodlesramen.actioncenter.plex.cache.PlexSync
import com.phonoodlesramen.actioncenter.plex.cache.PlexSyncWorker
import com.phonoodlesramen.actioncenter.plex.cache.snapshots
import com.phonoodlesramen.actioncenter.profiles.KidSync
import com.phonoodlesramen.actioncenter.profiles.KidTokens
import com.phonoodlesramen.actioncenter.profiles.PlexViewers
import com.phonoodlesramen.actioncenter.profiles.ProfileDatabase
import com.phonoodlesramen.actioncenter.profiles.ProfileStore
import com.phonoodlesramen.actioncenter.profiles.ProfileViewers
import com.phonoodlesramen.actioncenter.profiles.ProfilesController
import com.phonoodlesramen.actioncenter.profiles.RoomProfileStore
import com.phonoodlesramen.actioncenter.profiles.SealedKidTokens
import com.phonoodlesramen.actioncenter.profiles.StoredKidSource
import com.phonoodlesramen.actioncenter.profiles.StoredProfiles
import com.phonoodlesramen.actioncenter.profiles.cards
import com.phonoodlesramen.actioncenter.search.LibrarySearch
import com.phonoodlesramen.actioncenter.search.PlexLibrarySearch
import com.phonoodlesramen.actioncenter.search.PlexStreamingSearch
import com.phonoodlesramen.actioncenter.search.SearchCheck
import com.phonoodlesramen.actioncenter.search.StreamingSearch
import com.phonoodlesramen.actioncenter.safety.CrashGuard
import com.phonoodlesramen.actioncenter.safety.SharedPrefsCrashStore
import com.phonoodlesramen.actioncenter.settings.SettingsRepository
import com.phonoodlesramen.actioncenter.setup.AndroidSetupChecks
import com.phonoodlesramen.actioncenter.setup.DataStoreSetup
import com.phonoodlesramen.actioncenter.setup.SetupMonitor
import com.phonoodlesramen.actioncenter.station.AndroidKeyStoreKeys
import com.phonoodlesramen.actioncenter.station.DataStoreStationStore
import com.phonoodlesramen.actioncenter.station.GuideStation
import com.phonoodlesramen.actioncenter.station.GuideSwitch
import com.phonoodlesramen.actioncenter.station.StationGuideEditor
import com.phonoodlesramen.actioncenter.station.StationGuideSource
import com.phonoodlesramen.actioncenter.station.StationSpots
import com.phonoodlesramen.actioncenter.update.AndroidApkInspector
import com.phonoodlesramen.actioncenter.update.AndroidIdleSignals
import com.phonoodlesramen.actioncenter.update.DataStoreUpdates
import com.phonoodlesramen.actioncenter.update.ForegroundState
import com.phonoodlesramen.actioncenter.update.GitHubReleases
import com.phonoodlesramen.actioncenter.update.IdleSignals
import com.phonoodlesramen.actioncenter.update.PackageInstallerUpdates
import com.phonoodlesramen.actioncenter.update.UpdateConfig
import com.phonoodlesramen.actioncenter.update.Updater
import com.phonoodlesramen.actioncenter.update.WorkUpdateSchedule
import com.phonoodlesramen.actioncenter.suggest.EngineFeedback
import com.phonoodlesramen.actioncenter.suggest.FeedbackRules
import com.phonoodlesramen.actioncenter.suggest.FeedbackStore
import com.phonoodlesramen.actioncenter.suggest.KidLibrary
import com.phonoodlesramen.actioncenter.suggest.KidView
import com.phonoodlesramen.actioncenter.suggest.Library
import com.phonoodlesramen.actioncenter.suggest.Profiles
import com.phonoodlesramen.actioncenter.suggest.SuggestionDatabase
import com.phonoodlesramen.actioncenter.suggest.SuggestionEngine
import com.phonoodlesramen.actioncenter.system.AndroidDeviceInfoSource
import com.phonoodlesramen.actioncenter.system.AndroidPackageStateSource
import com.phonoodlesramen.actioncenter.system.AndroidTvProgramsReader
import com.phonoodlesramen.actioncenter.system.StockLauncherMonitor
import com.phonoodlesramen.actioncenter.system.WatchNextProbe
import com.phonoodlesramen.actioncenter.system.WorkSchedule
import com.phonoodlesramen.actioncenter.tvprovider.AndroidTvProviderReader
import com.phonoodlesramen.actioncenter.tvprovider.ChannelsAndAppsController
import com.phonoodlesramen.actioncenter.tvprovider.OtherAppsRows
import com.phonoodlesramen.actioncenter.wallpaper.AndroidWallpaperSources
import com.phonoodlesramen.actioncenter.wallpaper.StoredWallpaperController
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperController
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperRepository
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperSources
import com.phonoodlesramen.actioncenter.weather.OpenMeteoClient
import com.phonoodlesramen.actioncenter.weather.WeatherRepository
import com.phonoodlesramen.actioncenter.weather.WeatherWorker
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Manual DI (D9): the app's single object graph.
 *
 * @param plexOverride replaces plex.tv in tests, so none of them reaches the network.
 * @param espnOverride replaces ESPN in tests (Phase 13), for the same reason.
 * @param guideOverride replaces the Plex Guide's lineup in tests, so the home's guide has channels without a server.
 */
class AppContainer(
    app: Application,
    plexOverride: PlexAccounts? = null,
    espnOverride: EspnClient? = null,
    private val guideOverride: GuideSource? = null,
) {
    private val packageManager = app.packageManager

    val crashGuard = CrashGuard(
        SharedPrefsCrashStore(app.getSharedPreferences("crash_guard", Context.MODE_PRIVATE)),
    )
    val intentResolver = AndroidIntentResolver(packageManager)
    val intentProbe = IntentProbe(intentResolver)

    private val apps = LaunchableAppsRepository(AndroidLauncherActivitySource(packageManager), app.packageName)
    private val stockLauncher = StockLauncherMonitor(AndroidPackageStateSource(packageManager))

    /** Other apps' channels and Watch Next rows (L8, L9); needs `READ_TV_LISTINGS`. */
    private val tvProvider = AndroidTvProviderReader(app)

    val homeStateLoader = HomeStateLoader(
        crashGuard = crashGuard,
        stockLauncher = stockLauncher,
        apps = apps,
        intentProbe = intentProbe,
        device = AndroidDeviceInfoSource(app),
        watchNext = WatchNextProbe(AndroidTvProgramsReader(app)),
        tvProvider = tvProvider,
    )

    /** One DataStore per file for the whole process (D10). A corrupt file starts over. */
    val settings by lazy {
        SettingsRepository(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { app.preferencesDataStoreFile("settings") },
            ),
        )
    }

    val appArt = AndroidAppArtSource(packageManager)

    /** One client for weather, Plex and artwork. */
    val http by lazy { OkHttpClient() }

    /** Weather has its own DataStore file: the location, unit and last reading (D16). */
    val weather by lazy {
        WeatherRepository(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { app.preferencesDataStoreFile("weather") },
            ),
            OpenMeteoClient(http),
        )
    }

    val weatherSchedule = WorkSchedule({ WorkManager.getInstance(app) }, "weather", WeatherWorker::class.java)

    private val version = runCatching { packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0"

    /** The Plex sign-in, its own DataStore, tokens sealed by the Android Keystore (P4). */
    private val plexStore by lazy {
        PlexStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { app.preferencesDataStoreFile("plex") },
            ),
            KeystoreSecretBox(),
        )
    }
    private val plexHeaders by lazy { PlexHeaders(clientId = { plexStore.clientId() }, version = version, device = SHIELD_PLEX_DEVICE) }
    private val plexAccounts: PlexAccounts by lazy { plexOverride ?: PlexTv(http, plexHeaders) }

    private val plexReach by lazy { PlexServerReach(http, plexHeaders) }

    val plexSession by lazy { PlexSession(plexStore, plexAccounts, plexReach) }

    /** The linked server's address and token, for artwork URLs. Kept in memory only. */
    val plexAccess get() = plexStore.access

    /** The linked server's machine id, for Watch in Plezy from the guide's player (D11). */
    val plexServerId: Flow<String?> get() = plexStore.link.map { it.server?.clientIdentifier }

    /** The current profile's ratings (K4): null for you, who sees everything. Today's age, for a screen open a while. */
    val currentCeiling: Flow<Set<String>?>
        get() = profileStore.current(flowOf(""), flow { emit(LocalDate.now()) }).map { it.ceiling }.distinctUntilChanged()

    /** The Plex cache (Room). The home screen reads only this; [plexSync] fills it. */
    private val plexDatabase by lazy {
        Room.databaseBuilder(app, PlexDatabase::class.java, PlexDatabase.NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    private val plexLibrary by lazy { PlexLibrary(http, plexHeaders) }

    val plexSync by lazy {
        PlexSync(
            plexStore, plexAccounts, plexReach, plexLibrary, plexDatabase.dao(),
            log = { Log.i(PLEX_LOG_TAG, it) },
            // The library may have changed: the suggestion engine runs again (it never
            // throws), and the Plex Guide extends its schedule, and keeps doing so in the
            // background from now on.
            afterSync = {
                suggestionEngine.run()
                // Each linked kid's Continue Watching and plays, then their picks (Phase 5, K2); neither throws.
                kidSync.sync()
                suggestionEngine.runKids()
                guideSchedule.keepFresh()
                keepGuideFresh()
                appUsage.refresh()
                plexHomeCheck.runOnce()
            },
        )
    }

    /** Phase 5's Plex Home check (step 2): once per launch, after the first sync; counts only, no names or tokens. */
    private val plexHomeCheck by lazy {
        PlexHomeCheck(
            PlexHome(http, plexHeaders), plexAccounts, http, plexHeaders,
            ownerToken = { plexStore.userToken() },
            server = {
                val id = plexStore.link.first().server?.clientIdentifier
                val access = plexStore.access.first()
                if (id != null && access != null) id to access else null
            },
            log = { Log.i(PLEX_LOG_TAG, it) },
        )
    }

    /** Your feedback and the engine's picks (Phase 3): their own file, so a cache rebuild or a sign-out never wipes them. */
    private val suggestionDatabase by lazy {
        Room.databaseBuilder(app, SuggestionDatabase::class.java, SuggestionDatabase.NAME)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
    }

    /**
     * The suggestion engine (Phase 3): after each sync, after feedback, and on a new day; for you, then each kid (Phase 5).
     * Settings › Lenses › Move on from a title (Phase 14, L5) says when a title passed over rests.
     */
    private val suggestionEngine by lazy {
        SuggestionEngine(
            plexDatabase.suggestLibrary(),
            suggestionDatabase.dao(),
            kids = StoredKidSource(profileStore, profileDatabase.kidPlex()),
            moveOnDays = { settings.settings.first().lensPrefs.moveOn.days },
        )
    }

    /** Feedback is the current profile's (Phase 5). */
    private val suggestionFeedback by lazy {
        EngineFeedback(FeedbackStore(suggestionDatabase.dao()), suggestionEngine, profile = { profileStore.currentId.first() })
    }

    /** Each linked kid's Plex after each sync (Phase 5, K2): their token from [kidTokens], your server's address. */
    private val kidSync by lazy {
        KidSync(
            kids = { profileStore.kids.first() },
            tokens = kidTokens,
            access = { plexStore.access.first() },
            server = plexLibrary,
            dao = profileDatabase.kidPlex(),
            episodesOf = plexDatabase.dao()::episodesOf,
        )
    }

    /** A kid linked, unlinked or changed in Settings › Profiles: their Plex and picks now, not at the next sync. Never throws. */
    suspend fun refreshKids() {
        kidSync.sync()
        suggestionEngine.runKids()
    }

    /** A title's file on the linked server, for the guide's player (G5) and the Playback check. */
    val playables by lazy { PlexPlayables(plexStore.access, plexDatabase.dao(), plexLibrary, plexHeaders) }

    /** Each guide program's credits marker (Phase 11): the watched point and the credits card. */
    val slotCredits by lazy { PlexSlotCredits(plexLibrary, plexStore.access) }

    /** An episode's next one, from the cache: the guide player's credits card (K5). */
    val nextEpisodes by lazy { NextEpisodes(plexDatabase.dao()::item, plexDatabase.dao()::episodesOf) }

    /** Who's watching, as Plex knows them (Phase 11, K3): whose token the guide writes with, and Plezy's profile. */
    val plexViewers: PlexViewers by lazy {
        ProfileViewers(profileStore.currentId, profileStore.kids, kidTokens, plexStore.access, account = { plexStore.link.first().account })
    }

    /**
     * The guide's writes to Plex (Phase 11, amending G6): watched at the credits (Settings ›
     * Plex › Count guide viewing in Plex) and a branch's resume point. A write that outlasts
     * its call gives up after 10 s.
     */
    val guidePlexWrites by lazy {
        GuidePlexWrites(
            plexViewers,
            PlexWatchState(http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build(), plexHeaders),
            counting = { settings.settings.first().countGuideViewing },
            scope = appScope,
            log = { Log.i(PlaybackCheck.LOG_TAG, it) },
        )
    }

    /** Whether Plezy is on this TV: with no Play in chosen, Play goes to it (K4). */
    fun plezyInstalled(): Boolean = intentResolver.canResolve(IntentSpec.LaunchApp(Handoffs.PLEZY))

    /** Settings › Plex › Play in as it stands on this TV (K4): the choice, or Plezy when it's installed. */
    val playIn: Flow<PlayIn> get() = settings.settings.map { PlayIn.of(it.playIn, plezyInstalled()) }.distinctUntilChanged()

    /** Diagnostics › Playback check (Phase 2.6). */
    val playbackCheck by lazy { PlaybackCheck(plexDatabase.dao(), plexStore.access, plexLibrary, playables) }

    /**
     * Search's library (Phase 15, Q1): Plex's own search as the profile on now. A kid with
     * their own Plex user searches as them; one without searches as you, and their ceiling
     * applies either way.
     */
    val librarySearch: LibrarySearch by lazy {
        PlexLibrarySearch(
            server = plexLibrary,
            access = { profile ->
                val yours = plexStore.access.first()
                if (yours == null || !profile.kid) yours else kidTokens.serverToken(profile.id)?.let { ServerAccess(yours.baseUri, it) } ?: yours
            },
            titlesLike = plexDatabase.dao()::titlesLike,
            items = plexDatabase.dao()::items,
            titles = { plexDatabase.suggestLibrary().titles() },
        )
    }

    /**
     * The die (the top bar, spec §4): the Plex cache as a table to roll at, for whoever's on. A
     * kid's is their own view of it (their ceiling, their plays, K2-K4); Not tonight's titles
     * stay off it. Read when the tray opens, once per sync and day.
     */
    val dice: DiceTable by lazy {
        val kids = StoredKidSource(profileStore, profileDatabase.kidPlex())
        DiceTable(
            read = { profile ->
                val now = System.currentTimeMillis()
                val shared = Library.read(plexDatabase.suggestLibrary())
                val library = if (!profile.kid) {
                    shared
                } else {
                    val theirs = kids.kids(LocalDate.now()).firstOrNull { it.id == profile.id } ?: KidView(profile.id, profile.ceiling.orEmpty())
                    KidLibrary.of(shared, theirs)
                }
                val notTonight = FeedbackRules.hides(suggestionDatabase.dao().feedback(profile.id), now) { library.lastViewed(it) }.notTonight
                withContext(Dispatchers.Default) { Dice.deck(library, notTonight, now) }
            },
            log = { Log.i(SuggestionEngine.LOG_TAG, it) },
        )
    }

    /** Plex's catalog (Phase 15, Q3): Search's streaming row and Diagnostics › Search check. */
    val plexCatalog: PlexCatalogApi by lazy { PlexCatalog(http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build(), plexHeaders) }

    /**
     * Search's streaming row (Phase 15, Q3): Plex's catalog and where it says a title streams,
     * which needs your plex.tv token, whoever's watching (it's the same for everyone).
     */
    val streamingSearch: StreamingSearch by lazy { PlexStreamingSearch(plexCatalog, token = { plexStore.userToken() }) }

    /** Diagnostics › Search check (Phase 15): your server's search, the catalog, "where to watch" with your token, other apps' providers. */
    val searchCheck by lazy {
        SearchCheck(
            server = plexLibrary,
            access = { plexStore.access.first() },
            catalog = plexCatalog,
            userToken = { plexStore.userToken() },
            searchables = {
                app.getSystemService(SearchManager::class.java)?.searchablesInGlobalSearch.orEmpty()
                    .mapNotNull { it.searchActivity?.packageName }.distinct().sorted()
            },
        )
    }

    /** The Plex Guide's channels and schedule: their own file, so a sign-out or a cache rebuild never wipes them (G0). */
    private val guideDatabase by lazy {
        Room.databaseBuilder(app, GuideDatabase::class.java, GuideDatabase.NAME)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
    }

    /** Which Plex server the lineup was made from (G19), and whether it's had its Family channel (K8). */
    private val lineupServer by lazy { SharedPrefsLineupServer(app.getSharedPreferences("guide", Context.MODE_PRIVATE)) }

    /**
     * The Plex Guide (Phase 2.6): the starter lineup and a rolling 24 hours, made from the
     * library cache. It remembers the server its lineup came from, and remakes it for a new one (G19).
     * While this TV is paired with a guide station (Phase 8) it rests: the station schedules.
     */
    val plexGuide by lazy {
        GuideEngine(
            guideDatabase, plexDatabase,
            log = { Log.i(GuideEngine.LOG_TAG, it) },
            linkedServer = { plexStore.link.first().server?.clientIdentifier },
            lineupServer = lineupServer,
        )
    }

    /**
     * The guide station on the NAS (Phase 8): pairing (Settings › Plex Guide › Guide station),
     * this TV's copy of the station's guide and spot, and leaving. An unpaired TV never pairs
     * by itself, and its key is made in the Keystore only when it pairs.
     */
    val guideStation by lazy {
        GuideStation(
            store = DataStoreStationStore(
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    produceFile = { app.preferencesDataStoreFile("guide_station") },
                ),
            ),
            keys = { AndroidKeyStoreKeys() },
            http = http,
            guideDb = guideDatabase,
            lineupServer = lineupServer,
            linkedServer = { plexStore.link.first().server?.clientIdentifier },
            model = Build.MODEL.orEmpty(),
            scope = appScope,
            log = { Log.i(GuideStation.LOG_TAG, it) },
        )
    }

    /** Each seam is the station's while this TV is paired, and its own otherwise. */
    private val guideSwitch by lazy { GuideSwitch(guideStation.paired) }

    /** The Plex Guide's lineup, for the Live row, the Guide card, the full guide and the player. */
    val guide: GuideSource by lazy {
        guideOverride ?: guideSwitch.source(local = plexGuide, station = StationGuideSource(guideDatabase, guideStation.guideCopy, ChannelLooks(plexDatabase), log = { Log.i(GuideStation.LOG_TAG, it) }))
    }

    private val localGuideEditor by lazy { RoomGuideEditor(guideDatabase, plexDatabase, plexGuide, log = { Log.i(GuideEngine.LOG_TAG, it) }) }

    /** Settings › Plex Guide and the phone editor change channels through this (G1, G18): the station's, once paired (N4). */
    val guideEditor: GuideEditor by lazy {
        // The lineup is read from guide.db either way: the station's copy is kept there.
        guideSwitch.editor(local = localGuideEditor, station = StationGuideEditor(guideStation, localGuideEditor.lineup))
    }

    /**
     * Keeps the guide's schedule ahead: this TV's own engine extends it, or, while paired, the
     * station's copy is checked. Never throws.
     */
    suspend fun keepGuideFresh() {
        if (guideStation.isPaired()) guideStation.refresh() else plexGuide.extend()
    }

    /** Extends the Plex Guide every 3 hours; it reads only the cache, so it needs no network. */
    val guideSchedule = WorkSchedule(
        { WorkManager.getInstance(app) }, "guide", GuideWorker::class.java, everyMinutes = 180, needsNetwork = false,
    )

    val plexSchedule = WorkSchedule({ WorkManager.getInstance(app) }, "plex-sync", PlexSyncWorker::class.java)

    /** Work that outlives a screen, such as saving a setting after Back, or the player's spot after Home. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Where the guide player was left (P3): its own DataStore file, so it survives the process
     * being killed. While paired, the house's one spot on the station (N6), cached here.
     */
    val spots: SpotStore by lazy {
        val local = DataStoreSpots(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { app.preferencesDataStoreFile("player_spot") },
            ),
        )
        guideSwitch.spots(local = local, station = StationSpots(guideStation))
    }

    /**
     * Each profile's favorite channels (Phase 13): on the station while paired, by the profile's
     * key, with a copy in their own DataStore file; this TV's own otherwise. Deleted channels
     * leave a list when it's next saved, against every channel in `guide.db`, off ones too.
     */
    val channelFavorites by lazy {
        ChannelFavorites(
            store = DataStoreFavorites(
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    produceFile = { app.preferencesDataStoreFile("favorites") },
                ),
            ),
            station = guideStation,
            profile = profileStore.current(settings.settings.map { it.name }, flow { emit(LocalDate.now()) }),
            knownChannels = { guideEditor.lineup.first().map { it.id } },
            log = { Log.i(GuideStation.LOG_TAG, it) },
        )
    }

    /** ESPN's public site API (Phase 13, F11): only while this TV isn't paired; the station asks for the house otherwise. */
    private val espn by lazy { espnOverride ?: EspnClient(http) }

    /**
     * Setup without a computer: Settings › Setup and the top bar's setup chip. Read on every
     * resume; what it remembers (the chip hidden, HomeWatch seen on, permissions asked) is in
     * its own DataStore file.
     */
    val setup: SetupMonitor by lazy {
        SetupMonitor(
            checks = AndroidSetupChecks(app),
            store = DataStoreSetup(
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    produceFile = { app.preferencesDataStoreFile("setup") },
                ),
            ),
            scope = appScope,
        )
    }

    /**
     * The NFL (Phase 13): the guide station's copy while paired, followed while the home is in
     * front; ESPN's, made on this TV, otherwise. Drawn from its own DataStore file.
     */
    val nfl: NflSource by lazy {
        NflSource(
            store = DataStoreNfl(
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    produceFile = { app.preferencesDataStoreFile("nfl") },
                ),
            ),
            station = guideStation,
            espn = espn,
            zone = ZoneId.systemDefault(),
            scope = appScope,
            log = { Log.i(NFL_LOG_TAG, it) },
        )
    }

    /** Team logos, each fetched once into the cache directory: from the station while paired, else ESPN's. */
    val nflLogos by lazy { NflLogos(File(app.cacheDir, "nfl-logos"), guideStation, espn, log = { Log.i(NFL_LOG_TAG, it) }) }

    /** The operator's folders in shared storage: wallpapers and app banners (L2, L7). */
    val sharedFolders by lazy { SharedFolders.android(app) }

    /** Wallpaper settings have their own DataStore file (L1-L4). */
    private val wallpaperRepository by lazy {
        WallpaperRepository(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { app.preferencesDataStoreFile("wallpaper") },
            ),
        )
    }

    /** Settings › Wallpaper. */
    val wallpaperController: WallpaperController by lazy { StoredWallpaperController(wallpaperRepository, sharedFolders, appScope) }

    /** Custom app banners from the Icons folder, and Choose art's picks in their own DataStore file (L7). */
    val customBanners: CustomBanners by lazy {
        StoredCustomBanners(
            sharedFolders,
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { app.preferencesDataStoreFile("custom_banners") },
            ),
            appScope,
        )
    }

    /** What the wallpaper styles read: the folders, and the Plex cache for Poster wall. */
    val wallpaperSources: WallpaperSources by lazy { AndroidWallpaperSources(sharedFolders) { plexDatabase.dao().randomPosters(it) } }

    /** Channels and Continue in your apps (L8, L9); their settings have their own DataStore file. */
    private val otherAppsRows by lazy { OtherAppsRows.android(app, tvProvider, sharedFolders, appScope) }

    /** Settings › Channels & apps. */
    val channelsAndApps: ChannelsAndAppsController get() = otherAppsRows.controller

    /** Kids, the age brackets and who's watching (Phase 5), in `profiles.db`. */
    private val profileDatabase by lazy {
        Room.databaseBuilder(app, ProfileDatabase::class.java, ProfileDatabase.NAME)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
    }

    val profileStore: ProfileStore by lazy { RoomProfileStore(profileDatabase.dao()) }

    /** Each linked kid's server token (K2): sealed like yours, in its own DataStore file. */
    val kidTokens: KidTokens by lazy {
        SealedKidTokens(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { app.preferencesDataStoreFile("kid_tokens") },
            ),
            KeystoreSecretBox(),
        )
    }

    /** Settings › Profiles (K10): the kids, the age brackets, and linking a kid's Plex Home user (K2). */
    val profiles: ProfilesController by lazy {
        StoredProfiles(
            profileStore, kidTokens, PlexHome(http, plexHeaders), plexAccounts,
            ownerToken = { plexStore.userToken() },
            serverId = { plexStore.link.first().server?.clientIdentifier },
            scope = appScope,
            plexChanged = ::refreshKids,
        )
    }

    /** Which apps you open, and when (M5): the Apps row's order. */
    val appUsage: AppUsage by lazy { AndroidAppUsage.create(app) }

    /** Back to YouTube TV: the last app you watched, from Android's usage events. */
    private val recentApps by lazy { UsageRecentApps(AndroidUsageEvents(app), app.packageName) }

    /**
     * Back to YouTube TV: the YouTube TV channels opened here and the visit
     * forgotten, in their own DataStore file, on this TV only.
     */
    val tvChannels: TvChannelStore by lazy {
        DataStoreTvChannels(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { app.preferencesDataStoreFile("youtube_tv") },
            ),
        )
    }

    /** The Claude API key from your phone (M10): Keystore-sealed, in `noBackupFilesDir`. */
    private val aiKeyVault by lazy { AiKeyVault(File(app.noBackupFilesDir, AiKeyVault.FILE), KeystoreSecretBox(AiKeyVault.ALIAS)) }

    /** The last night's run, in its own DataStore file, for Settings › AI blurbs. */
    private val aiBlurbRuns by lazy {
        BlurbRuns(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { app.preferencesDataStoreFile("ai_blurbs") },
            ),
        )
    }

    /**
     * Settings › AI blurbs (M7-M11), off by default. It keeps the nightly run scheduled
     * while it's on (about 4 AM, unmetered), runs once when it's first on with a key, and
     * clears the blurbs when it's turned off. The key page stores the key through it.
     */
    val aiBlurbs: StoredAiBlurbs by lazy {
        StoredAiBlurbs(
            settings = settings.settings,
            vault = aiKeyVault,
            runs = aiBlurbRuns,
            schedule = WorkBlurbSchedule({ WorkManager.getInstance(app) }),
            clearBlurbs = { suggestionDatabase.dao().pruneBlurbs(Long.MAX_VALUE) },
            scope = appScope,
        )
    }

    /** The night's request to Claude Haiku 5.5 (M11), for [com.phonoodlesramen.actioncenter.ai.BlurbWorker]. */
    val blurbWriter by lazy {
        BlurbWriter(settings.settings, aiKeyVault, ClaudeBlurbs(http), aiBlurbRuns, suggestionDatabase.dao(), plexDatabase.dao())
    }

    /**
     * Updates from this build's GitHub releases (Settings › About, safe mode): a check once a day
     * unless it's Off, and a download and Android's installer only after a press. Debug builds
     * never check. The download waits in the cache; what it remembers is in its own DataStore file.
     */
    /** Whether Action Center's own screens are in front and touched, for Install automatically. */
    val foreground = ForegroundState()

    val updates: Updater by lazy {
        val config = UpdateConfig.fromBuild()
        // Its screensaver listener is made only once Install automatically asks.
        val signals by lazy { AndroidIdleSignals(app, foreground) }
        Updater(
            config = config,
            feed = GitHubReleases(http, config),
            inspector = AndroidApkInspector(app),
            installer = PackageInstallerUpdates(app),
            store = DataStoreUpdates(
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    produceFile = { app.preferencesDataStoreFile("updates") },
                ),
            ),
            dir = File(app.cacheDir, "updates"),
            canInstall = { app.packageManager.canRequestPackageInstalls() },
            freeBytes = {
                // What Android would free up for the download, cache included; the plain free space if it can't say.
                val storage = app.getSystemService(StorageManager::class.java)
                runCatching { storage.getAllocatableBytes(storage.getUuidForPath(app.cacheDir)) }.getOrElse { app.cacheDir.usableSpace }
            },
            idle = IdleSignals { signals.snapshot() },
            schedule = WorkUpdateSchedule({ WorkManager.getInstance(app) }),
            scope = appScope,
        )
    }

    private val homeSystem = object : HomeSystem {
        override suspend fun launchableApps() = apps.apps()
        override fun stockLauncherState() = stockLauncher.state()
    }

    /**
     * The home's view model, for as long as the app runs, never a Home screen's own: Android
     * can make a new Home screen while the process lives (a Google TV waking into Google
     * TV's launcher, then [com.phonoodlesramen.actioncenter.home.HomeWatchService]), and it
     * opens with the cards the last one had instead of empty. Made on first use, so safe mode
     * never builds it.
     */
    val home: HomeViewModel by lazy { homeViewModel() }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun homeViewModel() = HomeViewModel(
        settings,
        homeSystem,
        compute = Dispatchers.Default,
        weather = weather.state,
        plexLink = plexStore.link,
        plexSignIn = plexSession.status,
        plexSync = plexDatabase.dao().syncStateFlow().map { state ->
            PlexSyncStatus(
                offline = state?.error == PlexSync.OFFLINE,
                failure = state?.error?.takeIf { it != PlexSync.OFFLINE },
                lastSuccessMillis = state?.lastSuccessAt,
                lastAttemptMillis = state?.lastAttemptAt,
            )
        },
        plexSyncing = plexSync.running,
        // The current profile's picks (Phase 5). A kid's cards are theirs: their Up Next, within
        // their ceiling; blurbs are only ever yours (AI blurbs never run for kids).
        plexSnapshots = { days ->
            val dao = suggestionDatabase.dao()
            profileStore.currentId.flatMapLatest { id ->
                if (id == Profiles.OWNER) {
                    plexDatabase.snapshots(days, dao.picks(id), blurbs = dao.blurbs(id))
                } else {
                    val kid = profileDatabase.kidPlex().cards(id, profileStore.kids, profileStore.brackets, days)
                    plexDatabase.snapshots(days, dao.picks(id), kid = kid)
                }
            }
        },
        wallpaperPrefs = wallpaperRepository.prefs,
        homeRows = otherAppsRows.rows,
        guideLineup = guide.card,
        suggestions = suggestionFeedback,
        appUsage = appUsage,
        profileStore = profileStore,
        spots = spots,
        favorites = channelFavorites.current,
        nfl = nfl.view,
        recentApps = recentApps,
        tvChannels = tvChannels,
    )

    private companion object {
        /** The Plex sync's and the Plex Home check's lines in logcat (the guide's are [GuideEngine.LOG_TAG]). */
        const val PLEX_LOG_TAG = "ActionCenterPlex"

        /** The NFL's lines in logcat: copies, ESPN's failures, logos. Never a setting or a score. */
        const val NFL_LOG_TAG = "ActionCenterNfl"
    }
}
