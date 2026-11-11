package com.phonoodlesramen.actioncenter.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where updates come from: a check for the latest release, and its APK. */
interface UpdateFeed {
    /** The latest full release's `update.json`. Never throws but to cancel. */
    suspend fun latest(): ReleaseFile

    /**
     * Downloads [info]'s APK to [dest], hashing it on the way, so it never sits in memory. Null
     * when it's whole and matches [UpdateInfo.size] and [UpdateInfo.sha256]; otherwise why not,
     * and [dest] is gone. [progress] gets whole percents. Never throws but to cancel.
     */
    suspend fun download(info: UpdateInfo, dest: File, progress: (Int) -> Unit): String?
}

/**
 * [UpdateFeed] from a repository's GitHub releases ([UpdateConfig]). It asks for files, not
 * GitHub's API: `releases/latest/download/update.json` redirects to the newest full release's
 * file, with no token and no rate limit to share between a house's TVs.
 */
class GitHubReleases(
    private val http: OkHttpClient,
    private val config: UpdateConfig,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : UpdateFeed {
    override suspend fun latest(): ReleaseFile = withContext(io) {
        try {
            http.newCall(Request.Builder().url(config.latestUrl).build()).execute().use { response ->
                when {
                    response.code == NOT_FOUND -> ReleaseFile.None
                    !response.isSuccessful -> ReleaseFile.Failed("GitHub answered ${response.code}")
                    else -> UpdateInfo.parse(response.peekBody(MAX_INFO_BYTES).string())
                }
            }
        } catch (e: IOException) {
            ReleaseFile.Failed(couldNotReach(e))
        } catch (e: IllegalArgumentException) {
            ReleaseFile.Failed("The update address isn't one: ${e.message}")
        }
    }

    override suspend fun download(info: UpdateInfo, dest: File, progress: (Int) -> Unit): String? = withContext(io) {
        val part = File(dest.parentFile, "${dest.name}.part")
        val why = try {
            dest.parentFile?.mkdirs()
            http.newCall(Request.Builder().url(config.apkUrl(info)).build()).execute().use { response ->
                if (response.isSuccessful) {
                    response.body.byteStream().use { copy(it, part, info, progress) } ?: keep(part, dest)
                } else {
                    "GitHub answered ${response.code}"
                }
            }
        } catch (e: IOException) {
            couldNotReach(e)
        } catch (e: IllegalArgumentException) {
            "The update address isn't one: ${e.message}"
        }
        part.delete()
        if (why != null) dest.delete()
        why
    }

    /** Copies [input] to [part], hashing it: null when it's [info]'s APK, else why not. */
    private suspend fun copy(input: InputStream, part: File, info: UpdateInfo, progress: (Int) -> Unit): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        var read = 0L
        var shown = -1
        part.outputStream().use { output ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                read += count
                if (read > info.size) return "The download is bigger than the release says"
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
                val percent = (read * PERCENT / info.size).toInt()
                if (percent != shown) {
                    shown = percent
                    progress(percent)
                }
            }
        }
        return when {
            read != info.size -> "The download stopped short"
            digest.digest().toHex() != info.sha256 -> "The download didn't match the release's SHA-256"
            else -> null
        }
    }

    private fun keep(part: File, dest: File): String? = if (part.renameTo(dest)) null else "Couldn't keep the download"

    private fun couldNotReach(e: IOException) = "Couldn't reach GitHub" + (e.message?.let { " ($it)" } ?: "")

    private companion object {
        const val NOT_FOUND = 404
        const val PERCENT = 100

        /** `update.json` is a few hundred bytes; anything far bigger isn't one. */
        const val MAX_INFO_BYTES = 64L * 1024
        const val BUFFER = 64 * 1024
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
