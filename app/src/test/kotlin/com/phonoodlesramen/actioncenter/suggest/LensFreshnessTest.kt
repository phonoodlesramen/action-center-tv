package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.plex.cache.PlayEntity
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/** Made-up titles. Today is 2 November 2026 at 8 PM, UTC. */
class LensFreshnessTest {
    private val today: LocalDate = LocalDate.of(2026, 11, 2)
    private val now = today.atTime(20, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun movie(key: String) = ItemEntity(
        ratingKey = key, sectionKey = "1", type = "movie", title = key, year = null, durationMs = null,
        viewCount = 0, lastViewedAt = null, addedAt = null, updatedAt = null, contentRating = null, audienceRating = null,
        rating = null, thumb = null, art = null, genres = "", syncedAt = 0,
    )

    private fun shown(key: String, vararg daysAgo: Long) = daysAgo.map { LensShownEntity(Profiles.OWNER, key, today.minusDays(it).toString()) }

    private fun played(key: String, daysAgo: Long) = PlayEntity("$key@$daysAgo", key, "movie", key, today.minusDays(daysAgo).atTime(21, 0).toEpochSecond(ZoneOffset.UTC), null)

    private fun rests(shown: List<LensShownEntity>, days: Int?, plays: List<PlayEntity> = emptyList()) =
        LensFreshness.rests(shown, days, Library(listOf(movie("jaws"), movie("heat")), emptyList(), plays, emptyList()), Profiles.OWNER, now, ZoneOffset.UTC)

    @Test
    fun `on the card on 3 days without a play, a title rests for 30 days`() {
        val rested = rests(shown("jaws", 1, 4, 9) + shown("heat", 1, 2), days = 3)

        assertEquals(listOf("jaws"), rested.map { it.ratingKey })
        assertEquals(FeedbackKind.LENS_RESTED, rested.single().kind)
        assertEquals(now + FeedbackRules.REST_MILLIS, rested.single().untilMillis)
    }

    @Test
    fun `today doesn't count until it's over`() {
        assertEquals(emptyList<FeedbackEntity>(), rests(shown("jaws", 0, 1, 2), days = 3))
        assertEquals(listOf("jaws"), rests(shown("jaws", 0, 1), days = 1).map { it.ratingKey })
    }

    @Test
    fun `a play starts the count again`() {
        val shown = shown("jaws", 1, 6, 7, 8)

        assertEquals(emptyList<FeedbackEntity>(), rests(shown, days = 3, plays = listOf(played("jaws", 5))))
        assertEquals(listOf("jaws"), rests(shown, days = 3, plays = listOf(played("jaws", 20))).map { it.ratingKey })
    }

    @Test
    fun `the setting's days, and Once it's watched rests nothing`() {
        val week = shown("jaws", 1, 2, 3, 4, 5, 6)

        assertEquals(emptyList<FeedbackEntity>(), rests(week, days = 7))
        assertEquals(listOf("jaws"), rests(week + shown("jaws", 7), days = 7).map { it.ratingKey })
        assertEquals(emptyList<FeedbackEntity>(), rests(week + shown("jaws", 7), days = null))
    }
}
