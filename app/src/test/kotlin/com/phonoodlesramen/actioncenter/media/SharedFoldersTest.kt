package com.phonoodlesramen.actioncenter.media

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fake shared storage laid out like the operator's Shield (Pictures/Wallpapers, Pictures/Icons). */
class SharedFoldersTest {
    private val root = Files.createTempDirectory("sdcard").toFile().apply {
        file("Pictures/Wallpapers/loop.mp4")
        file("Pictures/Wallpapers/notes.txt")
        file("Pictures/Wallpapers/Wallpaper images/vortex.JPG")
        file("Pictures/Wallpapers/Wallpaper images/spheres.png")
        file("Pictures/Icons/Youtube TV.png")
        file("Pictures/Icons/Flat icons/DISNEY+.png")
        file("Download/app.apk")
        File(this, "Movies").mkdirs()
    }

    private fun File.file(path: String) = File(this, path).apply { parentFile!!.mkdirs(); writeText("x") }

    private val folders = SharedFolders(root) { true }

    @Test
    fun `lists one kind of file in a folder, by name, case-insensitive about extensions`() {
        assertEquals(listOf("loop.mp4"), folders.files(SharedFolders.WALLPAPERS, MediaKind.VIDEO).map { it.name })
        assertEquals(
            listOf("spheres.png", "vortex.JPG"),
            folders.files("${SharedFolders.WALLPAPERS}/Wallpaper images", MediaKind.IMAGE).map { it.name },
        )
    }

    @Test
    fun `recursive takes in subfolders, for the Icons folder's Flat icons`() {
        assertEquals(listOf("DISNEY+.png", "Youtube TV.png"), folders.files(SharedFolders.ICONS, MediaKind.IMAGE, recursive = true).map { it.name })
        assertEquals(listOf("Youtube TV.png"), folders.files(SharedFolders.ICONS, MediaKind.IMAGE).map { it.name })
    }

    @Test
    fun `the folder chooser offers only folders with images or videos`() {
        assertEquals(
            listOf("Pictures/Icons", "Pictures/Icons/Flat icons", "Pictures/Wallpapers", "Pictures/Wallpapers/Wallpaper images"),
            folders.mediaFolders(),
        )
    }

    @Test
    fun `without the storage grant everything is empty, and nothing throws`() {
        val locked = SharedFolders(root) { false }
        assertTrue(locked.files(SharedFolders.WALLPAPERS, MediaKind.VIDEO).isEmpty())
        assertTrue(locked.mediaFolders().isEmpty())
    }

    @Test
    fun `a missing folder is empty too`() {
        assertTrue(folders.files("Pictures/Nope", MediaKind.IMAGE).isEmpty())
    }
}
