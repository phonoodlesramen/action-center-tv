package com.phonoodlesramen.actioncenter.update

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Updates from inside the app: Settings › About, safe mode's screen, and the daily check. Only
 * a press downloads or installs. Every call is quick and never throws: the work runs in the
 * background, and [status] says how it went.
 */
interface UpdatesController {
    val status: StateFlow<UpdateStatus>

    /** Check for updates, now. */
    fun checkNow() {}

    /** Download the available update if it isn't yet, check it, and hand it to Android's installer. */
    fun install() {}

    /**
     * The top bar's chip: install it if it's downloaded and Install unknown apps is on (Android
     * asks first on Android 11), else Settings › About.
     */
    fun chipPressed(openAbout: () -> Unit) = openAbout()

    fun setMode(mode: UpdateMode) {}

    /**
     * Once a Home screen opens: keeps the daily check scheduled (or not, by the mode), tidies
     * an update that's now installed, and returns "Updated to 1.2.0" once, the first time a
     * newer version starts.
     */
    suspend fun started(): String? = null

    /** What Android's installer said ([UpdateStatusReceiver]). */
    fun installerSaid(answer: InstallerAnswer) {}

    /** Previews, screenshots and tests: a build that doesn't update. */
    companion object None : UpdatesController {
        override val status: StateFlow<UpdateStatus> = MutableStateFlow(UpdateStatus(enabled = false))
    }
}

/** What the updater is doing right now. */
sealed interface UpdateWork {
    data object Idle : UpdateWork
    data object Checking : UpdateWork
    data class Downloading(val percent: Int) : UpdateWork

    /** Android reads the download, and it's compared with the installed app. */
    data object Verifying : UpdateWork

    /** Android's installer has it. */
    data object Installing : UpdateWork
}

/** What About and safe mode show. */
data class UpdateStatus(
    /** This build checks for updates: a release build with a repository ([UpdateConfig]). */
    val enabled: Boolean = true,
    /** Why it doesn't: a debug build. */
    val debugBuild: Boolean = false,
    /** `owner/name`: where updates come from. */
    val repo: String = "",
    val mode: UpdateMode = UpdateMode.ASK,
    /** Android 12 and later: an update installs with no dialog, so Install automatically can install by itself. */
    val silentInstalls: Boolean = false,
    /** When the last check got an answer (wall clock). */
    val checkedMillis: Long? = null,
    /** The latest release, when it's newer than this copy. */
    val available: UpdateInfo? = null,
    /** [available]'s APK is downloaded and checked. */
    val ready: Boolean = false,
    val work: UpdateWork = UpdateWork.Idle,
    val problem: String? = null,
    val problemMillis: Long? = null,
)

/**
 * The top bar's chip while an update waits for the person (A6): it takes the context chip's
 * place, below the stock launcher's and above Finish setup. Grown-ups only.
 */
sealed interface UpdateChip {
    val label: String

    /** A newer release, not downloaded yet: OK opens Settings › About. */
    data class Available(val version: String) : UpdateChip {
        override val label: String get() = "Update available · $version"
    }

    /** Downloaded and checked: OK installs it (on Android 11, Android asks once more). */
    data class Ready(val version: String) : UpdateChip {
        override val label: String get() = "Update ready · $version"
    }

    companion object {
        /** The chip for [status], if any: none for a kid, while it's being installed, or with nothing new. */
        fun of(status: UpdateStatus, kid: Boolean): UpdateChip? {
            val available = status.available ?: return null
            return when {
                kid || !status.enabled -> null
                status.work == UpdateWork.Installing -> null
                status.ready -> Ready(available.versionName)
                else -> Available(available.versionName)
            }
        }
    }
}

/** About's status line, and whether it's in the caution colour. */
data class UpdateLine(val text: String, val caution: Boolean = false) {
    companion object {
        private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR

        fun of(status: UpdateStatus, nowMillis: Long, zone: ZoneId): UpdateLine {
            val name = status.available?.versionName
            return when {
                !status.enabled && status.debugBuild -> UpdateLine("Debug builds don't update themselves.")
                !status.enabled -> UpdateLine("This build doesn't check for updates: it was built without a repository to check.")
                status.work == UpdateWork.Checking -> UpdateLine("Checking for updates…")
                status.work is UpdateWork.Downloading -> UpdateLine("Downloading $name… ${status.work.percent}%")
                status.work == UpdateWork.Verifying -> UpdateLine("Checking the download…")
                status.work == UpdateWork.Installing -> UpdateLine("Installing $name…")
                status.problem != null -> {
                    val at = status.problemMillis?.let { " (${clock(it, zone)})" }.orEmpty()
                    UpdateLine("${status.problem}$at", caution = true)
                }
                status.available != null && status.ready && status.mode == UpdateMode.AUTOMATIC && status.silentInstalls ->
                    UpdateLine("$name is ready, and installs once the TV is idle")
                status.available != null && status.ready -> UpdateLine("$name is ready to install")
                status.available != null -> UpdateLine("$name is available · ${status.available.megabytes} MB")
                status.checkedMillis != null -> UpdateLine("Up to date · checked ${ago(status.checkedMillis, nowMillis, zone)}")
                status.mode == UpdateMode.OFF -> UpdateLine("Not checked yet. It checks only when you ask.")
                else -> UpdateLine("Not checked yet. It checks once a day.")
            }
        }

        /** "just now", "12 min ago", "3 h ago", or "on Nov 10". */
        fun ago(thenMillis: Long, nowMillis: Long, zone: ZoneId): String {
            val since = (nowMillis - thenMillis).coerceAtLeast(0)
            return when {
                since < MINUTE -> "just now"
                since < HOUR -> "${since / MINUTE} min ago"
                since < DAY -> "${since / HOUR} h ago"
                else -> "on " + DateTimeFormatter.ofPattern("MMM d", Locale.US).format(Instant.ofEpochMilli(thenMillis).atZone(zone))
            }
        }

        private fun clock(millis: Long, zone: ZoneId): String = CLOCK.format(Instant.ofEpochMilli(millis).atZone(zone))
    }
}
