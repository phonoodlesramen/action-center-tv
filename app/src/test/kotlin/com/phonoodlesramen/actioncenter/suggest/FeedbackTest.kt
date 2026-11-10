package com.phonoodlesramen.actioncenter.suggest

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FeedbackTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SuggestionDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private var now = 1_793_000_000_000L
    private val store = FeedbackStore(db.dao()) { now }
    private val day = 24 * 60 * 60_000L

    @After
    fun tearDown() = db.close()

    private fun notTonight(previous: FeedbackEntity?, at: Long) =
        FeedbackRules.record(previous, Profiles.OWNER, "m1", FeedbackKind.NOT_TONIGHT, "", at)

    @Test
    fun `Not tonight skips a title for 2 weeks, and a second within 90 days for 6 months`() {
        val first = notTonight(null, now)
        assertEquals(now + 14 * day, first.untilMillis)

        val second = notTonight(first, now + 89 * day)
        assertEquals(2, second.count)
        assertEquals(now + 89 * day + 182 * day, second.untilMillis)

        val later = notTonight(first, now + 91 * day)
        assertEquals("past 90 days it starts over", 1 to now + 91 * day + 14 * day, later.count to later.untilMillis)
    }

    @Test
    fun `each card's hide lasts as long as S10 says`() {
        fun until(kind: FeedbackKind) = FeedbackRules.record(null, Profiles.OWNER, "m1", kind, "", now).untilMillis - now
        assertEquals(30 * day, until(FeedbackKind.QUICK_WATCH_HIDDEN))
        assertEquals(182 * day, until(FeedbackKind.LENS_HIDDEN))
        assertEquals(Long.MAX_VALUE - now, until(FeedbackKind.UP_NEXT_REMOVED))
    }

    @Test
    fun `hides are what's still running, and playing a title again lifts Remove from Up Next`() {
        val rows = listOf(
            FeedbackRules.record(null, Profiles.OWNER, "m1", FeedbackKind.NOT_TONIGHT, "", now - 15 * day),
            FeedbackRules.record(null, Profiles.OWNER, "m2", FeedbackKind.NOT_TONIGHT, "", now - day),
            FeedbackRules.record(null, Profiles.OWNER, "s1", FeedbackKind.UP_NEXT_REMOVED, "", now - 3 * day),
            FeedbackRules.record(null, Profiles.OWNER, "s2", FeedbackKind.UP_NEXT_REMOVED, "", now - 3 * day),
            FeedbackRules.record(null, Profiles.OWNER, "m3", FeedbackKind.LENS_HIDDEN, "CLASSIC", now),
            FeedbackRules.record(null, Profiles.OWNER, "m4", FeedbackKind.QUICK_WATCH_HIDDEN, "", now),
        )
        val watchedAgain = mapOf("s2" to (now - day) / 1_000)

        val hides = FeedbackRules.hides(rows, now) { watchedAgain[it] }

        assertEquals(setOf("m2"), hides.notTonight)
        assertEquals(setOf("s1"), hides.upNext)
        assertEquals(setOf("m4"), hides.quickWatch)
        assertEquals(mapOf("CLASSIC" to setOf("m3")), hides.lens)
    }

    @Test
    fun `Undo takes feedback back, the first goes and a second strike goes back to the first`() = runTest {
        val first = store.record("m1", FeedbackKind.NOT_TONIGHT)
        now += day
        val second = store.record("m1", FeedbackKind.NOT_TONIGHT)
        assertEquals(2, store.all().single().count)

        store.undo(second)
        assertEquals(1, store.all().single().count)
        store.undo(first)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `a lens hide is per lens, and feedback that's faded is pruned`() = runTest {
        store.record("m1", FeedbackKind.LENS_HIDDEN, scope = "CLASSIC")
        store.record("m1", FeedbackKind.LENS_HIDDEN, scope = "THROWBACK")
        assertEquals(setOf("CLASSIC", "THROWBACK"), store.all().map { it.scope }.toSet())

        // Their 6 months ran out over a year ago.
        now += 600 * day
        store.record("m2", FeedbackKind.QUICK_WATCH_HIDDEN)
        store.prune()

        assertEquals(listOf("m2"), store.all().map { it.ratingKey })
        assertNull(db.dao().feedback(Profiles.OWNER, "m1", FeedbackKind.LENS_HIDDEN, "CLASSIC"))
    }
}
