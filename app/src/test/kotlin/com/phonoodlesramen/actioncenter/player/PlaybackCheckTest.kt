package com.phonoodlesramen.actioncenter.player

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phonoodlesramen.actioncenter.plex.PlexHeaders
import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.PlexMedia
import com.phonoodlesramen.actioncenter.plex.PlexPage
import com.phonoodlesramen.actioncenter.plex.PlexPlay
import com.phonoodlesramen.actioncenter.plex.PlexSection
import com.phonoodlesramen.actioncenter.plex.PlexServerApi
import com.phonoodlesramen.actioncenter.plex.SHIELD_PLEX_DEVICE
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import com.phonoodlesramen.actioncenter.plex.cache.PlexDatabase
import com.phonoodlesramen.actioncenter.plex.cache.toEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Made-up titles; the shapes follow the Plex fixtures. */
@RunWith(RobolectricTestRunner::class)
class PlaybackCheckTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PlexDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.dao()
    private val access = ServerAccess("https://10-0-0-5.abc.plex.direct:32400/", "server-secret")
    private val headers = PlexHeaders({ "client-1" }, "0.3.0", SHIELD_PLEX_DEVICE)

    @After
    fun tearDown() = db.close()

    private class FakeServer : PlexServerApi {
        val details = mutableMapOf<String, PlexItem>()
        var history = emptyList<PlexPlay>()
        override suspend fun continueWatching(access: ServerAccess, count: Int) = emptyList<PlexItem>()
        override suspend fun sections(access: ServerAccess) = emptyList<PlexSection>()
        override suspend fun items(access: ServerAccess, section: String, type: Int, start: Int, size: Int, updatedSince: Long?) =
            PlexPage(emptyList<PlexItem>(), 0, 0)
        override suspend fun history(access: ServerAccess, sinceSeconds: Long, accountId: Int, size: Int) = history
        override suspend fun metadata(access: ServerAccess, ratingKey: String) = details[ratingKey]
    }

    private fun movie(key: String, title: String, media: PlexMedia) =
        PlexItem(key, "movie", title, year = 2016, durationMs = 6_000_000, media = media)

    private suspend fun library(vararg items: PlexItem) = dao.upsertItems(items.map { it.toEntity("1", 0) })

    private val bigMkv = movie("1", "Big Atlas", PlexMedia("/library/parts/1/1/file.mkv", "mkv", "hevc", "main 10", "4k", "truehd", 8, 60_000_000_000))
    private val dolby = movie("2", "Glowing Reef", PlexMedia("/library/parts/2/1/file.mkv", "mkv", "hevc", "main 10", "4k", "eac3", 6, 30_000_000_000))
    private val sdr = movie("3", "Quiet Field", PlexMedia("/library/parts/3/1/file.mp4", "mp4", "h264", "high", "1080", "aac", 2, 4_000_000_000))

    private fun check(server: FakeServer, logs: MutableList<String> = mutableListOf()) = PlaybackCheck(
        dao, flowOf(access), server, PlexPlayables(flowOf(access), dao, server, headers), clock = { 1_793_000_000_000L }, log = { logs += it },
    )

    @Test
    fun `it picks one title per test, merging labels when one title covers several`() = runTest {
        library(bigMkv, dolby, sdr)
        val server = FakeServer().apply {
            details["1"] = bigMkv.copy(media = bigMkv.media!!.copy(dynamicRange = PlexMedia.HDR10))
            details["2"] = dolby.copy(media = dolby.media!!.copy(dynamicRange = PlexMedia.DOLBY_VISION))
        }

        val cases = check(server).pick()

        assertEquals(listOf("1", "2"), cases.map { it.ratingKey })
        assertEquals(listOf("Largest MKV", "HEVC 10-bit", "HDR10", "TrueHD"), cases[0].labels)
        assertEquals(listOf("Dolby Vision", "DD+"), cases[1].labels)
        assertEquals("MKV · HEVC main 10 · 4k · TrueHD 8 ch · 60.0 GB", cases[0].detail)
        assertEquals("Big Atlas · 2016", cases[0].title)
    }

    @Test
    fun `a DTS title is played too, and the DTS titles are counted`() = runTest {
        val dts = movie("4", "Paper Harbor", PlexMedia("/library/parts/4/1/file.mkv", "mkv", "h264", "high", "1080", "dca", 6, 20_000_000_000))
        val dtsToo = movie("5", "The Long Signal", PlexMedia("/library/parts/5/1/file.mkv", "mkv", "h264", "high", "1080", "dca", 6, 10_000_000_000))
        library(bigMkv, dts, dtsToo, sdr)
        val logs = mutableListOf<String>()
        val check = check(FakeServer(), logs)

        assertEquals(listOf("DTS"), check.pick().single { it.ratingKey == "4" }.labels)
        check.run { _, _ -> ProbeOutcome(1_200, "h264 1920×1080", "vnd.dts → PCM (decoded)", null) }

        assertEquals(2, check.state.value.dtsTitles)
        assertTrue(logs.any { "2 titles have DTS as their main audio" in it })
    }

    @Test
    fun `it plays each from the middle, records what happened, and finds no Plex history`() = runTest {
        library(bigMkv)
        val played = mutableListOf<Pair<PlaybackSource, Long>>()
        val logs = mutableListOf<String>()
        val check = check(FakeServer(), logs)

        check.run { source, start ->
            played += source to start
            ProbeOutcome(firstFrameMillis = 1_400, video = "HDR10 (PQ) 3840×1600 · OMX.Nvidia.h265.decode", audio = "true-hd → TrueHD passthrough", error = null)
        }

        val (source, start) = played.single()
        assertEquals("https://10-0-0-5.abc.plex.direct:32400/library/parts/1/1/file.mkv", source.uri)
        assertEquals("server-secret", source.headers["X-Plex-Token"])
        assertFalse("the token never goes in the URL", "server-secret" in source.uri || "server-secret" in source.toString())
        assertEquals(3_000_000L, start)
        val state = check.state.value
        assertEquals(PlaybackCheckState.Phase.DONE, state.phase)
        assertTrue(state.results.single().passed)
        assertEquals("Plex recorded no plays for these titles.", state.history)
        assertTrue(logs.any { "first frame 1400 ms" in it })
        assertFalse("nothing secret in the log", logs.any { "server-secret" in it })
    }

    @Test
    fun `a failure is reported, and a play Plex recorded is named`() = runTest {
        library(bigMkv)
        val server = FakeServer().apply { history = listOf(PlexPlay("h", "1", "movie", "Big Atlas", 1_793_000_000, null)) }
        val check = check(server)

        check.run { _, _ -> ProbeOutcome(null, null, null, "ERROR_CODE_DECODING_FAILED: no decoder") }

        val state = check.state.value
        assertFalse(state.results.single().passed)
        assertEquals("ERROR_CODE_DECODING_FAILED: no decoder", state.results.single().error)
        assertEquals("Plex recorded: Big Atlas", state.history)
    }

    @Test
    fun `a picture with no sound fails`() = runTest {
        library(bigMkv)
        val logs = mutableListOf<String>()
        val check = check(FakeServer(), logs)

        check.run { _, _ -> ProbeOutcome(firstFrameMillis = 1_649, video = "Dolby Vision 3840×1602 · OMX.Nvidia.h265.decode", audio = null, error = null) }

        val result = check.state.value.results.single()
        assertFalse(result.passed)
        assertEquals(PlaybackCheck.noSound(1_649), result.error)
        assertTrue(logs.any { "FAILED No sound" in it })
    }

    @Test
    fun `with nothing cached it says so instead of playing`() = runTest {
        val check = check(FakeServer())

        check.run { _, _ -> error("nothing should play") }

        assertTrue(check.state.value.problem!!.startsWith("Nothing to play yet"))
    }

    @Test
    fun `a title the cache has no file for is found through its details, like a trailer extra`() = runTest {
        val server = FakeServer().apply { details["77"] = PlexItem("77", "clip", "Trailer", media = PlexMedia("/library/parts/77/1/file.mp4")) }

        val source = PlexPlayables(flowOf(access), dao, server, headers).source("77")!!

        assertEquals("https://10-0-0-5.abc.plex.direct:32400/library/parts/77/1/file.mp4", source.uri)
        assertNull(PlexPlayables(flowOf(access), dao, server, headers).source("78"))
        assertNull("not linked: nothing to play", PlexPlayables(flowOf(null), dao, server, headers).source("77"))
    }
}
