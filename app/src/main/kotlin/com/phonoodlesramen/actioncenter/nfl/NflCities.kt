package com.phonoodlesramen.actioncenter.nfl

/**
 * A team's city as the app draws it (ui-spec "Two cities"): its home stadium at dusk, seen from a
 * drone with the city around it, a picture made for the app with Artlist's image generation from
 * public-domain aerial photographs, shipped in `assets/nfl/cities/` (docs/third-party-notices.md).
 */
data class CityArt(
    /** What it shows: "Lumen Field's arches, downtown Seattle and the Space Needle behind it". */
    val place: String,
    /** Where the stadium is, from the picture's left (0) to its right (1): kept in view when a side is cropped. */
    val focusX: Float = 0.5f,
    /** The same from its top (0) to its bottom (1). */
    val focusY: Float = 0.5f,
)

/** The teams' cities, and where each one's picture is in the app's assets. */
object NflCities {
    /** All 32, by ESPN's abbreviation, by conference and division. A team without one has its colours. */
    val art: Map<String, CityArt> = mapOf(
        "BUF" to CityArt("The new Highmark Stadium under its canopy, Orchard Park"),
        "MIA" to CityArt("Hard Rock Stadium under its canopy, Miami Gardens", focusY = 0.47f),
        "NE" to CityArt("Gillette Stadium and its lighthouse beside Patriot Place, Foxborough", focusX = 0.55f),
        "NYJ" to CityArt("MetLife Stadium from the east, the Meadowlands behind it, East Rutherford", focusX = 0.48f, focusY = 0.52f),
        "BAL" to CityArt("M&T Bank Stadium beside the ballpark at Camden Yards, Baltimore", focusX = 0.49f, focusY = 0.56f),
        "CIN" to CityArt("Paycor Stadium on the Ohio, downtown Cincinnati behind it", focusX = 0.56f),
        "CLE" to CityArt("Huntington Bank Field on the Lake Erie shore, downtown Cleveland behind it", focusY = 0.52f),
        "PIT" to CityArt("Acrisure Stadium where the three rivers meet, Pittsburgh", focusX = 0.43f, focusY = 0.52f),
        "HOU" to CityArt("NRG Stadium beside the Astrodome, downtown Houston on the horizon", focusX = 0.42f, focusY = 0.45f),
        "IND" to CityArt("Lucas Oil Stadium's brick south end, downtown Indianapolis behind it", focusX = 0.51f),
        "JAX" to CityArt("EverBank Stadium on the St. Johns River, Jacksonville", focusX = 0.56f, focusY = 0.52f),
        "TEN" to CityArt("Nissan Stadium across the Cumberland from downtown Nashville", focusX = 0.51f, focusY = 0.52f),
        "DEN" to CityArt("Empower Field at Mile High, the Rockies behind it, Denver", focusX = 0.49f, focusY = 0.42f),
        "KC" to CityArt("Arrowhead Stadium beside Kauffman Stadium, Kansas City", focusX = 0.51f, focusY = 0.47f),
        "LV" to CityArt("Allegiant Stadium beside the Strip, Las Vegas", focusX = 0.54f),
        "LAC" to CityArt("SoFi Stadium from the north-east, the airport and the Pacific behind it, Inglewood", focusX = 0.55f, focusY = 0.61f),
        "DAL" to CityArt("AT&T Stadium and its glass ends, Arlington", focusX = 0.52f, focusY = 0.46f),
        "NYG" to CityArt("MetLife Stadium from the west, Manhattan on the horizon, East Rutherford", focusX = 0.53f, focusY = 0.53f),
        "PHI" to CityArt("Lincoln Financial Field in the sports complex, Philadelphia"),
        "WSH" to CityArt("Northwest Stadium in its lots, Landover", focusX = 0.55f, focusY = 0.49f),
        "CHI" to CityArt("Soldier Field on the lakefront, the Chicago skyline behind it", focusY = 0.59f),
        "DET" to CityArt("Ford Field's brick warehouse front, downtown Detroit behind it", focusX = 0.49f, focusY = 0.62f),
        "GB" to CityArt("Lambeau Field among the houses of Green Bay", focusX = 0.51f, focusY = 0.47f),
        "MIN" to CityArt("U.S. Bank Stadium's glass prow, downtown Minneapolis behind it", focusX = 0.56f),
        "ATL" to CityArt("Mercedes-Benz Stadium's petals, downtown Atlanta behind it", focusY = 0.55f),
        "CAR" to CityArt("Bank of America Stadium below uptown Charlotte", focusX = 0.53f, focusY = 0.49f),
        "NO" to CityArt("The Superdome in downtown New Orleans, the Mississippi behind it", focusX = 0.47f, focusY = 0.45f),
        "TB" to CityArt("Raymond James Stadium, downtown Tampa on the horizon", focusX = 0.48f, focusY = 0.49f),
        "ARI" to CityArt("State Farm Stadium in the desert, the mountains behind it, Glendale", focusY = 0.53f),
        "LAR" to CityArt("SoFi Stadium from the south-west, downtown Los Angeles on the horizon, Inglewood", focusX = 0.6f, focusY = 0.49f),
        "SF" to CityArt("Levi's Stadium and its suite tower, the bay behind it, Santa Clara", focusX = 0.55f, focusY = 0.47f),
        "SEA" to CityArt("Lumen Field's arches, downtown Seattle and the Space Needle behind it", focusX = 0.48f, focusY = 0.56f),
    )

    /** The pictures' folder in the assets. */
    const val DIR = "nfl/cities"

    /** [abbr]'s picture, 1280 px wide: "nfl/cities/phi.webp". */
    fun asset(abbr: String): String = "$DIR/${abbr.lowercase()}.webp"
}

/** Where a team's city picture comes from, as something Coil loads. The default has none: the teams' colours are drawn. */
fun interface NflCityImages {
    /** [abbr]'s picture, or null for none. */
    fun image(abbr: String): Any?

    companion object {
        val None = NflCityImages { null }

        /** The app's own, from its assets. */
        val Bundled = NflCityImages { abbr -> if (abbr in NflCities.art) "file:///android_asset/${NflCities.asset(abbr)}" else null }
    }
}

/**
 * Two cities meet where the teams do (ui-spec "Two cities"): before kickoff they fade into each
 * other; once it's on, a zig-zag (the clash). Once it's over, the loser's city goes dark, as a
 * final's loser is faint on its tile; a tie, or a hidden score, leaves both lit.
 */
object Clash {
    enum class Seam { FADE, ZIGZAG }

    fun seam(game: NflGame): Seam = if (game.state == GameState.PRE) Seam.FADE else Seam.ZIGZAG

    /** The team whose city goes dark: the loser of a final shown; else nobody. */
    fun dimmed(game: NflGame, hidden: Boolean): String? = when {
        hidden || game.state != GameState.FINAL -> null
        game.winner == game.away -> game.home
        game.winner == game.home -> game.away
        else -> null
    }
}
