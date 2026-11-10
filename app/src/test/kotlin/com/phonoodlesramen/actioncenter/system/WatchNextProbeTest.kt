package com.phonoodlesramen.actioncenter.system

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchNextProbeTest {
    private fun reader(
        canRead: Boolean = true,
        watchNext: () -> List<String> = { emptyList() },
        preview: () -> List<String> = { emptyList() },
    ) = object : TvProgramsReader {
        override fun canRead() = canRead
        override fun watchNextPackages() = watchNext()
        override fun previewPackages() = preview()
    }

    @Test
    fun `without READ_TV_LISTINGS it reports not granted`() {
        assertEquals(WatchNextResult.NotGranted, WatchNextProbe(reader(canRead = false)).probe())
    }

    @Test
    fun `counts rows per package, most first`() {
        val result = WatchNextProbe(
            reader(
                watchNext = { listOf("com.edde746.plezy", "com.google.android.youtube.tv", "com.edde746.plezy") },
                preview = { listOf("com.netflix.ninja") },
            ),
        ).probe() as WatchNextResult.Counts

        assertEquals(
            listOf("com.edde746.plezy" to 2, "com.google.android.youtube.tv" to 1),
            result.watchNext.toList(),
        )
        assertEquals(mapOf("com.netflix.ninja" to 1), result.preview)
    }

    @Test
    fun `no rows gives empty counts`() {
        assertEquals(WatchNextResult.Counts(emptyMap(), emptyMap()), WatchNextProbe(reader()).probe())
    }

    @Test
    fun `a provider error is reported instead of thrown`() {
        val result = WatchNextProbe(reader(watchNext = { throw SecurityException("no access") })).probe()

        assertEquals(WatchNextResult.Failed("SecurityException: no access"), result)
    }
}
