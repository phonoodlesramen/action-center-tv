package com.phonoodlesramen.actioncenter.tvprovider

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `channels.json` (Q3) and the network banners from the Icons folder (L8). */
class ChannelListTest {
    @Test
    fun `a plain list replaces YouTube TV's networks, in its own order`() {
        val list = ChannelListParser.parse(
            """
            [
              {"name": "ESPN", "link": "https://tv.youtube.com/watch/aaa", "banner": "ESPN"},
              {"name": " Channel 8 ", "link": "https://tv.youtube.com/watch/bbb"}
            ]
            """,
        )

        assertEquals(
            ChannelList.Loaded(
                listOf(
                    ChannelListEntry("ESPN", "https://tv.youtube.com/watch/aaa", "ESPN"),
                    ChannelListEntry("Channel 8", "https://tv.youtube.com/watch/bbb"),
                ),
            ),
            list,
        )
        assertFalse((list as ChannelList.Loaded).includeYouTubeTv)
    }

    @Test
    fun `the object form can add to YouTube TV's networks, and url works as well as link`() {
        val list = ChannelListParser.parse(
            """{"includeYouTubeTv": true, "channels": [{"name": "PBS", "url": "intent://tv.youtube.com/watch/ccc#Intent;scheme=https;end"}]}""",
        )

        assertEquals(
            ChannelList.Loaded(
                listOf(ChannelListEntry("PBS", "intent://tv.youtube.com/watch/ccc#Intent;scheme=https;end")),
                includeYouTubeTv = true,
            ),
            list,
        )
    }

    @Test
    fun `entries without a name or a link that opens something are skipped and counted`() {
        val list = ChannelListParser.parse(
            """
            [
              {"name": "ESPN", "link": "https://tv.youtube.com/watch/aaa"},
              {"name": "", "link": "https://tv.youtube.com/watch/bbb"},
              {"name": "No link"},
              {"name": "A file", "link": "file:///sdcard/x"},
              {"name": "Not a string", "link": 42},
              "just text"
            ]
            """,
        )

        assertEquals(ChannelList.Loaded(listOf(ChannelListEntry("ESPN", "https://tv.youtube.com/watch/aaa")), skipped = 5), list)
    }

    @Test
    fun `a file that isn't JSON, or isn't a list, says why`() {
        val broken = ChannelListParser.parse("""[{"name": "ESPN", "link": """)
        assertTrue(broken is ChannelList.Problem && broken.reason.startsWith("it isn't valid JSON"))

        assertEquals(ChannelList.Problem("it should be a list of channels, like the example"), ChannelListParser.parse("""{"name": "ESPN"}"""))
        assertEquals(ChannelList.Problem("it should be a list of channels, like the example"), ChannelListParser.parse("\"ESPN\""))
    }

    @Test
    fun `the file is read from the app's folder, and a missing folder or file is just missing`() {
        val folder = Files.createTempDirectory("files").toFile()
        val file = ChannelListFile { folder }
        assertEquals(ChannelList.Missing, file.read())
        assertEquals(ChannelList.Missing, ChannelListFile { null }.read())

        File(folder, ChannelListFile.NAME).writeText("""[{"name": "ESPN", "link": "https://tv.youtube.com/watch/aaa"}]""")
        assertEquals(ChannelList.Loaded(listOf(ChannelListEntry("ESPN", "https://tv.youtube.com/watch/aaa"))), file.read())

        File(folder, ChannelListFile.NAME).writeText("x".repeat(300 * 1024))
        assertEquals(ChannelList.Problem("it's over 256 KB"), file.read())
    }

    @Test
    fun `Settings shows the path as adb spells it`() {
        val shield = ChannelListFile { File("/storage/emulated/0/Android/data/com.phonoodlesramen.actioncenter/files") }
        assertEquals("/sdcard/Android/data/com.phonoodlesramen.actioncenter/files/channels.json", shield.path())
        assertEquals("/sdcard/Android/data/com.phonoodlesramen.actioncenter/files/channels.json", ChannelListFile { null }.path())
    }

    @Test
    fun `names are compared in lower case, letters and digits only`() {
        assertEquals("foxsports", normalisedName("Fox Sports.png".substringBeforeLast('.')))
        assertEquals("foxsports1", normalisedName("FOX Sports 1"))
        assertEquals("disney", normalisedName("Disney+"))
    }

    private val icons = Files.createTempDirectory("Icons").toFile().apply {
        listOf("ESPN.png", "Fox Sports.png", "PBS.png", "PBS Kids.png", "FX.png", "Flat icons/ESPN.png", "Flat icons/NETFLIX.png").forEach {
            File(this, it).apply { parentFile!!.mkdirs(); writeText("x") }
        }
    }
    private val banners = NetworkBanners(icons.walkTopDown().filter { it.isFile }.toList())

    @Test
    fun `a network finds its banner by exact name first, preferring the folder's own over Flat icons`() {
        assertEquals(File(icons, "ESPN.png"), banners.find("ESPN"))
        assertEquals(File(icons, "PBS Kids.png"), banners.find("PBS KIDS"))
        assertEquals(File(icons, "Flat icons/NETFLIX.png"), banners.find("Netflix"))
    }

    @Test
    fun `failing that, the longest banner name it starts with, of three letters or more`() {
        assertEquals(File(icons, "Fox Sports.png"), banners.find("FOX Sports 1"))
        assertEquals(File(icons, "ESPN.png"), banners.find("ESPN2"))
        assertEquals(File(icons, "PBS.png"), banners.find("PBS SoCal"))
        assertNull("FX is too short to match as a prefix", banners.find("FXX"))
        assertNull(banners.find("Channel 8"))
        assertNull(banners.find("!!!"))
    }

    @Test
    fun `the list's banner name wins, with or without its extension`() {
        assertEquals(File(icons, "Fox Sports.png"), banners.find("FS1", banner = "Fox Sports"))
        assertEquals(File(icons, "PBS.png"), banners.find("WETA", banner = "PBS.png"))
        assertEquals("an unknown banner falls back to the name", File(icons, "ESPN.png"), banners.find("ESPN", banner = "Nope"))
    }
}
