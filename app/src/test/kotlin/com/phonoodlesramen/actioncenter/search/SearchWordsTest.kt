package com.phonoodlesramen.actioncenter.search

import com.phonoodlesramen.actioncenter.ui.search.without
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 15, Q2: the few words Search reads as chips. */
class SearchWordsTest {
    private fun read(text: String) = SearchWords.read(text)

    @Test
    fun `plain words all go to Plex, with no chips`() {
        val words = read("  harbor   lights ")

        assertEquals(SearchWords("harbor lights", "harbor lights", emptyList()), words)
        assertFalse(words.narrowed)
    }

    @Test
    fun `decades in any common spelling`() {
        assertEquals(listOf(SearchChip.Decade(1990)), read("90s action").chips)
        assertEquals("action", read("90s action").query)
        assertEquals(listOf(SearchChip.Decade(1980)), read("'80s").chips)
        assertEquals(listOf(SearchChip.Decade(1970)), read("1970s").chips)
        assertEquals("this century's", listOf(SearchChip.Decade(2000)), read("00s").chips)
        assertEquals(listOf(SearchChip.Decade(2020)), read("20s").chips)
        assertEquals("not a decade", emptyList<SearchChip>(), read("85s").chips)
    }

    @Test
    fun `kids or family, movies or shows, and new`() {
        val words = read("Kids movies")

        assertEquals(listOf(SearchChip.Kids, SearchChip.Movies), words.chips)
        assertEquals("", words.query)
        assertEquals("Kids movies", words.text)
        assertEquals(listOf(SearchChip.Kids), read("family").chips)
        assertEquals(listOf(SearchChip.Shows, SearchChip.New), read("new comedy series").chips.reversed())
        assertEquals("comedy", read("new comedy series").query)
        assertEquals(listOf(SearchChip.Shows), read("TV").chips)
    }

    @Test
    fun `a word read twice is one chip`() {
        assertEquals(listOf(SearchChip.Movies), read("movies films").chips)
    }

    @Test
    fun `each chip passes only what fits it`() {
        val now = 2_000_000_000L
        assertTrue(passes(listOf(SearchChip.Decade(1990)), "movie", 1995, "R", null, now))
        assertFalse(passes(listOf(SearchChip.Decade(1990)), "movie", 2001, "R", null, now))
        assertFalse("no year, no decade", passes(listOf(SearchChip.Decade(1990)), "movie", null, "R", null, now))
        assertTrue(passes(listOf(SearchChip.Kids), "movie", 2014, "PG", null, now))
        assertTrue(passes(listOf(SearchChip.Kids), "show", 2014, "TV-Y7", null, now))
        assertFalse(passes(listOf(SearchChip.Kids), "movie", 2014, "PG-13", null, now))
        assertFalse("unrated isn't kids", passes(listOf(SearchChip.Kids), "movie", 2014, null, null, now))
        assertTrue(passes(listOf(SearchChip.Shows), "episode", 2014, null, null, now))
        assertFalse(passes(listOf(SearchChip.Movies), "show", 2014, null, null, now))
        assertTrue(passes(listOf(SearchChip.New), "movie", 2014, null, now - 29 * 86_400, now))
        assertFalse(passes(listOf(SearchChip.New), "movie", 2014, null, now - 31 * 86_400, now))
        assertTrue("no chips, everything", passes(emptyList(), "movie", null, null, null, now))
    }

    @Test
    fun `taking a chip off takes its word out of the field`() {
        assertEquals("action", without("90s action", SearchChip.Decade(1990)))
        assertEquals("kids", without("kids movies", SearchChip.Movies))
        assertEquals("comedy", without("new comedy", SearchChip.New))
    }
}
