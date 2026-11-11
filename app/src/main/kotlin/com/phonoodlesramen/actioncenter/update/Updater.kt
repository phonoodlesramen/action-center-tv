package com.phonoodlesramen.actioncenter.update

import android.os.Build
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Updates from the build's GitHub releases ([UpdateConfig]): a check once a day ([UpdateMode.ASK])
 * or only when asked ([UpdateMode.OFF]), and a download, its checks and Android's installer
 * only after a press.
 *
 * - **A check** reads the latest release's `update.json` ([UpdateFeed.latest]). It's newer when
 *   its version code is above the installed one.
 * - **Install** downloads the APK to [dir] (the cache) unless it's there already, checks its size
 *   and SHA-256 on the way, then that Android reads it as Action Center, newer, and signed with
 *   this copy's key ([UpdateVerification]). Only then does Android's installer get it.
 * - **One thing at a time:** a press while something runs does nothing.
 * - **Never throws:** a crash would put the next start in safe mode. Every failure becomes the
 *   problem on About's line.
 */
class Updater(
    private val config: UpdateConfig,
    private val feed: UpdateFeed,
    private val inspector: ApkInspector,
    private val installer: UpdateInstaller,
    private val store: UpdateStore,
    private val dir: File,
    /** Android's Install unknown apps is on for Action Center. */
    private val canInstall: () -> Boolean,
    private val schedule: UpdateSchedule,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sdk: Int = Build.VERSION.SDK_INT,
    /** What the cache's storage can give a download (`StorageManager.getAllocatableBytes` on a TV). */
    private val freeBytes: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** How long "Installing" shows when Android's installer says nothing (its dialog left open). */
    private val installingForMillis: Long = INSTALLING_FOR_MILLIS,
) : UpdatesController {
    private val work = MutableStateFlow<UpdateWork>(UpdateWork.Idle)
    private val busy = Mutex()
    private val startedOnce = AtomicBoolean(false)

    /** The installed app's own facts; null only if Android can't say. */
    private val installed: ApkFacts? by lazy { runCatching { inspector.installed() }.getOrNull() }

    override val status: StateFlow<UpdateStatus> =
        combine(store.mode, store.record, work) { mode, record, work -> statusOf(mode, record, work) }
            .stateIn(scope, SharingStarted.Eagerly, statusOf(UpdateMode.ASK, UpdateRecord(), UpdateWork.Idle))

    private fun statusOf(mode: UpdateMode, record: UpdateRecord, work: UpdateWork): UpdateStatus {
        val base = UpdateStatus(enabled = config.enabled, debugBuild = config.debugBuild, repo = config.repo, mode = mode)
        if (!config.enabled) return base
        val available = UpdateInfo.decode(record.latest)?.takeIf { it.versionCode > (installed?.versionCode ?: Long.MAX_VALUE) }
        return base.copy(
            checkedMillis = record.checkedMillis,
            available = available,
            ready = available != null && record.downloaded == available.versionCode && apk(available).exists(),
            work = work,
            problem = record.problem,
            problemMillis = record.problemMillis,
        )
    }

    override fun checkNow() {
        scope.launch { check() }
    }

    /** A check, now: for the daily worker, and Check for updates. */
    suspend fun check() {
        if (!config.enabled) return
        exclusive {
            work.value = UpdateWork.Checking
            val now = clock()
            when (val found = feed.latest()) {
                is ReleaseFile.Found -> store.update {
                    it.copy(checkedMillis = now, latest = found.info.encode(), problem = null, problemMillis = null)
                }
                ReleaseFile.None -> store.update { it.copy(checkedMillis = now, latest = null, problem = null, problemMillis = null) }
                ReleaseFile.TooNew -> problem("The latest release needs a newer Action Center: install it by hand")
                ReleaseFile.Unreadable -> problem("The latest release's update information couldn't be read")
                is ReleaseFile.Failed -> problem(found.why)
            }
        }
    }

    override fun install() {
        // Android's installer already has it (its dialog may be up): a second press starts nothing.
        if (!config.enabled || work.value == UpdateWork.Installing) return
        scope.launch { exclusive { installNow() } }
    }

    private suspend fun installNow() {
        val mine = installed ?: return problem("Android couldn't say which version this is")
        val info = UpdateInfo.decode(store.record.first().latest)?.takeIf { it.versionCode > mine.versionCode } ?: return
        if (!canInstall()) return problem("Android needs Install unknown apps turned on for Action Center first")
        if (info.minSdk > sdk) return problem("${info.versionName} needs a newer Android than this TV's")
        val apk = apk(info)
        if (store.record.first().downloaded != info.versionCode || !apk.exists()) {
            withContext(io) { clean(keep = null) }
            store.update { it.copy(downloaded = null) }
            if (freeBytes() < info.size * 2) return problem("There isn't room on this TV for the ${info.megabytes} MB update")
            work.value = UpdateWork.Downloading(0)
            feed.download(info, apk) { work.value = UpdateWork.Downloading(it) }?.let { return problem(it) }
            store.update { it.copy(downloaded = info.versionCode) }
        }
        // Checked every time, not just after a download: the file waits in the cache.
        work.value = UpdateWork.Verifying
        val facts = withContext(io) { runCatching { inspector.archive(apk) }.getOrNull() }
        UpdateVerification.problem(info, facts, mine)?.let {
            withContext(io) { apk.delete() }
            store.update { record -> record.copy(downloaded = null) }
            return problem("$it, so it was deleted")
        }
        store.update { it.copy(problem = null, problemMillis = null) }
        work.value = UpdateWork.Installing
        installer.install(apk)?.let { return problem(it) }
        // Android has it. It replaces the app, or its dialog waits; if it never says, stop saying "Installing".
        scope.launch {
            delay(installingForMillis)
            if (work.value == UpdateWork.Installing) work.value = UpdateWork.Idle
        }
    }

    override fun installerSaid(answer: InstallerAnswer) {
        scope.launch {
            when (answer) {
                InstallerAnswer.Asking -> Unit
                InstallerAnswer.Cancelled, InstallerAnswer.Installed -> work.value = UpdateWork.Idle
                is InstallerAnswer.Failed -> problem("Android didn't install it: ${answer.why}")
            }
        }
    }

    override fun setMode(mode: UpdateMode) {
        scope.launch {
            store.setMode(mode)
            if (config.enabled) keepSchedule(mode)
        }
    }

    override suspend fun started(): String? {
        if (!config.enabled || !startedOnce.compareAndSet(false, true)) return null
        return try {
            withContext(io) {
                keepSchedule(store.mode.first())
                val mine = installed ?: return@withContext null
                val record = store.record.first()
                val updated = record.seenVersionCode != null && mine.versionCode > record.seenVersionCode
                val stale = UpdateInfo.decode(record.latest)?.let { it.versionCode <= mine.versionCode } ?: true
                if (stale) clean(keep = null)
                store.update {
                    it.copy(
                        seenVersionCode = mine.versionCode,
                        downloaded = if (stale) null else it.downloaded,
                        problem = if (updated) null else it.problem,
                        problemMillis = if (updated) null else it.problemMillis,
                    )
                }
                if (updated) "Updated to ${mine.versionName}" else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun keepSchedule(mode: UpdateMode) {
        runCatching { if (mode == UpdateMode.OFF) schedule.cancel() else schedule.daily() }
    }

    /** Runs [block] unless something else is: a press while a check or download runs does nothing. */
    private suspend fun exclusive(block: suspend () -> Unit) {
        if (!busy.tryLock()) return
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem("Something went wrong: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            if (work.value != UpdateWork.Installing) work.value = UpdateWork.Idle
            busy.unlock()
        }
    }

    private suspend fun problem(why: String) {
        work.value = UpdateWork.Idle
        store.update { it.copy(problem = why, problemMillis = clock()) }
    }

    private fun apk(info: UpdateInfo) = File(dir, "${info.versionCode}.apk")

    /** Deletes every file in [dir] but [keep]. */
    private fun clean(keep: File?) {
        dir.listFiles()?.filter { it != keep }?.forEach { it.delete() }
    }

    private companion object {
        const val INSTALLING_FOR_MILLIS = 2 * 60_000L
    }
}
