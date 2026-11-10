package com.phonoodlesramen.actioncenter.lastapp

import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.apps.usage.UsageEvent
import com.phonoodlesramen.actioncenter.apps.usage.UsageEventSource
import com.phonoodlesramen.actioncenter.player.GuidePlayerActivity
import kotlin.coroutines.cancellation.CancellationException

/**
 * The last time an app you watch was in front: [packageName] came to the front at [frontAt]
 * and was left at [leftAt], when another screen came up or the screen went off (millis).
 * [frontAt] names the visit: Forget this forgets that one.
 */
data class AppVisit(val packageName: String, val frontAt: Long, val leftAt: Long)

/** The last app watched, from Android's usage events (Back to YouTube TV). Pure. */
object LastVisit {
    /**
     * The last visit to an app [watching] counts, from [events] (oldest first). Anything else
     * coming to the front, or the screen going off, ends a visit without starting one: the
     * home, a keyboard, a chooser or the screensaver never take the place of what you watched.
     * Moving between one app's activities is one visit. A visit still open at the end (the
     * home has come up, but Android hasn't written it yet) ends at [nowMillis].
     */
    fun of(events: Sequence<UsageEvent>, nowMillis: Long, watching: (UsageEvent) -> Boolean): AppVisit? {
        var packageName: String? = null
        var frontAt = 0L
        var leftAt: Long? = null
        for (event in events) {
            val open = packageName != null && leftAt == null
            when {
                event.screenOff -> if (open) leftAt = event.millis
                !event.resumed -> Unit
                watching(event) -> if (!open || event.packageName != packageName) {
                    packageName = event.packageName
                    frontAt = event.millis
                    leftAt = null
                }
                open -> leftAt = event.millis
            }
        }
        return packageName?.let { AppVisit(it, frontAt, leftAt ?: nowMillis) }
    }
}

/** Where the last visit comes from: Android's usage events, read on every resume of the home. */
fun interface RecentApps {
    /** The last visit to one of [apps] (the TV's launchable apps); null without usage access, or if none. Never throws. */
    suspend fun last(apps: Collection<LaunchableApp>): AppVisit?

    companion object {
        /** No usage access, previews and tests: no visit, so no card. */
        val None = RecentApps { null }
    }
}

/**
 * [RecentApps] from Android's usage events over the last [LOOK_BACK_MILLIS]. An app counts
 * when it has a TV launcher icon, and isn't Action Center, a Home app or one of
 * [NOT_WATCHING]. Action Center's guide player counts too ([PLAYER]): it's watching
 * something else. The caller reads it off the main thread.
 */
class UsageRecentApps(
    private val source: UsageEventSource,
    private val ownPackage: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecentApps {
    override suspend fun last(apps: Collection<LaunchableApp>): AppVisit? = try {
        if (!source.granted()) {
            null
        } else {
            val now = clock()
            val watchable = apps.mapTo(HashSet()) { it.packageName } - source.homePackages() - NOT_WATCHING - ownPackage
            LastVisit.of(source.events(now - LOOK_BACK_MILLIS, now), now) { event ->
                event.packageName in watchable || (event.packageName == ownPackage && event.className == PLAYER)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        /** A day: YouTube TV left on all afternoon still has the start of its visit inside it. */
        const val LOOK_BACK_MILLIS = 24 * 60 * 60_000L

        /** Apps with an icon that aren't watching: Settings, and Google's search. */
        val NOT_WATCHING = setOf("com.android.tv.settings", "com.google.android.katniss")

        /** Action Center's guide player. */
        val PLAYER: String = GuidePlayerActivity::class.java.name
    }
}
