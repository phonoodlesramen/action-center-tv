package com.phonoodlesramen.actioncenter.nfl

import com.phonoodlesramen.actioncenter.safety.summary
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A photo of the city a team plays for (ui-spec "Two cities"), from Wikimedia Commons: public
 * domain, CC0 or CC BY, which ask for no more than a credit (docs/third-party-notices.md). Never
 * share-alike: the app crops and tints them.
 */
data class CityPhoto(
    /** What it shows, for the credits: "Boathouse Row, Philadelphia". */
    val place: String,
    /** The file's name on Commons, without "File:". */
    val file: String,
    /** Who took it: "Carol M. Highsmith". */
    val author: String,
    /** "Public domain", "CC0" or "CC BY 4.0" (any CC BY version). */
    val licence: String,
    /** Where the picture's interest is, from its left (0) to its right (1): kept in view when a side is cropped. */
    val focusX: Float = 0.5f,
    /** The same from its top (0) to its bottom (1). */
    val focusY: Float = 0.5f,
)

/** The city photos and where to fetch them. */
object NflCities {
    /** One photo per team, by ESPN's abbreviation. A team without one has its colours, as before. */
    val photos: Map<String, CityPhoto> = mapOf()

    /** Wide enough for half the screen at 1080p with room to crop; one of Commons' usual sizes. */
    const val WIDTH_PX = 1280

    private val FILE_PATH = "https://commons.wikimedia.org/wiki/Special:FilePath/".toHttpUrl()

    /** [photo] at [widthPx] wide: Commons redirects to its thumbnail on `upload.wikimedia.org`. */
    fun url(photo: CityPhoto, widthPx: Int = WIDTH_PX): HttpUrl =
        FILE_PATH.newBuilder().addPathSegment(photo.file).addQueryParameter("width", widthPx.toString()).build()
}

/** Where city photos come from for drawing. The default has none: the teams' colours are drawn. */
fun interface NflCityFiles {
    /** [abbr]'s city photo, as a file to decode; null while it can't be had. */
    suspend fun file(abbr: String): File?

    companion object {
        val None = NflCityFiles { null }
    }
}

/**
 * City photos in the app's cache directory, each fetched once from Wikimedia Commons, paired or
 * not (the station has nothing to do with them). A fetch that fails isn't tried again for 10
 * minutes, so a missing photo never hammers anyone; the teams' colours show meanwhile. The file's
 * name carries the photo's, so a new choice of photo in an update replaces the old one.
 */
class NflCityPhotos(
    private val dir: File,
    private val http: OkHttpClient,
    private val photos: Map<String, CityPhoto> = NflCities.photos,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
    private val urlOf: (CityPhoto) -> HttpUrl = { NflCities.url(it) },
) : NflCityFiles {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    override suspend fun file(abbr: String): File? {
        val photo = photos[abbr] ?: return null
        val file = File(dir, "$abbr-${Integer.toHexString(photo.file.hashCode())}.jpg")
        if (withContext(io) { file.isFile }) return file
        return locks.computeIfAbsent(abbr) { Mutex() }.withLock {
            if (withContext(io) { file.isFile }) return@withLock file
            if (failedAt[abbr]?.let { clock() - it < RETRY_MILLIS } == true) return@withLock null
            val bytes = fetch(abbr, photo)
            if (bytes == null) {
                failedAt[abbr] = clock()
                return@withLock null
            }
            withContext(io) {
                dir.mkdirs()
                // The team's last photo, from before an update chose another.
                dir.listFiles { old -> old.name.startsWith("$abbr-") && old.name != file.name }?.forEach { it.delete() }
                val part = File(dir, "${file.name}.part")
                part.writeBytes(bytes)
                if (!part.renameTo(file)) part.delete()
            }
            file.takeIf { it.isFile }
        }
    }

    private suspend fun fetch(abbr: String, photo: CityPhoto): ByteArray? = try {
        withContext(io) {
            // Wikimedia asks every client to say what it is and where to find it.
            val request = Request.Builder().url(urlOf(photo)).header("User-Agent", USER_AGENT).header("Accept", "image/*").build()
            http.newCall(request).execute().use { response ->
                val type = response.header("Content-Type").orEmpty()
                when {
                    !response.isSuccessful -> throw IOException("Commons answered ${response.code}")
                    !type.startsWith("image/") -> throw IOException("Commons sent $type, not a picture")
                    else -> response.body.bytes().takeIf { it.isNotEmpty() }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("nfl: no city photo for $abbr: ${e.summary()}")
        null
    }

    companion object {
        private const val RETRY_MILLIS = 10 * 60_000L
        const val USER_AGENT = "ActionCenter (https://github.com/phonoodlesramen/action-center-tv)"
    }
}

/**
 * Two cities meet where the teams do (ui-spec "Two cities"): before kickoff they fade into each
 * other; once it's on, a zig-zag (the clash). Once it's over, the loser's city goes dark, as a
 * final's loser is faint on its tile; a tie, or a hidden score, leaves both lit.
 */
object Clash {
    enum class Seam { FADE, ZIGZAG }

    fun seam(game: NflGame): Seam = if (game.state == GameState.PRE) Seam.FADE else Seam.ZIGZAG

    /** The team whose city goes dark: the loser of a final shown; else nobody. */
    fun dimmed(game: NflGame, hidden: Boolean): String? = when {
        hidden || game.state != GameState.FINAL -> null
        game.winner == game.away -> game.home
        game.winner == game.home -> game.away
        else -> null
    }
}
