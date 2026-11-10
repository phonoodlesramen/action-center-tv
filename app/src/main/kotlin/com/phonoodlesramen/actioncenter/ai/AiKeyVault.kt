package com.phonoodlesramen.actioncenter.ai

import com.phonoodlesramen.actioncenter.plex.SecretBox
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * The Claude API key (M10), typed on your phone and kept on the Shield only: sealed with
 * an AES-GCM key that never leaves the Android Keystore ([SecretBox], alias [ALIAS]), and
 * written to [file] in `noBackupFilesDir`, so no backup carries it.
 *
 * - **Never shown:** only its last 4 characters ([ending]) ever leave this class for the
 *   screen. It's never logged, and nothing here puts it in an exception's message.
 * - **Unreadable** (the Keystore key gone, a damaged file) is the same as no key: add it
 *   again on your phone.
 * - **Forget key** deletes the file.
 */
class AiKeyVault(
    private val file: File,
    private val box: SecretBox,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Bumped on every save and forget, so [ending] reads the file again. */
    private val changes = MutableStateFlow(0)

    /** The stored key's last 4 characters, or null without a readable key. */
    val ending: Flow<String?> = changes
        .map { key()?.let(AiKeys::ending) }
        .distinctUntilChanged()
        .flowOn(io)

    /** The key, for the nightly request only; null when none is stored or it can't be read. */
    suspend fun key(): String? = withContext(io) {
        try {
            if (file.isFile) box.open(file.readText()) else null
        } catch (_: IOException) {
            null
        }
    }

    /** Replaces the stored key: written to a new file, then moved over the old one. */
    suspend fun save(key: String) {
        withContext(io) {
            file.parentFile?.mkdirs()
            val next = File(file.parentFile, file.name + ".new")
            next.writeText(box.seal(key))
            if (!next.renameTo(file)) {
                next.delete()
                throw IOException("Couldn't store the key")
            }
        }
        changes.update { it + 1 }
    }

    suspend fun forget() {
        withContext(io) { file.delete() }
        changes.update { it + 1 }
    }

    companion object {
        /** The Keystore alias of the AES key that seals it. */
        const val ALIAS = "ai-key"

        /** The file in `noBackupFilesDir`. */
        const val FILE = "ai-key"
    }
}

/** What a Claude API key looks like, and the most of it the TV ever shows (M10). */
object AiKeys {
    /** Claude API keys start with this ("sk-ant-api03-…"). */
    const val PREFIX = "sk-ant-"
    private const val MIN_LENGTH = 24
    private const val MAX_LENGTH = 512

    /** "abcd": the last 4 characters, never more. */
    fun ending(key: String): String = key.takeLast(4)

    /**
     * Why [key] (already trimmed) can't be a Claude API key, as a sentence for the phone;
     * null when it can. The sentence never repeats the key.
     */
    fun problem(key: String): String? = when {
        key.isEmpty() -> "Paste the key first."
        // A header can only carry visible ASCII: a space or a line break means it was copied with something else.
        key.any { it.code !in 0x21..0x7E } -> "That has spaces or other characters a key never has. Copy just the key and try again."
        !key.startsWith(PREFIX) -> "That doesn't look like a Claude API key. They start with $PREFIX."
        key.length !in MIN_LENGTH..MAX_LENGTH -> "That key is too short or too long. Copy the whole key and try again."
        else -> null
    }
}
