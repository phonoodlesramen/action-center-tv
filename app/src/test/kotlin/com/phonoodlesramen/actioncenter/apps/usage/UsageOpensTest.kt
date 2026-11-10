package com.phonoodlesramen.actioncenter.apps.usage

import com.phonoodlesramen.actioncenter.apps.UsageHours
import com.phonoodlesramen.actioncenter.apps.UsageRow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/** M5: Android's resume events as opens per app and hour. */
class UsageOpensTest {
    private val zone = ZoneOffset.UTC
    private val week = UsageHours.weekStart(LocalDate.of(2026, 10, 26))

    /** Monday 26 October 2026 at [hour]:[minute] UTC. */
    private fun monday(hour: Int, minute: Int) = LocalDateTime.of(2026, 10, 26, hour, minute).toInstant(zone).toEpochMilli()

    private fun resumed(packageName: String, millis: Long) = UsageEvent(packageName, millis, resumed = true)

    private fun count(vararg events: UsageEvent, front: String? = null) =
        UsageOpens.count(events.asSequence(), front, skip = setOf(ACTION_CENTER, PROJECTIVY, TV_LAUNCHER), zone)

    @Test
    fun `each app brought to the front is an open in its hour`() {
        val opens = count(
            resumed(YOUTUBE, monday(21, 5)),
            resumed(NETFLIX, monday(21, 40)),
            resumed(YOUTUBE, monday(22, 10)),
        )

        assertEquals(
            setOf(UsageRow(YOUTUBE, week, 21, 1), UsageRow(NETFLIX, week, 21, 1), UsageRow(YOUTUBE, week, 22, 1)),
            opens.rows.toSet(),
        )
        assertEquals(3, opens.count)
        assertEquals(YOUTUBE, opens.front)
    }

    @Test
    fun `moving between one app's activities is one open, and other events don't count`() {
        val opens = count(
            resumed(YOUTUBE, monday(21, 5)),
            UsageEvent(YOUTUBE, monday(21, 6), resumed = false),
            resumed(YOUTUBE, monday(21, 7)),
            resumed(YOUTUBE, monday(21, 50)),
        )

        assertEquals(listOf(UsageRow(YOUTUBE, week, 21, 1)), opens.rows)
    }

    @Test
    fun `Action Center and Home apps never count, but going back to an app from them is a new open`() {
        val opens = count(
            resumed(YOUTUBE, monday(21, 5)),
            resumed(ACTION_CENTER, monday(21, 20)),
            resumed(YOUTUBE, monday(21, 21)),
            resumed(PROJECTIVY, monday(21, 30)),
            resumed(TV_LAUNCHER, monday(21, 31)),
            resumed(YOUTUBE, monday(21, 32)),
            resumed(ACTION_CENTER, monday(21, 59)),
        )

        assertEquals(listOf(UsageRow(YOUTUBE, week, 21, 3)), opens.rows)
        assertEquals(ACTION_CENTER, opens.front)
    }

    @Test
    fun `the app in front at the end of the last read doesn't count again`() {
        val opens = count(resumed(YOUTUBE, monday(21, 5)), resumed(NETFLIX, monday(21, 40)), front = YOUTUBE)

        assertEquals(listOf(UsageRow(NETFLIX, week, 21, 1)), opens.rows)
    }

    @Test
    fun `hours are local time`() {
        // Sunday 25 October, 23:30 UTC is Monday 0:30 an hour east.
        val sundayNight = LocalDateTime.of(2026, 10, 25, 23, 30).toInstant(zone).toEpochMilli()

        val opens = UsageOpens.count(sequenceOf(resumed(YOUTUBE, sundayNight)), front = null, skip = emptySet(), ZoneOffset.ofHours(1))

        assertEquals(listOf(UsageRow(YOUTUBE, week, 0, 1)), opens.rows)
    }

    @Test
    fun `no events, no opens`() {
        assertEquals(Opens(emptyList(), 0, YOUTUBE), count(front = YOUTUBE))
    }

    private companion object {
        const val ACTION_CENTER = "com.phonoodlesramen.actioncenter"
        const val PROJECTIVY = "com.spocky.projengmenu"
        const val TV_LAUNCHER = "com.google.android.tvlauncher"
        const val YOUTUBE = "com.google.android.youtube.tv"
        const val NETFLIX = "com.netflix.ninja"
    }
}
