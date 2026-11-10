package com.phonoodlesramen.actioncenter.wallpaper

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phonoodlesramen.actioncenter.media.MediaKind
import com.phonoodlesramen.actioncenter.media.SharedFolders
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How Settings › Wallpaper groups the styles (L1). */
enum class WallpaperFamily(val title: String) { CLASSIC("Classic"), CREATIVE("Creative"), FRONTIER("Frontier") }

/** The wallpaper styles chosen in L1. Frosted glass is [CardStyle]; Now showing is an idle overlay. */
enum class WallpaperStyle(val title: String, val family: WallpaperFamily, val animated: Boolean) {
    MIDNIGHT("Midnight", WallpaperFamily.CLASSIC, animated = false),
    COVER_ART("Cover art", WallpaperFamily.CLASSIC, animated = false),
    YOUR_IMAGES("Your images", WallpaperFamily.CLASSIC, animated = false),
    ACCENT_GRADIENT("Accent gradient", WallpaperFamily.CLASSIC, animated = false),
    GLOW_SPIRITS("Glow spirits", WallpaperFamily.CREATIVE, animated = true),
    VIDEO_LOOP("Video loop", WallpaperFamily.CREATIVE, animated = true),
    AMBIENT_LIGHT("Ambient light", WallpaperFamily.CREATIVE, animated = true),
    POSTER_WALL("Poster wall", WallpaperFamily.CREATIVE, animated = true),
    LIVING_SKY("Living sky", WallpaperFamily.FRONTIER, animated = true),
}

/** How cards without art sit on the wallpaper (L4). */
enum class CardStyle { SOLID, GLASS }

enum class ImageFit { FILL, FIT }

/**
 * Settings › Wallpaper. Every style's options live here, so styles never add fields of
 * their own; a style ignores the ones it doesn't use.
 */
data class WallpaperPrefs(
    val style: WallpaperStyle = WallpaperStyle.MIDNIGHT,
    /** A black scrim over the wallpaper, 0 to [MAX_DIM]. */
    val dim: Float = 0f,
    /** Cover art: 0 is sharp, 1 the strongest blur. */
    val blur: Float = 0.6f,
    /** Your images: the operator's own pictures start sharp. */
    val imageBlur: Float = 0f,
    /** Animated styles: 0 is the slowest, 1 the fastest. */
    val speed: Float = 0.5f,
    /** Your images and Video loop read this folder, relative to shared storage (L2). */
    val folder: String = SharedFolders.WALLPAPERS,
    /** Your images: one file in [folder]; null shows them all as a slideshow. */
    val image: String? = null,
    /** Video loop: one file in [folder]; null plays the first. */
    val video: String? = null,
    val slideshowMinutes: Int = 5,
    val fit: ImageFit = ImageFit.FILL,
    /** Your images: a slow pan and zoom. */
    val slowPan: Boolean = true,
    val cardStyle: CardStyle = CardStyle.SOLID,
    /** Now showing: after [nowShowingMinutes] without a key press, the pick fills the screen. */
    val nowShowing: Boolean = false,
    val nowShowingMinutes: Int = 5,
) {
    companion object {
        const val MAX_DIM = 0.8f
    }
}

/** Wallpaper settings have their own DataStore file (D10), so nothing else can corrupt them. */
class WallpaperRepository(private val store: DataStore<Preferences>) {
    val prefs: Flow<WallpaperPrefs> = store.data.map { it.toPrefs() }

    /** Applies [change] to the current settings and saves every field. */
    suspend fun update(change: (WallpaperPrefs) -> WallpaperPrefs) = store.edit { stored ->
        val next = change(stored.toPrefs())
        stored[STYLE] = next.style.name
        stored[DIM] = next.dim.coerceIn(0f, WallpaperPrefs.MAX_DIM)
        stored[BLUR] = next.blur.coerceIn(0f, 1f)
        stored[IMAGE_BLUR] = next.imageBlur.coerceIn(0f, 1f)
        stored[SPEED] = next.speed.coerceIn(0f, 1f)
        stored[FOLDER] = next.folder
        stored.setOrRemove(IMAGE, next.image)
        stored.setOrRemove(VIDEO, next.video)
        stored[SLIDESHOW] = next.slideshowMinutes.coerceAtLeast(1)
        stored[FIT] = next.fit.name
        stored[SLOW_PAN] = next.slowPan
        stored[CARD_STYLE] = next.cardStyle.name
        stored[NOW_SHOWING] = next.nowShowing
        stored[NOW_SHOWING_MINUTES] = next.nowShowingMinutes.coerceAtLeast(1)
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.setOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else this[key] = value
    }

    private fun Preferences.toPrefs(): WallpaperPrefs {
        val defaults = WallpaperPrefs()
        return WallpaperPrefs(
            style = enumOr(this[STYLE], defaults.style),
            dim = this[DIM] ?: defaults.dim,
            blur = this[BLUR] ?: defaults.blur,
            imageBlur = this[IMAGE_BLUR] ?: defaults.imageBlur,
            speed = this[SPEED] ?: defaults.speed,
            folder = this[FOLDER] ?: defaults.folder,
            image = this[IMAGE],
            video = this[VIDEO],
            slideshowMinutes = this[SLIDESHOW] ?: defaults.slideshowMinutes,
            fit = enumOr(this[FIT], defaults.fit),
            slowPan = this[SLOW_PAN] ?: defaults.slowPan,
            cardStyle = enumOr(this[CARD_STYLE], defaults.cardStyle),
            nowShowing = this[NOW_SHOWING] ?: defaults.nowShowing,
            nowShowingMinutes = this[NOW_SHOWING_MINUTES] ?: defaults.nowShowingMinutes,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        val STYLE = stringPreferencesKey("style")
        val DIM = floatPreferencesKey("dim")
        val BLUR = floatPreferencesKey("blur")
        val IMAGE_BLUR = floatPreferencesKey("image_blur")
        val SPEED = floatPreferencesKey("speed")
        val FOLDER = stringPreferencesKey("folder")
        val IMAGE = stringPreferencesKey("image")
        val VIDEO = stringPreferencesKey("video")
        val SLIDESHOW = intPreferencesKey("slideshow_minutes")
        val FIT = stringPreferencesKey("fit")
        val SLOW_PAN = booleanPreferencesKey("slow_pan")
        val CARD_STYLE = stringPreferencesKey("card_style")
        val NOW_SHOWING = booleanPreferencesKey("now_showing")
        val NOW_SHOWING_MINUTES = intPreferencesKey("now_showing_minutes")
    }
}

/** What Settings › Wallpaper reads and changes. [None] for previews and screenshots. */
interface WallpaperController {
    val prefs: StateFlow<WallpaperPrefs>

    fun update(change: (WallpaperPrefs) -> WallpaperPrefs) {}

    /** Folders with images or videos, for the folder chooser; empty without the storage grant. */
    fun folders(): List<String> = emptyList()

    /** False until `READ_EXTERNAL_STORAGE` is granted, so Settings can say how. */
    fun canReadFolders(): Boolean = false

    companion object None : WallpaperController {
        override val prefs: StateFlow<WallpaperPrefs> = MutableStateFlow(WallpaperPrefs())
    }
}

class StoredWallpaperController(
    private val repository: WallpaperRepository,
    private val folders: SharedFolders,
    private val scope: CoroutineScope,
) : WallpaperController {
    override val prefs: StateFlow<WallpaperPrefs> = repository.prefs.stateIn(scope, SharingStarted.Eagerly, WallpaperPrefs())

    override fun update(change: (WallpaperPrefs) -> WallpaperPrefs) {
        scope.launch { repository.update(change) }
    }

    override fun folders() = folders.mediaFolders()

    override fun canReadFolders() = folders.canRead()
}

/**
 * What wallpaper styles read besides their settings: the operator's folders and the
 * Plex cache. [None] for previews and screenshots.
 */
interface WallpaperSources {
    /** Poster art paths (Plex `thumb`) from the cache, for Poster wall. */
    suspend fun posters(limit: Int): List<String> = emptyList()

    /** Files of [kind] in [folder] (Your images, Video loop); empty without the storage grant. */
    fun files(folder: String, kind: MediaKind): List<File> = emptyList()

    companion object None : WallpaperSources
}

/** @param posters the Plex cache's random poster paths (`PlexDao.randomPosters`). */
class AndroidWallpaperSources(
    private val folders: SharedFolders,
    private val posters: suspend (limit: Int) -> List<String> = { emptyList() },
) : WallpaperSources {
    override suspend fun posters(limit: Int) = posters.invoke(limit)

    override fun files(folder: String, kind: MediaKind) = folders.files(folder, kind)
}
