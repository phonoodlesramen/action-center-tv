package com.phonoodlesramen.actioncenter.guide

import com.phonoodlesramen.actioncenter.profiles.AgeBrackets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K8, R2: a channel is kid-safe when it can play something, every rating of what it can play
 * is inside the kid's ceiling, and nothing it can play is unrated (K4); kids see only those.
 */
class KidGuideTest {
    /** The 7-9 bracket's ceiling (K3): G, PG, TV-Y, TV-Y7, TV-G, TV-PG. */
    private val nine = AgeBrackets.DEFAULT.first().ratings

    /** The 10-12 bracket's: adds PG-13. */
    private val twelve = AgeBrackets.DEFAULT.last().ratings

    /** A channel whose rule can play titles rated [ratings], and an unrated one when [unrated]. */
    private fun channel(id: String, vararg ratings: String, unrated: Boolean = false) =
        GuideChannel(id, number = id.hashCode(), name = id, logo = null, slots = emptyList(), contentRatings = ratings.toSet(), unrated = unrated)

    private val lineup = GuideCardState.Ready(
        channels = listOf(
            channel("movies", "G", "PG", "PG-13", "R"),
            channel("family", "G", "PG", "TV-Y", "TV-Y7", "TV-G", "TV-PG"),
            channel("teen", "PG", "PG-13"),
            // A marathon of a TV-Y7 show: its rule names no ratings, but it plays nothing else.
            channel("tide-pool-marathon", "TV-Y7"),
            channel("cartoons", "TV-Y", "TV-Y7", unrated = true),
        ),
        updatedAtMillis = 42L,
    )

    @Test
    fun `what it plays all inside the ceiling is kid-safe`() {
        assertTrue(KidGuide.safe(listOf("TV-Y", "TV-Y7"), unrated = false, nine))
        assertTrue(KidGuide.safe(StarterLineup.FAMILY_RATINGS, unrated = false, nine))
        assertTrue("a pool narrower than its rule's ratings", KidGuide.safe(listOf("G"), unrated = false, nine))
    }

    @Test
    fun `one rating outside the ceiling isn't`() {
        assertFalse(KidGuide.safe(listOf("PG", "PG-13"), unrated = false, nine))
        assertTrue("PG-13 is inside 10-12's", KidGuide.safe(listOf("PG", "PG-13"), unrated = false, twelve))
        assertFalse(KidGuide.safe(listOf("G", "R"), unrated = false, twelve))
        assertFalse("NR is a rating, and no kid's", KidGuide.safe(listOf("G", "NR"), unrated = false, twelve))
    }

    @Test
    fun `an unrated title it can play makes it not kid-safe (K4)`() {
        assertFalse(KidGuide.safe(listOf("TV-Y"), unrated = true, nine))
        assertFalse(KidGuide.safe(emptyList(), unrated = true, nine))
        assertFalse(KidGuide.safe(channel("cartoons", "TV-Y", unrated = true), nine))
    }

    @Test
    fun `a channel that can play nothing isn't`() {
        assertFalse(KidGuide.safe(emptyList(), unrated = false, nine))
        assertFalse(KidGuide.safe(channel("empty"), twelve))
    }

    @Test
    fun `an adult sees the lineup as it is`() {
        assertSame(lineup, KidGuide.of(lineup, ceiling = null))
    }

    @Test
    fun `a kid sees only the kid-safe channels, in lineup order`() {
        val nineSees = KidGuide.of(lineup, nine) as GuideCardState.Ready
        assertEquals(listOf("family", "tide-pool-marathon"), nineSees.channels.map { it.id })
        assertEquals(42L, nineSees.updatedAtMillis)

        val twelveSees = KidGuide.of(lineup, twelve) as GuideCardState.Ready
        assertEquals(listOf("family", "teen", "tide-pool-marathon"), twelveSees.channels.map { it.id })
    }

    @Test
    fun `a lineup with nothing for the kid says so`() {
        val adultOnly = GuideCardState.Ready(listOf(channel("movies", "PG", "R"), channel("teen", "PG", "PG-13")), null)
        assertEquals(GuideCardState.NoneForKids, KidGuide.of(adultOnly, nine))
        // An empty ceiling (an override with nothing ticked) lets nothing through.
        assertEquals(GuideCardState.NoneForKids, KidGuide.of(lineup, emptySet()))
    }

    @Test
    fun `why there are no channels yet is everyone's`() {
        for (state in listOf(GuideCardState.NotSetUp, GuideCardState.Building, GuideCardState.NothingToFill, GuideCardState.NoneOn)) {
            assertSame(state, KidGuide.of(state, nine))
        }
    }
}
