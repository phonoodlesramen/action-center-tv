package com.phonoodlesramen.actioncenter.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [UpdateVerification]: a download replaces the app only as Action Center, newer, and signed with its key. */
class UpdateVerificationTest {
    private val key = "a".repeat(64)
    private val other = "b".repeat(64)
    private val installed = ApkFacts(PACKAGE, 100, setOf(key), "1.0.0")
    private val info = UpdateInfo(1, 200, "1.1.0", "v1.1.0", "app-release.apk", 10, "c".repeat(64), key)
    private val good = ApkFacts(PACKAGE, 200, setOf(key), "1.1.0")

    @Test
    fun `Action Center, the release's version, newer, and this copy's key installs`() {
        assertNull(UpdateVerification.problem(info, good, installed))
    }

    @Test
    fun `anything else doesn't, and says why`() {
        val cases = mapOf(
            null to "Android couldn't read the download",
            good.copy(packageName = "com.example.other") to "The download is another app",
            good.copy(versionCode = 300) to "The download isn't the version the release names",
            good.copy(signers = setOf(other)) to "The download isn't signed with this copy's key",
        )
        cases.forEach { (download, why) -> assertEquals(why, UpdateVerification.problem(info, download, installed)) }
        assertEquals(
            "The download isn't newer than this copy",
            UpdateVerification.problem(info.copy(versionCode = 100), good.copy(versionCode = 100), installed),
        )
        assertEquals(
            "The download isn't signed the way the release says",
            UpdateVerification.problem(info.copy(signerSha256 = other), good, installed),
        )
    }

    private companion object {
        const val PACKAGE = "com.phonoodlesramen.actioncenter"
    }
}
