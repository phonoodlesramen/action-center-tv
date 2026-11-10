package com.phonoodlesramen.actioncenter.ai

import com.phonoodlesramen.actioncenter.ai.BlurbFixtures.item
import com.phonoodlesramen.actioncenter.ai.BlurbFixtures.pick
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.suggest.Surfaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which picks get a blurb, and what each one carries (M8). */
class BlurbPicksTest {
    private val films = (1..40).associate { "m$it" to item("m$it", "Film $it") }

    @Test
    fun `tonight's six picks and the first three of each lens that's on, each title once`() {
        val picks = (0 until 8).map { pick(Surfaces.HERO, it, "m${it + 1}", "Hero $it") } +
            (0 until 5).map { pick(Surfaces.lens(Lens.CLASSIC), it, "m${it + 20}", "Classic $it") } +
            // The hero's m2 again: written once, for the hero's reason.
            listOf(pick(Surfaces.lens(Lens.ACTION), 0, "m2", "Action 0")) +
            (1 until 4).map { pick(Surfaces.lens(Lens.ACTION), it, "m${it + 30}", "Action $it") } +
            // Off: never sent.
            (0 until 3).map { pick(Surfaces.lens(Lens.COMFORT), it, "m${it + 36}", "Comfort $it") } +
            // Not cards that get blurbs.
            listOf(pick(Surfaces.QUICK_WATCH, 0, "m10", "Quick"), pick(Surfaces.UP_NEXT, 0, "m11", "Up next"), pick(Surfaces.moreLike("m1"), 0, "m12", "Like"))

        val chosen = BlurbPicks.choose(picks, setOf(Lens.CLASSIC, Lens.ACTION), films)

        assertEquals(
            listOf("m1", "m2", "m3", "m4", "m5", "m6", "m20", "m21", "m22", "m31", "m32"),
            chosen.map { it.ratingKey },
        )
        assertEquals("Hero 1", chosen.first { it.ratingKey == "m2" }.reason)
        assertEquals((1..11).map { "p$it" }, chosen.map { it.sent.id })
        // Each saved against the reason it was sent with.
        chosen.forEach { assertEquals(it.reason, it.sent.reason) }
    }

    @Test
    fun `every lens on gives about 30`() {
        val picks = (0 until 6).map { pick(Surfaces.HERO, it, "m${it + 1}", "Hero") } +
            Lens.entries.flatMapIndexed { index, lens -> (0 until 5).map { pick(Surfaces.lens(lens), it, "l$index-$it", "Lens") } }
        val titles = picks.associate { it.ratingKey to item(it.ratingKey, "Title ${it.ratingKey}") }

        assertEquals(BlurbPicks.HERO + Lens.entries.size * BlurbPicks.PER_LENS, BlurbPicks.choose(picks, Lens.entries.toSet(), titles).size)
    }

    @Test
    fun `an episode goes as its show, with the episode's own length`() {
        val chosen = BlurbPicks.choose(BlurbFixtures.picks, emptySet(), BlurbFixtures.titles)
        val episode = chosen.single { it.ratingKey == "rk-ep-77" }.sent

        assertEquals(
            BlurbPick(
                id = "p2",
                title = "Harbour Lights",
                kind = "series",
                year = 2019,
                genres = listOf("Drama", "Mystery"),
                rating = "TV-14",
                minutes = 47,
                reason = "You're 2 episodes from the end of Harbour Lights's season 2",
                // "Last watched 3 days ago" stays home (M8).
                evidence = listOf("Rated 8.3"),
            ),
            episode,
        )
    }

    @Test
    fun `when you watched never goes, and a pick whose reason is when isn't sent`() {
        val picks = listOf(
            pick(Surfaces.HERO, 0, "m1", "Watched 12 days ago"),
            pick(Surfaces.HERO, 1, "m2", "Because you liked Heat", "Last watched yesterday|Watched today|Rated 7.9"),
        )

        val chosen = BlurbPicks.choose(picks, emptySet(), films)

        assertEquals(listOf("m2"), chosen.map { it.ratingKey })
        assertEquals(listOf("Rated 7.9"), chosen.single().sent.evidence)
        assertEquals("p1", chosen.single().sent.id)
    }

    @Test
    fun `a pick whose title isn't in the cache is left out`() {
        val picks = listOf(pick(Surfaces.HERO, 0, "gone", "Because"), pick(Surfaces.HERO, 1, "m1", "Because"))
        assertEquals(listOf("m1"), BlurbPicks.choose(picks, emptySet(), films).map { it.ratingKey })
    }

    @Test
    fun `no picks, nothing to send`() {
        assertTrue(BlurbPicks.choose(emptyList(), Lens.entries.toSet(), films).isEmpty())
    }
}
