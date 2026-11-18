package com.phonoodlesramen.actioncenter.nfl

/**
 * A team's city as the app draws it (ui-spec "Two cities"): a picture made for the app with
 * Artlist's image generation, shipped in `assets/nfl/cities/` (docs/third-party-notices.md).
 */
data class CityArt(
    /** What it shows: "Boathouse Row and the skyline on the Schuylkill, Philadelphia". */
    val place: String,
    /** Where the picture's interest is, from its left (0) to its right (1): kept in view when a side is cropped. */
    val focusX: Float = 0.5f,
    /** The same from its top (0) to its bottom (1). */
    val focusY: Float = 0.5f,
)

/** The teams' cities, and where each one's picture is in the app's assets. */
object NflCities {
    /** One per team, by ESPN's abbreviation. A team without one has its colours, as before. */
    val art: Map<String, CityArt> = mapOf(
        "PHI" to CityArt("Boathouse Row and the skyline on the Schuylkill, Philadelphia", focusY = 0.45f),
        "WSH" to CityArt("The Capitol across its reflecting pool, Washington", focusY = 0.7f),
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
