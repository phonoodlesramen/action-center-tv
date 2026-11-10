package com.phonoodlesramen.actioncenter.safety

/** What the home screen should open as: the full screen, or the minimal safe-mode one. */
enum class StartupMode { NORMAL, SAFE }

data class CrashRecord(val timeMillis: Long, val summary: String)

/** Where the crash flag lives. Writes must be synchronous: the process is about to die. */
interface CrashStore {
    fun read(): CrashRecord?
    fun write(record: CrashRecord)
    fun clear()
}

/**
 * Makes sure a crash never leaves the Shield without a home screen. An uncaught
 * exception records a crash, and every launch after that opens safe mode until the
 * user chooses to leave it.
 */
class CrashGuard(
    private val store: CrashStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun startupMode(): StartupMode = if (store.read() == null) StartupMode.NORMAL else StartupMode.SAFE

    fun lastCrash(): CrashRecord? = store.read()

    /** The user chose to try the normal screen again. */
    fun leaveSafeMode() = store.clear()

    fun recordCrash(error: Throwable) = store.write(CrashRecord(clock(), error.summary()))

    /**
     * Wraps [previous] so every uncaught exception is recorded first. The previous
     * handler always runs, so the process still dies the way Android expects.
     */
    fun handler(previous: Thread.UncaughtExceptionHandler?) =
        Thread.UncaughtExceptionHandler { thread, error ->
            try {
                recordCrash(error)
            } catch (_: Throwable) {
                // Recording is best effort; never mask the original crash.
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }

    companion object {
        /** [summary]'s cap (`:guide-core`, where the guide engine logs with it too). */
        const val MAX_SUMMARY_LENGTH = SUMMARY_MAX_LENGTH
    }
}
