package com.phonoodlesramen.actioncenter.search

import com.phonoodlesramen.actioncenter.plex.CatalogDetails
import com.phonoodlesramen.actioncenter.plex.CatalogOffer
import com.phonoodlesramen.actioncenter.plex.CatalogSearch
import com.phonoodlesramen.actioncenter.plex.CatalogTitle
import com.phonoodlesramen.actioncenter.plex.PlexCatalogApi
import com.phonoodlesramen.actioncenter.plex.PlexException
import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.PlexPage
import com.phonoodlesramen.actioncenter.plex.PlexPlay
import com.phonoodlesramen.actioncenter.plex.PlexSearchHit
import com.phonoodlesramen.actioncenter.plex.PlexSearchHub
import com.phonoodlesramen.actioncenter.plex.PlexSection
import com.phonoodlesramen.actioncenter.plex.PlexServerApi
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Diagnostics › Search check (Phase 15): each piece's answer, in words, and never a token. */
class SearchCheckTest {
    private val token = "secret-user-token"

    private object Server : PlexServerApi {
        override suspend fun search(access: ServerAccess, query: String, limit: Int) = listOf(
            PlexSearchHub("movie", "Movies", listOf(PlexSearchHit("1", "movie", "The Harbor"), PlexSearchHit("2", "movie", "Night", reason = "actor", reasonTitle = "Dana"))),
            PlexSearchHub("show", "Shows", listOf(PlexSearchHit("3", "show", "The Watch"))),
        )

        override suspend fun continueWatching(access: ServerAccess, count: Int) = emptyList<PlexItem>()
        override suspend fun sections(access: ServerAccess) = emptyList<PlexSection>()
        override suspend fun items(access: ServerAccess, section: String, type: Int, start: Int, size: Int, updatedSince: Long?) = PlexPage<PlexItem>(emptyList(), 0, 0)
        override suspend fun history(access: ServerAccess, sinceSeconds: Long, accountId: Int, size: Int) = emptyList<PlexPlay>()
    }

    private class Catalog(private val refuse: Boolean = false) : PlexCatalogApi {
        override suspend fun search(query: String, limit: Int) = CatalogSearch(listOf(CatalogTitle("5d77", "movie", "Harbor Lights", 1995)), listOf("Harbor Heat"))
        override suspend fun details(ratingKey: String) = CatalogDetails(ratingKey, "R", 16)
        override suspend fun offers(ratingKey: String, token: String): List<CatalogOffer> {
            if (refuse) throw PlexException.Unauthorized()
            return listOf(CatalogOffer("netflix", "Netflix", "subscription", "US", "https://www.netflix.com/title/1"), CatalogOffer("amazon", "Amazon Video", "rent"))
        }
    }

    private suspend fun check(catalog: PlexCatalogApi = Catalog(), linked: Boolean = true, signedIn: Boolean = true): SearchCheckResult {
        var last = SearchCheckResult()
        SearchCheck(
            server = Server,
            access = { if (linked) ServerAccess("https://server.invalid", "server-token") else null },
            catalog = catalog,
            userToken = { if (signedIn) token else null },
            searchables = { listOf("com.example.movies") },
        ).run { last = it }
        return last
    }

    @Test
    fun `every piece's answer, and the offers for the buttons`() = runTest {
        val result = check()

        assertTrue(result.done)
        assertEquals(
            listOf(
                "2 hubs: movie 2, show 1; 1 found through something else (an actor, a genre)",
                "1 titles, first Harbor Lights (1995); 1 suggested terms",
                "Rated R · Common Sense 16+",
                "2 offers: Netflix [netflix] (subscription), Amazon Video [amazon] (rent), no link",
                "1: com.example.movies",
            ),
            result.lines.map { it.result },
        )
        assertTrue(result.lines.all { it.ok })
        assertEquals("Harbor Lights", result.title?.title)
        assertEquals(2, result.offers.size)
        assertFalse("the token never shows", result.lines.any { token in it.label || token in it.result })
    }

    @Test
    fun `Plex saying no, no account and no server are each said, in the caution colour`() = runTest {
        assertEquals("Plex said no (401)", check(Catalog(refuse = true)).lines[3].result)
        assertEquals("No Plex account on this TV", check(signedIn = false).lines[3].result)
        val unlinked = check(linked = false).lines.first()
        assertEquals("No server linked", unlinked.result)
        assertFalse(unlinked.ok)
    }
}
