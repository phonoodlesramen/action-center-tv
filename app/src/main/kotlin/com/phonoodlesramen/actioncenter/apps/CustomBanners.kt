package com.phonoodlesramen.actioncenter.apps

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phonoodlesramen.actioncenter.media.MediaKind
import com.phonoodlesramen.actioncenter.media.SharedFolders
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One image in the operator's Icons folder (L7).
 *
 * @param name relative to the folder, e.g. "Netflix.png" or "Flat icons/NETFLIX.png".
 * @param path the file itself, for decoding.
 */
data class BannerFile(val name: String, val path: String) {
    /** The file's own name without its folder or extension: what an app's label is matched against. */
    val title: String get() = name.substringAfterLast('/').substringBeforeLast('.')

    /** In a subfolder such as `Flat icons`. */
    val inSubfolder: Boolean get() = '/' in name

    /** In the `Channels` subfolder: a guide channel's icon (I3), not an app's banner. */
    val channelIcon: Boolean get() = name.substringBefore('/', missingDelimiterValue = "").equals(SharedFolders.CHANNEL_ICONS, ignoreCase = true)
}

/** What Choose art saved for an app. */
sealed interface BannerChoice {
    /** The default: the file whose name matches the app's, else the app's own banner. */
    data object Automatic : BannerChoice

    /** The app's own banner, even when a file matches. */
    data object OwnBanner : BannerChoice

    /** A file from the Icons folder, by its [BannerFile.name]. */
    data class Chosen(val name: String) : BannerChoice
}

/**
 * Finds the banner for an app by a normalised name: lower case, letters and digits only,
 * so "Youtube TV.png" is YouTube TV's and "PrimeVideo.png" is Prime Video's.
 *
 * - An exact normalised match always wins.
 * - Otherwise one typo is forgiven ("Live Channes.png" for Live Channels), but only for
 *   names of six or more characters and only when exactly one file is that close.
 * - When the main folder and a subfolder both match, the main folder wins. Its banners are
 *   one consistent set of 16:9 tiles covering most apps, so the row doesn't mix styles;
 *   the `Flat icons` logos (transparent, some dark) stay a pick away in Choose art.
 */
class BannerIndex(files: List<BannerFile>) {
    private val byName: Map<String, BannerFile> = files
        .sortedWith(compareBy<BannerFile> { it.inSubfolder }.thenBy { it.name.lowercase(Locale.ROOT) })
        .groupBy { normalise(it.title) }
        .filterKeys { it.isNotEmpty() }
        .mapValues { (_, same) -> same.first() }

    fun match(label: String): BannerFile? {
        val key = normalise(label)
        if (key.isEmpty()) return null
        byName[key]?.let { return it }
        if (key.length < MIN_FORGIVING_LENGTH) return null
        return byName.filterKeys { it.length >= MIN_FORGIVING_LENGTH && oneEditApart(it, key) }.values.singleOrNull()
    }

    companion object {
        private const val MIN_FORGIVING_LENGTH = 6

        fun normalise(name: String): String = name.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

        /** True when one insertion, deletion or substitution turns [a] into [b]. */
        internal fun oneEditApart(a: String, b: String): Boolean {
            if (abs(a.length - b.length) > 1) return false
            val (short, long) = if (a.length <= b.length) a to b else b to a
            var i = 0
            var j = 0
            var edits = 0
            while (i < short.length && j < long.length) {
                if (short[i] == long[j]) {
                    i++
                    j++
                } else {
                    if (++edits > 1) return false
                    if (short.length == long.length) i++
                    j++
                }
            }
            return edits + (long.length - j) + (short.length - i) <= 1
        }
    }
}

/** The Icons folder and every app's Choose art pick. */
data class CustomBannersState(
    /** Every image in the folder, subfolders included. Empty without the storage grant. */
    val files: List<BannerFile> = emptyList(),
    /** Apps that aren't in here are [BannerChoice.Automatic]. */
    val choices: Map<String, BannerChoice> = emptyMap(),
    /** The `Channels` subfolder's images: the guide channels' icons (I3), not in [files]. */
    val channelIcons: List<BannerFile> = emptyList(),
) {
    private val index = BannerIndex(files)
    private val channelIndex = BannerIndex(channelIcons)

    /** The image named after a guide channel, matched as an app's banner is. */
    fun channelIconFor(name: String): BannerFile? = channelIndex.match(name)

    private val byName = files.associateBy(BannerFile::name)

    fun choiceFor(app: LaunchableApp): BannerChoice = choices[app.key] ?: BannerChoice.Automatic

    /** The file whose name matches [app]'s label, if any. */
    fun matchFor(app: LaunchableApp): BannerFile? = index.match(app.label)

    /**
     * What [app]'s card draws: a file, or null for the app's own banner (or its name). A
     * picked file that has since gone counts as Automatic.
     */
    fun bannerFor(app: LaunchableApp): BannerFile? = when (val choice = choiceFor(app)) {
        BannerChoice.Automatic -> matchFor(app)
        BannerChoice.OwnBanner -> null
        is BannerChoice.Chosen -> byName[choice.name] ?: matchFor(app)
    }
}

/** Custom app banners from `/sdcard/Pictures/Icons` (L7), with Choose art's picks. */
interface CustomBanners {
    val state: StateFlow<CustomBannersState>

    /** Reads the folder again: banners can be copied over at any time. */
    fun refresh()

    fun choose(app: LaunchableApp, choice: BannerChoice)

    /** No folder and no picks (screenshots, previews): every card shows the app's own banner. */
    companion object None : CustomBanners {
        override val state: StateFlow<CustomBannersState> = MutableStateFlow(CustomBannersState())
        override fun refresh() {}
        override fun choose(app: LaunchableApp, choice: BannerChoice) {}
    }
}

val LocalCustomBanners = staticCompositionLocalOf<CustomBanners> { CustomBanners.None }

/**
 * Lists the Icons folder through [SharedFolders] and keeps the picks in their own
 * DataStore, by app key. Without the storage grant the list is empty, so every card
 * falls back to the app's own banner without a word.
 */
class StoredCustomBanners(
    private val folders: SharedFolders,
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CustomBanners {
    private val files = MutableStateFlow<List<BannerFile>>(emptyList())

    override val state: StateFlow<CustomBannersState> = combine(
        files,
        store.data.catch { emit(emptyPreferences()) }.map(::choices),
    ) { found, picks -> CustomBannersState(found.filterNot { it.channelIcon }, picks, found.filter { it.channelIcon }) }
        .stateIn(scope, SharingStarted.Eagerly, CustomBannersState())

    init {
        refresh()
    }

    override fun refresh() {
        scope.launch(io) { files.value = runCatching { scan() }.getOrDefault(emptyList()) }
    }

    private fun scan(): List<BannerFile> {
        val folder = folders.resolve(SharedFolders.ICONS)
        return folders.files(SharedFolders.ICONS, MediaKind.IMAGE, recursive = true).map { file ->
            BannerFile(file.relativeTo(folder).path.replace(File.separatorChar, '/'), file.path)
        }
    }

    override fun choose(app: LaunchableApp, choice: BannerChoice) {
        scope.launch {
            store.edit { prefs ->
                val key = stringPreferencesKey(KEY_PREFIX + app.key)
                when (choice) {
                    BannerChoice.Automatic -> prefs.remove(key)
                    BannerChoice.OwnBanner -> prefs[key] = OWN_BANNER
                    is BannerChoice.Chosen -> prefs[key] = FILE_PREFIX + choice.name
                }
            }
        }
    }

    private fun choices(prefs: Preferences): Map<String, BannerChoice> = prefs.asMap().mapNotNull { (key, value) ->
        val app = key.name.removePrefix(KEY_PREFIX).takeIf { key.name.startsWith(KEY_PREFIX) }
        val choice = when {
            value == OWN_BANNER -> BannerChoice.OwnBanner
            value is String && value.startsWith(FILE_PREFIX) -> BannerChoice.Chosen(value.removePrefix(FILE_PREFIX))
            else -> null
        }
        if (app == null || choice == null) null else app to choice
    }.toMap()

    private companion object {
        const val KEY_PREFIX = "banner:"
        const val OWN_BANNER = "own"
        const val FILE_PREFIX = "file:"
    }
}
