package com.phonoodlesramen.actioncenter.plex.cache

import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.context.ContextMode
import com.phonoodlesramen.actioncenter.context.ContextSchedule
import com.phonoodlesramen.actioncenter.context.Hours
import com.phonoodlesramen.actioncenter.home.PickState
import com.phonoodlesramen.actioncenter.suggest.BlurbEntity
import com.phonoodlesramen.actioncenter.suggest.PickEntity
import com.phonoodlesramen.actioncenter.suggest.Profiles
import com.phonoodlesramen.actioncenter.suggest.Surfaces
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexCardsTest {
    private val zone = ZoneOffset.UTC

    /** Monday 26 October 2026, 8:41 PM. */
    private val now = LocalDateTime.of(2026, 10, 26, 20, 41)
    private fun daysAgo(days: Long) = now.minusDays(days).toEpochSecond(zone)

    private fun movie(
        key: String,
        title: String,
        year: Int = 2016,
        viewCount: Int = 0,
        lastViewedAt: Long? = null,
        addedDaysAgo: Long = 3,
        trailerKey: String? = null,
    ) = ItemEntity(
        ratingKey = key, sectionKey = "1", type = "movie", title = title, year = year, durationMs = 116 * 60_000L,
        viewCount = viewCount, lastViewedAt = lastViewedAt, addedAt = daysAgo(addedDaysAgo), updatedAt = null,
        contentRating = "PG-13", audienceRating = 8.0, rating = null, thumb = "/t/$key", art = "/a/$key", genres = "", syncedAt = 0,
        trailerKey = trailerKey,
    )

    private fun episode(key: String, show: String, season: Int, episode: Int, minutes: Long, watchedMinutes: Long = 0, position: Int) = ContinueEntity(
        position, key, "episode", "Episode $episode", show, season, episode, null, minutes * 60_000, watchedMinutes * 60_000,
        "TV-14", "/thumb/$key", null, "/art/$show",
    )

    private fun snapshot(
        continueWatching: List<ContinueEntity> = emptyList(),
        newest: List<ItemEntity> = emptyList(),
        classics: List<ItemEntity> = emptyList(),
        throwbacks: List<ItemEntity> = emptyList(),
        itemCount: Int = 10,
        picks: List<PickEntity> = emptyList(),
        titles: List<ItemEntity> = emptyList(),
        blurbs: List<BlurbEntity> = emptyList(),
    ) = PlexSnapshot(
        LocalDate.of(2026, 10, 26), continueWatching, newest, classics, throwbacks, itemCount, picks, titles.associateBy { it.ratingKey },
        blurbs.associateBy { it.ratingKey },
    )

    /** One of the engine's picks. */
    private fun row(surface: String, rank: Int, key: String, reason: String = "A reason", evidence: String = "") =
        PickEntity(Profiles.OWNER, surface, rank, key, reason, evidence, 0)

    private fun show(key: String, title: String) = movie(key, title).copy(type = "show", durationMs = null, art = "/a/$key", year = 2022)

    private fun episodeTitle(key: String, show: String, showTitle: String, season: Int, number: Int, minutes: Long) =
        movie(key, "Episode $number").copy(
            type = "episode", showKey = show, showTitle = showTitle, season = season, episode = number,
            durationMs = minutes * 60_000, contentRating = "TV-MA", thumb = "/t/$key", art = null,
        )

    @Test
    fun `an empty cache is null, so the hero keeps saying it's loading`() {
        assertNull(PlexCards.build(snapshot(itemCount = 0), now, zone))
    }

    @Test
    fun `Tonight's Pick is the newest unwatched film, with an honest reason`() {
        val content = PlexCards.build(snapshot(newest = listOf(movie("1", "Arrival"), movie("2", "Jaws", year = 1975, addedDaysAgo = 1))), now, zone)!!

        val pick = content.picks.first()
        assertEquals("Arrival", pick.title)
        assertEquals("2016 · 1h 56m · PG-13", pick.meta)
        assertEquals("Added to Plex 3 days ago", pick.reason)
        assertEquals("/a/1", pick.image)
        assertEquals("Added to Plex yesterday", content.picks[1].reason)
        assertTrue(content.fromPlex)
    }

    @Test
    fun `with nothing new, classics fill Tonight's Pick`() {
        val content = PlexCards.build(snapshot(classics = listOf(movie("9", "Jaws", year = 1975))), now, zone)!!

        assertEquals("Jaws", content.picks.single().title)
        assertEquals("51 years old and you've never seen it", content.picks.single().reason)
    }

    @Test
    fun `a pick carries its trailer extra and year, for the Trailer button`() {
        val content = PlexCards.build(
            snapshot(newest = listOf(movie("1", "Arrival", trailerKey = "1t"), movie("2", "Jaws", year = 1975))),
            now, zone,
        )!!

        assertEquals(listOf("1t" to 2016, null to 1975), content.picks.map { it.trailerKey to it.year })
        val classic = PlexCards.build(snapshot(classics = listOf(movie("9", "Heat", year = 1995, trailerKey = "9t"))), now, zone)!!
        assertEquals("9t" to 1995, classic.picks.single().let { it.trailerKey to it.year })
    }

    @Test
    fun `Up Next is Plex's Continue Watching, with time left and progress`() {
        val content = PlexCards.build(
            snapshot(continueWatching = listOf(episode("e1", "Slow Horses", 4, 2, minutes = 49, watchedMinutes = 8, position = 0))),
            now, zone,
        )!!

        val up = content.upNext.single()
        assertEquals("Slow Horses", up.title)
        assertEquals("S4E2", up.episode)
        assertEquals("41m left", up.timeLeft)
        assertEquals(8f / 49f, up.progress, 0.001f)
        assertEquals("/thumb/e1", up.image)
    }

    @Test
    fun `Quick Watch takes short items Up Next isn't showing, that fit before the cutoff`() {
        val cw = listOf(
            episode("a", "A", 1, 1, 30, position = 0),
            episode("b", "B", 1, 1, 30, position = 1),
            episode("c", "C", 1, 1, 30, position = 2),
            episode("bear", "The Bear", 2, 3, 26, position = 3),
            episode("long", "Shogun", 1, 7, 58, position = 4),
            episode("abbott", "Abbott Elementary", 3, 4, 22, watchedMinutes = 2, position = 5),
        )

        val content = PlexCards.build(snapshot(continueWatching = cw), now, zone)!!

        assertEquals("10:30", content.quickWatchCutoff)
        assertEquals(listOf("Abbott Elementary", "The Bear"), content.quickWatch.map { it.title })
        assertEquals("S3E4 · 20m left", content.quickWatch.first().meta)
        assertEquals("S2E3 · 26m", content.quickWatch[1].meta)
    }

    @Test
    fun `late in the evening the cutoff moves to 45 minutes from now`() {
        val late = PlexCards.build(snapshot(continueWatching = listOf(episode("a", "A", 1, 1, 30, position = 0))), now.withHour(23).withMinute(30), zone)!!

        assertEquals("12:15", late.quickWatchCutoff)
    }

    @Test
    fun `the lens card gets Throwback from last year's history and Classic by age`() {
        val seenLongAgo = movie("h", "Heat", year = 1995, viewCount = 2, lastViewedAt = LocalDateTime.of(2019, 3, 1, 20, 0).toEpochSecond(zone))
        val content = PlexCards.build(
            snapshot(throwbacks = listOf(movie("t", "Amélie", year = 2001, viewCount = 1)), classics = listOf(seenLongAgo)),
            now, zone,
        )!!

        assertEquals("One year ago this week you watched", content.lenses.getValue(Lens.THROWBACK).single().line)
        val classic = content.lenses.getValue(Lens.CLASSIC).single()
        assertEquals("31 years old · last watched in 2019", classic.line)
        assertTrue(classic.seen)
    }

    @Test
    fun `once the engine has run, Tonight's Pick is its picks, an episode shown as its show, with the evidence`() {
        val content = PlexCards.build(
            snapshot(
                picks = listOf(
                    row(Surfaces.HERO, 0, "e9", "You're 2 episodes from the end of Severance's season 2"),
                    row(Surfaces.HERO, 1, "m1", "Because you liked Heat", evidence = "Rated 8.4|New this week"),
                ),
                titles = listOf(episodeTitle("e9", "s1", "Severance", 2, 9, 50), show("s1", "Severance"), movie("m1", "Arrival")),
            ),
            now, zone,
        )!!

        val (finish, film) = content.picks
        assertEquals(
            listOf("Severance", "S2E9 · 50m · TV-MA", "Play S2E9", "s1", "/a/s1"),
            listOf(finish.title, finish.meta, finish.playLabel, finish.titleKey, finish.image),
        )
        assertEquals("e9", finish.id)
        assertEquals("Because you liked Heat", film.reason)
        assertEquals(listOf("Rated 8.4", "New this week"), film.evidence)
    }

    @Test
    fun `the hero and the lens card carry each title's logo, an episode its show's`() {
        val content = PlexCards.build(
            snapshot(
                picks = listOf(
                    row(Surfaces.HERO, 0, "e9"),
                    row(Surfaces.HERO, 1, "m1"),
                    row(Surfaces.lens(Lens.COMFORT), 0, "e3"),
                    row(Surfaces.lens(Lens.CLASSIC), 0, "m2"),
                    row(Surfaces.moreLike("m1"), 0, "m3"),
                ),
                titles = listOf(
                    episodeTitle("e9", "s1", "Severance", 2, 9, 50).copy(logo = "/library/metadata/e9/clearLogo/1"),
                    show("s1", "Severance").copy(logo = "/library/metadata/s1/clearLogo/1"),
                    movie("m1", "Arrival").copy(logo = "/library/metadata/m1/clearLogo/1"),
                    episodeTitle("e3", "s2", "Harbor Lights", 1, 3, 25),
                    show("s2", "Harbor Lights").copy(logo = "/library/metadata/s2/clearLogo/1"),
                    movie("m2", "Jaws"),
                    movie("m3", "Sicario").copy(logo = "/library/metadata/m3/clearLogo/1"),
                ),
            ),
            now, zone,
        )!!

        assertEquals("the show's, not the episode's", listOf("/library/metadata/s1/clearLogo/1", "/library/metadata/m1/clearLogo/1"), content.picks.map { it.logo })
        assertEquals("/library/metadata/s2/clearLogo/1", content.lenses.getValue(Lens.COMFORT).single().logo)
        assertNull("none: the title shows", content.lenses.getValue(Lens.CLASSIC).single().logo)
        assertEquals("/library/metadata/m3/clearLogo/1", content.moreLike.getValue("m1").single().logo)
    }

    @Test
    fun `before the engine runs, the interim cards carry logos too`() {
        val content = PlexCards.build(
            snapshot(
                newest = listOf(movie("m1", "Arrival").copy(logo = "/library/metadata/m1/clearLogo/1")),
                throwbacks = listOf(movie("t", "Amélie", year = 2001, viewCount = 1).copy(logo = "/library/metadata/t/clearLogo/1")),
                classics = listOf(movie("c", "Jaws", year = 1975)),
            ),
            now, zone,
        )!!

        assertEquals("/library/metadata/m1/clearLogo/1", content.picks.single().logo)
        assertEquals("/library/metadata/t/clearLogo/1", content.lenses.getValue(Lens.THROWBACK).single().logo)
        assertNull(content.lenses.getValue(Lens.CLASSIC).single().logo)
    }

    @Test
    fun `Up Next is the engine's order without the hero's show, until Not tonight moves the hero on`() {
        val cw = listOf(
            episode("e9", "Severance", 2, 9, 50, position = 0).copy(showKey = "s1"),
            episode("x1", "Slow Horses", 4, 2, 49, watchedMinutes = 8, position = 1).copy(showKey = "s2"),
        )
        val engine = snapshot(
            continueWatching = cw,
            picks = listOf(
                row(Surfaces.HERO, 0, "e9"), row(Surfaces.HERO, 1, "m1"),
                row(Surfaces.UP_NEXT, 0, "e9"), row(Surfaces.UP_NEXT, 1, "x1"),
            ),
            titles = listOf(episodeTitle("e9", "s1", "Severance", 2, 9, 50), show("s1", "Severance"), movie("m1", "Arrival")),
        )

        assertEquals(listOf("Slow Horses"), PlexCards.build(engine, now, zone)!!.upNext.map { it.title })
        val movedOn = PlexCards.build(engine, now, zone, shown = PickState(shownId = "m1"))!!
        assertEquals(listOf("Severance", "Slow Horses"), movedOn.upNext.map { it.title })
        assertEquals("s1", movedOn.upNext.first().titleKey)
    }

    @Test
    fun `long films move down Tonight's Pick by the mode, not the clock`() {
        val engine = snapshot(
            picks = listOf(row(Surfaces.HERO, 0, "long"), row(Surfaces.HERO, 1, "two"), row(Surfaces.HERO, 2, "short")),
            titles = listOf(
                movie("long", "Long").copy(durationMs = 170 * 60_000L), movie("two", "Two Hours").copy(durationMs = 120 * 60_000L),
                movie("short", "Short").copy(durationMs = 100 * 60_000L),
            ),
        )
        fun titles(at: LocalDateTime, schedule: ContextSchedule = ContextSchedule.DEFAULT) =
            PlexCards.build(engine, at, zone, schedule = schedule)!!.picks.map { it.title }

        assertEquals("Monday afternoon is daytime", listOf("Long", "Two Hours", "Short"), titles(now.withHour(14)))
        assertEquals("weeknight evening: over 2h 15m", listOf("Two Hours", "Short", "Long"), titles(now))
        assertEquals("late night: over 1h 45m", listOf("Short", "Long", "Two Hours"), titles(now.withHour(23)))
        assertEquals("Friday evening has no limit", listOf("Long", "Two Hours", "Short"), titles(now.plusDays(4)))
        val lateFromEight = ContextSchedule.DEFAULT
            .with(ContextMode.WEEKNIGHT_EVENING, Hours(LocalTime.of(18, 0), LocalTime.of(20, 0)))
            .with(ContextMode.LATE_NIGHT, Hours(LocalTime.of(20, 0), LocalTime.of(5, 0)))
        assertEquals("your schedule decides", listOf("Short", "Long", "Two Hours"), titles(now, lateFromEight))
    }

    @Test
    fun `each mode orders the engine's hero rows, and the pick on screen stays put`() {
        val engine = snapshot(
            picks = listOf(
                row(Surfaces.HERO, 0, "e7", "You're 4 episodes from the end of Copper Hill").copy(episodesLeft = 4),
                row(Surfaces.HERO, 1, "epic", "Because you liked Heat"),
                row(Surfaces.HERO, 2, "s1e1", "New to you"),
                row(Surfaces.HERO, 3, "short", "Rated 8.0"),
                row(Surfaces.HERO, 4, "family", "Rated 8.0"),
            ),
            titles = listOf(
                episodeTitle("e7", "copper", "Copper Hill", 2, 7, 50), show("copper", "Copper Hill"),
                // The new series' first episode has no rating of its own: its show's counts.
                episodeTitle("s1e1", "kite", "Kite Club", 1, 1, 24).copy(contentRating = null), show("kite", "Kite Club").copy(contentRating = "TV-Y7"),
                movie("epic", "Epic").copy(durationMs = 170 * 60_000L, contentRating = "R"),
                movie("short", "Short").copy(durationMs = 95 * 60_000L),
                movie("family", "Family").copy(durationMs = 100 * 60_000L, contentRating = "PG"),
            ),
        )
        fun ids(at: LocalDateTime) = PlexCards.build(engine, at, zone)!!.picks.map { it.id }
        val monday = now
        val friday = now.plusDays(4)
        val saturdayMorning = LocalDateTime.of(2026, 10, 31, 9, 0)

        assertEquals("daytime: 4 out goes below the first film", listOf("epic", "e7", "s1e1", "short", "family"), ids(monday.withHour(14)))
        assertEquals("weeknight evening", listOf("e7", "s1e1", "short", "family", "epic"), ids(monday))
        assertEquals("weekend evening", listOf("epic", "short", "family", "e7", "s1e1"), ids(friday))
        assertEquals("late night", listOf("short", "e7", "family", "epic", "s1e1"), ids(monday.withHour(23)))
        assertEquals("weekend morning", listOf("s1e1", "family", "e7", "epic", "short"), ids(saturdayMorning))

        // 10:29 PM is a weeknight evening and 10:30 PM late night: the hero you're on doesn't change.
        val shown = PickState(shownId = "family")
        assertEquals("family", shown.current(PlexCards.build(engine, monday.withHour(22).withMinute(29), zone, shown = shown)!!.picks)?.id)
        assertEquals("family", shown.current(PlexCards.build(engine, monday.withHour(22).withMinute(30), zone, shown = shown)!!.picks)?.id)
    }

    @Test
    fun `Quick Watch uses the weekend cutoff on Friday and Saturday nights`() {
        val engine = snapshot(picks = listOf(row(Surfaces.HERO, 0, "m1")), titles = listOf(movie("m1", "Arrival")))
        fun cutoff(day: Int, hour: Int) =
            PlexCards.build(engine, LocalDateTime.of(2026, 10, day, hour, 0), zone, cutoff = LocalTime.of(22, 30), weekendCutoff = LocalTime.of(0, 30))!!
                .quickWatchCutoff

        assertEquals("Thursday", "10:30", cutoff(29, 21))
        assertEquals("Friday", "12:30", cutoff(30, 21))
        assertEquals("Saturday", "12:30", cutoff(31, 21))
        assertEquals("Saturday 1 AM is still Friday's night: the next 45 minutes", "1:45", cutoff(31, 1))
        assertEquals("Friday 1 AM is Thursday's night", "10:30", cutoff(30, 1))
    }

    @Test
    fun `Quick Watch is the engine's candidates without Up Next, that fit before your cutoff`() {
        val engine = snapshot(
            continueWatching = listOf(episode("x1", "Slow Horses", 4, 2, 30, watchedMinutes = 10, position = 0)),
            picks = listOf(
                row(Surfaces.UP_NEXT, 0, "x1"),
                row(Surfaces.QUICK_WATCH, 0, "x1"), row(Surfaces.QUICK_WATCH, 1, "q1"), row(Surfaces.QUICK_WATCH, 2, "f1"),
            ),
            titles = listOf(episodeTitle("q1", "s3", "Abbott Elementary", 1, 3, 22), movie("f1", "Short Film").copy(durationMs = 40 * 60_000L)),
        )

        val default = PlexCards.build(engine, now, zone)!!
        assertEquals("10:30", default.quickWatchCutoff)
        assertEquals(listOf("Abbott Elementary" to "S1E3 · 22m", "Short Film" to "40m"), default.quickWatch.map { it.title to it.meta })
        assertEquals("s3", default.quickWatch.first().titleKey)

        // 9:15 PM is 34 minutes away: the 40-minute film no longer fits.
        val early = PlexCards.build(engine, now, zone, cutoff = LocalTime.of(21, 15))!!
        assertEquals("9:15", early.quickWatchCutoff)
        assertEquals(listOf("Abbott Elementary"), early.quickWatch.map { it.title })
    }

    @Test
    fun `Quick Watch doesn't repeat the hero's show`() {
        // Seen on the Shield: an episode of the hero's show in Quick Watch below it.
        val engine = snapshot(
            continueWatching = listOf(episode("next", "Late Sketch", 52, 1, 30, watchedMinutes = 20, position = 0).copy(showKey = "s9")),
            picks = listOf(
                row(Surfaces.HERO, 0, "old"),
                row(Surfaces.QUICK_WATCH, 0, "next"), row(Surfaces.QUICK_WATCH, 1, "f1"),
            ),
            titles = listOf(episodeTitle("old", "s9", "Late Sketch", 49, 18, 60), movie("f1", "Short Film").copy(durationMs = 40 * 60_000L)),
        )

        val content = PlexCards.build(engine, now, zone)!!

        assertEquals("Late Sketch", content.picks.single().title)
        assertEquals(listOf("Short Film"), content.quickWatch.map { it.title })
    }

    @Test
    fun `For the family keeps what's over by half past eight, and More like this comes by the pick`() {
        val engine = snapshot(
            picks = listOf(
                row(Surfaces.lens(Lens.FAMILY), 0, "short", "Rated PG"), row(Surfaces.lens(Lens.FAMILY), 1, "long", "Rated PG"),
                row(Surfaces.HERO, 0, "m1"), row(Surfaces.moreLike("m1"), 0, "like", "Also Dena Varga"),
            ),
            titles = listOf(
                movie("short", "Short").copy(durationMs = 100 * 60_000L), movie("long", "Long").copy(durationMs = 160 * 60_000L),
                movie("m1", "Arrival"), movie("like", "Sicario"),
            ),
        )

        val content = PlexCards.build(engine, now.withHour(18).withMinute(0), zone)!!
        assertEquals(listOf("Short"), content.lenses.getValue(Lens.FAMILY).map { it.title })
        assertEquals(listOf("Sicario" to "Also Dena Varga"), content.moreLike.getValue("m1").map { it.title to it.line })
    }

    @Test
    fun `an AI blurb shows in place of the reason it was written for, and Why this pick keeps the reason`() {
        val heat = "Because you liked Heat"
        val content = PlexCards.build(
            snapshot(
                picks = listOf(
                    row(Surfaces.HERO, 0, "m1", heat, evidence = "Rated 8.4"),
                    row(Surfaces.HERO, 1, "m2", "Rated 8.8 · never played"),
                    row(Surfaces.lens(Lens.CLASSIC), 0, "m3", "51 years old and you've never seen it"),
                ),
                titles = listOf(movie("m1", "Arrival"), movie("m2", "Jaws"), movie("m3", "Chinatown")),
                blurbs = listOf(
                    BlurbEntity(Profiles.OWNER, "m1", heat, "Another patient, clever puzzle like Heat", 0),
                    // Written when Jaws had another reason: its evidence has moved on, so the rule reason shows.
                    BlurbEntity(Profiles.OWNER, "m2", "Because you liked Alien", "Old blurb", 0),
                    BlurbEntity(Profiles.OWNER, "m3", "51 years old and you've never seen it", "A '70s noir you've somehow missed", 0),
                ),
            ),
            now, zone,
        )!!

        val (arrival, jaws) = content.picks
        assertEquals("Another patient, clever puzzle like Heat", arrival.reason)
        assertEquals(heat, arrival.because)
        assertEquals("Rated 8.8 · never played", jaws.reason)
        assertNull(jaws.because)
        assertEquals("A '70s noir you've somehow missed", content.lenses.getValue(Lens.CLASSIC).single().line)
    }
}
