package com.psmobile.psd.io

import java.nio.charset.Charset

private val LATIN1: Charset = Charsets.ISO_8859_1
private val UTF16BE: Charset = Charsets.UTF_16BE

/** Big-endian cursor over an in-memory block. PSD stores everything big-endian. */
class ByteCursor(val data: ByteArray, var pos: Int = 0, val limit: Int = data.size) {

    val remaining: Int get() = limit - pos
    fun hasRemaining(n: Int = 1): Boolean = remaining >= n

    fun skip(n: Int): ByteCursor = apply { pos += n }
    fun seek(p: Int): ByteCursor = apply { pos = p }

    fun u8(): Int = data[pos++].toInt() and 0xFF
    fun i8(): Int = data[pos++].toInt()

    fun u16(): Int {
        val v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2
        return v
    }

    fun i16(): Int = u16().toShort().toInt()

    fun i32(): Int {
        val d = data
        val p = pos
        val v = ((d[p].toInt() and 0xFF) shl 24) or
            ((d[p + 1].toInt() and 0xFF) shl 16) or
            ((d[p + 2].toInt() and 0xFF) shl 8) or
            (d[p + 3].toInt() and 0xFF)
        pos = p + 4
        return v
    }

    fun u32(): Long = i32().toLong() and 0xFFFFFFFFL

    fun i64(): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
        pos += 8
        return v
    }

    /** 4-byte length in PSD, 8-byte in PSB. */
    fun length(psb: Boolean): Long = if (psb) i64() else u32()

    /** Fixed-point 16.16 used by resolution resources. */
    fun fixed16(): Double = i32() / 65536.0

    fun bytes(n: Int): ByteArray = ByteArray(n).also {
        System.arraycopy(data, pos, it, 0, n)
        pos += n
    }

    fun signature(): String = String(data, pos, 4, LATIN1).also { pos += 4 }

    /** Pascal string: 1 length byte + chars, padded so the total occupies a multiple of [padTo]. */
    fun pascalString(padTo: Int = 2): String {
        val len = u8()
        val s = String(data, pos, len, LATIN1)
        pos += len
        val total = len + 1
        val rem = total % padTo
        if (rem != 0) pos += padTo - rem
        return s
    }

    /** Unicode string: 4-byte character count + UTF-16BE characters. */
    fun unicodeString(): String {
        val chars = i32()
        if (chars <= 0) return ""
        val byteLen = chars * 2
        val s = String(data, pos, minOf(byteLen, remaining), UTF16BE)
        pos += byteLen
        return s.trimEnd('\u0000')
    }

    fun view(len: Int): ByteCursor = ByteCursor(data, pos, pos + len)

    fun align(n: Int, base: Int = 0) {
        val rem = (pos - base) % n
        if (rem != 0) pos += n - rem
    }
}
