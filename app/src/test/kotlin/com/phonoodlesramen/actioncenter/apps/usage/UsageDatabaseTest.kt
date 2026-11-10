package com.phonoodlesramen.actioncenter.apps.usage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phonoodlesramen.actioncenter.apps.UsageRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** `usage.db` (M5): opens add up per bucket, 8 weeks are kept, and where the last read ended. */
@RunWith(RobolectricTestRunner::class)
class UsageDatabaseTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UsageDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.dao()

    /** Monday 26 October 2026, as an epoch day. */
    private val week = 20_752L

    @After
    fun tearDown() = db.close()

    @Test
    fun `a read's opens add to the bucket they fall in`() = runTest {
        dao.record(listOf(UsageRow(YOUTUBE, week, 21, 2)), UsageReadEntity(readUntil = 1), keepFromWeek = week - 49)
        dao.record(
            listOf(UsageRow(YOUTUBE, week, 21, 3), UsageRow(YOUTUBE, week, 22, 1), UsageRow(NETFLIX, week, 21, 1)),
            UsageReadEntity(readUntil = 2),
            keepFromWeek = week - 49,
        )

        assertEquals(
            setOf(UsageRow(YOUTUBE, week, 21, 5), UsageRow(YOUTUBE, week, 22, 1), UsageRow(NETFLIX, week, 21, 1)),
            dao.rows().first().toSet(),
        )
    }

    @Test
    fun `weeks before the oldest kept go`() = runTest {
        dao.record(
            listOf(UsageRow(YOUTUBE, week - 56, 21, 4), UsageRow(YOUTUBE, week - 49, 21, 2), UsageRow(YOUTUBE, week, 21, 1)),
            UsageReadEntity(readUntil = 1),
            keepFromWeek = week - 49,
        )
        assertEquals(setOf(week - 49, week), dao.rows().first().map { it.weekStart }.toSet())

        dao.record(emptyList(), UsageReadEntity(readUntil = 2), keepFromWeek = week - 42)

        assertEquals(listOf(UsageRow(YOUTUBE, week, 21, 1)), dao.rows().first())
    }

    @Test
    fun `the last read is where the latest one ended, and the app in front then`() = runTest {
        assertNull(dao.lastRead())

        dao.record(emptyList(), UsageReadEntity(readUntil = 1_000, front = YOUTUBE), keepFromWeek = week - 49)
        dao.record(emptyList(), UsageReadEntity(readUntil = 2_000, front = null), keepFromWeek = week - 49)

        assertEquals(UsageReadEntity(readUntil = 2_000, front = null), dao.lastRead())
    }

    private companion object {
        const val YOUTUBE = "com.google.android.youtube.tv"
        const val NETFLIX = "com.netflix.ninja"
    }
}
