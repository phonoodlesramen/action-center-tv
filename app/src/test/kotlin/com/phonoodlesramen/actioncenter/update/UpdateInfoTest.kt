package com.phonoodlesramen.actioncenter.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A release's `update.json` (docs/releasing.md), and where a build looks for it. */
class UpdateInfoTest {
    private val sha = "674becd0457fa0161ec6aa94718c87a05952b3f966ff37dea174b289ac22aeb8"
    private val signer = "3c7fc77c5e9240aed6f6e9b2172c5989ecc7b7cf36a3c3780707455f5a03577e"

    /** v1.0.0's, as CI's release job writes it. */
    private fun file(
        format: Int = 1,
        tag: String = "v1.0.0",
        apk: String = "app-release.apk",
        sha256: String = sha,
        notes: String = "https://github.com/someone/action-center-tv/releases/tag/v1.0.0",
        extra: String = "",
    ) = """
        {
          "format": $format,
          "versionCode": 451560,
          "versionName": "1.0.0",
          "tag": "$tag",
          "apk": "$apk",
          "size": 53572870,
          "sha256": "$sha256",
          "signerSha256": "$signer",
          "minSdk": 30,
          "notesUrl": "$notes"$extra
        }
    """.trimIndent()

    @Test
    fun `CI's file reads, with its sizes in megabytes`() {
        val info = (UpdateInfo.parse(file()) as ReleaseFile.Found).info
        assertEquals(451560L, info.versionCode)
        assertEquals("1.0.0", info.versionName)
        assertEquals(sha, info.sha256)
        assertEquals(30, info.minSdk)
        assertEquals(51L, info.megabytes)
    }

    @Test
    fun `a field it doesn't know is ignored, but a newer format is for a newer app`() {
        assertTrue(UpdateInfo.parse(file(extra = ",\n\"channel\": \"stable\"")) is ReleaseFile.Found)
        assertEquals(ReleaseFile.TooNew, UpdateInfo.parse(file(format = 2)))
    }

    @Test
    fun `anything CI couldn't have written is refused`() {
        listOf(
            file(tag = "../../evil"),
            file(apk = "x/app.apk"),
            file(apk = ".hidden"),
            file(sha256 = "not-a-hash"),
            file(sha256 = sha.uppercase()),
            file(notes = "http://example.com/plain"),
            "{}",
            "not json",
            "[1, 2]",
        ).forEach { assertEquals(it, ReleaseFile.Unreadable, UpdateInfo.parse(it)) }
    }

    @Test
    fun `what it encodes, it decodes, anything else decodes to nothing`() {
        val info = (UpdateInfo.parse(file()) as ReleaseFile.Found).info
        assertEquals(info, UpdateInfo.decode(info.encode()))
        assertNull(UpdateInfo.decode(null))
        assertNull(UpdateInfo.decode("garbage"))
    }

    @Test
    fun `a build looks at its repository's latest release, and each APK at its own release`() {
        val config = UpdateConfig("someone/action-center-tv")
        val info = (UpdateInfo.parse(file()) as ReleaseFile.Found).info
        assertEquals("https://github.com/someone/action-center-tv/releases/latest/download/update.json", config.latestUrl)
        assertEquals("https://github.com/someone/action-center-tv/releases/download/v1.0.0/app-release.apk", config.apkUrl(info))
        assertTrue(config.enabled)
    }

    @Test
    fun `a debug build, or one with no repository, never looks`() {
        assertFalse(UpdateConfig("").enabled)
        assertFalse(UpdateConfig("someone/action-center-tv", debugBuild = true).enabled)
        // These tests run the debug build.
        assertFalse(UpdateConfig.fromBuild().enabled)
    }
}
