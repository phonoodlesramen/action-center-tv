package com.phonoodlesramen.actioncenter.ui.settings

import com.phonoodlesramen.actioncenter.guide.StarterLineup
import com.phonoodlesramen.actioncenter.profiles.AgeBracket
import com.phonoodlesramen.actioncenter.profiles.AgeBrackets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** R2: a channel's Ratings choices in Settings › Plex Guide, from Settings › Profiles' age brackets. */
class ChannelRatingsTest {
    private val choices = ChannelRatings.choices(AgeBrackets.DEFAULT)

    @Test
    fun `Any, then each bracket's ratings, youngest first`() {
        assertEquals(listOf("Any", "Ages 7–9", "Ages 10–12"), choices.map { it.label })
        assertEquals(listOf("No limit", "Up to PG and TV-PG", "Up to PG-13 and TV-PG"), choices.map { it.detail })
        // Films then TV, each from the youngest: the 7-9 bracket's are the Family channel's.
        assertEquals(StarterLineup.FAMILY_RATINGS, choices[1].ratings)
        assertEquals(listOf("G", "PG", "PG-13", "TV-Y", "TV-Y7", "TV-G", "TV-PG"), choices[2].ratings)
        assertEquals(emptyList<String>(), choices[0].ratings)
    }

    @Test
    fun `a rule's ratings show as Any, the bracket with exactly those, or Custom`() {
        assertSame(ChannelRatings.ANY, ChannelRatings.of(emptyList(), choices))
        assertEquals("Ages 7–9", ChannelRatings.of(StarterLineup.FAMILY_RATINGS.reversed(), choices).label)
        val custom = ChannelRatings.of(listOf("TV-Y7", "MADE-UP", "G"), choices)
        assertEquals("Custom", custom.label)
        assertEquals("G, TV-Y7, MADE-UP", custom.detail)
    }

    @Test
    fun `OK steps round, and from Custom to the youngest bracket's`() {
        assertEquals("Ages 7–9", ChannelRatings.next(emptyList(), choices).label)
        assertEquals("Ages 10–12", ChannelRatings.next(choices[1].ratings, choices).label)
        assertSame(ChannelRatings.ANY, ChannelRatings.next(choices[2].ratings, choices))
        assertEquals("Ages 7–9", ChannelRatings.next(listOf("TV-Y"), choices).label)
    }

    @Test
    fun `a bracket with nothing ticked, or another's ratings, isn't a choice`() {
        val table = listOf(
            AgeBracket(10, 12, setOf("PG")),
            AgeBracket(4, 6, emptySet()),
            AgeBracket(7, 9, setOf("PG")),
            AgeBracket(13, 13, setOf("PG", "PG-13")),
        )
        val made = ChannelRatings.choices(table)
        assertEquals(listOf("Any", "Ages 7–9", "Age 13"), made.map { it.label })
    }

    @Test
    fun `with no bracket to choose, Any stays Any`() {
        val only = ChannelRatings.choices(listOf(AgeBracket(7, 9, emptySet())))
        assertEquals(listOf(ChannelRatings.ANY), only)
        assertSame(ChannelRatings.ANY, ChannelRatings.next(emptyList(), only))
        assertSame(ChannelRatings.ANY, ChannelRatings.next(listOf("G"), only))
    }
}
