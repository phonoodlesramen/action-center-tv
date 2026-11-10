package com.phonoodlesramen.actioncenter.apps.usage

import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Process
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phonoodlesramen.actioncenter.apps.UsageCounts
import com.phonoodlesramen.actioncenter.apps.UsageHours
import com.phonoodlesramen.actioncenter.apps.UsageRow
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowUsageStatsManager

/** The Apps row's counts from Android's usage events (M5), against Robolectric's usage stats and app ops. */
@RunWith(RobolectricTestRunner::class)
class AndroidAppUsageTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(app, UsageDatabase::class.java).allowMainThreadQueries().build()
    private val events = shadowOf(app.getSystemService(UsageStatsManager::class.java))
    private val logs = mutableListOf<String>()

    /** Monday 26 October 2026, 9 PM UTC. */
    private var now = LocalDateTime.of(2026, 10, 26, 21, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val week = UsageHours.weekStart(LocalDateTime.of(2026, 10, 26, 21, 0).toLocalDate())

    private fun usage(source: UsageEventSource = AndroidUsageEvents(app)) = AndroidAppUsage(
        db.dao(), source, app.packageName, clock = { now }, zone = { ZoneOffset.UTC }, io = Dispatchers.IO, log = { logs += it },
    )

    private fun access(mode: Int) =
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, mode)

    private fun resumed(packageName: String, minutesAgo: Long) = events.addEvent(
        ShadowUsageStatsManager.EventBuilder.buildEvent()
            .setPackage(packageName)
            .setTimeStamp(now - minutesAgo * MINUTE)
            .setEventType(UsageEvents.Event.ACTIVITY_RESUMED)
            .build(),
    )

    @Before
    fun projectivyIsAHomeApp() {
        val pm = shadowOf(app.packageManager)
        pm.addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = PROJECTIVY
                name = "$PROJECTIVY.MainActivity"
                applicationInfo = ApplicationInfo().apply { packageName = PROJECTIVY }
            },
        )
        pm.addIntentFilterForActivity(
            ComponentName(PROJECTIVY, "$PROJECTIVY.MainActivity"),
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) },
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `without usage access nothing is read or recorded`() = runTest {
        access(AppOpsManager.MODE_IGNORED)
        resumed(YOUTUBE, minutesAgo = 30)

        usage().refresh()

        assertEquals(UsageCounts(), usage().counts.first())
        assertNull(db.dao().lastRead())
        assertEquals(listOf("usage: no usage access, nothing read"), logs)
    }

    @Test
    fun `opens are counted, but not Action Center's or another Home app's, and the log says only how many`() = runTest {
        access(AppOpsManager.MODE_ALLOWED)
        resumed(YOUTUBE, minutesAgo = 50)
        resumed(app.packageName, minutesAgo = 40)
        resumed(YOUTUBE, minutesAgo = 39)
        resumed(PROJECTIVY, minutesAgo = 20)
        resumed(NETFLIX, minutesAgo = 10)

        val usage = usage()
        usage.refresh()

        assertEquals(setOf(UsageRow(YOUTUBE, week, 20, 2), UsageRow(NETFLIX, week, 20, 1)), usage.counts.first().rows.toSet())
        assertEquals(UsageReadEntity(readUntil = now, front = NETFLIX), db.dao().lastRead())
        assertEquals(listOf("usage: 5 events since 2026-08-31T21:00, oldest 2026-10-26T20:10, 3 opens"), logs)
    }

    @Test
    fun `a refresh within 15 minutes of the last reads nothing`() = runTest {
        access(AppOpsManager.MODE_ALLOWED)
        val usage = usage()
        usage.refresh()

        now += 5 * MINUTE
        resumed(YOUTUBE, minutesAgo = 0)
        now += 9 * MINUTE
        usage.refresh()
        assertEquals(UsageCounts(), usage.counts.first())

        now += MINUTE
        usage.refresh()
        assertEquals(listOf(UsageRow(YOUTUBE, week, 21, 1)), usage.counts.first().rows)
        assertEquals(2, logs.size)
    }

    @Test
    fun `each read starts where the last ended, and the first goes back 8 weeks`() = runTest {
        access(AppOpsManager.MODE_ALLOWED)
        resumed(KODI, minutesAgo = 9 * WEEK)
        resumed(NETFLIX, minutesAgo = 6 * WEEK)
        val usage = usage()
        usage.refresh()
        assertEquals(listOf(UsageRow(NETFLIX, week - 42, 21, 1)), usage.counts.first().rows)

        now += 20 * MINUTE
        resumed(YOUTUBE, minutesAgo = 5)
        usage.refresh()

        assertEquals(setOf(UsageRow(NETFLIX, week - 42, 21, 1), UsageRow(YOUTUBE, week, 21, 1)), usage.counts.first().rows.toSet())
        assertEquals("usage: 1 events since 2026-10-26T21:00, oldest 2026-10-26T21:15, 1 opens", logs.last())
    }

    @Test
    fun `a read that fails keeps the counts and never throws`() = runTest {
        db.dao().record(listOf(UsageRow(YOUTUBE, week, 20, 1)), UsageReadEntity(readUntil = now - WEEK * MINUTE), keepFromWeek = week - 49)
        val broken = object : UsageEventSource {
            override fun granted() = true
            override fun homePackages() = emptySet<String>()
            override fun events(from: Long, to: Long): Sequence<UsageEvent> = throw SecurityException("no access")
        }

        usage(broken).refresh()

        assertEquals(listOf(UsageRow(YOUTUBE, week, 20, 1)), usage().counts.first().rows)
        assertEquals(listOf("usage: failed: SecurityException: no access"), logs)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val WEEK = 7 * 24 * 60L
        const val PROJECTIVY = "com.spocky.projengmenu"
        const val YOUTUBE = "com.google.android.youtube.tv"
        const val NETFLIX = "com.netflix.ninja"
        const val KODI = "org.xbmc.kodi"
    }
}
