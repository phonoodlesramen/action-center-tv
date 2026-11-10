package com.phonoodlesramen.actioncenter.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdatingTest {
    private val running = MutableStateFlow(false)

    /** What the top bar is told, and when (virtual milliseconds). */
    private fun TestScope.watch(): List<Pair<Long, Boolean>> {
        val seen = mutableListOf<Pair<Long, Boolean>>()
        backgroundScope.launch { Updating.shown(running).collect { seen += currentTime to it } }
        runCurrent()
        return seen
    }

    /** Sets the sync's flag at [at] ms. */
    private fun TestScope.at(at: Long, value: Boolean) {
        advanceTimeBy(at - currentTime)
        running.value = value
        runCurrent()
    }

    @Test
    fun `a run under half a second shows nothing`() = runTest {
        val seen = watch()
        at(0, true)
        at(400, false)
        at(5_000, false)

        assertEquals(listOf(0L to false), seen)
    }

    @Test
    fun `a longer run shows half a second in, and goes as soon as it's done`() = runTest {
        val seen = watch()
        at(0, true)
        at(3_000, false)
        at(5_000, false)

        assertEquals(listOf(0L to false, 500L to true, 3_000L to false), seen)
    }

    @Test
    fun `a run that ends just after it shows keeps it a second, so it never blinks`() = runTest {
        val seen = watch()
        at(0, true)
        at(600, false)
        at(5_000, false)

        assertEquals(listOf(0L to false, 500L to true, 1_500L to false), seen)
    }

    @Test
    fun `the next run shows again`() = runTest {
        val seen = watch()
        at(0, true)
        at(2_000, false)
        at(10_000, true)
        at(12_000, false)

        assertEquals(listOf(0L to false, 500L to true, 2_000L to false, 10_500L to true, 12_000L to false), seen)
    }
}
