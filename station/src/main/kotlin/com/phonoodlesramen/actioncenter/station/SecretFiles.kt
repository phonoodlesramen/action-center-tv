package com.phonoodlesramen.actioncenter.station

import java.nio.ByteBuffer
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * The station's own files in its volume. Each is written whole (a temporary file, then an
 * atomic rename), so a crash never leaves half a file, and with mode 600 (700 for its
 * folder) where the file system has POSIX modes: only the container's user reads them.
 */
internal object SecretFiles {
    private val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Whether files here get mode 600 (on the NAS; not on Windows, where the tests also run). */
    val hasModes: Boolean get() = posix

    fun ensureDir(dir: Path, ownerOnly: Boolean = true) {
        if (!Files.isDirectory(dir)) {
            if (posix && ownerOnly) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } else {
                Files.createDirectories(dir)
            }
        }
    }

    fun write(path: Path, bytes: ByteArray) {
        ensureDir(path.parent)
        val tmp = path.resolveSibling(".${path.fileName}.tmp")
        Files.deleteIfExists(tmp)
        val attrs: Array<FileAttribute<*>> = if (posix) arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()
        Files.newByteChannel(tmp, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), *attrs).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun writeText(path: Path, text: String) = write(path, text.toByteArray(Charsets.UTF_8))

    fun delete(path: Path) {
        Files.deleteIfExists(path)
    }

    /** Null when the file isn't there. */
    fun read(path: Path): ByteArray? = try {
        Files.readAllBytes(path)
    } catch (e: NoSuchFileException) {
        null
    }

    fun readText(path: Path): String? = read(path)?.toString(Charsets.UTF_8)

    fun <T> writeJson(path: Path, serializer: KSerializer<T>, value: T) = writeText(path, json.encodeToString(serializer, value))

    /** Null when the file isn't there; a file that won't read throws, so a broken file is never taken for an empty one. */
    fun <T> readJson(path: Path, serializer: KSerializer<T>): T? = readText(path)?.let { json.decodeFromString(serializer, it) }
}
