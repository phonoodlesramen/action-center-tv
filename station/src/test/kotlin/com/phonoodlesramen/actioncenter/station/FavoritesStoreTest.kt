package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.guide.station.Favorites
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Each profile's favorite channels, by the key every TV agrees on (proposal A). */
class FavoritesStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var versions: Versions
    private lateinit var store: FavoritesStore

    @Before
    fun setUp() {
        versions = Versions(temp.root.toPath().resolve("version"))
        store = store()
    }

    private fun store() = FavoritesStore(temp.root.toPath().resolve("favorites.json"), versions)

    @Test
    fun `each profile key has its own favorites, in its order`() {
        val start = versions.current
        assertEquals("never saved: version 0, so a TV sends up the list it kept itself", Favorites(emptyList(), 0), store.get("owner"))

        assertEquals(Favorites(listOf("c-3", "c-1"), start + 1), store.put("owner", listOf("c-3", "c-1", "c-3")))
        store.put("plex:42", listOf("c-kids"))
        assertEquals(listOf("c-3", "c-1"), store.get("owner").channels)
        assertEquals(listOf("c-kids"), store.get("plex:42").channels)
        assertEquals(emptyList<String>(), store.get("plex:7").channels)
    }

    @Test
    fun `a change moves the house's version, and an unchanged list leaves it`() {
        val first = store.put("owner", listOf("c-1")).version
        assertEquals(first, versions.current)
        assertEquals(first, store.put("owner", listOf("c-1")).version)
        val moved = store.put("owner", listOf("c-2", "c-1")).version
        assertEquals(first + 1, moved)
        store.put("plex:42", listOf("c-kids"))
        assertEquals(moved + 1, store.get("plex:42").version)
        assertEquals(moved + 1, store.get("owner").version)
    }

    @Test
    fun `they survive a restart, and an emptied list is forgotten`() {
        store.put("owner", listOf("c-1", "c-2"))
        store.put("plex:42", listOf("c-kids"))
        store.put("plex:42", emptyList())
        store = store()
        assertEquals(listOf("c-1", "c-2"), store.get("owner").channels)
        assertEquals(emptyList<String>(), store.get("plex:42").channels)
        assertFalse(Files.readString(temp.root.toPath().resolve("favorites.json")).contains("plex:42"))
    }

    @Test
    fun `a key that isn't the owner or a Plex user is refused, and so is a list that can't be right`() {
        for (key in listOf("guest", "Owner", "plex:", "plex:abc", "plex:-1", "plex:42:1")) {
            refused { store.get(key) }
            refused { store.put(key, listOf("c-1")) }
        }
        refused { store.put("owner", listOf(" ")) }
        refused { store.put("owner", (1..501).map { "c-$it" }) }
        assertEquals(emptyList<String>(), store.get("owner").channels)
    }

    private fun refused(block: () -> Unit) {
        try {
            block()
            fail("Expected a 400")
        } catch (e: ApiException) {
            assertEquals(400, e.status)
        }
    }
}
