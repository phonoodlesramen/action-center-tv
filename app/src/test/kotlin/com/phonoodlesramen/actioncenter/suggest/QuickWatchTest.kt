package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.cache.ContinueEntity
import com.phonoodlesramen.actioncenter.plex.cache.EpisodeRow
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up titles. Now is Monday 26 October 2026, 8:41 PM. */
class QuickWatchTest {
    private val zone = ZoneOffset.UTC
    private val monday = LocalDateTime.of(2026, 10, 26, 20, 41)
    private val now = monday.toEpochSecond(zone) * 1_000

    private fun time(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)

    /** Phase 2's fixed rule, which the setting replaced: 10:30 PM, or the next 45 minutes from 10 PM. */
    private fun phase2Cutoff(at: LocalTime): LocalTime {
        val evening = LocalTime.of(22, 30)
        if (at < evening.minusMinutes(30)) return evening
        val later = at.plusMinutes(45)
        return later.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusMinutes(((later.minute + 14) / 15 * 15).toLong())
    }

    private fun daysAgo(days: Long) = monday.minusDays(days).toEpochSecond(zone)

    private fun title(key: String, type: String, minutes: Long?, viewCount: Int = 0, watchedMinutes: Long? = null, file: Boolean = true) = ItemEntity(
        ratingKey = key, sectionKey = "1", type = type, title = key, year = 2020, durationMs = minutes?.times(60_000), viewCount = viewCount,
        lastViewedAt = null, addedAt = null, updatedAt = null, contentRating = null, audienceRating = null, rating = null, thumb = null,
        art = null, genres = "", syncedAt = 0, partKey = if (file) "/library/parts/$key/1/file.mkv" else null,
        viewOffsetMs = watchedMinutes?.times(60_000),
    )

    private fun film(key: String, minutes: Long, viewCount: Int = 0, watchedMinutes: Long? = null, file: Boolean = true) =
        title(key, "movie", minutes, viewCount, watchedMinutes, file)

    private fun show(key: String) = title(key, "show", minutes = null, file = false)

    private fun episode(key: String, show: String, number: Int, minutes: Long = 25, watchedDaysAgo: Long? = null) =
        EpisodeRow(key, show, 1, number, minutes * 60_000, if (watchedDaysAgo != null) 1 else 0, watchedDaysAgo?.let(::daysAgo), null, null)

    /** A Continue Watching item: an episode when it has a [show], else a film. */
    private fun cw(position: Int, key: String, minutes: Long, watchedMinutes: Long = 0, show: String? = null) = ContinueEntity(
        position, key, if (show != null) "episode" else "movie", key, show, show?.let { 1 }, show?.let { 1 }, null,
        minutes * 60_000, watchedMinutes * 60_000, null, null, null, null, lastViewedAt = daysAgo(1), showKey = show,
    )

    @Test
    fun `with the default setting the cutoff is what the card gives today`() {
        val expected = mapOf(
            time(20) to time(22, 30),
            time(21, 59) to time(22, 30),
            time(22) to time(22, 45),
            time(22, 15) to time(23),
            time(23, 40) to time(0, 30),
        )
        expected.forEach { (at, cutoff) ->
            assertEquals("at $at", cutoff, QuickWatch.cutoff(QuickWatch.DEFAULT_CUTOFF, at))
            assertEquals("at $at", phase2Cutoff(at), QuickWatch.cutoff(QuickWatch.DEFAULT_CUTOFF, at))
        }
        for (minute in 0L until 24 * 60) {
            val at = LocalTime.MIDNIGHT.plusMinutes(minute)
            assertEquals("at $at", phase2Cutoff(at), QuickWatch.cutoff(QuickWatch.DEFAULT_CUTOFF, at))
        }
    }

    @Test
    fun `a later setting moves the switch with it`() {
        val setting = time(23, 30)
        assertEquals(setting, QuickWatch.cutoff(setting, time(20)))
        assertEquals("the default would have switched by now", setting, QuickWatch.cutoff(setting, time(22, 15)))
        assertEquals(setting, QuickWatch.cutoff(setting, time(22, 59)))
        assertEquals(time(23, 45), QuickWatch.cutoff(setting, time(23)))
        assertEquals(time(0, 30), QuickWatch.cutoff(setting, time(23, 40)))
    }

    @Test
    fun `a setting after midnight holds all evening, and the night runs on to morning`() {
        val halfPast = time(0, 30)
        assertEquals(halfPast, QuickWatch.cutoff(halfPast, time(20)))
        assertEquals(halfPast, QuickWatch.cutoff(halfPast, time(23, 59)))
        assertEquals("12:55, rounded up", time(1), QuickWatch.cutoff(halfPast, time(0, 10)))
        assertEquals(time(3, 45), QuickWatch.cutoff(halfPast, time(3)))
        assertEquals(halfPast, QuickWatch.cutoff(halfPast, time(8)))

        val quarterPast = time(0, 15)
        assertEquals("its switch is before midnight", time(0, 30), QuickWatch.cutoff(quarterPast, time(23, 45)))
        assertEquals(quarterPast, QuickWatch.cutoff(quarterPast, time(23, 44)))
    }

    @Test
    fun `resumes and next episodes come first by time left, then short unseen titles by taste`() {
        val continueWatching = listOf(
            cw(0, "tidewater-3", minutes = 49, watchedMinutes = 8, show = "tidewater"),
            cw(1, "bakery-4", minutes = 26, show = "bakery"),
            cw(2, "corner-office-2", minutes = 22, watchedMinutes = 2, show = "corner-office"),
            cw(3, "long-voyage", minutes = 150, watchedMinutes = 10),
            cw(4, "hidden-show-1", minutes = 20, show = "hidden-show"),
            cw(5, "skipped-show-2", minutes = 20, show = "skipped-show"),
            cw(6, "removed-film", minutes = 90, watchedMinutes = 60),
        )
        val titles = listOf(
            film("pocket-film", 40),
            film("tiny-film", 30),
            film("seen-film", 30, viewCount = 1),
            film("started-film", 30, watchedMinutes = 5),
            film("no-file", 30, file = false),
            film("feature", 100),
            film("skipped-film", 30),
            film("hidden-film", 30),
            show("night-shift"), show("old-show"), show("hour-show"), show("bakery"), show("caught-up"), show("paused-show"),
        )
        val episodes = listOf(
            episode("night-shift-1", "night-shift", 1, watchedDaysAgo = 5), episode("night-shift-2", "night-shift", 2),
            // Last finished 40 days ago.
            episode("old-show-1", "old-show", 1, watchedDaysAgo = 40), episode("old-show-2", "old-show", 2),
            episode("hour-show-1", "hour-show", 1, minutes = 55, watchedDaysAgo = 2), episode("hour-show-2", "hour-show", 2, minutes = 55),
            // Already in Continue Watching.
            episode("bakery-3", "bakery", 3, watchedDaysAgo = 1), episode("bakery-4", "bakery", 4),
            episode("caught-up-1", "caught-up", 1, watchedDaysAgo = 3),
            episode("paused-show-1", "paused-show", 1, watchedDaysAgo = 3), episode("paused-show-2", "paused-show", 2),
        )
        val input = RunInput(
            Library(titles, episodes, emptyList(), continueWatching),
            signals = emptyList(),
            hides = Hides(
                notTonight = setOf("skipped-film", "skipped-show"),
                upNext = setOf("removed-film"),
                quickWatch = setOf("hidden-show", "hidden-film", "paused-show"),
            ),
            affinity = Affinity { if (it.ratingKey == "tiny-film") 1.0 else 0.0 },
            now = now,
            zone = zone,
            reason = { if (it.ratingKey == "pocket-film") "Because you liked Heat" else null },
        )

        // Pocket Film and Night Shift tie on taste: the day's seed orders them.
        val tied = listOf("pocket-film" to "Because you liked Heat", "night-shift-2" to "Next episode")
            .sortedBy { Seeds.tieBreak(it.first, monday.toLocalDate()) }
        assertEquals(
            listOf(
                "corner-office-2" to "20m left",
                "bakery-4" to "Next episode",
                "tidewater-3" to "41m left",
                "tiny-film" to "Short and unseen",
            ) + tied,
            QuickWatch.candidates(input).map { it.ratingKey to it.reason },
        )
    }

    @Test
    fun `at most 12 candidates, Continue Watching first`() {
        val continueWatching = (0 until 3).map { cw(it, "resume-$it", minutes = 30 + it.toLong(), watchedMinutes = 10, show = "series-$it") }
        val titles = (1..20).map { film("short-$it", 30) }

        val picked = QuickWatch.candidates(RunInput(Library(titles, emptyList(), emptyList(), continueWatching), emptyList(), Hides(), now = now, zone = zone))

        assertEquals(12, picked.size)
        assertEquals(listOf("resume-0", "resume-1", "resume-2"), picked.take(3).map { it.ratingKey })
        assertTrue(picked.drop(3).all { it.reason == "Short and unseen" })
    }

    private fun minutes(vararg left: Pair<String, Long>) = left.map { (key, minutes) -> Picked(key, "") to minutes }

    @Test
    fun `fit keeps what ends before the cutoff, in order, up to the limit`() {
        val candidates = minutes("a" to 41, "b" to 20, "c" to 40, "d" to 0, "e" to 35)

        // 9:50 PM: the default cutoff, 10:30, is 40 minutes away.
        val early = time(21, 50)
        val cutoff = QuickWatch.cutoff(QuickWatch.DEFAULT_CUTOFF, early)
        assertEquals(listOf("b", "c"), QuickWatch.fit(candidates, early, cutoff).map { it.ratingKey })
        assertEquals(listOf("b", "c", "e"), QuickWatch.fit(candidates, early, cutoff, limit = 3).map { it.ratingKey })

        // 10:15 PM: the cutoff is 11:00, 45 minutes away, so 41 minutes fits too.
        val late = time(22, 15)
        assertEquals(listOf("a", "b"), QuickWatch.fit(candidates, late, QuickWatch.cutoff(QuickWatch.DEFAULT_CUTOFF, late)).map { it.ratingKey })

        assertEquals("nothing fits at the cutoff", emptyList<Picked>(), QuickWatch.fit(candidates, time(22, 30), time(22, 30)))
    }

    @Test
    fun `Friday and Saturday nights use the weekend cutoff, and a night runs on until 5 AM (M4)`() {
        val weeknights = time(22, 30)
        val weekends = time(23, 30)
        fun night(day: Int, hour: Int, minute: Int = 0) = QuickWatch.settingFor(LocalDateTime.of(2026, 10, day, hour, minute), weeknights, weekends)

        assertEquals("Thursday", weeknights, night(29, 23))
        assertEquals("Friday", weekends, night(30, 23))
        assertEquals("Saturday 1 AM is still Friday's night", weekends, night(31, 1))
        assertEquals("Sunday 1 AM is Saturday's night", weekends, QuickWatch.settingFor(LocalDateTime.of(2026, 11, 1, 1, 0), weeknights, weekends))
        assertEquals("Monday 1 AM is Sunday's night", weeknights, night(26, 1))
        assertEquals("Friday 4:59 AM is Thursday's night", weeknights, night(30, 4, 59))
        assertEquals("from 5 AM it's Friday", weekends, night(30, 5))
    }

    @Test
    fun `fit counts the minutes to a cutoff past midnight`() {
        val candidates = minutes("a" to 45, "b" to 46, "c" to 26, "d" to 25)

        // 11:40 PM: the cutoff is 12:30 AM, 50 minutes away, so 45 is the most.
        val late = time(23, 40)
        assertEquals(
            listOf("a", "c", "d"),
            QuickWatch.fit(candidates, late, QuickWatch.cutoff(QuickWatch.DEFAULT_CUTOFF, late), limit = 4).map { it.ratingKey },
        )
        // 11:50 PM with a 12:15 AM cutoff: 25 minutes.
        assertEquals(listOf("d"), QuickWatch.fit(candidates, time(23, 50), time(0, 15), limit = 4).map { it.ratingKey })
    }
}
