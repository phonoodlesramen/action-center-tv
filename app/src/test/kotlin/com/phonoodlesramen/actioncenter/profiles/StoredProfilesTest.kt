package com.phonoodlesramen.actioncenter.profiles

import com.phonoodlesramen.actioncenter.plex.PlexAccount
import com.phonoodlesramen.actioncenter.plex.PlexAccounts
import com.phonoodlesramen.actioncenter.plex.PlexHeaders
import com.phonoodlesramen.actioncenter.plex.PlexHome
import com.phonoodlesramen.actioncenter.plex.PlexHomeUser
import com.phonoodlesramen.actioncenter.plex.PlexPin
import com.phonoodlesramen.actioncenter.plex.PlexServer
import com.phonoodlesramen.actioncenter.plex.SHIELD_PLEX_DEVICE
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Settings › Profiles' store (K2, K9, K10): linking a kid's Plex Home user by the switch and
 * then `resources`, the PIN that's never kept, and saves in order. plex.tv is a local fake
 * server; every name, id, PIN and token is made up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoredProfilesTest {
    private val plexTv = MockWebServer()
    private lateinit var home: PlexHome

    @Before
    fun setUp() {
        plexTv.start()
        home = PlexHome(OkHttpClient(), PlexHeaders({ "client-123" }, "0.5.0", SHIELD_PLEX_DEVICE), plexTv.url("/"))
    }

    @After
    fun tearDown() = plexTv.close()

    private fun respond(body: String, code: Int = 200) = plexTv.enqueue(MockResponse.Builder().code(code).body(body).build())

    private val mayaUser = PlexHomeUser(1002, "u-maya", "Maya R", admin = false, restricted = true, restrictionProfile = "older_kid", protected = false)
    private val leoUser = PlexHomeUser(1003, "u-leo", "Leo R", admin = false, restricted = true, restrictionProfile = "little_kid", protected = true)
    private val maya = KidEntity("kid-maya", "Maya", 0xFFFF8A65, LocalDate.of(2017, 3, 14).toEpochDay())

    /** plex.tv's `resources`: each plex.tv token sees the servers listed for it. */
    private class Resources(val byToken: Map<String, List<PlexServer>>) : PlexAccounts {
        val asked = mutableListOf<String>()
        override suspend fun createPin() = PlexPin(1, "CODE", 0, null)
        override suspend fun checkPin(id: Long): PlexPin? = null
        override suspend fun account(token: String) = PlexAccount(1, "uuid", "owner", "Owner")
        override suspend fun servers(token: String): List<PlexServer> {
            asked += token
            return byToken[token].orEmpty()
        }
    }

    private fun server(id: String, token: String) = PlexServer("Basement", id, token, owned = false, connections = emptyList())

    private class Setup(val profiles: StoredProfiles, val store: MemoryProfileStore, val tokens: MemoryKidTokens, val resources: Resources)

    private fun TestScope.setup(
        resources: Resources = Resources(emptyMap()),
        kids: List<KidEntity> = listOf(maya),
        owner: String? = "owner-token",
        serverId: String? = "server-1",
    ): Setup {
        val store = MemoryProfileStore(kids)
        val tokens = MemoryKidTokens()
        val profiles = StoredProfiles(store, tokens, home, resources, { owner }, { serverId }, backgroundScope)
        return Setup(profiles, store, tokens, resources)
    }

    @Test
    fun `linking switches to the user, reads their token for your server from resources, and seals it`() = runTest {
        respond("""{"id": 1002, "uuid": "u-maya", "authToken": "maya-plex-tv-token"}""")
        val s = setup(Resources(mapOf("maya-plex-tv-token" to listOf(server("other", "nope"), server("server-1", "maya-server-token")))))

        val result = s.profiles.link(maya, mayaUser)
        runCurrent()

        assertEquals(PlexLinkResult.Linked(maya.copy(plexUserId = 1002, plexUserTitle = "Maya R")), result)
        assertEquals(mapOf("kid-maya" to "maya-server-token"), s.tokens.tokens)
        assertEquals("Plezy's link names them by it (Phase 11)", mapOf("kid-maya" to "u-maya"), s.tokens.uuids)
        assertEquals(listOf(maya.copy(plexUserId = 1002, plexUserTitle = "Maya R")), s.store.kids.value)
        assertEquals("resources asked with the user's own plex.tv token", listOf("maya-plex-tv-token"), s.resources.asked)
        val switch = plexTv.takeRequest()
        assertEquals("/api/v2/home/users/u-maya/switch", switch.url.encodedPath)
        assertEquals("owner-token", switch.headers["X-Plex-Token"])
        assertNull(switch.url.queryParameter("pin"))
    }

    @Test
    fun `a protected user's PIN goes to the switch and nowhere else`() = runTest {
        respond("""{"authToken": "leo-plex-tv-token"}""")
        val s = setup(Resources(mapOf("leo-plex-tv-token" to listOf(server("server-1", "leo-server-token")))))

        val result = s.profiles.link(maya, leoUser, pin = "4321")
        runCurrent()

        assertTrue(result is PlexLinkResult.Linked)
        assertEquals("4321", plexTv.takeRequest().url.queryParameter("pin"))
        val stored = s.store.written + s.tokens.written
        assertTrue(stored.isNotEmpty())
        assertTrue("the PIN never reaches storage: $stored", stored.none { "4321" in it })
    }

    @Test
    fun `a PIN Plex turns down says so, and the kid stays unlinked`() = runTest {
        respond("", code = 401)
        respond("", code = 401)
        val s = setup()

        val result = s.profiles.link(maya, leoUser, pin = "0000")
        runCurrent()

        assertEquals(PlexLinkResult.Failed("Plex didn't accept that PIN for Leo R. Try again."), result)
        assertTrue(s.tokens.written.isEmpty())
        assertEquals(listOf(maya), s.store.kids.value)
    }

    @Test
    fun `a user who can't see your server isn't linked, and why is said`() = runTest {
        respond("""{"authToken": "maya-plex-tv-token"}""")
        val s = setup(Resources(mapOf("maya-plex-tv-token" to listOf(server("someone-elses", "x")))))

        val result = s.profiles.link(maya, mayaUser)
        runCurrent()

        assertEquals(
            PlexLinkResult.Failed("Maya R can't see your Plex server. Give them access to its libraries in Plex, then try again."),
            result,
        )
        assertTrue(s.tokens.written.isEmpty())
        assertNull(s.store.kids.value.single().plexUserId)
    }

    @Test
    fun `plex_tv out of reach, or no sign-in or server yet, links nothing`() = runTest {
        plexTv.close()
        val offline = setup()
        assertEquals(PlexLinkResult.Failed(ProfilesController.OFFLINE), offline.profiles.link(maya, mayaUser))
        assertEquals(PlexLinkResult.Failed(ProfilesController.NOT_SIGNED_IN), setup(owner = null).profiles.link(maya, mayaUser))
        assertEquals(PlexLinkResult.Failed(ProfilesController.NO_SERVER), setup(serverId = null).profiles.link(maya, mayaUser))
        runCurrent()
        assertTrue(offline.tokens.written.isEmpty())
    }

    @Test
    fun `only your Home's managed users are offered`() = runTest {
        respond(
            """{"users": [
              {"id": 1001, "uuid": "u-owner", "title": "Owner", "admin": true, "restricted": false, "protected": true},
              {"id": 1002, "uuid": "u-maya", "title": "Maya R", "admin": false, "restricted": true, "restrictionProfile": "older_kid", "protected": false},
              {"id": 1003, "uuid": "u-leo", "title": "Leo R", "admin": false, "restricted": true, "restrictionProfile": "little_kid", "protected": true}
            ]}""",
        )
        val s = setup()

        val users = s.profiles.linkableUsers()

        assertEquals(listOf(mayaUser, leoUser), users.users)
        assertNull(users.problem)
        assertEquals(LinkableUsers(problem = ProfilesController.NOT_SIGNED_IN), setup(owner = null).profiles.linkableUsers())
    }

    @Test
    fun `unlinking or removing a kid forgets their token`() = runTest {
        val linked = maya.copy(plexUserId = 1002, plexUserTitle = "Maya R")
        val s = setup(kids = listOf(linked))
        s.tokens.tokens["kid-maya"] = "maya-server-token"

        s.profiles.unlink(linked)
        runCurrent()

        assertTrue(s.tokens.tokens.isEmpty())
        assertEquals(listOf(maya), s.store.kids.value)

        s.tokens.tokens["kid-maya"] = "again"
        s.profiles.removeKid(maya)
        runCurrent()

        assertTrue(s.tokens.tokens.isEmpty())
        assertTrue(s.store.kids.value.isEmpty())
    }

    @Test
    fun `saves land in the order they were made, and a link keeps a change made meanwhile`() = runTest {
        respond("""{"authToken": "maya-plex-tv-token"}""")
        val s = setup(Resources(mapOf("maya-plex-tv-token" to listOf(server("server-1", "maya-server-token")))))

        s.profiles.saveKid(maya.copy(color = 0xFFB39DDB))
        s.profiles.saveKid(maya.copy(color = 0xFF4DD0E1))
        // The page that started the link still had the old colour.
        s.profiles.link(maya, mayaUser)
        runCurrent()

        assertEquals(listOf(maya.copy(color = 0xFF4DD0E1, plexUserId = 1002, plexUserTitle = "Maya R")), s.store.kids.value)
        assertFalse(s.store.written.isEmpty())
    }
}
