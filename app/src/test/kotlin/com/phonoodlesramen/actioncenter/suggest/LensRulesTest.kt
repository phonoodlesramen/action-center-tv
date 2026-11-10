package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.plex.cache.ContinueEntity
import com.phonoodlesramen.actioncenter.plex.cache.EpisodeRow
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.plex.cache.PlayEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up titles. Times are UTC, and today is 20 October 2026 at 7 PM unless a test says otherwise. */
class LensRulesTest {
    private val october20: LocalDate = LocalDate.of(2026, 10, 20)

    /** Epoch seconds at noon. */
    private fun on(date: LocalDate) = date.atTime(12, 0).toEpochSecond(ZoneOffset.UTC)

    private fun on(year: Int, month: Int, day: Int) = on(LocalDate.of(year, month, day))

    private fun title(key: String, type: String, name: String) = ItemEntity(
        ratingKey = key, sectionKey = if (type == "movie") "1" else "2", type = type, title = name, year = null, durationMs = null,
        viewCount = 0, lastViewedAt = null, addedAt = null, updatedAt = null, contentRating = null, audienceRating = null,
        rating = null, thumb = null, art = null, genres = "", syncedAt = 0,
    )

    /** A film with a file to play. */
    private fun movie(key: String, name: String = key) = title(key, "movie", name).copy(partKey = "/library/parts/$key/1/file.mkv")

    private fun show(key: String, name: String = key) = title(key, "show", name)

    private fun episode(key: String, show: String, season: Int, number: Int, viewCount: Int = 0, lastViewed: Long? = null, minutes: Long = 45) =
        EpisodeRow(key, show, season, number, minutes * 60_000, viewCount, lastViewed, null, on(2020, 1, 1))

    private fun play(key: String, at: Long, type: String = "movie") = PlayEntity("$key@$at", key, type, key, at, null)

    /** [count] plays of an episode, 10 days apart, the latest on [last]. */
    private fun plays(key: String, count: Int, last: LocalDate) = (0 until count).map { play(key, on(last.minusDays(10L * it)), "episode") }

    private fun stalled(show: String) = Signal(show, SignalKind.STALLED, SignalWeights.STALLED, 0, Fade.HalfLife(SignalWeights.HALF_LIFE_MILLIS))

    private fun picks(
        titles: List<ItemEntity>,
        episodes: List<EpisodeRow> = emptyList(),
        plays: List<PlayEntity> = emptyList(),
        continueWatching: List<ContinueEntity> = emptyList(),
        signals: List<Signal> = emptyList(),
        hides: Hides = Hides(),
        affinity: Map<String, Double> = emptyMap(),
        reason: (ItemEntity) -> String? = { null },
        today: LocalDate = october20,
    ): Map<Lens, List<Picked>> = LensRules.picks(
        RunInput(
            Library(titles, episodes, plays, continueWatching), signals, hides,
            affinity = Affinity { affinity[it.ratingKey] ?: 0.0 },
            now = today.atTime(19, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            zone = ZoneOffset.UTC,
            reason = reason,
        ),
    )

    private fun Map<Lens, List<Picked>>.linesIn(lens: Lens) = getValue(lens).map { it.ratingKey to it.reason }

    @Test
    fun `throwback is a year ago today, then that week, then release anniversaries, then titles you loved, each once`() {
        val found = picks(
            titles = listOf(
                movie("today", "Paper Lanterns").copy(viewCount = 1, lastViewedAt = on(2025, 10, 20), releaseDate = "2016-10-21"),
                show("week", "Harbour Lights"),
                movie("nine-days").copy(viewCount = 1, lastViewedAt = on(2025, 10, 29)),
                movie("anniversary", "Long Summer").copy(viewCount = 1, lastViewedAt = on(2024, 5, 1), releaseDate = "2001-10-23"),
                movie("not-round").copy(viewCount = 1, lastViewedAt = on(2024, 5, 1), releaseDate = "2003-10-20"),
                movie("never-seen").copy(releaseDate = "2001-10-20"),
                movie("loved-and-released", "Quiet Harbour")
                    .copy(userRating = 9.0, viewCount = 1, lastViewedAt = on(2022, 6, 1), releaseDate = "1996-10-18"),
                movie("twice").copy(viewCount = 2, lastViewedAt = on(2021, 3, 1)),
                movie("loved-lately").copy(userRating = 10.0, viewCount = 1, lastViewedAt = on(2024, 1, 1)),
                show("rewatched-show"),
            ),
            episodes = listOf(
                episode("week-e1", "week", 1, 1, viewCount = 1, lastViewed = on(2025, 10, 26)),
                episode("rs-e1", "rewatched-show", 1, 1, viewCount = 2, lastViewed = on(2020, 1, 1)),
                episode("rs-e2", "rewatched-show", 1, 2, viewCount = 2, lastViewed = on(2020, 1, 2)),
            ),
            plays = listOf(play("today", on(2025, 10, 20)), play("week-e1", on(2025, 10, 26), "episode"), play("nine-days", on(2025, 10, 29))),
            affinity = mapOf("loved-and-released" to 1.0, "rewatched-show" to 0.5),
        )

        assertEquals(
            listOf(
                "today" to "One year ago today you watched",
                "week" to "One year ago this week you watched",
                "loved-and-released" to "Released 30 years ago this week",
                "anniversary" to "Released 25 years ago this week",
                "rewatched-show" to "You loved it, then never went back",
                "twice" to "You loved it, then never went back",
            ),
            found.linesIn(Lens.THROWBACK),
        )
    }

    @Test
    fun `a year ago from 29 February 2028 is 28 February 2027`() {
        val found = picks(
            titles = listOf(movie("leap-day"), movie("day-after")),
            plays = listOf(play("leap-day", on(2027, 2, 28)), play("day-after", on(2027, 3, 1))),
            today = LocalDate.of(2028, 2, 29),
        )

        assertEquals(
            listOf("leap-day" to "One year ago today you watched", "day-after" to "One year ago this week you watched"),
            found.linesIn(Lens.THROWBACK),
        )
    }

    @Test
    fun `a release anniversary counts across the new year, 3 days either side`() {
        val found = picks(
            titles = listOf(
                movie("old-year").copy(viewCount = 1, releaseDate = "2001-12-30"),
                movie("four-days-before").copy(viewCount = 1, releaseDate = "2001-12-29"),
                movie("new-year").copy(viewCount = 1, releaseDate = "1997-01-05"),
            ),
            affinity = mapOf("old-year" to 1.0),
            today = LocalDate.of(2027, 1, 2),
        )

        assertEquals(
            listOf("old-year" to "Released 25 years ago this week", "new-year" to "Released 30 years ago this week"),
            found.linesIn(Lens.THROWBACK),
        )
    }

    @Test
    fun `classic is films 25 or more years old, rated 7 or more, unseen or not in 5 years, best rated first`() {
        val found = picks(
            listOf(
                movie("never").copy(year = 1975, audienceRating = 8.5),
                movie("long-ago").copy(year = 1990, rating = 9.0, viewCount = 1, lastViewedAt = on(2019, 6, 1)),
                movie("just-old-enough").copy(year = 2001, audienceRating = 7.0),
                movie("too-young").copy(year = 2002, audienceRating = 9.0),
                movie("audience-says-no").copy(year = 1980, audienceRating = 6.9, rating = 9.5),
                movie("seen-lately").copy(year = 1980, audienceRating = 8.0, viewCount = 1, lastViewedAt = on(2023, 1, 1)),
                show("old-show").copy(year = 1980, audienceRating = 9.0),
            ),
        )

        assertEquals(
            listOf(
                "long-ago" to "36 years old · last watched in 2019",
                "never" to "51 years old and you've never seen it",
                "just-old-enough" to "25 years old and you've never seen it",
            ),
            found.linesIn(Lens.CLASSIC),
        )
    }

    @Test
    fun `a lens has at most 8 titles`() {
        val found = picks((0 until 12).map { movie("c$it").copy(year = 1980, audienceRating = 7.0 + it * 0.1) })

        assertEquals((11 downTo 4).map { "c$it" }, found.getValue(Lens.CLASSIC).map { it.ratingKey })
    }

    @Test
    fun `family is G to TV-PG, Family and Animation first, then what nobody watched this year`() {
        val found = picks(
            titles = listOf(
                movie("cartoon").copy(contentRating = "PG", genres = "Animation|Comedy"),
                show("kids-show").copy(contentRating = "TV-G", genres = "Kids & Family"),
                movie("family-lately").copy(contentRating = "G", genres = "Family", viewCount = 1, lastViewedAt = on(2026, 6, 1)),
                movie("loved").copy(contentRating = "PG", genres = "Comedy", userRating = 9.0, viewCount = 1, lastViewedAt = on(2024, 1, 1)),
                show("watched-show").copy(contentRating = "TV-Y7", genres = "Kids"),
                movie("teen").copy(contentRating = "PG-13", genres = "Family"),
                title("no-file", "movie", "no-file").copy(contentRating = "PG", genres = "Family"),
                movie("unrated").copy(genres = "Family"),
            ),
            episodes = listOf(episode("ws-e1", "watched-show", 1, 1, viewCount = 1, lastViewed = on(2026, 9, 1))),
            affinity = mapOf("cartoon" to 1.0),
        )

        assertEquals(
            listOf(
                "cartoon" to "Rated PG · nobody's seen it yet",
                "kids-show" to "Rated TV-G · nobody's seen it yet",
                "family-lately" to "Rated G",
                "loved" to "Rated PG · everyone loved it last time",
                "watched-show" to "Rated TV-Y7",
            ),
            found.linesIn(Lens.FAMILY),
        )
    }

    @Test
    fun `a family title fits when it's over by 8 30 PM, and nothing fits overnight`() {
        fun at(hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, 24, hour, minute)

        assertTrue(LensRules.familyFits(90 * 60_000L, at(19)))
        assertFalse(LensRules.familyFits(100 * 60_000L, at(19)))
        assertTrue("an unknown length fits", LensRules.familyFits(null, at(19)))
        assertFalse(LensRules.familyFits(10 * 60_000L, at(21)))
        assertFalse(LensRules.familyFits(null, at(20, 30)))
        assertFalse(LensRules.familyFits(90 * 60_000L, at(4)))
        assertTrue(LensRules.familyFits(90 * 60_000L, at(5)))
    }

    @Test
    fun `action is action and adventure not seen in 2 years, by taste then rating, with the engine's reason first`() {
        val found = picks(
            titles = listOf(
                movie("never").copy(genres = "Action|Thriller", audienceRating = 8.2),
                movie("long-ago").copy(genres = "Adventure", rating = 9.0, viewCount = 1, lastViewedAt = on(2021, 3, 1)),
                show("liked").copy(genres = "Action & Adventure"),
                movie("lately").copy(genres = "Action", viewCount = 1, lastViewedAt = on(2025, 12, 1)),
                movie("drama").copy(genres = "Drama"),
                title("no-file", "movie", "no-file").copy(genres = "Action"),
                movie("unrated").copy(genres = "Action"),
            ),
            affinity = mapOf("liked" to 1.0, "never" to 0.5),
            reason = { if (it.ratingKey == "liked") "Because you liked Iron Tide" else null },
        )

        assertEquals(
            listOf(
                "liked" to "Because you liked Iron Tide",
                "never" to "Rated 8.2 · never played",
                "long-ago" to "Last watched in 2021",
                "unrated" to "Never played",
            ),
            found.linesIn(Lens.ACTION),
        )
    }

    @Test
    fun `hidden gems are your library's top 15 percent by rating, never played, and 60 days in`() {
        // 40 ratings from 1.0 to 8.8: the top 15% is the 6 from 7.8 up.
        val rated = (0 until 40).map { movie("r$it").copy(audienceRating = 1.0 + it * 0.2, addedAt = on(2023, 3, 1)) }.toMutableList()
        rated[38] = rated[38].copy(viewCount = 1)
        rated[37] = rated[37].copy(viewOffsetMs = 600_000)
        rated[36] = rated[36].copy(addedAt = on(october20.minusDays(30)))
        rated[35] = show("r35").copy(audienceRating = 8.0, addedAt = on(2023, 3, 1))
        val found = picks(
            titles = rated + movie("unrated").copy(addedAt = on(2023, 3, 1)),
            episodes = listOf(episode("r35-e1", "r35", 1, 1, viewCount = 1, lastViewed = on(2025, 1, 1))),
            affinity = mapOf("r39" to 1.0),
        )

        assertEquals(
            listOf(
                "r39" to "Rated 8.8 · in your library since 2023, never played",
                "r34" to "Rated 7.8 · in your library since 2023, never played",
            ),
            found.linesIn(Lens.HIDDEN_GEM),
        )
    }

    @Test
    fun `a collection too big to be a series, a list like a top 250, isn't one`() {
        val list = (1..LensTuning.SERIES_MAX_FILMS + 1).map { n ->
            movie("top-$n", "Film $n").copy(collections = "Top 250", year = 1990 + n, viewCount = if (n == 1) 1 else 0)
        }

        assertEquals(emptyList<Picked>(), picks(titles = list).getValue(Lens.FINISH_SERIES))
        val series = list.drop(1).map { it.copy(viewCount = if (it.ratingKey == "top-2") 1 else 0) }
        assertEquals("one film fewer is a series", 1, picks(titles = series).getValue(Lens.FINISH_SERIES).size)
    }

    @Test
    fun `finish the series is the next film after the latest one you finished in each collection`() {
        fun film(key: String, name: String, collection: String, watched: Boolean, released: String? = null, year: Int? = null) =
            movie(key, name).copy(collections = collection, viewCount = if (watched) 1 else 0, releaseDate = released, year = year)
        val found = picks(
            titles = listOf(
                film("sk1", "Sand Kings", "Sand Kings Collection", watched = true, released = "2021-10-22"),
                film("sk3", "Sand Kings: Messiah", "Sand Kings Collection", watched = false, released = "2026-12-18"),
                film("sk2", "Sand Kings: Part Two", "Sand Kings Collection", watched = false, released = "2024-03-01"),
                film("h1", "Harbour One", "Harbour Trilogy", watched = true, year = 1990),
                film("h2", "Harbour Two", "Harbour Trilogy", watched = true, released = "1993-05-01"),
                film("h3", "Harbour Three", "Harbour Trilogy", watched = false, year = 1996),
                film("g1", "Gap One", "Gap Series", watched = true, year = 2000),
                film("g2", "Gap Two", "Gap Series", watched = false, year = 2002),
                film("g3", "Gap Three", "Gap Series", watched = true, year = 2004),
                film("g4", "Gap Four", "Gap Series", watched = false, year = 2006),
                film("u1", "Untouched One", "Untouched", watched = false, year = 2010),
                film("u2", "Untouched Two", "Untouched", watched = false, year = 2012),
                film("d1", "Done One", "Done", watched = true, year = 2010),
                film("d2", "Done Two", "Done", watched = true, year = 2012),
            ),
            affinity = mapOf("sk2" to 3.0, "h3" to 2.0, "g2" to 1.0),
        )

        assertEquals(
            listOf(
                "sk2" to "You watched Sand Kings · Sand Kings: Part Two is next",
                "h3" to "You watched Harbour Two · Harbour Three is next",
                "g2" to "You watched Gap One · Gap Two is next",
            ),
            found.linesIn(Lens.FINISH_SERIES),
        )
    }

    @Test
    fun `finish the series plays the next episode of a show you stopped, unless it's in Continue Watching`() {
        val watched = on(2026, 6, 1)
        val found = picks(
            titles = listOf(
                show("harbour", "Harbour Lights"), show("ferry", "Night Ferry"), show("deep", "Deep Water"),
                show("coast", "Coastline"), show("light", "Lighthouse"), show("tide", "Tidewater"),
            ),
            episodes = listOf(
                episode("hl-s0e1", "harbour", 0, 1),
                episode("hl-s1e1", "harbour", 1, 1, viewCount = 1, lastViewed = watched),
                episode("hl-s1e2", "harbour", 1, 2, viewCount = 1, lastViewed = watched),
                episode("hl-s2e1", "harbour", 2, 1, viewCount = 1, lastViewed = watched),
                episode("hl-s2e2", "harbour", 2, 2, viewCount = 1, lastViewed = watched),
                episode("hl-s2e3", "harbour", 2, 3, viewCount = 1, lastViewed = watched),
                episode("hl-s2e4", "harbour", 2, 4),
                episode("hl-s2e5", "harbour", 2, 5),
                episode("nf-s1e1", "ferry", 1, 1, viewCount = 1, lastViewed = watched),
                episode("nf-s1e2", "ferry", 1, 2),
                episode("dw-s1e1", "deep", 1, 1, viewCount = 1, lastViewed = watched),
                episode("dw-s1e2", "deep", 1, 2, viewCount = 1, lastViewed = watched),
                episode("dw-s2e1", "deep", 2, 1),
                episode("cl-s1e1", "coast", 1, 1, viewCount = 1, lastViewed = watched),
                episode("cl-s1e2", "coast", 1, 2),
                episode("lh-s1e1", "light", 1, 1, viewCount = 1, lastViewed = watched),
                episode("lh-s1e2", "light", 1, 2),
                episode("td-s1e1", "tide", 1, 1, viewCount = 1, lastViewed = watched),
                episode("td-s1e2", "tide", 1, 2),
            ),
            continueWatching = listOf(
                ContinueEntity(0, "cl-s1e2", "episode", "Episode 2", "Coastline", 1, 2, null, null, null, null, null, null, null, showKey = "coast"),
                ContinueEntity(1, "light", "show", "Lighthouse", null, null, null, null, null, null, null, null, null, null),
            ),
            signals = listOf("harbour", "ferry", "deep", "coast", "light").map(::stalled),
            affinity = mapOf("harbour" to 3.0, "ferry" to 2.0, "deep" to 1.0),
        )

        assertEquals(
            listOf(
                "hl-s2e4" to "You stopped Harbour Lights 3 episodes into season 2",
                "nf-s1e2" to "You stopped Night Ferry 1 episode into season 1",
                "dw-s2e1" to "You stopped Deep Water after season 1",
            ),
            found.linesIn(Lens.FINISH_SERIES),
        )
    }

    @Test
    fun `seasonal runs from 15 October, and through December, and not in between or in spring`() {
        val titles = listOf(
            movie("lanterns", "Lantern Night").copy(labels = "Halloween"),
            movie("lodge", "Snow Lodge").copy(collections = "Christmas"),
        )
        fun seasonal(year: Int, month: Int, day: Int) =
            picks(titles, today = LocalDate.of(year, month, day)).getValue(Lens.SEASONAL).map { it.ratingKey }

        assertEquals(emptyList<String>(), seasonal(2026, 10, 14))
        assertEquals(listOf("lanterns"), seasonal(2026, 10, 15))
        assertEquals(emptyList<String>(), seasonal(2026, 11, 30))
        assertEquals(listOf("lodge"), seasonal(2026, 12, 1))
        assertEquals(emptyList<String>(), seasonal(2027, 3, 15))
    }

    @Test
    fun `seasonal puts your collections and labels first, then whole words in the title or summary`() {
        val found = picks(
            titles = listOf(
                movie("label", "Lantern Night").copy(labels = "Favourites|halloween"),
                movie("collection", "The Hollow Fair").copy(collections = "Halloween"),
                movie("in-title", "The Haunted Pier"),
                movie("in-summary", "Quiet Street").copy(summary = "A family moves into a house full of ghosts."),
                movie("hyphenated", "Porch Light").copy(summary = "On trick-or-treat night, two kids get lost."),
                movie("part-word", "Witchita Nights").copy(summary = "A road trip across the plains."),
                movie("other-season", "Snow Lodge").copy(collections = "Christmas"),
            ),
            affinity = mapOf("label" to 1.0, "collection" to 0.5, "in-title" to 5.0, "in-summary" to 2.0, "hyphenated" to 1.0),
        )

        assertEquals(
            listOf("label", "collection", "in-title", "in-summary", "hyphenated"),
            found.getValue(Lens.SEASONAL).map { it.ratingKey },
        )
        assertEquals("A Halloween film you haven't seen", found.getValue(Lens.SEASONAL).first().reason)
    }

    @Test
    fun `a seasonal favourite is one you finished in Octobers running, or Decembers for the holidays`() {
        val halloween = picks(
            titles = listOf(
                movie("favourite", "Lantern Night").copy(labels = "Halloween", viewCount = 3),
                movie("gap", "Pumpkin Hill").copy(viewCount = 2),
                movie("unseen", "Ghost Lake"),
                show("show", "Haunted Harbour"),
            ),
            plays = listOf(
                play("favourite", on(2024, 10, 31)), play("favourite", on(2025, 10, 30)), play("favourite", on(2026, 10, 1)),
                play("gap", on(2024, 10, 31)), play("gap", on(2026, 10, 1)),
            ),
            affinity = mapOf("gap" to 3.0, "unseen" to 2.0, "show" to 1.0),
        )
        val holidays = picks(
            titles = listOf(movie("favourite", "Snow Lodge").copy(collections = "Holidays", viewCount = 2), movie("unseen", "The Reindeer Run")),
            plays = listOf(play("favourite", on(2024, 12, 24)), play("favourite", on(2025, 12, 20))),
            today = LocalDate.of(2026, 12, 10),
        )

        assertEquals(
            listOf(
                "favourite" to "A Halloween favourite, watched three Octobers running",
                "gap" to "A Halloween film you've seen",
                "unseen" to "A Halloween film you haven't seen",
                "show" to "A Halloween show you haven't seen",
            ),
            halloween.linesIn(Lens.SEASONAL),
        )
        assertEquals(
            listOf("favourite" to "A holiday favourite, watched two Decembers running", "unseen" to "A holiday film you haven't seen"),
            holidays.linesIn(Lens.SEASONAL),
        )
    }

    @Test
    fun `comfort is films finished twice and your 5 most-watched shows this year, rested and 2 hours or less`() {
        val september = LocalDate.of(2026, 9, 1)
        val found = picks(
            titles = listOf(
                movie("six").copy(viewCount = 6, lastViewedAt = on(2026, 8, 1), durationMs = 100 * 60_000L),
                movie("three").copy(viewCount = 1, durationMs = 95 * 60_000L),
                movie("lately").copy(viewCount = 2, lastViewedAt = on(2026, 10, 15), durationMs = 90 * 60_000L),
                movie("long").copy(viewCount = 4, lastViewedAt = on(2026, 1, 1), durationMs = 150 * 60_000L),
                movie("once").copy(viewCount = 1, lastViewedAt = on(2026, 1, 1)),
            ) + listOf("a", "b", "c", "d", "e", "f", "g").map { show(it) },
            episodes = listOf(
                episode("a1", "a", 1, 1, viewCount = 3, lastViewed = on(2026, 7, 1)),
                episode("a2", "a", 1, 2, viewCount = 3, lastViewed = on(september)),
                episode("a3", "a", 1, 3, viewCount = 5, lastViewed = on(2026, 8, 1), minutes = 150),
                episode("a4", "a", 1, 4),
                episode("b1", "b", 1, 1, viewCount = 2, lastViewed = on(september)),
                episode("c1", "c", 1, 1, viewCount = 1, lastViewed = on(september)),
                episode("d1", "d", 1, 1, viewCount = 1, lastViewed = on(september)),
                episode("e1", "e", 1, 1, viewCount = 1, lastViewed = on(september)),
                episode("f1", "f", 1, 1, viewCount = 1, lastViewed = on(september)),
                episode("g1", "g", 1, 1, viewCount = 1, lastViewed = on(2026, 10, 17)),
            ),
            plays = listOf(play("three", on(2026, 1, 5)), play("three", on(2026, 3, 5)), play("three", on(2026, 5, 5))) +
                plays("a2", 6, september) + plays("b1", 5, september) + plays("c1", 4, september) + plays("d1", 3, september) +
                plays("e1", 2, september) + plays("f1", 1, september) + plays("f1", 10, LocalDate.of(2025, 9, 30)) +
                plays("g1", 8, LocalDate.of(2026, 10, 17)),
        )

        assertEquals(
            setOf(
                "six" to "Watched six times · your most rewatched",
                "three" to "Watched three times",
                "a2" to "One of your most-watched shows",
                "b1" to "One of your most-watched shows",
                "c1" to "One of your most-watched shows",
                "d1" to "One of your most-watched shows",
                "e1" to "One of your most-watched shows",
            ),
            found.linesIn(Lens.COMFORT).toSet(),
        )
    }

    @Test
    fun `your most rewatched film is across all of them, so a rested one doesn't claim it`() {
        val found = picks(
            listOf(
                movie("nine").copy(viewCount = 9, lastViewedAt = on(2026, 10, 17)),
                movie("six").copy(viewCount = 6, lastViewedAt = on(2026, 8, 1)),
            ),
        )

        assertEquals(listOf("six" to "Watched six times"), found.linesIn(Lens.COMFORT))
    }

    @Test
    fun `Not tonight keeps a title out of every lens, an episode by its show, and Don't show this out of one lens`() {
        val found = picks(
            titles = listOf(
                movie("hidden-here").copy(year = 1980, audienceRating = 8.0, addedAt = on(2023, 1, 1)),
                movie("not-tonight").copy(year = 1975, audienceRating = 8.0, addedAt = on(2023, 1, 1)),
                show("stopped"),
                show("comfort"),
            ),
            episodes = listOf(
                episode("st-e1", "stopped", 1, 1, viewCount = 1, lastViewed = on(2026, 6, 1)),
                episode("st-e2", "stopped", 1, 2),
                episode("cm-e1", "comfort", 1, 1, viewCount = 1, lastViewed = on(2026, 8, 1)),
                episode("cm-e2", "comfort", 1, 2, viewCount = 2, lastViewed = on(2026, 9, 1)),
            ),
            plays = plays("cm-e2", 2, LocalDate.of(2026, 9, 1)),
            signals = listOf(stalled("stopped")),
            hides = Hides(
                notTonight = setOf("not-tonight", "st-e1"),
                lens = mapOf(Lens.CLASSIC.name to setOf("hidden-here"), Lens.COMFORT.name to setOf("cm-e1")),
            ),
        )

        assertEquals(Lens.entries.toSet(), found.keys)
        assertEquals(emptyList<Picked>(), found.getValue(Lens.CLASSIC))
        assertEquals(listOf("hidden-here"), found.getValue(Lens.HIDDEN_GEM).map { it.ratingKey })
        assertEquals(emptyList<Picked>(), found.getValue(Lens.FINISH_SERIES))
        assertEquals(emptyList<Picked>(), found.getValue(Lens.COMFORT))
    }

    /** Three classics, best rated first. */
    private val classics = (1..3).map { movie("c$it").copy(year = 1970, audienceRating = 9.0 - it * 0.1) }

    @Test
    fun `a title resting (passed over on the card) steps aside, and the lens's next one takes its place`() {
        val found = picks(titles = classics, hides = Hides(rested = mapOf("c1" to 100L)))

        assertEquals(listOf("c2", "c3"), found.getValue(Lens.CLASSIC).map { it.ratingKey })
    }

    @Test
    fun `a lens with only resting titles shows them, the longest-rested first`() {
        val found = picks(titles = classics, hides = Hides(rested = mapOf("c1" to 300L, "c2" to 100L, "c3" to 200L)))

        assertEquals(listOf("c2", "c3", "c1"), found.getValue(Lens.CLASSIC).map { it.ratingKey })
    }

    @Test
    fun `anything played in the last 2 weeks, or in Up Next, is off every lens`() {
        fun family(key: String) = movie(key).copy(contentRating = "PG", genres = "Family")
        val found = picks(
            titles = listOf(
                family("recent").copy(viewCount = 1, lastViewedAt = on(2026, 10, 10)),
                family("fortnight").copy(viewCount = 1, lastViewedAt = on(2026, 10, 6)),
                family("upnext"),
            ),
            continueWatching = listOf(ContinueEntity(0, "upnext", "movie", "upnext", null, null, null, 2010, 90 * 60_000L, 10 * 60_000L, "PG", null, null, null)),
        )

        assertEquals(listOf("fortnight"), found.getValue(Lens.FAMILY).map { it.ratingKey })
    }
}
