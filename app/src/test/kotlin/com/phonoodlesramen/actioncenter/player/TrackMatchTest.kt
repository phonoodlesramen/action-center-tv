package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.plex.PlexStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G13's matching: Plex's streams to Media3's tracks by kind and file order, languages
 * checked. Made-up files; Plex's codes are ISO 639-2/B ("ger"), Media3's are normalised
 * ("de"), as each really sends them.
 */
class TrackMatchTest {
    private val audio = PlexStream.AUDIO
    private val subtitles = PlexStream.SUBTITLES

    /** A file's audio or text tracks, one per Media3 group, in file order. */
    private fun tracks(vararg languages: String?, selected: Int = -1, ids: List<String?>? = null) =
        languages.mapIndexed { i, language -> FileTrack(group = i + 1, track = 0, language = language, id = ids?.get(i) ?: "${i + 2}", selected = i == selected) }

    @Test
    fun `audio matches in file order when the counts and languages agree`() {
        val streams = listOf(
            stream("11", audio, index = 2, language = "ger", title = "Deutsch (AC3 5.1)"),
            stream("10", audio, index = 1, language = "eng", title = "English (TrueHD 7.1)", selected = true),
        )

        val match = TrackMatch.audio(streams, tracks("en", "de", selected = 0))!!

        assertEquals(listOf("10" to 1, "11" to 2), match.pairs.map { (s, t) -> s.id to t.group })
        assertEquals(1, match.plexChoice?.group)
        assertEquals(
            listOf(TrackOption("10", "English (TrueHD 7.1)", selected = true), TrackOption("11", "Deutsch (AC3 5.1)", selected = false)),
            match.options(),
        )
    }

    @Test
    fun `a different count or a language that disagrees isn't a clear match`() {
        val streams = listOf(stream("10", audio, 1, "eng"), stream("11", audio, 2, "fre"))

        assertNull(TrackMatch.audio(streams, tracks("en")))
        assertNull(TrackMatch.audio(streams, tracks("fr", "en")))
        // A track that doesn't say its language agrees with anything.
        assertTrue(TrackMatch.audio(streams, tracks("en", null)) != null)
        assertTrue(TrackMatch.audio(streams, tracks("und", "fr-FR")) != null)
    }

    @Test
    fun `with nothing selected by Plex, Media3 keeps its own choice`() {
        val match = TrackMatch.audio(listOf(stream("10", audio, 1), stream("11", audio, 2)), tracks("en", "en"))!!
        assertNull(match.plexChoice)
    }

    @Test
    fun `a track the Shield can't play is neither Plex's choice nor offered`() {
        val streams = listOf(stream("10", audio, 1, selected = true), stream("11", audio, 2))
        val files = tracks("en", "en").let { listOf(it[0].copy(supported = false), it[1]) }

        val match = TrackMatch.audio(streams, files)!!

        assertNull(match.plexChoice)
        assertEquals(listOf("11"), match.options().map { it.streamId })
    }

    @Test
    fun `subtitles in the file match by order, files next to it by their id, and captions in the video are left out`() {
        val streams = listOf(
            stream("20", subtitles, index = 3, language = "eng", title = "English (SRT)"),
            stream("21", subtitles, index = 4, language = "eng", title = "English Forced (PGS)", forced = true, selected = true),
            stream("22", subtitles, index = null, language = "spa", title = "Español (SRT External)", key = "/library/streams/22", codec = "srt"),
            stream("23", subtitles, index = 0, language = "eng", title = "English (CC)").copy(inVideo = true),
        )
        val files = tracks("en", "en", "es", ids = listOf("3", "4", "plex-22"))

        val match = TrackMatch.subtitles(streams, files)!!

        assertEquals(listOf("20" to 1, "21" to 2, "22" to 3), match.pairs.map { (s, t) -> s.id to t.group })
        assertEquals(2, match.plexChoice?.group)
    }

    @Test
    fun `a subtitle file Media3 couldn't attach is simply not offered`() {
        val streams = listOf(
            stream("20", subtitles, index = 3),
            stream("22", subtitles, index = null, key = "/library/streams/22", codec = "smi"),
        )

        val match = TrackMatch.subtitles(streams, tracks("en"))!!

        assertEquals(listOf("20"), match.pairs.map { it.first.id })
    }

    @Test
    fun `the file's own subtitles not matching makes the whole list unclear`() {
        val streams = listOf(stream("20", subtitles, 3), stream("21", subtitles, 4))
        assertNull(TrackMatch.subtitles(streams, tracks("en")))
    }

    @Test
    fun `languages agree across Plex's and Media3's codes`() {
        assertTrue(TrackMatch.sameLanguage("ger", "de"))
        assertTrue(TrackMatch.sameLanguage("deu", "de-DE"))
        assertTrue(TrackMatch.sameLanguage("fre", "fr"))
        assertTrue(TrackMatch.sameLanguage("chi", "zh"))
        assertTrue(TrackMatch.sameLanguage("eng", "en-US"))
        assertFalse(TrackMatch.sameLanguage("eng", "fr"))
        assertTrue(TrackMatch.sameLanguage("und", "fr"))
        assertTrue(TrackMatch.sameLanguage(null, "fr"))
        assertEquals("deu", TrackMatch.language("ger"))
        assertNull(TrackMatch.language("und"))
    }

    @Test
    fun `two tracks with the same name use Plex's longer one, and a nameless one its language`() {
        val streams = listOf(
            stream("10", audio, 1, title = "English (AC3 5.1)").copy(extendedDisplayTitle = "Main (English AC3 5.1)"),
            stream("11", audio, 2, title = "English (AC3 5.1)").copy(extendedDisplayTitle = "Commentary (English AC3 5.1)"),
            stream("12", audio, 3, title = null).copy(language = "Français"),
            stream("13", audio, 4, title = null),
        )

        assertEquals(
            mapOf("10" to "Main (English AC3 5.1)", "11" to "Commentary (English AC3 5.1)", "12" to "Français", "13" to "Track 4"),
            TrackMatch.labels(streams),
        )
    }

    @Test
    fun `the log line has counts and languages, never a title`() {
        val streams = listOf(
            stream("10", audio, 1, "eng", title = "Director's Commentary", selected = true),
            stream("11", audio, 2, "ger", title = "Deutsch"),
        )
        val files = tracks("en", "de")

        val line = TrackMatch.describe(TrackKind.AUDIO, streams, files, TrackMatch.audio(streams, files))

        assertEquals("audio: Plex 2 (eng deu), file 2: matched 2, Plex picks 1", line)
        assertEquals(
            "subtitles: Plex 0 (), file 1: no clear match, Media3 chooses",
            TrackMatch.describe(TrackKind.SUBTITLES, streams, tracks("en"), null),
        )
        assertEquals("audio: 2 in the file, Plex's streams unknown: Media3 chooses", TrackMatch.describe(TrackKind.AUDIO, null, files, null))
    }
}
