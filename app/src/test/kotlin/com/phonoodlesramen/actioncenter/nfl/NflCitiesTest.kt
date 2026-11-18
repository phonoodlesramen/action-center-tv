package com.phonoodlesramen.actioncenter.nfl

import com.phonoodlesramen.actioncenter.ui.nfl.SampleNfl
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The teams' city pictures (ui-spec "Two cities"): one each, shipped with the app, and small. */
class NflCitiesTest {
    /** The app's assets, from the module's folder, where the tests run. */
    private val folder = File("src/main/assets/${NflCities.DIR}")

    @Test
    fun `every city listed has its picture, and every picture is listed`() {
        val teams = SampleNfl.teams.map { it.abbr }.toSet()
        NflCities.art.forEach { (team, art) ->
            assertTrue("$team isn't a team", team in teams)
            assertTrue("$team: no ${NflCities.asset(team)}", File("src/main/assets", NflCities.asset(team)).isFile)
            assertTrue("$team: no place", art.place.isNotBlank())
            assertTrue("$team: focus", art.focusX in 0f..1f && art.focusY in 0f..1f)
        }
        val listed = NflCities.art.keys.map { File(NflCities.asset(it)).name }.toSet()
        assertEquals("pictures nobody uses", emptySet<String>(), folder.list()!!.toSet() - listed)
    }

    @Test
    fun `each picture is 1280 px wide and under 150 KB, so all 32 stay near 3 MB`() {
        folder.listFiles()!!.forEach { file ->
            assertTrue("${file.name}: ${file.length() / 1024} KB", file.length() < 150 * 1024)
            // ImageIO reads no WebP, so the width is read from the file's VP8 header.
            assertEquals(file.name, 1280, WebP.width(file))
        }
    }

    /** Just enough of WebP's RIFF container to read a lossy picture's width. */
    private object WebP {
        fun width(file: File): Int {
            val b = file.readBytes()
            check(String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WEBP") { "${file.name} isn't a WebP" }
            return when (val chunk = String(b, 12, 4)) {
                // A lossy frame: its start code, then a 14-bit width.
                "VP8 " -> ((b[26].toInt() and 0xFF) or ((b[27].toInt() and 0xFF) shl 8)) and 0x3FFF
                // An extended header: the canvas width less one, in 24 bits.
                "VP8X" -> 1 + ((b[24].toInt() and 0xFF) or ((b[25].toInt() and 0xFF) shl 8) or ((b[26].toInt() and 0xFF) shl 16))
                else -> error("${file.name}: $chunk")
            }
        }
    }

    @Test
    fun `the app's own pictures load from its assets`() {
        assertEquals("file:///android_asset/nfl/cities/phi.webp", NflCityImages.Bundled.image("PHI"))
        assertEquals(null, NflCityImages.Bundled.image("XYZ"))
    }
}
