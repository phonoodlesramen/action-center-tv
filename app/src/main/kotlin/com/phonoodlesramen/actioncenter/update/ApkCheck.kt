package com.phonoodlesramen.actioncenter.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import java.io.File
import java.security.MessageDigest

/** An APK's package, version and signing certificates (each as its SHA-256, in hex). */
data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    val signers: Set<String>,
    val versionName: String = "",
)

/** What Android reads from a downloaded APK, and from the app that's installed. */
interface ApkInspector {
    /** [file]'s facts, or null when Android can't read it as an APK. */
    fun archive(file: File): ApkFacts?

    /** The installed Action Center's own. */
    fun installed(): ApkFacts
}

/**
 * [ApkInspector] with Android's PackageManager. It asks for both kinds of signer: a file's
 * [android.content.pm.SigningInfo] may come back empty on Android 11, and the older
 * [PackageInfo.signatures] then has the certificate.
 */
class AndroidApkInspector(private val context: Context) : ApkInspector {
    private val packageManager get() = context.packageManager

    override fun archive(file: File): ApkFacts? =
        packageManager.getPackageArchiveInfo(file.path, FLAGS)?.let(::facts)

    override fun installed(): ApkFacts = facts(packageManager.getPackageInfo(context.packageName, FLAGS))

    private fun facts(info: PackageInfo): ApkFacts {
        val signing = info.signingInfo
        val certificates: Array<Signature> = when {
            signing == null -> null
            signing.hasMultipleSigners() -> signing.apkContentsSigners
            else -> signing.signingCertificateHistory
        }?.takeIf { it.isNotEmpty() } ?: legacySignatures(info)
        return ApkFacts(info.packageName, info.longVersionCode, certificates.map { it.sha256() }.toSet(), info.versionName.orEmpty())
    }

    @Suppress("DEPRECATION")
    private fun legacySignatures(info: PackageInfo): Array<Signature> = info.signatures ?: emptyArray()

    private fun Signature.sha256(): String = MessageDigest.getInstance("SHA-256").digest(toByteArray()).toHex()

    private companion object {
        @Suppress("DEPRECATION")
        const val FLAGS = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
    }
}

/** Whether a download can replace the installed app. Size and SHA-256 are the download's own check. */
object UpdateVerification {
    /** Why [download] can't install over [installed] as [info]'s release, or null when it can. */
    fun problem(info: UpdateInfo, download: ApkFacts?, installed: ApkFacts): String? = when {
        download == null -> "Android couldn't read the download"
        download.packageName != installed.packageName -> "The download is another app"
        download.versionCode != info.versionCode -> "The download isn't the version the release names"
        download.versionCode <= installed.versionCode -> "The download isn't newer than this copy"
        download.signers.none { it in installed.signers } -> "The download isn't signed with this copy's key"
        info.signerSha256 !in download.signers -> "The download isn't signed the way the release says"
        else -> null
    }
}
