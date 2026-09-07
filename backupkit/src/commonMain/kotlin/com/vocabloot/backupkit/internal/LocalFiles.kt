package com.vocabloot.backupkit.internal

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlin.random.Random

/** The only local file access the library does, through kotlinx-io so it is identical on both platforms. */
internal object LocalFiles {

    fun readAllBytes(path: String): ByteArray? {
        val p = Path(path)
        if (!SystemFileSystem.exists(p)) return null
        return SystemFileSystem.source(p).buffered().use { it.readByteArray() }
    }

    /** Atomic: temp sibling + rename, so a crash mid-write never leaves a truncated file. */
    fun writeAllBytes(path: String, bytes: ByteArray) {
        val target = Path(path)
        target.parent?.let { SystemFileSystem.createDirectories(it, mustCreate = false) }
        val temp = Path(path + ".tmp-" + Random.nextLong().toString(16))
        try {
            SystemFileSystem.sink(temp).buffered().use { it.write(bytes) }
            SystemFileSystem.atomicMove(temp, target)
        } finally {
            SystemFileSystem.delete(temp, mustExist = false)
        }
    }

    fun fileSize(path: String): Long? = SystemFileSystem.metadataOrNull(Path(path))?.size

    fun delete(path: String) {
        SystemFileSystem.delete(Path(path), mustExist = false)
    }

    fun ensureParentDir(path: String) {
        Path(path).parent?.let { SystemFileSystem.createDirectories(it, mustCreate = false) }
    }

    fun exists(path: String): Boolean = SystemFileSystem.exists(Path(path))

    /** File names directly inside [dir]; empty when the directory is missing. */
    fun listNames(dir: String): List<String> =
        runCatching { SystemFileSystem.list(Path(dir)).map { it.name } }.getOrDefault(emptyList())

    /** Replace [to] with [from]. Rename first; a cross-volume source (CloudKit's staging area) falls back to copy + delete. */
    fun move(from: String, to: String) {
        ensureParentDir(to)
        SystemFileSystem.delete(Path(to), mustExist = false)
        runCatching { SystemFileSystem.atomicMove(Path(from), Path(to)) }.onFailure {
            val bytes = readAllBytes(from) ?: throw it
            writeAllBytes(to, bytes)
            delete(from)
        }
    }

    fun copy(from: String, to: String) {
        val bytes = readAllBytes(from) ?: throw IllegalStateException("missing file: $from")
        writeAllBytes(to, bytes)
    }
}
