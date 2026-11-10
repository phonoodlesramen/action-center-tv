package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.cache.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeaturesTest {
    private fun features(item: PlexItem) = Features.of(item.toEntity("1", 0))

    @Test
    fun `a film's features are its tags, decade, rating band and length`() {
        val heat = PlexItem(
            "m1", "movie", "Heat", year = 1995, durationMs = 170 * 60_000L, contentRating = "R",
            genres = listOf("Crime", "Drama"), collections = listOf("Heists"), directors = listOf("Dena Varga"),
            actors = listOf("Mara Quint"), labels = listOf("Favourites"),
        )

        assertEquals(
            setOf(
                Feature(FeatureKind.GENRE, "Crime"), Feature(FeatureKind.GENRE, "Drama"),
                Feature(FeatureKind.COLLECTION, "Heists"), Feature(FeatureKind.DIRECTOR, "Dena Varga"),
                Feature(FeatureKind.ACTOR, "Mara Quint"), Feature(FeatureKind.DECADE, "1990s"),
                Feature(FeatureKind.RATING, Features.ADULT), Feature(FeatureKind.LENGTH, "epic"),
            ),
            features(heat),
        )
    }

    @Test
    fun `a show has no length band, and missing fields add nothing`() {
        val show = PlexItem("s1", "show", "Harbour Lights", year = 2019, contentRating = "TV-PG", durationMs = 45 * 60_000L)

        assertEquals(setOf(Feature(FeatureKind.DECADE, "2010s"), Feature(FeatureKind.RATING, Features.FAMILY)), features(show))
        assertEquals(emptySet<Feature>(), features(PlexItem("m2", "movie", "Unknown")))
    }

    @Test
    fun `ratings fall into four bands, and unrated ones into none`() {
        assertEquals(Features.KIDS, Features.ratingBand("TV-Y7"))
        assertEquals(Features.FAMILY, Features.ratingBand(" pg "))
        assertEquals(Features.TEEN, Features.ratingBand("TV-14"))
        assertEquals(Features.ADULT, Features.ratingBand("TV-MA"))
        assertNull(Features.ratingBand("NR"))
        assertEquals(listOf("short", "standard", "long", "epic"), listOf(89, 90, 120, 150).map { Features.lengthBand(it * 60_000L) })
    }
}
