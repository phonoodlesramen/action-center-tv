package com.phonoodlesramen.actioncenter.ai

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phonoodlesramen.actioncenter.ai.BlurbFixtures.KEY
import com.phonoodlesramen.actioncenter.plex.FakeBox
import com.phonoodlesramen.actioncenter.plex.cache.PlexDatabase
import com.phonoodlesramen.actioncenter.settings.Settings
import com.phonoodlesramen.actioncenter.suggest.BlurbEntity
import com.phonoodlesramen.actioncenter.suggest.Profiles
import com.phonoodlesramen.actioncenter.suggest.SuggestionDatabase
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The night's run (M7-M11) with Room in memory and a fake for Claude: nothing is sent while
 * it's off or without a key, blurbs are saved with the reason they were written for, a
 * week-old one goes, and a failure is recorded for Settings rather than thrown.
 */
@RunWith(RobolectricTestRunner::class)
class BlurbWriterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plex = Room.inMemoryDatabaseBuilder(context, PlexDatabase::class.java).allowMainThreadQueries().build()
    private val suggestions = Room.inMemoryDatabaseBuilder(context, SuggestionDatabase::class.java).allowMainThreadQueries().build()
    private val dir = Files.createTempDirectory("ai").toFile()
    private val settings = MutableStateFlow(Settings(name = "Alex", aiBlurbs = true))
    private val vault = AiKeyVault(File(dir, AiKeyVault.FILE), FakeBox())
    private val logs = mutableListOf<String>()

    /** Tuesday 27 October 2026, 4:02 AM UTC. */
    private val now = java.time.LocalDateTime.of(2026, 10, 27, 4, 2).toInstant(ZoneOffset.UTC).toEpochMilli()

    private class FakeAuthor(var answer: (List<BlurbPick>) -> BlurbAnswer) : BlurbAuthor {
        val calls = mutableListOf<Pair<String, List<BlurbPick>>>()

        override suspend fun write(key: String, picks: List<BlurbPick>): BlurbAnswer {
            calls += key to picks
            return answer(picks)
        }
    }

    private val author = FakeAuthor { picks -> BlurbAnswer.Written(picks.associate { it.id to "A line for ${it.title}." }, TokenUsage(5_000, 2_500)) }

    @After
    fun tearDown() {
        plex.close()
        suggestions.close()
    }

    private fun TestScope.runs() = BlurbRuns(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "ai_blurbs.preferences_pb") })

    private fun TestScope.writer(runs: BlurbRuns = runs(), with: BlurbAuthor = author) =
        BlurbWriter(settings, vault, with, runs, suggestions.dao(), plex.dao(), clock = { now }, log = logs::add)

    private suspend fun library() {
        plex.dao().upsertItems(BlurbFixtures.titles.values.toList())
        suggestions.dao().replacePicks(Profiles.OWNER, BlurbFixtures.picks)
    }

    private suspend fun blurbs() = suggestions.dao().blurbs(Profiles.OWNER).first().sortedBy { it.ratingKey }

    @Test
    fun `nothing is sent while it's off, even with a key`() = runTest {
        library()
        vault.save(KEY)
        settings.value = Settings(aiBlurbs = false)
        val runs = runs()

        assertEquals(BlurbOutcome.SKIPPED, writer(runs).write())

        assertTrue(author.calls.isEmpty())
        assertNull(runs.last.first())
    }

    @Test
    fun `nothing is sent without a key`() = runTest {
        library()
        val runs = runs()

        assertEquals(BlurbOutcome.SKIPPED, writer(runs).write())

        assertTrue(author.calls.isEmpty())
        assertNull(runs.last.first())
    }

    @Test
    fun `blurbs are saved with the reason each was written for`() = runTest {
        library()
        vault.save(KEY)
        val runs = runs()

        assertEquals(BlurbOutcome.WRITTEN, writer(runs).write())

        val (key, sent) = author.calls.single()
        assertEquals(KEY, key)
        assertEquals(listOf("Heat", "Harbour Lights", "Ronin"), sent.map { it.title })
        assertEquals(
            listOf(
                BlurbEntity(Profiles.OWNER, "rk-48151", "Because you liked Collateral", "A line for Heat.", now),
                BlurbEntity(Profiles.OWNER, "rk-62342", "From the Heist collection", "A line for Ronin.", now),
                BlurbEntity(Profiles.OWNER, "rk-ep-77", "You're 2 episodes from the end of Harbour Lights's season 2", "A line for Harbour Lights.", now),
            ),
            blurbs(),
        )
        val run = runs.last.first()!!
        assertEquals(BlurbRun(now, 3, TokenUsage(5_000, 2_500)), run)
        assertEquals("Last night at 4:02 AM: 3 blurbs, 7,500 tokens, about $0.002", BlurbRunLine.of(run, now + 16 * 3_600_000, ZoneOffset.UTC))
    }

    @Test
    fun `only the ids sent are saved, against their own picks`() = runTest {
        library()
        vault.save(KEY)
        author.answer = { BlurbAnswer.Written(mapOf("p3" to "Another heist with weight.", "p7" to "Nobody asked."), TokenUsage(10, 10)) }

        writer().write()

        assertEquals(listOf("rk-62342" to "Another heist with weight."), blurbs().map { it.ratingKey to it.text })
    }

    @Test
    fun `blurbs over a week old go at each run`() = runTest {
        library()
        vault.save(KEY)
        val day = 24 * 3_600_000L
        suggestions.dao().saveBlurbs(
            listOf(
                BlurbEntity(Profiles.OWNER, "old", "A reason", "Eight days old.", now - 8 * day),
                BlurbEntity(Profiles.OWNER, "recent", "A reason", "Six days old.", now - 6 * day),
            ),
        )
        author.answer = { BlurbAnswer.Failed(ClaudeBlurbs.OFFLINE, retry = true) }

        writer().write()

        assertEquals(listOf("recent"), blurbs().map { it.ratingKey })
    }

    @Test
    fun `a failure is recorded for Settings, and busy is tried again later`() = runTest {
        library()
        vault.save(KEY)
        val runs = runs()
        author.answer = { BlurbAnswer.Failed(ClaudeBlurbs.BUSY, retry = true) }
        assertEquals(BlurbOutcome.RETRY, writer(runs).write())
        assertEquals(BlurbRun(now, error = ClaudeBlurbs.BUSY), runs.last.first())

        author.answer = { BlurbAnswer.Failed(ClaudeBlurbs.KEY_REFUSED, retry = false) }
        assertEquals(BlurbOutcome.FAILED, writer(runs).write())
        assertEquals(ClaudeBlurbs.KEY_REFUSED, runs.last.first()!!.error)
        assertTrue(blurbs().isEmpty())
    }

    @Test
    fun `something thrown is recorded, never thrown, and the key never reaches a log or Settings`() = runTest {
        library()
        vault.save(KEY)
        val runs = runs()
        // OkHttp quotes a header's value when it's refused: the key must not follow.
        val thrower = BlurbAuthor { key, _ -> throw IllegalArgumentException("Unexpected char in x-api-key value: $key") }

        assertEquals(BlurbOutcome.FAILED, writer(runs, thrower).write())

        val run = runs.last.first()!!
        assertEquals(BlurbWriter.SOMETHING_WENT_WRONG, run.error)
        val line = BlurbRunLine.of(run, now, ZoneOffset.UTC)
        assertFalse(KEY in line)
        assertTrue(logs.isNotEmpty())
        logs.forEach { assertFalse(it, KEY in it) }
    }

    @Test
    fun `no picks yet says so`() = runTest {
        vault.save(KEY)
        val runs = runs()

        assertEquals(BlurbOutcome.FAILED, writer(runs).write())

        assertTrue(author.calls.isEmpty())
        assertEquals(BlurbWriter.NO_PICKS, runs.last.first()!!.error)
    }

    @Test
    fun `end to end over HTTP, Claude being overloaded is recorded and retried`() = runTest {
        library()
        vault.save(KEY)
        val runs = runs()
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(529).body("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""").build())

            val outcome = writer(runs, ClaudeBlurbs(OkHttpClient(), server.url("/v1/messages"))).write()

            assertEquals(BlurbOutcome.RETRY, outcome)
            assertEquals(ClaudeBlurbs.OVERLOADED, runs.last.first()!!.error)
            assertEquals(KEY, server.takeRequest().headers["x-api-key"])
        }
        logs.forEach { assertFalse(it, KEY in it) }
    }
}
