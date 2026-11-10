package com.phonoodlesramen.actioncenter.tvprovider

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.phonoodlesramen.actioncenter.home.HomeRowsState
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [OtherAppsRows] with a fake provider: what it reads, where, and when. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OtherAppsRowsTest {
    private class FakeReader(var snapshot: TvProviderSnapshot, var granted: Boolean = true) : TvProviderReader {
        var reads = 0
        var fail = false
        var thread: String? = null

        override fun canRead() = granted

        override fun read(): TvProviderSnapshot {
            reads++
            thread = Thread.currentThread().name
            if (fail) throw IllegalStateException("the provider went away")
            return snapshot
        }
    }

    private val folder: File = Files.createTempDirectory("files").toFile()

    private fun TestScope.rows(
        reader: TvProviderReader,
        refreshes: Flow<Unit> = flow { emit(Unit) },
        io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
    ): OtherAppsRows {
        val store = File(Files.createTempDirectory("store").toFile(), "channels_and_apps.preferences_pb")
        return OtherAppsRows(
            reader = reader,
            store = ChannelsAndAppsStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { store }),
            channelFile = ChannelListFile { folder },
            bannerFiles = { emptyList() },
            labels = { it },
            refreshes = refreshes,
            scope = backgroundScope,
            io = io,
        )
    }

    private val shield = TvProviderSnapshot(
        channels = listOf(TvChannel(36, YOUTUBE_TV, "YouTube TV")),
        programs = listOf(
            network(1, "ESPN"),
            network(2, "Channel 8"),
            watchNext(10, "com.disney.disneyplus", engaged = 200),
            watchNext(11, "com.google.android.youtube.tv", engaged = 300),
            watchNext(12, "com.plexapp.android", engaged = 400),
        ),
    )

    @Test
    fun `builds both rows from one read made on the io dispatcher`() = runTest {
        val reader = FakeReader(shield)
        val io = Executors.newSingleThreadExecutor { Thread(it, "rows-io") }.asCoroutineDispatcher()

        val state = rows(reader, io = io).rows.first()

        assertEquals(listOf("ESPN", "Channel 8"), state.channels.map { it.name })
        assertEquals(listOf(11L, 10L), state.continueInApps.map { it.id })
        assertEquals("rows-io", reader.thread?.substringBefore(" @coroutine"))
        io.close()
    }

    @Test
    fun `a new subscriber gets the last read first, then a fresh one`() = runTest {
        val reader = FakeReader(shield)
        val rows = rows(reader)
        val first = rows.rows.first()
        // Long enough for the sharing to stop, like the home screen going away.
        advanceTimeBy(10_000)
        reader.snapshot = shield.copy(programs = shield.programs.filter { it.id != 11L })

        val seen = rows.rows.take(2).toList()

        assertEquals(first, seen[0])
        assertEquals(listOf(10L), seen[1].continueInApps.map { it.id })
        assertEquals(2, reader.reads)
    }

    @Test
    fun `each refresh reads again`() = runTest {
        val reader = FakeReader(shield)
        val refreshes = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        val rows = rows(reader, refreshes)
        val seen = mutableListOf<HomeRowsState>()
        backgroundScope.launch { rows.rows.collect { seen += it } }
        runCurrent()

        reader.snapshot = shield.copy(programs = shield.programs + watchNext(13, "com.spotify.tv.android", engaged = 500))
        refreshes.tryEmit(Unit)
        runCurrent()

        assertEquals(2, reader.reads)
        assertEquals(listOf(13L, 11L, 10L), seen.last().continueInApps.map { it.id })
    }

    @Test
    fun `a failed read keeps what the last one found`() = runTest {
        val reader = FakeReader(shield)
        val rows = rows(reader)
        val seen = mutableListOf<HomeRowsState>()
        backgroundScope.launch { rows.rows.collect { seen += it } }
        backgroundScope.launch { rows.controller.state.collect {} }
        runCurrent()

        reader.fail = true
        rows.controller.reload()
        runCurrent()

        assertEquals(2, reader.reads)
        assertEquals(1, seen.size)
        assertEquals(listOf("ESPN", "Channel 8"), seen.single().channels.map { it.name })
    }

    @Test
    fun `without READ_TV_LISTINGS the provider isn't touched, and the operator's list still makes channels`() = runTest {
        File(folder, ChannelListFile.NAME).writeText("""[{"name": "PBS", "link": "https://tv.youtube.com/watch/p"}]""")
        val reader = FakeReader(shield, granted = false)
        val rows = rows(reader)
        backgroundScope.launch { rows.controller.state.collect {} }

        val state = rows.rows.first()
        runCurrent()

        assertEquals(0, reader.reads)
        assertEquals(listOf("PBS"), state.channels.map { it.name })
        assertTrue(state.continueInApps.isEmpty())
        val settings = rows.controller.state.value
        assertTrue(settings.loaded)
        assertFalse(settings.canReadTvListings)
        assertEquals(1, settings.listChannels)
    }

    @Test
    fun `turning an app off in Settings takes it out of the row`() = runTest {
        val rows = rows(FakeReader(shield))
        val seen = mutableListOf<HomeRowsState>()
        backgroundScope.launch { rows.rows.collect { seen += it } }
        runCurrent()

        rows.controller.setAppShown("com.google.android.youtube.tv", false)
        runCurrent()

        assertEquals(listOf(10L), seen.last().continueInApps.map { it.id })
    }

    private fun network(id: Long, title: String) = TvProgram(
        id, TvProgram.Kind.PREVIEW, YOUTUBE_TV, title, type = 4, channelId = 36,
        intentUri = "intent://tv.youtube.com/watch/v$id?launch=launcher#Intent;scheme=https;package=$YOUTUBE_TV;end",
    )

    private fun watchNext(id: Long, pkg: String, engaged: Long) =
        TvProgram(id, TvProgram.Kind.WATCH_NEXT, pkg, "Title $id", intentUri = "https://example.com/$id", lastEngagementMillis = engaged)
}
