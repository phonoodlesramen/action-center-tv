package com.phonoodlesramen.actioncenter.favorites

import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.KidGuide
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Favorite channels' order (C3, C4), Move's swaps, and what's kept as they're saved. */
class FavoriteOrderTest {
    private val lineup = SampleGuide.ready().channels

    private fun ids(favorites: List<String>) = FavoriteOrder.ordered(lineup, favorites).map { it.id }

    @Test
    fun `favorites come first in the profile's order, then the rest by number`() {
        assertEquals(
            listOf("comedy", "tv-mix", "movies", "scifi", "classics", "marathon-ridge-line"),
            ids(listOf("comedy", "tv-mix")),
        )
        assertEquals(2, FavoriteOrder.leading(lineup, listOf("comedy", "tv-mix")))
    }

    @Test
    fun `without favorites the lineup is by number, as it was`() {
        assertEquals(lineup.map { it.id }, ids(emptyList()))
        assertEquals(0, FavoriteOrder.leading(lineup, emptyList()))
    }

    @Test
    fun `an off or deleted favorite is skipped, and an off one comes back in place when turned on`() {
        val favorites = listOf("classics", "gone", "comedy")
        val withoutClassics = lineup.filter { it.id != "classics" }
        assertEquals(listOf("comedy", "movies"), FavoriteOrder.ordered(withoutClassics, favorites).take(2).map { it.id })
        assertEquals(1, FavoriteOrder.leading(withoutClassics, favorites))
        // Turned on again: back first, where it was in the list.
        assertEquals(listOf("classics", "comedy", "movies"), ids(favorites).take(3))
    }

    @Test
    fun `Add goes at the end, Remove takes it out, and adding twice keeps one`() {
        assertEquals(listOf("comedy", "scifi"), FavoriteOrder.toggled(listOf("comedy"), "scifi", favorite = true))
        assertEquals(listOf("comedy"), FavoriteOrder.toggled(listOf("comedy"), "comedy", favorite = true))
        assertEquals(listOf("scifi"), FavoriteOrder.toggled(listOf("comedy", "scifi"), "comedy", favorite = false))
    }

    @Test
    fun `Move swaps a favorite with its neighbour, either way`() {
        val shown = lineup.mapTo(HashSet()) { it.id }
        val favorites = listOf("comedy", "tv-mix", "scifi")
        assertEquals(listOf("tv-mix", "comedy", "scifi"), FavoriteOrder.moved(favorites, shown, "comedy", +1))
        assertEquals(listOf("comedy", "scifi", "tv-mix"), FavoriteOrder.moved(favorites, shown, "scifi", -1))
    }

    @Test
    fun `at either end Move goes no further`() {
        val shown = lineup.mapTo(HashSet()) { it.id }
        val favorites = listOf("comedy", "tv-mix")
        assertNull(FavoriteOrder.moved(favorites, shown, "comedy", -1))
        assertNull(FavoriteOrder.moved(favorites, shown, "tv-mix", +1))
        // Never past the favorites into the rest of the row, nor for a channel that isn't one.
        assertNull(FavoriteOrder.moved(favorites, shown, "movies", -1))
    }

    @Test
    fun `a kid's hidden favorite keeps its place while Move swaps the ones they see`() {
        val kids = KidGuide.of(SampleGuide.withKidChannels(), SampleHomeState.maya.ceiling!!) as GuideCardState.Ready
        val favorites = SampleHomeState.mayaFavorites
        // Comedy isn't kid-safe: Maya's row has Cartoon Club, then Family, first.
        assertEquals(listOf("cartoons", "family"), FavoriteOrder.ordered(kids.channels, favorites).take(2).map { it.id })
        assertEquals(2, FavoriteOrder.leading(kids.channels, favorites))
        // Family moves left past Comedy, which she doesn't see, and Comedy stays put.
        val shown = kids.channels.mapTo(HashSet()) { it.id }
        assertEquals(listOf("family", "comedy", "cartoons"), FavoriteOrder.moved(favorites, shown, "family", -1))
        assertNull(FavoriteOrder.moved(favorites, shown, "cartoons", -1))
    }

    @Test
    fun `saving drops a deleted channel but keeps an off one and a kid's hidden one`() {
        val known = lineup.map { it.id } + "family"
        assertEquals(listOf("comedy", "family"), FavoriteOrder.pruned(listOf("comedy", "deleted", "family", "comedy"), known))
        // With the lineup not read yet, nothing goes.
        assertEquals(listOf("comedy", "deleted"), FavoriteOrder.pruned(listOf("comedy", "deleted"), emptyList()))
    }
}
