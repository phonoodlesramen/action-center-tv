package com.phonoodlesramen.actioncenter.update

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [GitHubReleases] against a local server laid out like GitHub's release downloads. */
class GitHubReleasesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var feed: GitHubReleases
    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private val dest by lazy { File(tmp.root, "updates/200.apk") }

    @Before
    fun setUp() {
        server.start()
        val base = server.url("/").toString().removeSuffix("/")
        feed = GitHubReleases(OkHttpClient(), UpdateConfig("someone/action-center-tv", baseUrl = base))
    }

    @After
    fun tearDown() = server.close()

    private fun info(size: Long = apk.size.toLong(), sha: String = sha256(apk)) = UpdateInfo(
        format = 1,
        versionCode = 200,
        versionName = "1.1.0",
        tag = "v1.1.0",
        apk = "app-release.apk",
        size = size,
        sha256 = sha,
        signerSha256 = "a".repeat(64),
    )

    private fun respond(code: Int, body: String = "") = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun respond(bytes: ByteArray) = server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(bytes)).build())

    @Test
    fun `the latest release's file, through GitHub's redirect to it`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(302).addHeader("Location", "/someone/action-center-tv/releases/download/v1.1.0/update.json").build(),
        )
        respond(200, info().encode())
        assertEquals(ReleaseFile.Found(info()), feed.latest())
        assertEquals("/someone/action-center-tv/releases/latest/download/update.json", server.takeRequest().url.encodedPath)
        assertEquals("/someone/action-center-tv/releases/download/v1.1.0/update.json", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `no update json is no update information, an error or no server says why`() = runTest {
        respond(404)
        assertEquals(ReleaseFile.None, feed.latest())
        respond(503)
        assertEquals(ReleaseFile.Failed("GitHub answered 503"), feed.latest())
        server.close()
        val failed = feed.latest()
        assertTrue(failed.toString(), failed is ReleaseFile.Failed && failed.why.startsWith("Couldn't reach GitHub"))
    }

    @Test
    fun `a download that matches is kept, from its own release, with its progress`() = runTest {
        respond(apk)
        val seen = mutableListOf<Int>()
        assertNull(feed.download(info(), dest) { seen += it })
        assertTrue(dest.readBytes().contentEquals(apk))
        assertEquals("/someone/action-center-tv/releases/download/v1.1.0/app-release.apk", server.takeRequest().url.encodedPath)
        assertEquals(100, seen.last())
        assertEquals(seen, seen.sorted())
        assertFalse(File(dest.parentFile, "200.apk.part").exists())
    }

    @Test
    fun `a download that doesn't match the release is deleted`() = runTest {
        respond(apk)
        assertEquals("The download didn't match the release's SHA-256", feed.download(info(sha = "0".repeat(64)), dest) {})
        assertFalse(dest.exists())

        respond(apk)
        assertEquals("The download stopped short", feed.download(info(size = apk.size + 10L), dest) {})
        assertFalse(dest.exists())

        respond(apk)
        assertEquals("The download is bigger than the release says", feed.download(info(size = apk.size - 10L), dest) {})
        assertFalse(dest.exists())

        respond(404)
        assertEquals("GitHub answered 404", feed.download(info(), dest) {})
        assertFalse(dest.exists())
        assertEquals(listOf<String>(), dest.parentFile!!.list()!!.toList())
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}
