package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.plex.PlexException
import com.phonoodlesramen.actioncenter.plex.PlexWatchApi
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import com.phonoodlesramen.actioncenter.profiles.PlexViewer
import com.phonoodlesramen.actioncenter.profiles.PlexViewers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guide's writes to Plex (Phase 11): for whom, when the setting allows, and how a branch waits. Tokens are made up. */
@OptIn(ExperimentalCoroutinesApi::class)
class GuidePlexWritesTest {
    private val access = ServerAccess("https://10-0-0-5.abc.plex.direct:32400", "kid-token")

    private class Api : PlexWatchApi {
        val sent = mutableListOf<String>()
        var failure: Exception? = null

        /** While set, every write waits for it: Plex answering late. */
        var answer: CompletableDeferred<Unit>? = null

        override suspend fun setPosition(access: ServerAccess, ratingKey: String, positionMs: Long) {
            answer?.await()
            failure?.let { throw it }
            sent += "progress $ratingKey @$positionMs as ${access.token}"
        }

        override suspend fun markWatched(access: ServerAccess, ratingKey: String) {
            answer?.await()
            failure?.let { throw it }
            sent += "watched $ratingKey as ${access.token}"
        }
    }

    private fun TestScope.writes(api: Api, viewer: PlexViewer?, counting: Boolean = true, logs: MutableList<String> = mutableListOf()) =
        GuidePlexWrites(PlexViewers { viewer }, api, counting = { counting }, scope = backgroundScope, log = { logs += it })

    @Test
    fun `watched goes to the profile's Plex user`() = runTest {
        val api = Api()

        assertTrue(writes(api, PlexViewer(access, "u-maya")).watched("7303"))

        assertEquals(listOf("watched 7303 as kid-token"), api.sent)
    }

    @Test
    fun `with the setting off, guide viewing sends nothing`() = runTest {
        val api = Api()

        assertFalse(writes(api, PlexViewer(access, null), counting = false).watched("7303"))

        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun `a kid without a Plex user sends nothing`() = runTest {
        val api = Api()

        assertFalse(writes(api, viewer = null).watched("7303"))
        val branch = writes(api, viewer = null).branch("7303", ResumeWrite.Position(600_000))

        assertEquals(Branch(profile = null, written = false), branch)
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun `a failed write is false, and its log has no token`() = runTest {
        val api = Api().apply { failure = PlexException.Unauthorized() }
        val logs = mutableListOf<String>()

        assertFalse(writes(api, PlexViewer(access, null), logs = logs).watched("7303"))

        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { "kid-token" in it })
    }

    @Test
    fun `a branch writes the position for the viewer, and names their profile`() = runTest {
        val api = Api()

        val branch = writes(api, PlexViewer(access, "u-maya")).branch("7303", ResumeWrite.Position(754_000))

        assertEquals(Branch("u-maya", written = true), branch)
        assertEquals(listOf("progress 7303 @754000 as kid-token"), api.sent)
    }

    @Test
    fun `a branch past the watched point marks it watched, and under a minute writes nothing`() = runTest {
        val api = Api()

        writes(api, PlexViewer(access, null)).branch("7303", ResumeWrite.Watched)
        val nothing = writes(api, PlexViewer(access, "u-maya")).branch("7304", ResumeWrite.Nothing)

        assertEquals(Branch("u-maya", written = true), nothing)
        assertEquals(listOf("watched 7303 as kid-token"), api.sent)
    }

    @Test
    fun `a branch waits two seconds at most, and the write still lands later`() = runTest {
        val api = Api().apply { answer = CompletableDeferred() }
        val started = testScheduler.currentTime

        val branch = writes(api, PlexViewer(access, "u-maya")).branch("7303", ResumeWrite.Position(754_000))

        assertEquals(Branch("u-maya", written = false), branch)
        assertEquals(GuideViewing.WRITE_TIMEOUT_MILLIS, testScheduler.currentTime - started)
        api.answer!!.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf("progress 7303 @754000 as kid-token"), api.sent)
    }

    @Test
    fun `a refused branch write is not written`() = runTest {
        val api = Api().apply { failure = PlexException.Failed(500, "no") }

        assertEquals(Branch(null, written = false), writes(api, PlexViewer(access, null)).branch("7303", ResumeWrite.Watched))
    }
}
