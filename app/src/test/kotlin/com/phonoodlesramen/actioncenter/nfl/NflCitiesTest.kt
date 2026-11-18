package com.phonoodlesramen.actioncenter.nfl

import com.phonoodlesramen.actioncenter.ui.nfl.SampleNfl
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The city photos' list (ui-spec "Two cities"): free to use as the app uses them, and credited. */
class NflCitiesTest {
    private val photos = NflCities.photos

    @Test
    fun `every photo is public domain, CC0 or CC BY, never share-alike`() {
        photos.forEach { (team, photo) ->
            val licence = photo.licence
            val ok = licence == "Public domain" || licence == "CC0" || Regex("""CC BY \d\.\d""").matches(licence)
            assertTrue("$team: ${photo.file} is $licence", ok)
        }
    }

    @Test
    fun `every photo is a team's, its own, with a place, an author and its interest in the frame`() {
        val teams = SampleNfl.teams.map { it.abbr }.toSet()
        photos.forEach { (team, photo) ->
            assertTrue("$team isn't a team", team in teams)
            assertTrue("$team: ${photo.file}", Regex(""".+\.(jpe?g|png|tiff?)""", RegexOption.IGNORE_CASE).matches(photo.file))
            assertTrue("$team: no place", photo.place.isNotBlank())
            assertTrue("$team: no author", photo.author.isNotBlank())
            assertTrue("$team: focus", photo.focusX in 0f..1f && photo.focusY in 0f..1f)
        }
        assertEquals("two teams share a photo", photos.size, photos.values.map { it.file }.toSet().size)
    }

    @Test
    fun `every photo is credited in the third-party notices`() {
        val notices = File("../docs/third-party-notices.md").readText()
        photos.forEach { (team, photo) ->
            assertTrue("$team: ${photo.file} isn't credited", notices.contains(photo.file))
        }
    }

    @Test
    fun `Commons is asked for the file by name, at the width drawn`() {
        val url = NflCities.url(CityPhoto("A made-up bridge", "Made-up bridge at dusk.jpg", "A. Nobody", "CC0"), 1280)
        assertEquals("https://commons.wikimedia.org/wiki/Special:FilePath/Made-up%20bridge%20at%20dusk.jpg?width=1280", url.toString())
    }
}
