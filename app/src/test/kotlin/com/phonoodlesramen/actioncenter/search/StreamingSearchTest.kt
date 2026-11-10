package com.phonoodlesramen.actioncenter.search

import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.plex.CatalogDetails
import com.phonoodlesramen.actioncenter.plex.CatalogOffer
import com.phonoodlesramen.actioncenter.plex.CatalogSearch
import com.phonoodlesramen.actioncenter.plex.CatalogTitle
import com.phonoodlesramen.actioncenter.plex.PlexCatalogApi
import com.phonoodlesramen.actioncenter.plex.PlexException
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 15, Q3: your streaming apps through Plex's catalog, as the profile on now. Made-up titles. */
class StreamingSearchTest {
    private val owner = ActiveProfile.owner("Alex")
    private val maya = ActiveProfile("kid-maya", "Maya", kid = true, ceiling = setOf("G", "PG", "TV-Y", "TV-Y7", "TV-G", "TV-PG"))

    private val netflix = LaunchableApp("com.netflix.ninja", "com.netflix.ninja.MainActivity", "Netflix")
    private val prime = LaunchableApp("com.amazon.amazonvideo.livingroom", "com.amazon.Main", "Prime Video")
    private val disney = LaunchableApp("com.disney.disneyplus", "com.disney.Main", "Disney+")
    private val youTube = LaunchableApp("com.google.android.youtube.tv", "com.google.Main", "YouTube")
    private val youTubeTv = LaunchableApp("com.google.android.youtube.tvunplugged", "com.google.Unplugged", "YouTube TV")
    private val apps = listOf(netflix, prime, disney, youTube, youTubeTv)

    private fun offer(platform: String, title: String, type: String = "subscription", url: String? = "https://watch.invalid/$platform") =
        CatalogOffer(platform, title, type, "US", url)

    private class Title(val title: CatalogTitle, val rating: String?, val offers: List<CatalogOffer>)

    /** The catalog: [titles] in this order, counting what it's asked. */
    private class Catalog(private val titles: List<Title>, private val failing: Set<String> = emptySet()) : PlexCatalogApi {
        var searches = 0
        val looked = mutableListOf<String>()
        var token: String? = null

        override suspend fun search(query: String, limit: Int): CatalogSearch {
            searches++
            return CatalogSearch(titles.map { it.title })
        }

        override suspend fun details(ratingKey: String): CatalogDetails? {
            looked += ratingKey
            if (ratingKey in failing) throw PlexException.Unreachable(java.io.IOException("down"))
            return titles.first { it.title.ratingKey == ratingKey }.rating?.let { CatalogDetails(ratingKey, it) }
        }

        override suspend fun offers(ratingKey: String, token: String): List<CatalogOffer> {
            this.token = token
            return titles.first { it.title.ratingKey == ratingKey }.offers
        }
    }

    private fun title(key: String, name: String, type: String = "movie", year: Int = 1995, rating: String? = "PG-13", genres: List<String> = emptyList(), offers: List<CatalogOffer>) =
        Title(CatalogTitle(key, type, name, year, art = "https://art.invalid/$key.jpg", genres = genres), rating, offers)

    private var day = LocalDate.of(2026, 11, 3)

    private fun search(catalog: PlexCatalogApi, signedIn: Boolean = true) = PlexStreamingSearch(catalog, token = { if (signedIn) "user-token" else null }, today = { day })

    private suspend fun StreamingSearch.find(
        typed: String,
        profile: ActiveProfile = owner,
        on: List<LaunchableApp> = apps,
        library: List<SearchItem> = emptyList(),
    ) = search(SearchWords.read(typed), profile, on, library)

    @Test
    fun `a title shows with the apps on this TV that stream it, subscription and free only, in the apps' order`() = runTest {
        val catalog = Catalog(
            listOf(
                title(
                    "a1", "Harbor Lights",
                    offers = listOf(
                        offer("amazon-prime-video", "Amazon Prime Video", url = null),
                        offer("netflix", "Netflix"),
                        offer("apple-itunes", "Apple TV", type = "buy"),
                        offer("amazon-video", "Amazon Video", type = "rent"),
                    ),
                ),
            ),
        )

        val found = search(catalog).find("harbor").single()

        assertEquals("Harbor Lights", found.title)
        assertEquals("1995 · PG-13", found.meta)
        assertEquals("https://art.invalid/a1.jpg", found.image)
        assertEquals(listOf("Netflix", "Prime Video"), found.apps.map { it.label })
        assertEquals("https://watch.invalid/netflix", found.apps[0].url)
        assertNull("Prime Video's offer had no link: OK opens its home", found.apps[1].url)
        assertEquals("user-token", catalog.token)
    }

    @Test
    fun `YouTube is left out and YouTube TV stays`() = runTest {
        val catalog = Catalog(
            listOf(
                title("a1", "Harbor Lights", offers = listOf(offer("youtube", "YouTube", type = "free"))),
                title("a2", "Harbor Watch", offers = listOf(offer("youtube-tv", "YouTube TV"), offer("youtube", "YouTube", type = "ads"))),
            ),
        )

        val found = search(catalog).find("harbor")

        assertEquals(listOf("Harbor Watch"), found.map { it.title })
        assertEquals(listOf("YouTube TV"), found.single().apps.map { it.label })
    }

    @Test
    fun `a service the check didn't name goes by the app's name, and one not on this TV doesn't show`() = runTest {
        val catalog = Catalog(
            listOf(
                title("a1", "Harbor Lights", offers = listOf(offer("disney-plus", "Disney Plus", type = "free"))),
                title("a2", "Harbor Watch", offers = listOf(offer("hulu", "Hulu"))),
            ),
        )

        val found = search(catalog).find("harbor")

        assertEquals(listOf("Harbor Lights"), found.map { it.title })
        assertEquals("Disney+", found.single().apps.single().label)
    }

    @Test
    fun `a kid sees only what's inside their ceiling and on their apps`() = runTest {
        val catalog = Catalog(
            listOf(
                title("a1", "Harbor Lights", rating = "R", offers = listOf(offer("netflix", "Netflix"))),
                title("a2", "Harbor Pups", rating = "G", offers = listOf(offer("netflix", "Netflix"), offer("disney-plus", "Disney Plus"))),
                title("a3", "Harbor Mystery", rating = null, offers = listOf(offer("netflix", "Netflix"))),
            ),
        )

        val found = search(catalog).find("harbor", profile = maya, on = listOf(disney))

        assertEquals("only G, and only on Disney+", listOf("Harbor Pups"), found.map { it.title })
        assertEquals(listOf("Disney+"), found.single().apps.map { it.label })
    }

    @Test
    fun `nobody sees titles rated X or NC-17, or unrated with an adult genre`() = runTest {
        val netflixOnly = listOf(offer("netflix", "Netflix"))
        val catalog = Catalog(
            listOf(
                title("a1", "Harbor Nights", rating = "NC-17", offers = netflixOnly),
                title("a2", "Harbor After Dark", rating = null, genres = listOf("Drama", "Erotic"), offers = netflixOnly),
                title("a3", "Harbor Lights", rating = null, genres = listOf("Drama"), offers = netflixOnly),
            ),
        )

        val found = search(catalog).find("harbor")

        assertEquals(listOf("Harbor Lights"), found.map { it.title })
    }

    @Test
    fun `titles already in your library are left out`() = runTest {
        val netflixOnly = listOf(offer("netflix", "Netflix"))
        val catalog = Catalog(
            listOf(
                title("a1", "Harbor Lights", year = 1995, offers = netflixOnly),
                title("a2", "Harbor Lights", year = 2019, offers = netflixOnly),
            ),
        )
        val library = listOf(SearchItem("9", "movie", "Harbor Lights", "1995 · PG-13", year = 1995))

        val found = search(catalog).find("harbor", library = library)

        assertEquals("the 2019 one isn't yours", listOf(2019), found.map { it.year })
    }

    @Test
    fun `chips narrow it, and new doesn't apply to the catalog`() = runTest {
        val netflixOnly = listOf(offer("netflix", "Netflix"))
        val catalog = Catalog(
            listOf(
                title("a1", "Harbor Lights", year = 1995, rating = "PG-13", offers = netflixOnly),
                title("a2", "Harbor Watch", type = "show", year = 2019, rating = "TV-PG", offers = netflixOnly),
                title("a3", "Harbor Pups", year = 1998, rating = "G", offers = netflixOnly),
            ),
        )
        val streaming = search(catalog)

        assertEquals(listOf("Harbor Lights", "Harbor Pups"), streaming.find("90s harbor").map { it.title })
        assertEquals(listOf("Harbor Watch"), streaming.find("harbor shows").map { it.title })
        assertEquals(listOf("Harbor Watch", "Harbor Pups"), streaming.find("kids harbor").map { it.title })
        assertEquals(3, streaming.find("new harbor").size)
        assertTrue("chips alone send nothing to the catalog", streaming.find("90s movies").isEmpty())
    }

    @Test
    fun `details and offers are held for the day`() = runTest {
        val catalog = Catalog(listOf(title("a1", "Harbor Lights", offers = listOf(offer("netflix", "Netflix")))))
        val streaming = search(catalog)

        streaming.find("harbor")
        streaming.find("harbor lights")
        assertEquals(listOf("a1"), catalog.looked)

        day = day.plusDays(1)
        streaming.find("harbor")
        assertEquals(listOf("a1", "a1"), catalog.looked)
    }

    @Test
    fun `a title the catalog can't describe is skipped, and asked again next time`() = runTest {
        val netflixOnly = listOf(offer("netflix", "Netflix"))
        val catalog = Catalog(
            listOf(title("a1", "Harbor Lights", offers = netflixOnly), title("a2", "Harbor Watch", offers = netflixOnly)),
            failing = setOf("a1"),
        )
        val streaming = search(catalog)

        assertEquals(listOf("Harbor Watch"), streaming.find("harbor").map { it.title })
        streaming.find("harbor")
        assertEquals(2, catalog.looked.count { it == "a1" })
    }

    @Test
    fun `when the first titles are on your apps, only they are looked up`() = runTest {
        val catalog = Catalog((1..10).map { title("a$it", "Harbor $it", offers = listOf(offer("netflix", "Netflix"))) })

        val found = search(catalog).find("harbor")

        assertEquals(PlexStreamingSearch.SHOWN, found.size)
        assertEquals((1..PlexStreamingSearch.GROUP).map { "a$it" }, catalog.looked)
    }

    @Test
    fun `when the first titles aren't on your apps, it looks further down, a group at a time`() = runTest {
        val elsewhere = listOf(offer("hulu", "Hulu"), offer("apple-itunes", "Apple TV", type = "buy"))
        val netflixOnly = listOf(offer("netflix", "Netflix"))
        val catalog = Catalog((1..20).map { title("a$it", "Harbor $it", offers = if (it <= 8) elsewhere else netflixOnly) })

        val found = search(catalog).find("harbor")

        assertEquals("the best matches on your apps, in the catalog's order", (9..14).map { "Harbor $it" }, found.map { it.title })
        assertEquals("three groups found 6; the last two titles weren't needed", (1..18).map { "a$it" }, catalog.looked)
    }

    @Test
    fun `titles you own don't use up a group`() = runTest {
        val netflixOnly = listOf(offer("netflix", "Netflix"))
        val catalog = Catalog((1..8).map { title("a$it", "Harbor $it", offers = netflixOnly) })
        val library = (1..6).map { SearchItem("$it", "movie", "Harbor $it", "1995 · PG-13", year = 1995) }

        val found = search(catalog).find("harbor", library = library)

        assertEquals(listOf("Harbor 7", "Harbor 8"), found.map { it.title })
        assertEquals(listOf("a7", "a8"), catalog.looked)
    }

    @Test
    fun `it stops at the catalog's last title`() = runTest {
        val catalog = Catalog((1..8).map { title("a$it", "Harbor $it", offers = listOf(offer(if (it == 8) "netflix" else "hulu", "Service"))) })

        val found = search(catalog).find("harbor")

        assertEquals(listOf("Harbor 8"), found.map { it.title })
        assertEquals(8, catalog.looked.size)
    }

    @Test
    fun `not signed in to Plex, or no apps, there's nothing to ask`() = runTest {
        val catalog = Catalog(listOf(title("a1", "Harbor Lights", offers = listOf(offer("netflix", "Netflix")))))

        assertTrue(search(catalog, signedIn = false).find("harbor").isEmpty())
        assertTrue(search(catalog).find("harbor", on = emptyList()).isEmpty())
        assertEquals(0, catalog.searches)
    }
}
