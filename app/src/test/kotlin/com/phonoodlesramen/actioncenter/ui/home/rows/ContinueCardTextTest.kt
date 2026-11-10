package com.phonoodlesramen.actioncenter.ui.home.rows

import com.phonoodlesramen.actioncenter.tvprovider.TvProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The caption and progress bar of a Continue in your apps card. */
class ContinueCardTextTest {
    private fun program(
        season: String? = null,
        episode: String? = null,
        episodeTitle: String? = null,
        duration: Long? = null,
        position: Long? = null,
    ) = TvProgram(1, TvProgram.Kind.WATCH_NEXT, "com.disney.disneyplus", "A show", episodeTitle = episodeTitle, season = season,
        episode = episode, durationMillis = duration, positionMillis = position)

    @Test
    fun `episodes read S2E26, then the episode's title`() {
        assertEquals("S2E26 · The Finale", episodeLine(program(season = "2", episode = "26", episodeTitle = "The Finale")))
        assertEquals("S2E26", episodeLine(program(season = "2", episode = "26")))
        assertEquals("E5 · Pilot", episodeLine(program(episode = "5", episodeTitle = "Pilot")))
        assertEquals("The Science of Sleep", episodeLine(program(episodeTitle = "The Science of Sleep")))
    }

    @Test
    fun `numbers an app wrote out in words are kept as they are, and blanks are dropped`() {
        assertEquals("Special · Behind the scenes", episodeLine(program(season = "Special", episodeTitle = "Behind the scenes")))
        assertNull(episodeLine(program(season = " ", episodeTitle = "")))
        assertNull(episodeLine(program()))
    }

    @Test
    fun `progress is position over duration, and only when the app gave both`() {
        assertEquals(0.25f, progress(program(duration = 400_000, position = 100_000)))
        assertEquals(1f, progress(program(duration = 400_000, position = 900_000)))
        assertNull(progress(program(duration = 400_000, position = 0)))
        assertNull(progress(program(duration = 0, position = 100)))
        assertNull(progress(program(position = 100)))
    }
}
