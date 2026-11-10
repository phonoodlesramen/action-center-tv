package com.phonoodlesramen.actioncenter.tvprovider

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** When the rows are read: on coming to the front, then on provider changes while there. */
@OptIn(ExperimentalCoroutinesApi::class)
class RowRefreshTest {
    @Test
    fun `reads once on coming to the front, once per burst of changes, and not at all behind another app`() = runTest {
        val inFront = MutableStateFlow(true)
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        var reads = 0
        backgroundScope.launch { refreshes(inFront, changes, debounceMillis = 1_000).collect { reads++ } }
        runCurrent()
        assertEquals("on coming to the front", 1, reads)

        repeat(5) { changes.tryEmit(Unit); advanceTimeBy(100) }
        assertEquals("still settling", 1, reads)
        advanceTimeBy(1_001)
        assertEquals("one read for the burst", 2, reads)

        inFront.value = false
        runCurrent()
        changes.tryEmit(Unit)
        advanceTimeBy(5_000)
        assertEquals("nothing while another app is in front", 2, reads)

        inFront.value = true
        runCurrent()
        assertEquals("again on coming back", 3, reads)
    }
}
