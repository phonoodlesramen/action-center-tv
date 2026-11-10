package com.phonoodlesramen.actioncenter.safety

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashGuardTest {
    private val store = InMemoryCrashStore()
    private val guard = CrashGuard(store, clock = { 1_000L })

    /** A fresh guard over the same store stands in for the next app launch. */
    private fun nextLaunch() = CrashGuard(store)

    @Test
    fun `starts normally when no crash was recorded`() {
        assertEquals(StartupMode.NORMAL, guard.startupMode())
        assertNull(guard.lastCrash())
    }

    @Test
    fun `an uncaught exception makes the next launch safe mode`() {
        guard.handler(previous = null)
            .uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        assertEquals(StartupMode.SAFE, nextLaunch().startupMode())
    }

    @Test
    fun `the crash is passed on to the previous handler`() {
        var seen: Throwable? = null
        val error = IllegalStateException("boom")

        guard.handler { _, e -> seen = e }.uncaughtException(Thread.currentThread(), error)

        assertSame(error, seen)
    }

    @Test
    fun `the previous handler still runs when recording the crash fails`() {
        val brokenStore = object : CrashStore {
            override fun read(): CrashRecord? = null
            override fun write(record: CrashRecord) = throw IOException("disk full")
            override fun clear() = Unit
        }
        var seen: Throwable? = null
        val error = IllegalStateException("boom")

        CrashGuard(brokenStore).handler { _, e -> seen = e }
            .uncaughtException(Thread.currentThread(), error)

        assertSame(error, seen)
    }

    @Test
    fun `safe mode lasts across launches until the user leaves it`() {
        guard.recordCrash(RuntimeException("boom"))
        assertEquals(StartupMode.SAFE, nextLaunch().startupMode())
        assertEquals(StartupMode.SAFE, nextLaunch().startupMode())

        nextLaunch().leaveSafeMode()

        assertEquals(StartupMode.NORMAL, nextLaunch().startupMode())
    }

    @Test
    fun `records when and what crashed`() {
        guard.recordCrash(IllegalStateException("boom"))

        assertEquals(CrashRecord(1_000L, "IllegalStateException: boom"), guard.lastCrash())
    }

    @Test
    fun `an exception without a message is summarized by its type`() {
        guard.recordCrash(NullPointerException())

        assertEquals("NullPointerException", guard.lastCrash()?.summary)
    }

    @Test
    fun `long crash messages are truncated`() {
        guard.recordCrash(RuntimeException("x".repeat(1_000)))

        assertTrue(guard.lastCrash()!!.summary.length <= CrashGuard.MAX_SUMMARY_LENGTH)
    }
}
