package com.phonoodlesramen.actioncenter.context

import org.junit.Assert.assertEquals
import org.junit.Test

/** M3 on one fixed set of the engine's hero picks. */
class ModeRulesTest {
    private fun film(minutes: Long?, rating: String? = "PG-13") = HeroPick(HeroPick.Kind.FILM, minutes?.times(60_000), rating)
    private fun finish(left: Int) = HeroPick(HeroPick.Kind.FINISH, 50 * 60_000L, "TV-MA", episodesLeft = left)
    private val series = HeroPick(HeroPick.Kind.SERIES, 45 * 60_000L, "TV-14")

    /** In the engine's order: the finish pick first, the series you haven't started third. */
    private fun picks(left: Int) = listOf(
        "finish" to finish(left),
        "epic" to film(170, "R"),
        "series" to series,
        "short" to film(95),
        "two-hours" to film(120),
        "family" to film(100, "PG"),
    )

    private fun order(mode: ContextMode, left: Int = 3) = ModeRules.order(picks(left), mode) { it.second }.map { it.first }

    private val engine = listOf("finish", "epic", "series", "short", "two-hours", "family")

    @Test
    fun `daytime keeps the engine's order`() {
        assertEquals(engine, order(ContextMode.DAYTIME))
    }

    @Test
    fun `weeknight evening moves films over 2h 15m down, and the finish pick leads from 4 episodes out`() {
        val expected = listOf("finish", "series", "short", "two-hours", "family", "epic")
        assertEquals(expected, order(ContextMode.WEEKNIGHT_EVENING, left = 3))
        assertEquals(expected, order(ContextMode.WEEKNIGHT_EVENING, left = 4))
    }

    @Test
    fun `weekend evening has no length limit, and films come before series`() {
        val expected = listOf("epic", "short", "two-hours", "family", "finish", "series")
        assertEquals(expected, order(ContextMode.WEEKEND_EVENING, left = 3))
        assertEquals(expected, order(ContextMode.WEEKEND_EVENING, left = 4))
    }

    @Test
    fun `late night moves films over 1h 45m and the new series down, and the finish pick comes first`() {
        assertEquals(listOf("finish", "short", "family", "epic", "series", "two-hours"), order(ContextMode.LATE_NIGHT, left = 3))
        assertEquals("4 out, it goes below the first film", listOf("short", "finish", "family", "epic", "series", "two-hours"), order(ContextMode.LATE_NIGHT, left = 4))
    }

    @Test
    fun `weekend morning puts family ratings first`() {
        assertEquals(listOf("family", "finish", "epic", "series", "short", "two-hours"), order(ContextMode.WEEKEND_MORNING, left = 3))
        assertEquals("already below a film, 4 out stays", listOf("family", "finish", "epic", "series", "short", "two-hours"), order(ContextMode.WEEKEND_MORNING, left = 4))
    }

    @Test
    fun `outside weeknight evenings a finish pick 4 out moves below the first film`() {
        assertEquals(listOf("epic", "finish", "series", "short", "two-hours", "family"), order(ContextMode.DAYTIME, left = 4))
        val noFilms = listOf("finish" to finish(4), "series" to series)
        assertEquals("with no film it stays", listOf("finish", "series"), ModeRules.order(noFilms, ContextMode.DAYTIME) { it.second }.map { it.first })
    }

    @Test
    fun `the family ratings are For the family's, PG and TV-PG or lower`() {
        val ratings = listOf("R", "TV-14", "G", "PG", "TV-Y", "TV-Y7", "TV-G", "TV-PG", "PG-13", null)
        val ordered = ModeRules.order(ratings, ContextMode.WEEKEND_MORNING) { film(90, it) }

        assertEquals(listOf("G", "PG", "TV-Y", "TV-Y7", "TV-G", "TV-PG", "R", "TV-14", "PG-13", null), ordered)
    }

    @Test
    fun `the limits are over, not at, and an unknown length counts as short`() {
        val films = listOf("at-limit" to film(135), "unknown" to film(null), "over" to film(136))
        fun order(mode: ContextMode) = ModeRules.order(films, mode) { it.second }.map { it.first }

        assertEquals(listOf("at-limit", "unknown", "over"), order(ContextMode.WEEKNIGHT_EVENING))
        assertEquals(listOf("unknown", "at-limit", "over"), order(ContextMode.LATE_NIGHT))
    }

    @Test
    fun `rules only reorder, never drop a pick`() {
        ContextMode.entries.forEach { mode ->
            assertEquals(mode.label, engine.sorted(), order(mode, left = 4).sorted())
        }
        assertEquals(emptyList<String>(), ModeRules.order(emptyList<String>(), ContextMode.LATE_NIGHT) { series })
    }
}
