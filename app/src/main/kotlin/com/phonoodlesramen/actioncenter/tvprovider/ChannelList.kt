package com.phonoodlesramen.actioncenter.tvprovider

import android.annotation.SuppressLint
import com.phonoodlesramen.actioncenter.media.MediaKind
import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One network in the operator's channel list (Q3): what the button says and what it opens. */
data class ChannelListEntry(
    val name: String,
    /** A `tv.youtube.com/watch/…` link, any other https link, or an `intent:` URI. */
    val link: String,
    /** A file in the Icons folder, by name; null matches [name]. */
    val banner: String? = null,
)

/** What reading `channels.json` found. */
sealed interface ChannelList {
    /** No file: the buttons come from YouTube TV's own row. */
    data object Missing : ChannelList

    /** A file that can't be used, and why, for Settings. */
    data class Problem(val reason: String) : ChannelList

    /**
     * @param skipped entries without a name or a usable link.
     * @param includeYouTubeTv keep YouTube TV's networks and add these to them, instead of
     *   replacing them.
     */
    data class Loaded(val entries: List<ChannelListEntry>, val skipped: Int = 0, val includeYouTubeTv: Boolean = false) : ChannelList
}

/**
 * Parses `channels.json`: a list of `{"name": …, "link": …, "banner": …}`, which replaces
 * YouTube TV's networks, or `{"includeYouTubeTv": true, "channels": [ … ]}`, which adds to
 * them. Entries without a name or a link that opens something are skipped and counted,
 * so one typo doesn't lose the whole list.
 */
object ChannelListParser {
    private val json = Json { isLenient = true }

    fun parse(text: String): ChannelList {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return ChannelList.Problem("it isn't valid JSON (${e.message.orEmpty().lineSequence().first().take(MAX_REASON)})")
        }
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> root["channels"] as? JsonArray
            else -> null
        } ?: return ChannelList.Problem("it should be a list of channels, like the example")
        val entries = items.mapNotNull(::entry)
        val include = ((root as? JsonObject)?.get("includeYouTubeTv") as? JsonPrimitive)?.booleanOrNull == true
        return ChannelList.Loaded(entries, skipped = items.size - entries.size, includeYouTubeTv = include)
    }

    private fun entry(item: JsonElement): ChannelListEntry? {
        val fields = item as? JsonObject ?: return null
        val name = fields.text("name") ?: return null
        val link = (fields.text("link") ?: fields.text("url"))?.takeIf(::opensSomething) ?: return null
        return ChannelListEntry(name, link, fields.text("banner"))
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }

    /** A web link (YouTube TV's app opens tv.youtube.com) or an Android intent URI. */
    private fun opensSomething(link: String): Boolean {
        val scheme = link.substringBefore(':', missingDelimiterValue = "").lowercase()
        return scheme in setOf("https", "http", "intent") && link.length > scheme.length + 3
    }

    private const val MAX_REASON = 120
}

/**
 * `channels.json` in the app's own folder on shared storage. Android 11's scoped storage
 * lets the app read that folder without any permission, and `adb push` can write to it,
 * while a JSON file elsewhere (`/sdcard/Documents`) isn't a media file, so
 * `READ_EXTERNAL_STORAGE` doesn't cover it.
 *
 * @param folder the app's external files folder; null when shared storage isn't mounted.
 */
@SuppressLint("SdCardPath") // Only ever shown to the operator, spelt the way adb takes it; files come from [folder].
class ChannelListFile(private val folder: () -> File?) {
    /** Where the operator copies the file to, for Settings: `/sdcard/…`, as adb spells it. */
    fun path(): String = File(folder() ?: File(DEFAULT_FOLDER), NAME).invariantSeparatorsPath.replaceFirst(EMULATED_STORAGE, "/sdcard")

    fun read(): ChannelList {
        val file = folder()?.let { File(it, NAME) } ?: return ChannelList.Missing
        if (!file.isFile) return ChannelList.Missing
        if (file.length() > MAX_BYTES) return ChannelList.Problem("it's over ${MAX_BYTES / 1024} KB")
        return try {
            ChannelListParser.parse(file.readText())
        } catch (_: IOException) {
            ChannelList.Problem("it couldn't be read")
        } catch (_: SecurityException) {
            ChannelList.Problem("it couldn't be read")
        }
    }

    companion object {
        const val NAME = "channels.json"

        /** `getExternalFilesDir(null)` on the Shield. */
        const val DEFAULT_FOLDER = "/sdcard/Android/data/com.phonoodlesramen.actioncenter/files"
        private const val MAX_BYTES = 256L * 1024
        private const val EMULATED_STORAGE = "/storage/emulated/0"
    }
}

/** Lower case, letters and digits only: "Fox Sports.png" and "FOX Sports" both become "foxsports". */
fun normalisedName(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

/**
 * Network banners from the Icons folder (L7, L8), by normalised name. A name matches its
 * banner exactly, or else starts with one ("FOX Sports 1" takes "Fox Sports.png"); the
 * folder's own banners win over its subfolders'.
 */
class NetworkBanners(files: List<File>) {
    private val byName: Map<String, File> = files
        .groupBy { normalisedName(it.nameWithoutExtension) }
        .filterKeys { it.isNotEmpty() }
        .mapValues { (_, same) -> same.minWith(compareBy({ it.path.count { c -> c == File.separatorChar } }, { it.path.lowercase() })) }

    /** [banner] (a file name, with or without its extension) first, then [name]. */
    fun find(name: String, banner: String? = null): File? {
        banner?.let { wanted -> byName[normalisedName(withoutImageExtension(wanted))]?.let { return it } }
        val key = normalisedName(name)
        if (key.isEmpty()) return null
        byName[key]?.let { return it }
        return byName.entries
            .filter { (prefix, _) -> prefix.length >= MIN_PREFIX && key.startsWith(prefix) }
            .maxByOrNull { (prefix, _) -> prefix.length }
            ?.value
    }

    private fun withoutImageExtension(file: String): String {
        val extension = file.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return if (extension in MediaKind.IMAGE.extensions) file.substringBeforeLast('.') else file
    }

    private companion object {
        const val MIN_PREFIX = 3
    }
}
