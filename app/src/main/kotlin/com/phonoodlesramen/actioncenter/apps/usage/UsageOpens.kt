package com.phonoodlesramen.actioncenter.apps.usage

import com.phonoodlesramen.actioncenter.apps.UsageHours
import com.phonoodlesramen.actioncenter.apps.UsageRow
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One of Android's usage events: [resumed] when it's an activity of [packageName] ([className])
 * coming to the front, [screenOff] when the screen went off (then [packageName] is `android`).
 */
data class UsageEvent(
    val packageName: String,
    val millis: Long,
    val resumed: Boolean,
    val className: String = "",
    val screenOff: Boolean = false,
)

/** A read's opens per bucket, how many, and the app in front at its end. */
data class Opens(val rows: List<UsageRow>, val count: Int, val front: String?)

/** Android's resume events as opens (M5). Pure. */
object UsageOpens {
    /**
     * An open is the front app changing to another package, so moving between one app's
     * activities is one open. [skip] (Action Center and the Home apps) changes the front app
     * but never counts, so leaving an app for the home and going back is a second open.
     * [front] is the app in front at the end of the last read: still there, it doesn't
     * count again. [events] is read once, in order, so it can stream from Android.
     */
    fun count(events: Sequence<UsageEvent>, front: String?, skip: Set<String>, zone: ZoneId): Opens {
        var current = front
        var count = 0
        val buckets = HashMap<Bucket, Int>()
        for (event in events) {
            if (!event.resumed || event.packageName == current) continue
            current = event.packageName
            if (event.packageName in skip) continue
            val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.millis), zone)
            buckets.merge(Bucket(event.packageName, UsageHours.weekStart(time.toLocalDate()), UsageHours.hourOfWeek(time)), 1, Int::plus)
            count++
        }
        val rows = buckets.map { (bucket, opens) -> UsageRow(bucket.packageName, bucket.weekStart, bucket.hourOfWeek, opens) }
        return Opens(rows, count, current)
    }

    private data class Bucket(val packageName: String, val weekStart: Long, val hourOfWeek: Int)
}
