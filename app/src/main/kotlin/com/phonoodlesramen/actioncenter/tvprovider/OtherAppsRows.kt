package com.phonoodlesramen.actioncenter.tvprovider

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.phonoodlesramen.actioncenter.home.ChannelButton
import com.phonoodlesramen.actioncenter.home.HomeRowsState
import com.phonoodlesramen.actioncenter.media.MediaKind
import com.phonoodlesramen.actioncenter.media.SharedFolders
import com.phonoodlesramen.actioncenter.safety.summary
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Apps whose Watch Next entries Up Next already shows (D15, L9), so the new row leaves them out. */
val UP_NEXT_APPS = setOf("com.plexapp.android", "tv.plex.app", "com.edde746.plezy")

/** YouTube TV, whose own home-screen row supplies the Channels buttons (Q3). */
const val YOUTUBE_TV = "com.google.android.youtube.tvunplugged"

/** The name of YouTube TV's home-screen channel of live networks. */
const val YOUTUBE_TV_CHANNEL = "YouTube TV"

/** An app's name for Settings and the quick actions. Null when the app isn't installed (or visible). */
fun interface AppLabels {
    fun label(packageName: String): String?
}

class AndroidAppLabels(private val packageManager: PackageManager) : AppLabels {
    private val cache = ConcurrentHashMap<String, String>()

    override fun label(packageName: String): String? = cache[packageName] ?: try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
            .also { cache[packageName] = it }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}

/** One read of everything the rows need, made off the main thread. */
data class OtherAppsSnapshot(
    /** False until `READ_TV_LISTINGS` is granted (`scripts/grant-permissions.ps1`). */
    val canReadTvListings: Boolean = false,
    /** Watch Next entries that open something, from every app but Plex and Plezy, newest first. */
    val watchNext: List<TvProgram> = emptyList(),
    val channelList: ChannelList = ChannelList.Missing,
    /** [channelList] as buttons, banners matched. */
    val listChannels: List<ChannelButton> = emptyList(),
    /** YouTube TV's live networks, if its preview row has any. */
    val youTubeTvChannels: List<ChannelButton> = emptyList(),
    val channelListPath: String = "${ChannelListFile.DEFAULT_FOLDER}/${ChannelListFile.NAME}",
)

/** The mapping from provider rows to buttons and cards. Pure, so tests don't need a provider. */
object OtherApps {
    /** L9: every app's Watch Next but Up Next's, only entries that open something, most recently engaged first. */
    fun watchNext(programs: List<TvProgram>): List<TvProgram> = programs
        .filter { it.kind == TvProgram.Kind.WATCH_NEXT && it.packageName !in UP_NEXT_APPS && !it.intentUri.isNullOrBlank() }
        .sortedWith(compareByDescending<TvProgram> { it.lastEngagementMillis ?: Long.MIN_VALUE }.thenBy { it.packageName }.thenBy { it.id })

    /**
     * Q3: the networks in YouTube TV's home-screen row, one button per name, in its
     * order. On the Shield (10/26/2026) that's one channel, "YouTube TV", whose programs
     * are each a live network tagged as a clip, not live, so they're picked by package
     * and channel, never by type. A network's banner comes from the Icons folder;
     * failing that, the program's own art.
     */
    fun youTubeTvChannels(provider: TvProviderSnapshot, banners: NetworkBanners?): List<ChannelButton> {
        val programs = provider.programs.filter { it.kind == TvProgram.Kind.PREVIEW && it.packageName == YOUTUBE_TV }
        // Its own "YouTube TV" channel if it has one (a later version might add others); otherwise all of its rows.
        val live = provider.channels
            .filter { it.packageName == YOUTUBE_TV && normalisedName(it.displayName) == normalisedName(YOUTUBE_TV_CHANNEL) }
            .map { it.id }.toSet()
        return programs
            .filter { live.isEmpty() || it.channelId in live }
            .filter { !it.intentUri.isNullOrBlank() && normalisedName(it.title).isNotEmpty() }
            .distinctBy { normalisedName(it.title) }
            .map { program ->
                ChannelButton(
                    id = "youtube-tv-${program.id}",
                    name = program.title,
                    intentUri = program.intentUri!!,
                    art = banners?.find(program.title)?.path ?: program.thumbnailUri ?: program.posterArtUri,
                    source = ChannelButton.Source.YOUTUBE_TV,
                )
            }
    }

    /** The operator's list, in its order, each with its banner. */
    fun listChannels(list: ChannelList, banners: NetworkBanners?): List<ChannelButton> =
        (list as? ChannelList.Loaded)?.entries.orEmpty().mapIndexed { index, entry ->
            ChannelButton(
                id = "list-$index-${normalisedName(entry.name)}",
                name = entry.name,
                intentUri = entry.link,
                art = banners?.find(entry.name, entry.banner)?.path,
                source = ChannelButton.Source.CHANNEL_LIST,
            )
        }

    /**
     * The Channels row (Q3): YouTube TV's networks, unless the operator's list has
     * channels. The list replaces them, or with `"includeYouTubeTv": true` adds to them:
     * a list entry with a network's name takes that network's place, and the rest follow.
     */
    fun channels(snapshot: OtherAppsSnapshot): List<ChannelButton> {
        val list = snapshot.listChannels
        val youTubeTv = snapshot.youTubeTvChannels
        if (list.isEmpty()) return youTubeTv
        if ((snapshot.channelList as? ChannelList.Loaded)?.includeYouTubeTv != true) return list
        val byName = list.associateBy { normalisedName(it.name) }
        val merged = youTubeTv.map { byName[normalisedName(it.name)] ?: it }
        val used = merged.map { normalisedName(it.name) }.toSet()
        return merged + list.filter { normalisedName(it.name) !in used }
    }

    fun rows(snapshot: OtherAppsSnapshot, prefs: ChannelsAndAppsPrefs) = HomeRowsState(
        channels = channels(snapshot),
        continueInApps = snapshot.watchNext.filter { it.packageName !in prefs.hiddenApps },
    )

    /** Every app with something to continue, plus any turned off, by name. */
    fun settings(snapshot: OtherAppsSnapshot?, prefs: ChannelsAndAppsPrefs, labels: AppLabels): ChannelsAndAppsState {
        val counts = snapshot?.watchNext.orEmpty().groupingBy { it.packageName }.eachCount()
        val apps = (counts.keys + prefs.hiddenApps).map { pkg ->
            WatchNextApp(pkg, labels.label(pkg) ?: pkg, counts[pkg] ?: 0, shown = pkg !in prefs.hiddenApps)
        }.sortedBy { it.name.lowercase() }
        return ChannelsAndAppsState(
            loaded = snapshot != null,
            canReadTvListings = snapshot?.canReadTvListings ?: true,
            apps = apps,
            channelList = snapshot?.channelList ?: ChannelList.Missing,
            channels = snapshot?.let(::channels)?.size ?: 0,
            listChannels = snapshot?.listChannels?.size ?: 0,
            youTubeTvChannels = snapshot?.youTubeTvChannels?.size ?: 0,
            channelListPath = snapshot?.channelListPath ?: OtherAppsSnapshot().channelListPath,
        )
    }
}

/**
 * The Channels and Continue in your apps rows (L8, L9), from what other apps publish to
 * Android TV plus the operator's channel list.
 *
 * Everything is read on [io], again whenever [refreshes] fires (the home screen coming to
 * the front, a provider change) or Settings asks. A new subscriber gets the last read
 * first, so the rows never wait on the provider.
 */
class OtherAppsRows(
    private val reader: TvProviderReader,
    private val store: ChannelsAndAppsStore,
    private val channelFile: ChannelListFile,
    /** Images in the Icons folder (L7), for network banners; empty without the storage grant. */
    private val bannerFiles: () -> List<File>,
    private val labels: AppLabels,
    refreshes: Flow<Unit>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val reloads = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The provider's last good read, kept when a later one fails. */
    @Volatile
    private var lastProvider: TvProviderSnapshot? = null

    /** The last read and settings, shared by the rows and Settings, and kept for the next subscriber. */
    private val latest: StateFlow<Pair<OtherAppsSnapshot, ChannelsAndAppsPrefs>?> = combine(
        merge(refreshes, reloads).conflate().map { withContext(io) { read() } },
        store.prefs,
    ) { read, prefs -> read to prefs }
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_MILLIS), null)

    val rows: Flow<HomeRowsState> = latest.filterNotNull().map { (read, prefs) -> OtherApps.rows(read, prefs) }.distinctUntilChanged()

    /** Settings › Channels & apps. */
    val controller: ChannelsAndAppsController = object : ChannelsAndAppsController {
        override val state: StateFlow<ChannelsAndAppsState> = latest.map { OtherApps.settings(it?.first, it?.second ?: ChannelsAndAppsPrefs(), labels) }
            .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_MILLIS), ChannelsAndAppsState())

        override fun setAppShown(packageName: String, shown: Boolean) {
            scope.launch { store.setAppShown(packageName, shown) }
        }

        override fun reload() {
            reloads.tryEmit(Unit)
        }
    }

    /** Never throws: a provider or file problem leaves that part as it was, or empty. */
    private fun read(): OtherAppsSnapshot {
        val canRead = guard("permission", false) { reader.canRead() }
        val provider = if (canRead) guard("provider", lastProvider) { reader.read() }?.also { lastProvider = it } else null
        val list = guard("channel list", ChannelList.Missing) { channelFile.read() }
        val programs = provider?.programs.orEmpty()
        // The Icons folder is listed only when there's a network to match.
        val banners by lazy { NetworkBanners(guard("banners", emptyList(), bannerFiles)) }
        val hasNetworks = programs.any { it.kind == TvProgram.Kind.PREVIEW && it.packageName == YOUTUBE_TV }
        return OtherAppsSnapshot(
            canReadTvListings = canRead,
            watchNext = OtherApps.watchNext(programs),
            channelList = list,
            listChannels = if (list is ChannelList.Loaded && list.entries.isNotEmpty()) OtherApps.listChannels(list, banners) else emptyList(),
            youTubeTvChannels = if (provider != null && hasNetworks) OtherApps.youTubeTvChannels(provider, banners) else emptyList(),
            channelListPath = guard("channel list path", OtherAppsSnapshot().channelListPath) { channelFile.path() },
        )
    }

    private inline fun <T> guard(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "$what: ${e.summary()}")
        fallback
    }

    companion object {
        private const val LOG_TAG = "ActionCenterRows"
        private const val KEEP_MILLIS = 5_000L

        /** Settings › Channels & apps has its own DataStore file. */
        private const val STORE_FILE = "channels_and_apps"

        /** The app's wiring: provider changes and resumes, `channels.json`, the Icons folder. */
        fun android(app: Application, reader: TvProviderReader, folders: SharedFolders, scope: CoroutineScope) = OtherAppsRows(
            reader = reader,
            store = ChannelsAndAppsStore(
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    produceFile = { app.preferencesDataStoreFile(STORE_FILE) },
                ),
            ),
            // Asking for the folder also creates it, so `adb push` has somewhere to go.
            channelFile = ChannelListFile { app.getExternalFilesDir(null) },
            bannerFiles = { folders.files(SharedFolders.ICONS, MediaKind.IMAGE, recursive = true).filterNot(folders::isChannelIcon) },
            labels = AndroidAppLabels(app.packageManager),
            refreshes = refreshes(AppInFront(app).inFront, app.contentResolver.tvProviderChanges()),
            scope = scope,
        )
    }
}

/** An app that publishes Watch Next, for Settings › Channels & apps. */
data class WatchNextApp(val packageName: String, val name: String, val entries: Int, val shown: Boolean)

/** What Settings › Channels & apps shows. */
data class ChannelsAndAppsState(
    /** False until the first read: Settings says nothing about permissions yet. */
    val loaded: Boolean = false,
    val canReadTvListings: Boolean = true,
    val apps: List<WatchNextApp> = emptyList(),
    val channelList: ChannelList = ChannelList.Missing,
    /** Buttons in the Channels row. */
    val channels: Int = 0,
    /** Buttons from the operator's list, which replace or add to YouTube TV's. */
    val listChannels: Int = 0,
    /** Buttons YouTube TV's own row would give. */
    val youTubeTvChannels: Int = 0,
    val channelListPath: String = OtherAppsSnapshot().channelListPath,
)

/** What Settings › Channels & apps reads and changes. [None] for previews and screenshots. */
interface ChannelsAndAppsController {
    val state: StateFlow<ChannelsAndAppsState> get() = NO_STATE

    /** Turns an app's entries in Continue in your apps on or off. */
    fun setAppShown(packageName: String, shown: Boolean) {}

    /** Reads `channels.json` and the provider again. */
    fun reload() {}

    companion object None : ChannelsAndAppsController
}

private val NO_STATE: StateFlow<ChannelsAndAppsState> = MutableStateFlow(ChannelsAndAppsState())
