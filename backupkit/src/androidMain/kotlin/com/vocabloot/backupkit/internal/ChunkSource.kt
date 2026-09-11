package com.vocabloot.backupkit.internal

import java.io.RandomAccessFile

/** Bytes for a resumable upload, read one window at a time so a large file never sits in memory. */
internal interface ChunkSource {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

internal class BytesChunkSource(private val bytes: ByteArray) : ChunkSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray = bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
}

internal class FileChunkSource(private val path: String, override val size: Long) : ChunkSource {
    override fun read(offset: Long, length: Int): ByteArray = RandomAccessFile(path, "r").use { file ->
        file.seek(offset)
        val out = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val n = file.read(out, filled, length - filled)
            if (n < 0) error("unexpected end of $path at ${offset + filled}")
            filled += n
        }
        out
    }
}
