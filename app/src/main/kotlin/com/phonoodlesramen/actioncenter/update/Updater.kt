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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Updates from the build's GitHub releases ([UpdateConfig]), in one of three modes:
 * - **Install automatically** ([UpdateMode.AUTOMATIC]): a check once a day downloads a new release
 *   ahead (an unmetered network, storage not low). On Android 12 and later it installs with no
 *   dialog at a quiet moment ([IdleRule]), checked once a minute while the app runs and right after
 *   the download; on Android 11 Android always asks, so it waits for a press, and the top bar
 *   says Update ready.
 * - **Ask me** ([UpdateMode.ASK], the default): a check once a day; a download and Android's
 *   installer only after a press.
 * - **Off** ([UpdateMode.OFF]): only Check for updates checks.
 *
 * Every install goes the same way: download the APK to [dir] (the cache) unless it's there
 * already, check its size and SHA-256 on the way, then that Android reads it as Action Center,
 * newer, and signed with this copy's key ([UpdateVerification]). Only then does Android's
 * installer get it.
 * - **One thing at a time:** a press while something runs does nothing.
 * - **One automatic try per version:** if Android wants a press, or the install fails, the update
 *   waits for one; it never tries again by itself.
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
    /** What the cache's storage can give a download (`StorageManager.getAllocatableBytes` on a TV). */
    private val freeBytes: () -> Long,
    /** Whether the TV is in use, for Install automatically ([IdleRule]). */
    private val idle: IdleSignals = IdleSignals { IdleSnapshot() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val sdk: Int = Build.VERSION.SDK_INT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** How long "Installing" shows when Android's installer says nothing (its dialog left open). */
    private val installingForMillis: Long = INSTALLING_FOR_MILLIS,
    /** How often Install automatically looks for a quiet moment while the app runs. */
    private val idleCheckMillis: Long = IDLE_CHECK_MILLIS,
) : UpdatesController {
    private val work = MutableStateFlow<UpdateWork>(UpdateWork.Idle)
    private val busy = Mutex()
    private val startedOnce = AtomicBoolean(false)

    /** Android 12 and later install an update the app asks for with no dialog. */
    private val silentInstalls = sdk >= ANDROID_12

    /** The installed app's own facts; null only if Android can't say. */
    private val installed: ApkFacts? by lazy { runCatching { inspector.installed() }.getOrNull() }

    override val status: StateFlow<UpdateStatus> =
        combine(store.mode, store.record, work) { mode, record, work -> statusOf(mode, record, work) }
            .stateIn(scope, SharingStarted.Eagerly, statusOf(UpdateMode.ASK, UpdateRecord(), UpdateWork.Idle))

    private fun statusOf(mode: UpdateMode, record: UpdateRecord, work: UpdateWork): UpdateStatus {
        val base = UpdateStatus(
            enabled = config.enabled,
            debugBuild = config.debugBuild,
            repo = config.repo,
            mode = mode,
            silentInstalls = silentInstalls,
        )
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

    /** A check, now: for the daily worker, and Check for updates. Install automatically then downloads it soon. */
    suspend fun check() {
        if (!config.enabled) return
        exclusive {
            work.value = UpdateWork.Checking
            val now = clock()
            when (val found = feed.latest()) {
                is ReleaseFile.Found -> {
                    store.update {
                        it.copy(checkedMillis = now, latest = found.info.encode(), problem = null, problemMillis = null)
                    }
                    val newer = found.info.versionCode > (installed?.versionCode ?: Long.MAX_VALUE)
                    if (newer && store.mode.first() == UpdateMode.AUTOMATIC) runCatching { schedule.downloadSoon() }
                }
                ReleaseFile.None -> store.update { it.copy(checkedMillis = now, latest = null, problem = null, problemMillis = null) }
                ReleaseFile.TooNew -> problem("The latest release needs a newer Action Center: install it by hand")
                ReleaseFile.Unreadable -> problem("The latest release's update information couldn't be read")
                is ReleaseFile.Failed -> problem(found.why)
            }
        }
    }

    /**
     * Install automatically's download, for its worker: the available update, downloaded and
     * checked, then installed at once if Android 12 or later and the TV is idle.
     */
    suspend fun downloadAhead() {
        if (!config.enabled || store.mode.first() != UpdateMode.AUTOMATIC) return
        exclusive {
            val info = newer() ?: return@exclusive
            prepare(info) ?: return@exclusive
        }
        installIfIdle()
    }

    override fun install() {
        // Android's installer already has it (its dialog may be up): a second press starts nothing.
        if (!config.enabled || work.value == UpdateWork.Installing) return
        scope.launch {
            exclusive {
                val info = newer() ?: return@exclusive
                if (!canInstall()) return@exclusive problem("Android needs Install unknown apps turned on for Action Center first")
                val apk = prepare(info) ?: return@exclusive
                hand(apk, byPress = true)
            }
        }
    }

    override fun chipPressed(openAbout: () -> Unit) {
        val now = status.value
        if (now.ready && now.work == UpdateWork.Idle && canInstall()) install() else openAbout()
    }

    /** [record]'s latest release, when it's newer than this copy. */
    private fun available(record: UpdateRecord): UpdateInfo? =
        UpdateInfo.decode(record.latest)?.takeIf { it.versionCode > (installed?.versionCode ?: Long.MAX_VALUE) }

    /** The latest release, when it's newer than this copy. */
    private suspend fun newer(): UpdateInfo? {
        val mine = installed ?: run {
            problem("Android couldn't say which version this is")
            return null
        }
        return UpdateInfo.decode(store.record.first().latest)?.takeIf { it.versionCode > mine.versionCode }
    }

    /**
     * [info]'s APK, downloaded unless it's there already and checked every time (the file waits in
     * the cache): null, with the problem on About's line, when it can't be used.
     */
    private suspend fun prepare(info: UpdateInfo): File? {
        val mine = installed ?: return null
        if (info.minSdk > sdk) {
            problem("${info.versionName} needs a newer Android than this TV's")
            return null
        }
        val apk = apk(info)
        if (store.record.first().downloaded != info.versionCode || !apk.exists()) {
            withContext(io) { clean() }
            store.update { it.copy(downloaded = null) }
            if (freeBytes() < info.size * 2) {
                problem("There isn't room on this TV for the ${info.megabytes} MB update")
                return null
            }
            work.value = UpdateWork.Downloading(0)
            val why = feed.download(info, apk) { work.value = UpdateWork.Downloading(it) }
            if (why != null) {
                problem(why)
                return null
            }
            store.update { it.copy(downloaded = info.versionCode) }
        }
        work.value = UpdateWork.Verifying
        val facts = withContext(io) { runCatching { inspector.archive(apk) }.getOrNull() }
        UpdateVerification.problem(info, facts, mine)?.let {
            withContext(io) { apk.delete() }
            store.update { record -> record.copy(downloaded = null) }
            problem("$it, so it was deleted")
            return null
        }
        store.update { it.copy(problem = null, problemMillis = null) }
        work.value = UpdateWork.Idle
        return apk
    }

    /** Hands [apk] to Android's installer; "Installing" shows until it answers, or for [installingForMillis]. */
    private suspend fun hand(apk: File, byPress: Boolean) {
        work.value = UpdateWork.Installing
        installer.install(apk, byPress)?.let { return problem(it) }
        scope.launch {
            delay(installingForMillis)
            if (work.value == UpdateWork.Installing) work.value = UpdateWork.Idle
        }
    }

    /**
     * Install automatically on Android 12 and later: a ready update installs now if the TV is idle
     * ([IdleRule]), once per version.
     */
    private suspend fun installIfIdle() {
        if (!silentInstalls || work.value != UpdateWork.Idle || store.mode.first() != UpdateMode.AUTOMATIC) return
        val record = store.record.first()
        val info = available(record) ?: return
        if (record.downloaded != info.versionCode || !apk(info).exists() || record.autoTried == info.versionCode) return
        if (!IdleRule.mayInstall(idle.snapshot(), clock()) || !canInstall()) return
        exclusive {
            store.update { it.copy(autoTried = info.versionCode) }
            val apk = prepare(info) ?: return@exclusive
            hand(apk, byPress = false)
        }
    }

    override fun installerSaid(answer: InstallerAnswer) {
        scope.launch {
            when (answer) {
                InstallerAnswer.Asking -> Unit
                InstallerAnswer.Cancelled, InstallerAnswer.Installed, InstallerAnswer.NeedsPress -> work.value = UpdateWork.Idle
                is InstallerAnswer.Failed -> problem("Android didn't install it: ${answer.why}")
            }
        }
    }

    override fun setMode(mode: UpdateMode) {
        scope.launch {
            store.setMode(mode)
            if (!config.enabled) return@launch
            keepSchedule(mode)
            // Just turned on with an update waiting: download it now.
            val record = store.record.first()
            val waiting = available(record)
            if (mode == UpdateMode.AUTOMATIC && waiting != null && record.downloaded != waiting.versionCode) {
                runCatching { schedule.downloadSoon() }
            }
        }
    }

    override suspend fun started(): String? {
        if (!config.enabled || !startedOnce.compareAndSet(false, true)) return null
        if (silentInstalls) {
            scope.launch {
                while (isActive) {
                    delay(idleCheckMillis)
                    runCatching { installIfIdle() }
                }
            }
        }
        return try {
            withContext(io) {
                keepSchedule(store.mode.first())
                val mine = installed ?: return@withContext null
                val record = store.record.first()
                val updated = record.seenVersionCode != null && mine.versionCode > record.seenVersionCode
                val stale = UpdateInfo.decode(record.latest)?.let { it.versionCode <= mine.versionCode } ?: true
                if (stale) clean()
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

    /** Deletes every file in [dir]. */
    private fun clean() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val INSTALLING_FOR_MILLIS = 2 * 60_000L
        const val IDLE_CHECK_MILLIS = 60_000L
        const val ANDROID_12 = 31
    }
}
