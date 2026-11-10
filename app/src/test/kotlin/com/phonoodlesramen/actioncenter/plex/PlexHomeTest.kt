package com.phonoodlesramen.actioncenter.plex

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Plex Home on plex.tv (Phase 5, K2). The answers are hand-written to the community-documented shape; every name, id and token is made up. */
class PlexHomeTest {
    private val server = MockWebServer()
    private lateinit var home: PlexHome

    @Before
    fun setUp() {
        server.start()
        home = PlexHome(OkHttpClient(), PlexHeaders({ "client-123" }, "0.5.0", SHIELD_PLEX_DEVICE), server.url("/"))
    }

    @After
    fun tearDown() = server.close()

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private val users = """
        {"id": 77, "name": "The Home", "users": [
          {"id": 1001, "uuid": "u-owner", "title": "Owner", "username": "owner", "admin": true, "restricted": false, "protected": true},
          {"id": 1002, "uuid": "u-maya", "title": "Maya", "admin": false, "restricted": true, "restrictionProfile": "older_kid", "protected": false},
          {"id": 1003, "uuid": "u-leo", "title": "Leo", "admin": false, "restricted": true, "restrictionProfile": "little_kid", "protected": true, "extra": 1}
        ]}
    """.trimIndent()

    @Test
    fun `your Home's users, with who's managed, restricted and has a PIN`() = runTest {
        respond(users)

        val list = home.users("owner-token")

        assertEquals(listOf("Owner", "Maya", "Leo"), list.map { it.title })
        assertEquals(listOf(true, false, false), list.map { it.admin })
        assertEquals(listOf(null, "older_kid", "little_kid"), list.map { it.restrictionProfile })
        assertEquals(listOf(true, false, true), list.map { it.protected })
        val request = server.takeRequest()
        assertEquals("/api/v2/home/users", request.url.encodedPath)
        assertEquals("owner-token", request.headers["X-Plex-Token"])
        assertNull("the token never goes in the URL", request.url.queryParameter("X-Plex-Token"))
    }

    @Test
    fun `a switch takes the v2 call by uuid, with a PIN only when given`() = runTest {
        respond("""{"id": 1002, "uuid": "u-maya", "authToken": "maya-plex-tv-token"}""")
        val maya = PlexHomeUser(1002, "u-maya", "Maya", admin = false, restricted = true, restrictionProfile = "older_kid", protected = false)

        val switched = home.switchTo("owner-token", maya)

        assertEquals(PlexHome.Switched("maya-plex-tv-token", "v2"), switched)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v2/home/users/u-maya/switch", request.url.encodedPath)
        assertNull(request.url.queryParameter("pin"))
    }

    @Test
    fun `when the v2 call fails, the older one by id gives the token, in XML`() = runTest {
        respond("", code = 404)
        respond("""<?xml version="1.0"?><user id="1003" title="Leo" authenticationToken="leo-plex-tv-token"/>""")
        val leo = PlexHomeUser(1003, "u-leo", "Leo", admin = false, restricted = true, restrictionProfile = "little_kid", protected = true)

        val switched = home.switchTo("owner-token", leo, pin = "1234")

        assertEquals(PlexHome.Switched("leo-plex-tv-token", "legacy"), switched)
        server.takeRequest()
        val legacy = server.takeRequest()
        assertEquals("/api/home/users/1003/switch", legacy.url.encodedPath)
        assertEquals("1234", legacy.url.queryParameter("pin"))
    }

    @Test
    fun `no token from either call is a failure`() = runTest {
        respond("", code = 404)
        respond("{}")
        val maya = PlexHomeUser(1002, "u-maya", "Maya", admin = false, restricted = true, restrictionProfile = null, protected = false)

        val failed = try {
            home.switchTo("owner-token", maya)
            null
        } catch (e: PlexException) {
            e
        }
        assertTrue(failed is PlexException.Failed)
    }

    @Test
    fun `a token is read from JSON or XML, and a blank one isn't one`() {
        assertEquals("a", PlexHome.tokenIn("""{"authToken": "a"}"""))
        assertEquals("b", PlexHome.tokenIn("""{"authenticationToken": "b"}"""))
        assertEquals("c", PlexHome.tokenIn("""<user authenticationToken="c"/>"""))
        assertNull(PlexHome.tokenIn("""{"authToken": ""}"""))
        assertNull(PlexHome.tokenIn("not a token"))
    }
}
