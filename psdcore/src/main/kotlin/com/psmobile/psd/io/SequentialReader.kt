package com.psmobile.psd.io

import java.io.EOFException

/**
 * Buffered forward reader over a [SeekableSource] that keeps track of its absolute file offset.
 *
 * The layer-record block of a PSB can be several megabytes of tightly packed variable-length
 * structures, so it is walked sequentially while the real pixel payload — hundreds of megabytes
 * further into the file — is only ever referenced by offset.
 */
class SequentialReader(
    private val src: SeekableSource,
    startPosition: Long = 0L,
    bufferSize: Int = 1 shl 16,
) {
    private val buf = ByteArray(bufferSize)
    private var bufOrigin = startPosition
    private var bufLen = 0
    private var bufPos = 0

    /** Absolute offset of the next byte to be read. */
    var position: Long
        get() = bufOrigin + bufPos
        set(value) {
            if (value in bufOrigin..(bufOrigin + bufLen)) {
                bufPos = (value - bufOrigin).toInt()
            } else {
                bufOrigin = value
                bufLen = 0
                bufPos = 0
            }
        }

    private fun ensure(n: Int) {
        if (bufLen - bufPos >= n) return
        require(n <= buf.size) { "Requested $n bytes, buffer holds ${buf.size}" }
        val keep = bufLen - bufPos
        if (keep > 0) System.arraycopy(buf, bufPos, buf, 0, keep)
        bufOrigin += bufPos
        bufPos = 0
        bufLen = keep
        while (bufLen < n) {
            val read = src.read(bufOrigin + bufLen, buf, bufLen, buf.size - bufLen)
            if (read <= 0) throw EOFException("End of file at ${bufOrigin + bufLen}")
            bufLen += read
        }
    }

    fun skip(n: Long) {
        position += n
    }

    fun u8(): Int {
        ensure(1)
        return buf[bufPos++].toInt() and 0xFF
    }

    fun u16(): Int {
        ensure(2)
        val v = ((buf[bufPos].toInt() and 0xFF) shl 8) or (buf[bufPos + 1].toInt() and 0xFF)
        bufPos += 2
        return v
    }

    fun i16(): Int = u16().toShort().toInt()

    fun i32(): Int {
        ensure(4)
        val p = bufPos
        val v = ((buf[p].toInt() and 0xFF) shl 24) or
            ((buf[p + 1].toInt() and 0xFF) shl 16) or
            ((buf[p + 2].toInt() and 0xFF) shl 8) or
            (buf[p + 3].toInt() and 0xFF)
        bufPos = p + 4
        return v
    }

    fun u32(): Long = i32().toLong() and 0xFFFFFFFFL

    fun i64(): Long {
        ensure(8)
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[bufPos + i].toLong() and 0xFF)
        bufPos += 8
        return v
    }

    fun length(psb: Boolean): Long = if (psb) i64() else u32()

    fun bytes(n: Int): ByteArray {
        val out = ByteArray(n)
        var done = 0
        while (done < n) {
            val chunk = minOf(n - done, buf.size)
            ensure(minOf(chunk, 1))
            val avail = minOf(bufLen - bufPos, n - done)
            System.arraycopy(buf, bufPos, out, done, avail)
            bufPos += avail
            done += avail
        }
        return out
    }

    fun signature(): String = String(bytes(4), Charsets.ISO_8859_1)
}
