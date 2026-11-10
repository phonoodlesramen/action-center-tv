package com.phonoodlesramen.actioncenter.system

import android.content.Context
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.net.Uri
import com.phonoodlesramen.actioncenter.safety.summary

sealed interface WatchNextResult {
    data object NotGranted : WatchNextResult
    data class Failed(val reason: String) : WatchNextResult

    /** Rows per package, most first. */
    data class Counts(val watchNext: Map<String, Int>, val preview: Map<String, Int>) : WatchNextResult
}

/** Reads the package name of every row other apps published to the TV provider. */
interface TvProgramsReader {
    fun canRead(): Boolean
    fun watchNextPackages(): List<String>
    fun previewPackages(): List<String>
}

/**
 * Which apps' Watch Next and preview rows we can read (research §3). This decides
 * whether Up Next can include apps other than Plex in Phase 3.
 */
class WatchNextProbe(private val reader: TvProgramsReader) {
    fun probe(): WatchNextResult {
        if (!reader.canRead()) return WatchNextResult.NotGranted
        return try {
            WatchNextResult.Counts(
                watchNext = countByPackage(reader.watchNextPackages()),
                preview = countByPackage(reader.previewPackages()),
            )
        } catch (e: Exception) {
            WatchNextResult.Failed(e.summary())
        }
    }

    private fun countByPackage(packages: List<String>): Map<String, Int> =
        packages.groupingBy { it }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .associate { it.key to it.value }
}

class AndroidTvProgramsReader(private val context: Context) : TvProgramsReader {
    // Granted over ADB by scripts/grant-permissions.ps1 (`pm grant`).
    override fun canRead() =
        context.checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

    override fun watchNextPackages() = packagesIn(TvContract.WatchNextPrograms.CONTENT_URI)

    override fun previewPackages() = packagesIn(TvContract.PreviewPrograms.CONTENT_URI)

    private fun packagesIn(uri: Uri): List<String> {
        val column = TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME
        return context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndexOrThrow(column)
            buildList { while (cursor.moveToNext()) add(cursor.getString(index).orEmpty()) }
        }.orEmpty()
    }

    private companion object {
        const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
    }
}
