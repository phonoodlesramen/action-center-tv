package com.phonoodlesramen.actioncenter.nfl

import com.phonoodlesramen.actioncenter.safety.summary
import com.phonoodlesramen.actioncenter.station.NflReply
import com.phonoodlesramen.actioncenter.station.NflStation
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The two sizes a logo is kept at, as the station serves them: `s` for rows and tiles, `l` for details, team pages and Settings' grid. */
enum class LogoSize(val key: String, val px: Int) {
    SMALL("s", 64),
    LARGE("l", 192),
    ;

    companion object {
        /** The smallest that covers [px] on screen, so it's never decoded larger than it's drawn. */
        fun forPx(px: Int): LogoSize = if (px <= SMALL.px) SMALL else LARGE
    }
}

/** Where team logos come from for drawing (Phase 13). The default has none: the stand-in is drawn. */
fun interface NflLogoFiles {
    /** [abbr]'s logo at [size], as a file to decode; null while it can't be had. */
    suspend fun file(abbr: String, size: LogoSize): File?

    companion object {
        val None = NflLogoFiles { _, _ -> null }
    }
}

/**
 * Team logos in the app's cache directory, each fetched once: paired, from the guide station
 * (`/v1/nfl/logos`, mutual TLS); unpaired, through ESPN's resizer ([EspnClient.logo]). A fetch that
 * fails isn't tried again for 10 minutes, so a missing logo never hammers anyone; the stand-in
 * (a circle in the team's colour) shows meanwhile.
 */
class NflLogos(
    private val dir: File,
    private val station: NflStation,
    private val espn: EspnClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
) : NflLogoFiles {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    override suspend fun file(abbr: String, size: LogoSize): File? {
        if (!TEAM.matches(abbr)) return null
        val file = File(dir, "$abbr-${size.key}.png")
        if (withContext(io) { file.isFile }) return file
        return locks.computeIfAbsent(file.name) { Mutex() }.withLock {
            if (withContext(io) { file.isFile }) return@withLock file
            if (failedAt[file.name]?.let { clock() - it < RETRY_MILLIS } == true) return@withLock null
            val bytes = fetch(abbr, size)
            if (bytes == null) {
                failedAt[file.name] = clock()
                return@withLock null
            }
            withContext(io) {
                dir.mkdirs()
                val part = File(dir, "${file.name}.part")
                part.writeBytes(bytes)
                if (!part.renameTo(file)) part.delete()
            }
            file.takeIf { it.isFile }
        }
    }

    private suspend fun fetch(abbr: String, size: LogoSize): ByteArray? = try {
        if (station.paired.first()) {
            (station.nflLogo(abbr, size.key) as? NflReply.Answered)?.value
        } else {
            espn.logo(abbr, size.px)
        }?.takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("nfl: no logo for $abbr: ${e.summary()}")
        null
    }

    private companion object {
        const val RETRY_MILLIS = 10 * 60_000L
        val TEAM = Regex("[A-Z]{2,3}")
    }
}
