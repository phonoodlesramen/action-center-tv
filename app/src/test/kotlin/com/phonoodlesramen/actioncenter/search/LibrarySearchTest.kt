package com.phonoodlesramen.actioncenter.search

import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.home.ChannelButton
import com.phonoodlesramen.actioncenter.plex.PlexException
import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.PlexPage
import com.phonoodlesramen.actioncenter.plex.PlexPlay
import com.phonoodlesramen.actioncenter.plex.PlexSearchHit
import com.phonoodlesramen.actioncenter.plex.PlexSearchHub
import com.phonoodlesramen.actioncenter.plex.PlexSection
import com.phonoodlesramen.actioncenter.plex.PlexServerApi
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 15, Q1-Q2: your library through Plex's search, as the profile on now. Made-up titles. */
class LibrarySearchTest {
    private val now = 2_000_000_000L
    private val yours = ServerAccess("https://server.invalid", "yours")
    private val kidsToken = ServerAccess("https://server.invalid", "kid")
    private val owner = ActiveProfile.owner("Alex")
    private val maya = ActiveProfile("kid-maya", "Maya", kid = true, ceiling = setOf("G", "PG", "TV-Y", "TV-Y7", "TV-G", "TV-PG"))

    private fun hit(key: String, title: String, type: String = "movie", year: Int? = 1995, rating: String? = "PG", reason: String? = null, reasonTitle: String? = null) =
        PlexSearchHit(key, type, title, year, rating, art = "/art/$key", reason = reason, reasonTitle = reasonTitle)

    private fun row(key: String, title: String, type: String = "movie", year: Int = 1995, rating: String? = "PG", addedAt: Long? = null) = ItemEntity(
        ratingKey = key, sectionKey = "1", type = type, title = title, year = year, durationMs = null, viewCount = 0,
        lastViewedAt = null, addedAt = addedAt, updatedAt = null, contentRating = rating, audienceRating = null, rating = null,
        thumb = null, art = "/art/$key", genres = "", syncedAt = 0,
    )

    /** A server whose search answers by query; it records who asked. */
    private class FakeServer(private val answers: Map<String, List<PlexSearchHub>>, var down: Boolean = false) : PlexServerApi {
        val asked = mutableListOf<Pair<String, String>>()

        override suspend fun search(access: ServerAccess, query: String, limit: Int): List<PlexSearchHub> {
            asked += access.token to query
            if (down) throw PlexException.Unreachable(IOException("down"))
            return answers[query].orEmpty()
        }

        override suspend fun continueWatching(access: ServerAccess, count: Int) = emptyList<PlexItem>()
        override suspend fun sections(access: ServerAccess) = emptyList<PlexSection>()
        override suspend fun items(access: ServerAccess, section: String, type: Int, start: Int, size: Int, updatedSince: Long?) = PlexPage<PlexItem>(emptyList(), 0, 0)
        override suspend fun history(access: ServerAccess, sinceSeconds: Long, accountId: Int, size: Int) = emptyList<PlexPlay>()
    }

    private fun search(server: FakeServer, cache: List<ItemEntity> = emptyList(), linked: Boolean = true) = PlexLibrarySearch(
        server = server,
        access = { profile -> if (!linked) null else if (profile.kid) kidsToken else yours },
        titlesLike = { pattern, limit -> cache.filter { pattern.replace("\\", "").lowercase() in it.title.lowercase() }.take(limit) },
        items = { keys -> cache.filter { it.ratingKey in keys } },
        titles = { cache },
        nowSeconds = { now },
    )

    @Test
    fun `plain words keep Plex's order and its reasons in words, searched as you`() = runTest {
        val server = FakeServer(
            mapOf(
                "harbor" to listOf(
                    PlexSearchHub("movie", "Movies", listOf(hit("1", "Harbor Lights", rating = "R"), hit("2", "Night Shift", reason = "actor", reasonTitle = "Dana Example"))),
                    PlexSearchHub("actor", "Actors", emptyList()),
                    PlexSearchHub("collection", "Collections", listOf(hit("9", "Harbor Films", type = "collection"))),
                ),
            ),
        )

        val answer = search(server).search(SearchWords.read("harbor"), owner)

        assertEquals(listOf("1", "2"), answer.items.map { it.ratingKey })
        assertEquals("1995 · R", answer.items[0].meta)
        assertEquals("With Dana Example", answer.items[1].why)
        assertFalse(answer.offline)
        assertEquals(listOf("yours" to "harbor"), server.asked)
    }

    @Test
    fun `a kid searches as their own Plex user, and their ceiling hides the rest, unrated too`() = runTest {
        val server = FakeServer(mapOf("harbor" to listOf(PlexSearchHub("movie", "Movies", listOf(hit("1", "Harbor Lights", rating = "R"), hit("2", "Harbor Pup", rating = "G"), hit("3", "Harbor Mystery", rating = null))))))

        val answer = search(server).search(SearchWords.read("harbor"), maya)

        assertEquals(listOf("Harbor Pup"), answer.items.map { it.title })
        assertEquals("kid", server.asked.single().first)
    }

    @Test
    fun `with chips, a close title as typed leads, then the rest narrowed`() = runTest {
        val server = FakeServer(
            mapOf(
                "the harbor show" to listOf(PlexSearchHub("movie", "Movies", listOf(hit("7", "The Harbor Show", year = 1998), hit("8", "Other", year = 1990)))),
                "the harbor" to listOf(PlexSearchHub("show", "Shows", listOf(hit("5", "Harbor Watch", type = "show", year = 2019), hit("7", "The Harbor Show", year = 1998)))),
            ),
        )

        val answer = search(server).search(SearchWords.read("the harbor show"), owner)

        assertEquals("the film keeps its name; the show chip narrows the rest", listOf("7", "5"), answer.items.map { it.ratingKey })
    }

    @Test
    fun `new uses when the title was added, from the copy on this TV`() = runTest {
        val server = FakeServer(mapOf("harbor" to listOf(PlexSearchHub("movie", "Movies", listOf(hit("1", "Harbor Lights"), hit("2", "Harbor Old"))))))
        val cache = listOf(row("1", "Harbor Lights", addedAt = now - 86_400), row("2", "Harbor Old", addedAt = now - 90 * 86_400))

        val answer = search(server, cache).search(SearchWords.read("new harbor"), owner)

        assertEquals(listOf("1"), answer.items.map { it.ratingKey })
    }

    @Test
    fun `chips alone list what's on this TV that fits, newest first`() = runTest {
        val server = FakeServer(emptyMap())
        val cache = listOf(
            row("1", "Pup Adventure", rating = "G", addedAt = 10),
            row("2", "Grown-up Film", rating = "R", addedAt = 30),
            row("3", "Family Film", rating = "PG", addedAt = 20),
            row("4", "Kid Show", type = "show", rating = "TV-Y", addedAt = 40),
        )

        val answer = search(server, cache).search(SearchWords.read("kids movies"), owner)

        assertEquals(listOf("3", "1"), answer.items.map { it.ratingKey })
        assertTrue("Plex isn't asked", server.asked.isEmpty())
    }

    @Test
    fun `with Plex down, or no server, titles on this TV by name, and it says so`() = runTest {
        val cache = listOf(row("1", "Harbor Lights", rating = "R"), row("2", "Lighthouse"))
        val down = search(FakeServer(emptyMap(), down = true), cache).search(SearchWords.read("harbor"), owner)
        val unlinked = search(FakeServer(emptyMap()), cache, linked = false).search(SearchWords.read("harbor"), owner)

        assertEquals(listOf("1"), down.items.map { it.ratingKey })
        assertTrue(down.offline)
        assertEquals(listOf("1"), unlinked.items.map { it.ratingKey })
        assertTrue(unlinked.offline)
        assertEquals("a kid offline keeps their ceiling", emptyList<SearchItem>(), search(FakeServer(emptyMap(), down = true), cache).search(SearchWords.read("harbor"), maya).items)
    }

    @Test
    fun `nothing typed asks nothing`() = runTest {
        val server = FakeServer(emptyMap())

        assertEquals(emptyList<SearchItem>(), search(server).search(SearchWords.read("   "), owner).items)
        assertTrue(server.asked.isEmpty())
    }

    @Test
    fun `on now is guide channels by name or program, then networks by name`() {
        val at = 1_000_000L
        fun slot(title: String, filler: Boolean = false) = GuideSlot(at - 60_000, at + 60_000, title, null, "k", null, "/art/$title", "PG", filler, null)
        val guide = GuideCardState.Ready(
            listOf(
                GuideChannel("c1", 1, "Harbor Movies", "/logo/1", listOf(slot("Lighthouse"))),
                GuideChannel("c2", 2, "Classics", null, listOf(slot("Harbor Lights"))),
                GuideChannel("c3", 3, "Trailers", null, listOf(slot("Harbor trailer", filler = true))),
            ),
            updatedAtMillis = null,
        )
        val networks = listOf(ChannelButton("n1", "Harbor News", "intent://example"), ChannelButton("n2", "ESPN", "intent://espn"))

        val found = onNow(SearchWords.read("harbor"), guide, networks, at)

        assertEquals(listOf("Harbor Movies", "Classics", "Harbor News"), found.map { it.name })
        assertEquals(listOf("c1", "c2", null), found.map { it.channelId })
        assertEquals("Harbor Lights", found[1].now)
        assertEquals("intent://example", found[2].intentUri)
        assertEquals("one letter is too little", emptyList<OnNowItem>(), onNow(SearchWords.read("h"), guide, networks, at))
    }
}
