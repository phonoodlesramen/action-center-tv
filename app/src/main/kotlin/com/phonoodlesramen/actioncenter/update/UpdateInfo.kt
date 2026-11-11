package com.phonoodlesramen.actioncenter.update

import com.phonoodlesramen.actioncenter.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Where this build looks for its updates, set when it was built (`app/build.gradle.kts`): a
 * repository's GitHub releases. Debug builds, and release builds made without a repository,
 * never look.
 */
data class UpdateConfig(
    /** `owner/name`, or empty for none. */
    val repo: String,
    val baseUrl: String = GITHUB,
    /** A debug build: About says that's why it doesn't update. */
    val debugBuild: Boolean = false,
) {
    val enabled: Boolean get() = repo.isNotEmpty() && !debugBuild

    /** The latest full release's `update.json`: GitHub skips pre-releases and drafts for `latest`. */
    val latestUrl: String get() = "$baseUrl/$repo/releases/latest/download/$FILE"

    /** [info]'s APK, from its own release, so a newer release meanwhile can't swap it. */
    fun apkUrl(info: UpdateInfo): String = "$baseUrl/$repo/releases/download/${info.tag}/${info.apk}"

    companion object {
        const val GITHUB = "https://github.com"
        const val FILE = "update.json"

        fun fromBuild() = UpdateConfig(BuildConfig.UPDATE_REPO, BuildConfig.UPDATE_BASE_URL, debugBuild = BuildConfig.DEBUG)
    }
}

/**
 * A release's `update.json` (`docs/releasing.md`): what CI read from the signed APK. The app
 * compares [versionCode]s, since a name between tags (1.2.0-3-gabc1234) doesn't sort, and a
 * download has to match [size], [sha256] and [signerSha256].
 */
@Serializable
data class UpdateInfo(
    val format: Int,
    val versionCode: Long,
    val versionName: String,
    val tag: String,
    val apk: String,
    val size: Long,
    val sha256: String,
    val signerSha256: String,
    val minSdk: Int = 1,
    val notesUrl: String = "",
) {
    /** Megabytes, rounded, for About: 53,572,870 bytes is "51 MB". */
    val megabytes: Long get() = (size + MEGABYTE / 2) / MEGABYTE

    /** Every value is one CI could have written: a name can't leave its place in a URL. */
    internal fun valid(): Boolean =
        format in 1..FORMAT && versionCode > 0 && size > 0 &&
            HEX.matches(sha256) && HEX.matches(signerSha256) &&
            NAME.matches(tag) && NAME.matches(apk) && NAME.matches(versionName) &&
            !tag.startsWith(".") && !apk.startsWith(".") &&
            (notesUrl.isEmpty() || notesUrl.startsWith("https://"))

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        /** The newest `format` this app reads. A newer one is for a newer app. */
        const val FORMAT = 1
        private const val MEGABYTE = 1024L * 1024
        private val HEX = Regex("[0-9a-f]{64}")
        private val NAME = Regex("[A-Za-z0-9._+-]{1,100}")
        internal val json = Json { ignoreUnknownKeys = true }

        /** What a release's `update.json` says, or why it can't be used. */
        fun parse(text: String): ReleaseFile {
            val format = runCatching { json.parseToJsonElement(text).jsonObject["format"]?.jsonPrimitive?.int }.getOrNull()
                ?: return ReleaseFile.Unreadable
            if (format > FORMAT) return ReleaseFile.TooNew
            val info = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
            return if (info != null && info.valid()) ReleaseFile.Found(info) else ReleaseFile.Unreadable
        }

        /** [text] as [encode] wrote it, or null. */
        fun decode(text: String?): UpdateInfo? =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }?.takeIf { it.valid() }
    }
}

/** What a check found: the latest release's `update.json`, or why there's nothing to use. */
sealed interface ReleaseFile {
    data class Found(val info: UpdateInfo) : ReleaseFile

    /** The latest release has no `update.json`: one made before it existed (v1.0.0), or no release at all. */
    data object None : ReleaseFile

    /** A `format` newer than this app reads. */
    data object TooNew : ReleaseFile

    /** Not an `update.json` this app can trust. */
    data object Unreadable : ReleaseFile

    /** No answer: [why] is for About's line. */
    data class Failed(val why: String) : ReleaseFile
}
