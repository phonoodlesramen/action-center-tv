package com.phonoodlesramen.actioncenter.ui.wallpaper.motion

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Video loop's file choice and player lifecycle; ExoPlayer itself can't play under Robolectric. */
class VideoLoopTest {
    private val folder = File("/sdcard/Pictures/Wallpapers")
    private val loops = listOf(File(folder, "Rain on glass.mp4"), File(folder, "Slow clouds.mp4"))

    @Test
    fun `the chosen video if it's still there, else the folder's first`() {
        assertEquals(loops[1], VideoLoopFiles.choose("Slow clouds.mp4", loops))
        assertEquals(loops[0], VideoLoopFiles.choose(null, loops))
        assertEquals(loops[0], VideoLoopFiles.choose("deleted.mp4", loops))
    }

    @Test
    fun `no videos, or no storage grant, means no video`() {
        assertNull(VideoLoopFiles.choose(null, emptyList()))
        assertNull(VideoLoopFiles.choose("Slow clouds.mp4", emptyList()))
    }

    private class FakePlayer(val file: File) : LoopPlayer<String> {
        var surface: String? = null
        var released = false

        override fun attach(surface: String?) {
            this.surface = surface
        }

        override fun release() {
            released = true
        }
    }

    private var now = 0L
    private val opened = mutableListOf<FakePlayer>()
    private val session = VideoLoopSession(open = { file -> FakePlayer(file).also { opened += it } }, clock = { now })

    @Test
    fun `a player exists only between start and stop`() {
        session.file = loops[0]
        assertTrue("Nothing plays before ON_START", opened.isEmpty())

        session.start()
        session.start()
        assertEquals(1, opened.size)
        assertEquals(loops[0], opened.single().file)

        session.stop()
        assertTrue(opened.single().released)
        assertNull(session.player)

        session.start()
        assertEquals(2, opened.size)
    }

    @Test
    fun `no file, no player`() {
        session.start()

        assertNull(session.player)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a new file swaps the player while started, and waits while stopped`() {
        session.file = loops[0]
        session.start()

        session.file = loops[1]
        assertTrue(opened[0].released)
        assertEquals(loops[1], (session.player as FakePlayer).file)

        session.stop()
        session.file = loops[0]
        assertEquals(2, opened.size)
    }

    @Test
    fun `the player moves to the surface whenever either arrives, and leaves it on release`() {
        session.file = loops[0]
        session.start()
        session.surface = "surface 1"
        assertEquals("surface 1", opened[0].surface)

        session.stop()
        assertNull(opened[0].surface)

        session.start()
        assertEquals("A new player goes straight to the surface", "surface 1", opened[1].surface)
    }

    @Test
    fun `an error starts the loop again, but three quick ones in a row stop it until the next start`() {
        session.file = loops[0]
        session.start()

        assertTrue(session.failed())
        now += 5_000
        assertTrue(session.failed())
        now += 5_000
        assertFalse(session.failed())
        assertNull(session.player)
        assertEquals(3, opened.size)
        assertTrue(opened.all { it.released })

        session.stop()
        session.start()
        assertEquals(4, opened.size)
    }

    @Test
    fun `errors far apart keep looping (a file cut short fails at its end each time)`() {
        session.file = loops[1]
        session.start()

        repeat(5) {
            now += VideoLoopSession.RETRY_WINDOW_MILLIS
            assertTrue(session.failed())
        }
        assertSame(opened.last(), session.player)
    }
}
