package com.phonoodlesramen.actioncenter.station

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The house's version: one number that goes up whenever the guide or the spot changes. It's
 * the guide's `ETag`, every edit's answer, and what `GET /v1/changes` waits on. It's kept in
 * the volume, and a restart moves it on by one, so a TV never takes a restarted station's
 * guide for the one it has.
 *
 * Waiting uses a lock and a condition rather than `synchronized`, so a held `changes` call
 * never pins a virtual thread's carrier.
 */
class Versions(private val file: Path?) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var closed = false

    @Volatile
    var current: Long = (file?.let { SecretFiles.readText(it)?.trim()?.toLongOrNull() } ?: 0L) + 1
        private set

    init {
        persist()
    }

    /** One more change: wakes every `changes` call waiting. Returns the new version. */
    fun bump(): Long = lock.withLock {
        current += 1
        persist()
        changed.signalAll()
        current
    }

    /**
     * The version once it's other than [after]: at once if it already is, else when it next
     * changes. Null after [timeoutMillis] with no change, or once the station is closing.
     * "Other than" rather than "above", so a station restored from a backup still answers.
     */
    fun awaitChange(after: Long, timeoutMillis: Long): Long? {
        lock.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (current == after) {
                if (closed || remaining <= 0) return null
                remaining = changed.awaitNanos(remaining)
            }
            return current
        }
    }

    /** The station is stopping: every waiting call returns. */
    fun close() = lock.withLock {
        closed = true
        changed.signalAll()
    }

    private fun persist() {
        file?.let { SecretFiles.writeText(it, current.toString()) }
    }
}
