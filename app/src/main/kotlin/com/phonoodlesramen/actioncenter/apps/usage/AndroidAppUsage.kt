package com.phonoodlesramen.actioncenter.apps.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.room.Room
import com.phonoodlesramen.actioncenter.apps.AppUsage
import com.phonoodlesramen.actioncenter.apps.UsageAccess
import com.phonoodlesramen.actioncenter.apps.UsageCounts
import com.phonoodlesramen.actioncenter.apps.UsageHours
import com.phonoodlesramen.actioncenter.apps.UsageScore
import com.phonoodlesramen.actioncenter.safety.summary
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What [AndroidAppUsage] reads from Android. */
interface UsageEventSource {
    /** Usage access ([UsageAccess]). */
    fun granted(): Boolean

    /** Every package with a Home activity: the stock launcher (disabled or not), Projectivy, Action Center. */
    fun homePackages(): Set<String>

    /** Android's events from [from] to [to] (millis), oldest first, read as they're used. */
    fun events(from: Long, to: Long): Sequence<UsageEvent>
}

/**
 * The Apps row's counts (M5), from Android's usage events into `usage.db`. Android keeps
 * only a few days of events, so each read adds its opens to 8 weeks of the launcher's own.
 * [refresh] runs on every resume and after every Plex sync, at most every 15 minutes, off
 * the main thread. It never throws: without usage access it records nothing, and a read that
 * fails keeps the old counts. Each refresh logs one line under `ActionCenterApps`: how many
 * events and opens it read, and the oldest event Android still had; never which apps.
 */
class AndroidAppUsage(
    private val dao: UsageDao,
    private val source: UsageEventSource,
    /** Action Center itself, which never counts. */
    private val ownPackage: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = { Log.i(LOG_TAG, it) },
) : AppUsage {
    private val reading = Mutex()

    /** When the last refresh began, held under [reading]; null until the first. */
    private var lastRefresh: Long? = null

    override val counts: Flow<UsageCounts> = dao.rows().map(::UsageCounts)

    override suspend fun refresh() {
        withContext(io) {
            reading.withLock {
                val now = clock()
                // A clock moved back reads again rather than waiting.
                if (lastRefresh?.let { now - it in 0 until REFRESH_EVERY_MILLIS } == true) return@withLock
                lastRefresh = now
                try {
                    if (source.granted()) read(now) else log("usage: no usage access, nothing read")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("usage: failed: ${e.summary()}")
                }
            }
        }
    }

    private suspend fun read(now: Long) {
        val zone = zone()
        val keepFromWeek = UsageScore.oldestWeek(UsageHours.weekStart(Instant.ofEpochMilli(now).atZone(zone).toLocalDate()))
        val last = dao.lastRead()
        // The first read goes back 8 weeks, for whatever Android still has.
        val since = (last?.readUntil ?: (now - FIRST_READ_MILLIS)).coerceIn(now - FIRST_READ_MILLIS, now)
        var events = 0
        var oldest: Long? = null
        val opens = UsageOpens.count(
            source.events(since, now).onEach {
                events++
                oldest = minOf(oldest ?: it.millis, it.millis)
            },
            front = last?.front,
            skip = source.homePackages() + ownPackage,
            zone = zone,
        )
        dao.record(opens.rows, UsageReadEntity(readUntil = now, front = opens.front), keepFromWeek)
        log("usage: $events events since ${minute(since, zone)}, oldest ${oldest?.let { minute(it, zone) } ?: "none"}, ${opens.count} opens")
    }

    private fun minute(millis: Long, zone: ZoneId) =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).truncatedTo(ChronoUnit.MINUTES).toString()

    companion object {
        const val LOG_TAG = "ActionCenterApps"
        const val REFRESH_EVERY_MILLIS = 15 * 60_000L
        const val FIRST_READ_MILLIS = UsageScore.WEEKS * 7 * 24 * 60 * 60_000L

        /** The app's: `usage.db`, opened on its first read. */
        fun create(context: Context): AndroidAppUsage {
            val database = Room.databaseBuilder(context, UsageDatabase::class.java, UsageDatabase.NAME)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
            return AndroidAppUsage(database.dao(), AndroidUsageEvents(context), context.packageName)
        }
    }
}

/** [UsageEventSource] on the Shield: the app op, the package manager and `UsageStatsManager`. */
class AndroidUsageEvents(private val context: Context) : UsageEventSource {
    override fun granted() = UsageAccess.granted(context)

    override fun homePackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        // The stock launcher is disabled, but older events still name it.
        return context.packageManager.queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)
            .mapTo(HashSet()) { it.activityInfo.packageName }
    }

    override fun events(from: Long, to: Long): Sequence<UsageEvent> = sequence {
        val events = context.getSystemService(UsageStatsManager::class.java)?.queryEvents(from, to) ?: return@sequence
        // One Event, filled in turn: Android's list is the only copy.
        val event = UsageEvents.Event()
        while (events.getNextEvent(event)) {
            yield(
                UsageEvent(
                    packageName = event.packageName.orEmpty(),
                    millis = event.timeStamp,
                    resumed = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED,
                    className = event.className.orEmpty(),
                    screenOff = event.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                ),
            )
        }
    }
}
