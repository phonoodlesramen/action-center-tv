package com.phonoodlesramen.actioncenter.media

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.phonoodlesramen.actioncenter.setup.SetupPermission
import java.io.File

/** What a folder can hold for the wallpaper (L2) and the custom app banners (L7). */
enum class MediaKind(val extensions: Set<String>) {
    IMAGE(setOf("jpg", "jpeg", "png", "webp", "gif")),
    VIDEO(setOf("mp4", "mkv", "webm", "mov")),
    ;

    fun matches(file: File) = file.isFile && file.extension.lowercase() in extensions
}

/**
 * The operator's images and videos in the Shield's shared storage. On Android 11,
 * `READ_EXTERNAL_STORAGE` (granted once over ADB) gives read-only access to media files
 * by path; without it every list is empty and nothing throws.
 *
 * @param root shared storage, `/sdcard` on the Shield.
 */
class SharedFolders(private val root: File, private val permitted: () -> Boolean) {
    fun canRead(): Boolean = permitted()

    /** [folder] relative to shared storage (e.g. [WALLPAPERS]), or an absolute path. */
    fun resolve(folder: String): File = File(folder).takeIf { it.isAbsolute } ?: File(root, folder)

    /** Files of [kind] in [folder], by name. With [recursive], its subfolders' too (the Icons folder has "Flat icons"). */
    fun files(folder: String, kind: MediaKind, recursive: Boolean = false): List<File> {
        if (!canRead()) return emptyList()
        val dir = resolve(folder)
        val found = if (recursive) dir.walkTopDown().maxDepth(MAX_DEPTH).filter(kind::matches).toList()
        else dir.listFiles()?.filter(kind::matches).orEmpty()
        return found.sortedBy { it.path.lowercase() }
    }

    /**
     * Folders that hold images or videos, under Pictures, Movies and Download, for the
     * folder chooser. Paths are relative to shared storage.
     */
    fun mediaFolders(): List<String> {
        if (!canRead()) return emptyList()
        return CHOOSER_ROOTS.flatMap { top ->
            File(root, top).walkTopDown().maxDepth(MAX_DEPTH).filter { it.isDirectory }.filter { dir ->
                dir.listFiles()?.any { file -> MediaKind.entries.any { it.matches(file) } } == true
            }.map { it.relativeTo(root).path.replace(File.separatorChar, '/') }.toList()
        }.sortedBy { it.lowercase() }
    }

    companion object {
        /** The operator's wallpapers: images, videos, and a "Wallpaper images" subfolder (L2). */
        const val WALLPAPERS = "Pictures/Wallpapers"

        /** The operator's 16:9 app banners, named after the apps (L7). */
        const val ICONS = "Pictures/Icons"

        private val CHOOSER_ROOTS = listOf("Pictures", "Movies", "Download")
        private const val MAX_DEPTH = 3

        /**
         * On the TV's shared storage, while "Your pictures" is allowed (Settings › Setup): the
         * storage permission up to Android 12, images and video from Android 13.
         */
        @Suppress("DEPRECATION") // The path is still /sdcard; access goes through the permission.
        fun android(context: Context) = SharedFolders(Environment.getExternalStorageDirectory()) {
            SetupPermission.PICTURES.permissions(Build.VERSION.SDK_INT)
                .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        }
    }
}
