package com.phonoodlesramen.actioncenter.guide

import com.phonoodlesramen.actioncenter.guide.Fixtures.movie
import com.phonoodlesramen.actioncenter.guide.Fixtures.series
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which glyph a channel gets (I1, I7), and a one-show channel's show poster. */
class ChannelLookTest {
    private val movies = ChannelRule(types = listOf(ChannelRule.MOVIE))
    private val episodes = ChannelRule(types = listOf(ChannelRule.EPISODE))

    private fun pick(name: String, rule: ChannelRule? = movies, showGenres: List<String> = emptyList()) =
        ChannelGlyphs.pick(name, rule, showGenres)

    @Test
    fun `the starter lineup's channels each get their glyph`() {
        assertEquals("movie", pick("Movies"))
        assertEquals("tv_gen", pick("TV Mix", episodes))
        assertEquals("theaters", pick("Classics", movies.copy(minAgeYears = 25)))
        assertEquals("family_restroom", pick("Family", movies.copy(contentRatings = listOf("G", "PG"))))
        assertEquals("sentiment_very_satisfied", pick("Comedy", movies.copy(genres = listOf("Comedy"))))
        assertEquals("sentiment_very_satisfied", pick("Comedy TV", episodes.copy(genres = listOf("Comedy"))))
        assertEquals("rocket_launch", pick("Sci-Fi", movies.copy(genres = listOf("Sci-Fi"))))
        assertEquals("rocket_launch", pick("Science Fiction", movies.copy(genres = listOf("Science Fiction"))))
        assertEquals("skull", pick("Horror", movies.copy(genres = listOf("Horror"))))
        assertEquals("local_fire_department", pick("Action & Adventure TV", episodes.copy(genres = listOf("Action & Adventure"))))
    }

    @Test
    fun `words join to the next one, and anything not a letter or a digit splits them`() {
        assertEquals("rocket_launch", ChannelGlyphs.match("Sci-Fi & Fantasy"))
        assertEquals("emoji_events", ChannelGlyphs.match("Game Show"))
        assertEquals("mic", ChannelGlyphs.match("Stand-Up"))
        assertEquals("family_restroom", ChannelGlyphs.match("90's Family"))
        assertEquals("military_tech", ChannelGlyphs.match("War & Politics"))
        assertNull(ChannelGlyphs.match("Dad's Picks"))
        assertNull(ChannelGlyphs.match(""))
    }

    @Test
    fun `the name comes before the rule's genres, then its labels and collections`() {
        assertEquals("the house's own word wins", "featured_seasonal_and_gifts", pick("Holidays", movies.copy(genres = listOf("Family"))))
        assertEquals("theater_comedy", pick("Weekend Picks", movies.copy(genres = listOf("Reality", "Drama"))))
        assertEquals("featured_seasonal_and_gifts", pick("December", movies.copy(labels = listOf("Christmas"))))
        assertEquals("skull", pick("October", movies.copy(collections = listOf("Halloween Night"))))
        assertNull("nothing matches: the monogram", pick("Dad's Picks"))
    }

    @Test
    fun `a one-show channel goes by its show's genres before its name`() {
        val marathon = episodes.copy(shows = listOf("4800"), order = ChannelRule.Order.IN_ORDER)
        assertTrue(ChannelGlyphs.isOneShow(marathon))
        assertEquals("cruelty_free", pick("Family Hour Marathon", marathon, listOf("Animation", "Comedy")))
        assertEquals("family_restroom", pick("Family Hour Marathon", marathon))
        assertEquals("two shows aren't one", false, ChannelGlyphs.isOneShow(marathon.copy(shows = listOf("1", "2"))))
        assertEquals("films too aren't only a show", false, ChannelGlyphs.isOneShow(marathon.copy(types = listOf(ChannelRule.MOVIE, ChannelRule.EPISODE))))
    }

    @Test
    fun `every glyph has a distinct name`() {
        assertEquals(ChannelGlyphs.ALL.size, ChannelGlyphs.ALL.toSet().size)
    }

    @Test
    fun `the lineup and the editor carry each channel's glyph, and a marathon its show's poster`() = runBlocking {
        val library = (1..15).map { movie("m${it.toString().padStart(2, '0')}", "Glowing Reef $it", 100, trailer = "t$it") } +
            series("ridge", "Ridge Line", listOf("Western", "Drama"), seasons = 4, perSeason = 10, minutes = 52, views = 8)
        GuideHarness().use { h ->
            h.synced(library)
            h.engine.extend()
            val channels = withTimeout(TIMEOUT) { h.engine.card.filterIsInstance<GuideCardState.Ready>().first() }.channels.associateBy { it.id }
            assertEquals("movie", channels.getValue("movies").glyph)
            assertNull(channels.getValue("movies").logo)
            assertEquals("tv_gen", channels.getValue("tv-mix").glyph)
            assertEquals("landscape", channels.getValue("marathon-ridge").glyph)
            assertEquals("/library/metadata/ridge/thumb", channels.getValue("marathon-ridge").logo)

            val editable = withTimeout(TIMEOUT) { h.editor.lineup.first { it.isNotEmpty() } }.associateBy { it.id }
            assertEquals("landscape", editable.getValue("marathon-ridge").glyph)
            assertEquals("/library/metadata/ridge/thumb", editable.getValue("marathon-ridge").logo)
            assertEquals("movie", editable.getValue("movies").glyph)
        }
    }

    @Test
    fun `without a cache, a look comes from the name and rule alone`() = runBlocking {
        val marathon = episodes.copy(shows = listOf("4800"), order = ChannelRule.Order.IN_ORDER)
        assertEquals(ChannelLook(glyph = null), ChannelLooks(null).of("Ridge Line Marathon", marathon))
        assertEquals(ChannelLook(glyph = "movie"), ChannelLooks(null).of("Movies", movies.toJson()))
        assertEquals("a rule that won't read", ChannelLook(glyph = "movie"), ChannelLooks(null).of("Movies", "{not json"))
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
