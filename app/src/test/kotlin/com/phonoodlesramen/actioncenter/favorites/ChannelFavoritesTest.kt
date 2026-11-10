package com.phonoodlesramen.actioncenter.favorites

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phonoodlesramen.actioncenter.guide.GuideDatabase
import com.phonoodlesramen.actioncenter.guide.LineupServer
import com.phonoodlesramen.actioncenter.guide.station.Favorites
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.station.FakeStation
import com.phonoodlesramen.actioncenter.station.FakeStation.Companion.NOW
import com.phonoodlesramen.actioncenter.station.GuideStation
import com.phonoodlesramen.actioncenter.station.GuideStationTest
import com.phonoodlesramen.actioncenter.station.MemoryStationStore
import com.phonoodlesramen.actioncenter.station.SoftwareKeys
import com.phonoodlesramen.actioncenter.station.StationException
import com.phonoodlesramen.actioncenter.station.StationPhase
import com.phonoodlesramen.actioncenter.station.StationReach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Favorite channels (Phase 13, C1) against a fake guide station over mutual TLS, as
 * [GuideStationTest] runs it: this TV's own while unpaired, the station's by the profile's
 * key once paired, a change elsewhere followed through `changes`, the station down (the
 * change refused), a 1.1 station (404: kept here, sent once it's updated), a kid with and
 * without a Plex user, and a deleted channel dropped as the list is saved.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelFavoritesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, GuideDatabase::class.java).allowMainThreadQueries().build()
    private val stationStore = MemoryStationStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fake = FakeStation().start()
    private val station = GuideStation(
        stationStore, { SoftwareKeys() }, OkHttpClient(), db, LineupServer.InMemory("server-1"), linkedServer = { "server-1" },
        model = "SHIELD Android TV", scope = scope, clock = { NOW }, timing = GuideStationTest.FAST,
    )
    private val profile = MutableStateFlow(ActiveProfile.owner("Alex"))
    private val store = MemoryFavorites()
    private var known = listOf("c-comedy", "c-cartoons")
    private val favorites = ChannelFavorites(store, station, profile, knownChannels = { known })

    private val maya = ActiveProfile("kid-maya", "Maya", kid = true, ceiling = setOf("TV-Y"), plexUserId = 42)
    private val sam = ActiveProfile("kid-sam", "Sam", kid = true, ceiling = setOf("TV-Y"))

    @After
    fun tearDown() {
        scope.cancel()
        fake.stop()
        db.close()
    }

    private fun <T> await(flow: Flow<T>, what: String, until: (T) -> Boolean): T = runBlocking {
        var last: T? = null
        try {
            withTimeout(10_000) { flow.first { last = it; until(it) } }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $what; last: $last", e)
        }
    }

    private fun pair() {
        station.pair(fake.address, "Kitchen")
        val pairing = await(station.view, "the code") { it.phase is StationPhase.Pairing }.phase as StationPhase.Pairing
        fake.approve(pairing.code)
        await(stationStore.saved, "the copy") { it.guide != null && it.deviceId != null }
    }

    private fun favoriteCalls() = fake.calls.filter { it.contains("/v1/favorites/") }

    @Test
    fun `the key is the owner's, a linked kid's Plex Home user, or none for a kid without one`() {
        assertEquals("owner", FavoriteKeys.station(ActiveProfile.owner("Alex")))
        assertEquals("plex:42", FavoriteKeys.station(maya))
        assertNull(FavoriteKeys.station(sam))
        // A kid without a Plex user keeps theirs on this TV, under their profile.
        assertEquals("kid:kid-sam", FavoriteKeys.local(sam))
        assertEquals("plex:42", FavoriteKeys.local(maya))
    }

    @Test
    fun `unpaired, favorites are this TV's own`() = runBlocking<Unit> {
        assertEquals(FavoriteChange.Saved, favorites.set("c-comedy", true))
        assertEquals(FavoriteChange.Saved, favorites.set("c-cartoons", true))

        assertEquals(listOf("c-comedy", "c-cartoons"), favorites.current.first())
        assertEquals(FavoritesCopy(listOf("c-comedy", "c-cartoons"), unsent = true), store.current.profiles["owner"])
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `paired, a change goes to the station under the profile's key, and the copy keeps its version`() = runBlocking<Unit> {
        pair()
        assertEquals(FavoriteChange.Saved, favorites.set("c-cartoons", true))
        assertEquals(FavoriteChange.Saved, favorites.reorder(listOf("c-cartoons", "c-comedy")))

        assertEquals(listOf("c-cartoons", "c-comedy"), fake.favoritesOf("owner")?.channels)
        assertEquals(listOf("c-cartoons", "c-comedy"), favorites.current.first())
        assertEquals(FavoritesCopy(listOf("c-cartoons", "c-comedy"), fake.favoritesOf("owner")!!.version), store.current.profiles["owner"])
        assertEquals("""PUT /v1/favorites/owner {"channels":["c-cartoons","c-comedy"]}""", favoriteCalls().last())
    }

    @Test
    fun `a change on another TV arrives through changes while the home is in front`() {
        pair()
        val following = scope.launch {
            launch { station.keepUp() }
            launch { favorites.keepUp() }
        }
        try {
            fake.saveFavoritesElsewhere("owner", listOf("c-comedy", "c-cartoons"))
            await(favorites.current, "the other TV's favorites") { it == listOf("c-comedy", "c-cartoons") }
            // Another profile's change is theirs alone.
            fake.saveFavoritesElsewhere("plex:42", listOf("c-cartoons"))
            await(favorites.current, "still yours") { it == listOf("c-comedy", "c-cartoons") }
            profile.value = maya
            await(favorites.current, "Maya's") { it == listOf("c-cartoons") }
        } finally {
            following.cancel()
        }
    }

    @Test
    fun `with the station down a change is refused with editing's sentence, and nothing changes`() = runBlocking<Unit> {
        pair()
        favorites.set("c-comedy", true)
        fake.down = true

        assertEquals(FavoriteChange.Refused(StationException.CANT_REACH), favorites.set("c-cartoons", true))
        assertEquals(FavoriteChange.Refused(StationException.CANT_REACH), favorites.reorder(emptyList()))
        assertEquals(listOf("c-comedy"), favorites.current.first())
        assertEquals(StationReach.UNREACHABLE, station.view.value.reach)

        fake.down = false
        assertEquals(FavoriteChange.Saved, favorites.set("c-cartoons", true))
        assertEquals(listOf("c-comedy", "c-cartoons"), fake.favoritesOf("owner")?.channels)
    }

    @Test
    fun `a 1-1 station keeps favorites on this TV and says it needs an update, and gets them once it's updated`() = runBlocking<Unit> {
        pair()
        fake.keepsFavorites = false

        assertEquals(FavoriteChange.Saved, favorites.set("c-cartoons", true))
        assertEquals(listOf("c-cartoons"), favorites.current.first())
        assertTrue(store.current.profiles.getValue("owner").unsent)
        assertTrue(await(station.view, "the update note") { it.favoritesNeedUpdate }.favoritesNeedUpdate)

        // Updated: it has none yet, so this TV's go up to it.
        fake.keepsFavorites = true
        favorites.fetch()
        assertEquals(listOf("c-cartoons"), fake.favoritesOf("owner")?.channels)
        assertFalse(store.current.profiles.getValue("owner").unsent)
        assertFalse(station.view.value.favoritesNeedUpdate)
    }

    @Test
    fun `once the station has favorites, they win over ones this TV kept`() = runBlocking<Unit> {
        store.update { FavoritesSaved(mapOf("owner" to FavoritesCopy(listOf("c-comedy"), unsent = true))) }
        fake.saveFavoritesElsewhere("owner", listOf("c-cartoons"))
        pair()

        favorites.fetch()
        assertEquals(listOf("c-cartoons"), favorites.current.first())
        assertEquals(Favorites(listOf("c-cartoons"), fake.favoritesOf("owner")!!.version), fake.favoritesOf("owner"))
    }

    @Test
    fun `a kid linked to Plex keeps theirs on the station, and one without a Plex user on this TV alone`() = runBlocking<Unit> {
        pair()
        profile.value = maya
        favorites.set("c-cartoons", true)
        assertEquals(listOf("c-cartoons"), fake.favoritesOf("plex:42")?.channels)
        assertNull(fake.favoritesOf("owner"))

        profile.value = sam
        fake.calls.clear()
        favorites.set("c-cartoons", true)
        favorites.fetch()
        assertEquals(listOf("c-cartoons"), favorites.current.first())
        assertEquals(FavoritesCopy(listOf("c-cartoons"), unsent = true), store.current.profiles["kid:kid-sam"])
        assertTrue(favoriteCalls().isEmpty())
    }

    @Test
    fun `a deleted channel leaves the list the next time it's saved, and an off one stays`() = runBlocking<Unit> {
        store.update { FavoritesSaved(mapOf("owner" to FavoritesCopy(listOf("c-comedy", "c-westerns", "c-off")))) }
        // c-off is in the lineup, turned off; c-westerns was deleted.
        known = listOf("c-comedy", "c-cartoons", "c-off")
        // Shown as it is until then.
        assertEquals(listOf("c-comedy", "c-westerns", "c-off"), favorites.current.first())

        favorites.set("c-cartoons", true)
        assertEquals(listOf("c-comedy", "c-off", "c-cartoons"), favorites.current.first())
    }
}
