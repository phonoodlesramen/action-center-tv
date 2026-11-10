package com.phonoodlesramen.actioncenter.content

/**
 * Made-up content for design review, behind Settings › Diagnostics › Sample content.
 * Never shown by default: a kid pressing Play on a sample pick would get nothing.
 */
object SampleContentSource : ContentSource {
    override fun content() = HomeContent(
        picks = listOf(
            Pick("severance", "Severance", "Season 2 · 2 episodes left · TV-MA", "You're 2 episodes from the end of the season", "Play S2E9"),
            Pick("oceans", "Ocean's Eleven", "2001 · 1h 56m · PG-13", "Because you finished three heist films this month"),
            Pick("arrival", "Arrival", "2016 · 1h 56m · PG-13", "Because you rated Interstellar 5 stars"),
        ),
        // Ocean's Eleven is both the second pick and a Classic: the lens card skips it
        // while it's Tonight's Pick (U20).
        lenses = mapOf(
            Lens.THROWBACK to listOf(
                LensPick("heat", "Heat", "1995 · 2h 50m", "One year ago today you watched", seen = true),
                LensPick("amelie", "Amélie", "2001 · 2h 2m", "Released 25 years ago · you watched it twice", seen = true),
                LensPick("prestige", "The Prestige", "2006 · 2h 10m", "You loved it, then never went back", seen = true),
            ),
            Lens.CLASSIC to listOf(
                LensPick("jaws", "Jaws", "1975 · 2h 4m · PG", "51 years old and you've never seen it", seen = false),
                LensPick("oceans", "Ocean's Eleven", "2001 · 1h 56m · PG-13", "25 this year · last watched in 2019", seen = true),
            ),
            Lens.FAMILY to listOf(
                LensPick("paddington", "Paddington 2", "2017 · 1h 44m · PG", "Rated PG · everyone loved it last time", seen = true),
                LensPick("irongiant", "The Iron Giant", "1999 · 1h 26m · PG", "Rated PG · nobody's seen it yet", seen = false),
            ),
            Lens.ACTION to listOf(
                LensPick("furyroad", "Mad Max: Fury Road", "2015 · 2h · R", "You gave it five stars", seen = true),
                LensPick("maverick", "Top Gun: Maverick", "2022 · 2h 11m · PG-13", "In your library since July, never played", seen = false),
            ),
            Lens.HIDDEN_GEM to listOf(
                LensPick("coherence", "Coherence", "2013 · 1h 29m · NR", "Rated 88% by critics · never played", seen = false),
            ),
            Lens.FINISH_SERIES to listOf(
                LensPick("dune2", "Dune: Part Two", "2024 · 2h 46m · PG-13", "You watched Dune · Part Two is next", seen = false),
            ),
            Lens.SEASONAL to listOf(
                LensPick("hocus", "Hocus Pocus", "1993 · 1h 36m · PG", "A Halloween favourite, watched three Octobers running", seen = true),
                LensPick("beetlejuice", "Beetlejuice", "1988 · 1h 32m · PG", "A Halloween classic you haven't seen", seen = false),
            ),
            Lens.COMFORT to listOf(
                LensPick("budapest", "The Grand Budapest Hotel", "2014 · 1h 40m · R", "Watched six times · your most rewatched", seen = true),
                LensPick("office", "The Office", "S4E13 · 22m · TV-14", "Your usual before bed", seen = true),
            ),
        ),
        quickWatch = listOf(
            QuickItem("bear", "The Bear", "S2E3 · 26m"),
            QuickItem("abbott", "Abbott Elementary", "S3E4 · 22m"),
        ),
        quickWatchCutoff = "10:30",
        upNext = listOf(
            UpNextItem("slow", "Slow Horses", "S4E2", "41m left", 0.35f),
            UpNextItem("shogun", "Shōgun", "S1E7", "12m left", 0.84f),
            UpNextItem("bake", "Great British Bake Off", "S12E3", "52m left", 0.08f),
        ),
    )
}
