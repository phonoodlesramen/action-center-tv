package com.phonoodlesramen.actioncenter.profiles

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phonoodlesramen.actioncenter.plex.PlexException
import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.PlexPage
import com.phonoodlesramen.actioncenter.plex.PlexPlay
import com.phonoodlesramen.actioncenter.plex.PlexSection
import com.phonoodlesramen.actioncenter.plex.PlexServerAccount
import com.phonoodlesramen.actioncenter.plex.PlexServerApi
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Each kid's own Plex (K2). Every name, id and token here is made up. */
@RunWith(RobolectricTestRunner::class)
class KidSyncTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ProfileDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.dao()
    private val plex = db.kidPlex()
    private val nowSeconds = 1_793_100_000L
    private val lines = mutableListOf<String>()
    private val server = FakeServer()
    private val tokens = FakeTokens()

    @After
    fun tearDown() = db.close()

    private fun kid(id: String, name: String, plexUserId: Long? = null, plexUserTitle: String? = name) = KidEntity(
        id, name, color = 0xFFFF8A65, birthday = LocalDate.of(2017, 3, 2).toEpochDay(), plexUserId = plexUserId,
        plexUserTitle = plexUserTitle?.takeIf { plexUserId != null },
    )

    private val maya = kid("maya", "Maya", plexUserId = 31_415_926)

    private fun sync(kids: List<KidEntity>, linked: Boolean = true) = KidSync(
        kids = { kids },
        tokens = tokens,
        access = { if (linked) ServerAccess("https://basement:32400", YOURS) else null },
        server = server,
        dao = plex,
        clock = { nowSeconds * 1_000 },
        log = lines::add,
    )

    private fun episode(key: String, title: String, show: String) =
        PlexItem(key, "episode", title, showTitle = show, season = 1, episode = 3, durationMs = 1_320_000, viewOffsetMs = 300_000, contentRating = "TV-Y", showKey = "8000")

    private fun play(key: String, viewedAt: Long, show: String? = "8000") = PlexPlay("/status/sessions/history/$key$viewedAt", key, "episode", "An episode", viewedAt, null, show)

    @Test
    fun `a linked kid's Continue Watching is read with their token, and each sync replaces it`() = runTest {
        tokens.saved["maya"] = "maya-token"
        server.continueWatching["maya-token"] = listOf(episode("8801", "The Big Race", "Puddle Jumpers"), episode("8802", "Rainy Day", "Puddle Jumpers"))
        server.continueWatching[YOURS] = listOf(episode("5101", "Hello Darkness", "Slow Horses"))

        sync(listOf(maya)).sync()

        assertEquals("theirs, never yours", listOf("8801", "8802"), plex.continueWatchingNow("maya").map { it.ratingKey })
        assertEquals(listOf("continue maya-token"), server.asked.filter { it.startsWith("continue") })
        val first = plex.continueWatchingNow("maya").first()
        assertEquals(Triple("Puddle Jumpers", 300_000L, "8000"), Triple(first.showTitle, first.viewOffsetMs, first.showKey))

        server.continueWatching["maya-token"] = listOf(episode("8803", "Sunny Day", "Puddle Jumpers"))
        sync(listOf(maya)).sync()
        assertEquals(listOf("8803"), plex.continueWatchingNow("maya").map { it.ratingKey })
    }

    @Test
    fun `an episode they stopped in its credits makes way for the next one, as on yours`() = runTest {
        tokens.saved["maya"] = "maya-token"
        // Stopped 20 seconds from the end, past Plex's 90%, and Plex still lists it.
        server.continueWatching["maya-token"] = listOf(episode("8801", "The Big Race", "Puddle Jumpers").copy(viewOffsetMs = 1_300_000))
        val next = ItemEntity(
            ratingKey = "8804", sectionKey = "2", type = "episode", title = "The Next Race", year = 2024, durationMs = 1_320_000, viewCount = 0,
            lastViewedAt = null, addedAt = 1_790_000_000, updatedAt = 1_790_000_000, contentRating = "TV-Y", audienceRating = null,
            rating = null, thumb = "/thumb/8804", art = null, genres = "", syncedAt = 0, showKey = "8000", showTitle = "Puddle Jumpers",
            season = 1, episode = 4,
        )

        KidSync({ listOf(maya) }, tokens, { ServerAccess("https://basement:32400", YOURS) }, server, plex, episodesOf = { listOf(next) }).sync()

        assertEquals(listOf("8804"), plex.continueWatchingNow("maya").map { it.ratingKey })
    }

    @Test
    fun `their plays come with their token when the server takes it, asked for their account`() = runTest {
        tokens.saved["maya"] = "maya-token"
        server.accounts = listOf(PlexServerAccount(1, "Parent"), PlexServerAccount(31_415_926, "Maya"))
        server.plays[31_415_926] = listOf(play("8801", nowSeconds - 3_600))
        server.plays[1] = listOf(play("5101", nowSeconds - 60, show = "5000"))

        sync(listOf(maya)).sync()

        assertEquals(listOf("8801"), plex.plays("maya").map { it.ratingKey })
        assertEquals("8000" to 31_415_926L, plex.plays("maya").single().let { it.showKey to it.accountId })
        assertEquals(listOf("accounts $YOURS", "history maya-token 31415926"), server.asked.filter { !it.startsWith("continue") })
        assertTrue(lines.toString(), lines.single().contains("history with their token 1, with yours 0"))
    }

    @Test
    fun `when the server refuses their token, yours asks for their account, found by their Home user's title`() = runTest {
        server.refuseHistoryForKids = true
        val leo = kid("leo", "Leo", plexUserId = 27_182_818)
        tokens.saved["leo"] = "leo-token"
        // This server lists Leo under its own number, not his plex.tv id: his title finds him.
        server.accounts = listOf(PlexServerAccount(1, "Parent"), PlexServerAccount(4, "leo"))
        server.plays[4] = listOf(play("8801", nowSeconds - 7_200), play("8802", nowSeconds - 3_600))

        sync(listOf(leo)).sync()

        assertEquals(listOf("8802", "8801"), plex.plays("leo").map { it.ratingKey })
        assertEquals(listOf("history leo-token 4", "history $YOURS 4"), server.asked.filter { it.startsWith("history") })
        assertTrue(lines.toString(), lines.single().contains("history with their token 0, with yours 1"))
    }

    @Test
    fun `plays are kept 400 days and asked for after the newest, and another Plex user starts over`() = runTest {
        tokens.saved["maya"] = "maya-token"
        server.accounts = listOf(PlexServerAccount(31_415_926, "Maya"), PlexServerAccount(27_182_818, "Leo"))
        val old = nowSeconds - 401 * 86_400
        plex.save("maya", null, KidPlays(31_415_926, listOf(KidPlayEntity("maya", "7001", "movie", old, null, 31_415_926)), 0))
        server.plays[31_415_926] = listOf(play("8801", nowSeconds - 3_600))

        sync(listOf(maya)).sync()
        assertEquals("the 401-day-old play is pruned", listOf("8801"), plex.plays("maya").map { it.ratingKey })
        sync(listOf(maya)).sync()
        assertEquals("the second sync asks after the newest play", nowSeconds - 3_600 + 1, server.since.last())

        // Linked to another Plex Home user: the first one's plays go.
        server.plays[27_182_818] = listOf(play("9901", nowSeconds - 60))
        sync(listOf(maya.copy(plexUserId = 27_182_818, plexUserTitle = "Leo"))).sync()
        assertEquals(listOf("9901"), plex.plays("maya").map { it.ratingKey })
    }

    @Test
    fun `a kid the server doesn't list keeps no plays, and never yours`() = runTest {
        tokens.saved["maya"] = "maya-token"
        plex.save("maya", null, KidPlays(31_415_926, listOf(KidPlayEntity("maya", "8801", "episode", nowSeconds - 60, "8000", 31_415_926)), 0))
        // Only you, and a name that isn't unique: no guess.
        server.accounts = listOf(PlexServerAccount(1, "Maya"), PlexServerAccount(7, "Maya"), PlexServerAccount(8, "Maya"))
        server.plays[1] = listOf(play("5101", nowSeconds - 60, show = "5000"))

        sync(listOf(maya)).sync()

        assertEquals(emptyList<KidPlayEntity>(), plex.plays("maya"))
        assertEquals(emptyList<String>(), server.asked.filter { it.startsWith("history") })
        assertTrue(lines.toString(), lines.single().contains("not on the server 1"))
        assertEquals(null, KidSync.accountFor(maya.copy(plexUserId = 1), listOf(PlexServerAccount(1, "Parent"))))
    }

    @Test
    fun `a kid with no Plex user or no token is skipped and keeps nothing, and a removed kid's rows go`() = runTest {
        val noUser = kid("ana", "Ana")
        val noToken = kid("leo", "Leo", plexUserId = 27_182_818)
        tokens.saved["ana"] = "ana-token"
        for (id in listOf("ana", "leo", "gone")) {
            plex.save(id, listOf(KidContinueEntity(id, 0, "5101", "episode", "Yours", null, null, null, null, null, null, null, null, null, null, null, null)), null)
        }

        sync(listOf(noUser, noToken)).sync()

        assertEquals(emptyList<String>(), server.asked)
        for (id in listOf("ana", "leo", "gone")) assertEquals(id, emptyList<KidContinueEntity>(), plex.continueWatchingNow(id))
        assertEquals(listOf("kids: 0 synced, 2 not linked; history with their token 0, with yours 0, not on the server 0"), lines)
    }

    @Test
    fun `nothing throws, and the log has counts only, never a name, an id or a token`() = runTest {
        tokens.saved["maya"] = "maya-token"
        server.accounts = listOf(PlexServerAccount(31_415_926, "Maya"))
        server.continueWatching["maya-token"] = listOf(episode("8801", "The Big Race", "Puddle Jumpers"))
        sync(listOf(maya)).sync()

        server.down = true
        sync(listOf(maya, kid("leo", "Leo"))).sync()
        assertEquals("what Maya had stays", listOf("8801"), plex.continueWatchingNow("maya").map { it.ratingKey })
        KidSync({ error("broken") }, tokens, { null }, server, plex, log = lines::add).sync()
        sync(listOf(maya), linked = false).sync()
        assertEquals("signed out: the kids' Plex data goes", emptyList<KidContinueEntity>(), plex.continueWatchingNow("maya"))

        assertEquals(
            listOf(
                "kids: 1 synced, 0 not linked; history with their token 1, with yours 0, not on the server 0",
                "kids: 0 synced, 0 not linked, 2 failed (server unreachable); history with their token 0, with yours 0, not on the server 0",
                "kids: failed: IllegalStateException",
                "kids: no server linked",
            ),
            lines,
        )
        val logged = lines.joinToString()
        for (secret in listOf("Maya", "maya", "Leo", "31415926", "maya-token", YOURS, "Puddle")) assertTrue(secret, secret !in logged)
    }

    private class FakeTokens : KidTokens {
        val saved = mutableMapOf<String, String>()
        override suspend fun serverToken(kidId: String) = saved[kidId]
        override suspend fun save(kidId: String, serverToken: String) {
            saved[kidId] = serverToken
        }
        override suspend fun forget(kidId: String) {
            saved.remove(kidId)
        }
    }

    /** A server that, like PMS, keeps everyone's plays under their own account. */
    private class FakeServer : PlexServerApi {
        var down = false
        var refuseHistoryForKids = false
        val continueWatching = mutableMapOf<String, List<PlexItem>>()
        var accounts = emptyList<PlexServerAccount>()

        /** Plays by account. */
        val plays = mutableMapOf<Int, List<PlexPlay>>()
        val asked = mutableListOf<String>()
        val since = mutableListOf<Long>()

        private fun check() {
            if (down) throw PlexException.Unreachable(IOException("down"))
        }

        override suspend fun continueWatching(access: ServerAccess, count: Int): List<PlexItem> {
            check()
            asked += "continue ${access.token}"
            return continueWatching[access.token].orEmpty()
        }

        override suspend fun sections(access: ServerAccess): List<PlexSection> = error("a kid's sync reads no library")

        override suspend fun items(access: ServerAccess, section: String, type: Int, start: Int, size: Int, updatedSince: Long?): PlexPage<PlexItem> =
            error("a kid's sync reads no library")

        override suspend fun history(access: ServerAccess, sinceSeconds: Long, accountId: Int, size: Int): List<PlexPlay> {
            check()
            asked += "history ${access.token} $accountId"
            since += sinceSeconds
            if (refuseHistoryForKids && access.token != YOURS) throw PlexException.Failed(403, "/status/sessions/history/all answered 403")
            return plays[accountId].orEmpty().filter { it.viewedAt > sinceSeconds }
        }

        override suspend fun accounts(access: ServerAccess): List<PlexServerAccount> {
            check()
            asked += "accounts ${access.token}"
            return accounts
        }
    }

    private companion object {
        const val YOURS = "your-token"
    }
}
