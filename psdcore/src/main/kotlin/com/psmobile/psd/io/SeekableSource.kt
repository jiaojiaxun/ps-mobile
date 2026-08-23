package com.psmobile.psd.io

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Random-access, thread-safe view over the bytes of a document.
 *
 * PSD/PSB files routinely reach hundreds of megabytes, so nothing is ever read wholesale.
 * Every consumer asks for the exact byte range it needs, which is what makes on-demand
 * tile decoding possible.
 */
interface SeekableSource : Closeable {

    val size: Long

    /** Reads up to [len] bytes at absolute [position]. Returns the number of bytes read, or -1 at EOF. */
    fun read(position: Long, dst: ByteArray, offset: Int, len: Int): Int

    /** Reads exactly [len] bytes at absolute [position] or throws [EOFException]. */
    fun readFully(position: Long, dst: ByteArray, offset: Int = 0, len: Int = dst.size - offset) {
        var done = 0
        while (done < len) {
            val n = read(position + done, dst, offset + done, len - done)
            if (n <= 0) throw EOFException(
                "Unexpected end of file at ${position + done} (wanted $len bytes from $position, size=$size)"
            )
            done += n
        }
    }

    fun readFully(position: Long, len: Int): ByteArray =
        ByteArray(len).also { readFully(position, it, 0, len) }
}

/** [SeekableSource] backed by a [FileChannel]; positional reads keep it usable from many threads. */
class ChannelSource private constructor(
    private val channel: FileChannel,
    private val extra: Closeable?,
    override val size: Long,
) : SeekableSource {

    override fun read(position: Long, dst: ByteArray, offset: Int, len: Int): Int {
        if (position >= size) return -1
        val capped = minOf(len.toLong(), size - position).toInt()
        if (capped <= 0) return -1
        return channel.read(ByteBuffer.wrap(dst, offset, capped), position)
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { extra?.close() }
    }

    companion object {
        fun of(file: File): ChannelSource {
            val raf = RandomAccessFile(file, "r")
            return ChannelSource(raf.channel, raf, raf.length())
        }

        /**
         * Wraps a file descriptor handed over by the platform (a `content://` document, for example).
         * [declaredSize] is used when the descriptor cannot report its own length.
         */
        fun of(fd: FileDescriptor, declaredSize: Long = -1L, owned: Closeable? = null): ChannelSource {
            val stream = FileInputStream(fd)
            val channel = stream.channel
            val len = runCatching { channel.size() }.getOrDefault(-1L).let { if (it > 0) it else declaredSize }
            require(len > 0) { "Cannot determine the size of the descriptor" }
            val closer = Closeable {
                runCatching { stream.close() }
                runCatching { owned?.close() }
            }
            return ChannelSource(channel, closer, len)
        }
    }
}

/** In-memory source, used for small embedded payloads and for tests. */
class ByteArraySource(private val data: ByteArray) : SeekableSource {
    override val size: Long get() = data.size.toLong()

    override fun read(position: Long, dst: ByteArray, offset: Int, len: Int): Int {
        if (position >= data.size) return -1
        val n = minOf(len, data.size - position.toInt())
        System.arraycopy(data, position.toInt(), dst, offset, n)
        return n
    }

    override fun close() = Unit
}
