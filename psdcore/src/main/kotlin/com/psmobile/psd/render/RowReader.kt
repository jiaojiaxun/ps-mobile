package com.psmobile.psd.render

import com.psmobile.psd.codec.PackBits
import com.psmobile.psd.io.SeekableSource
import com.psmobile.psd.model.ChannelPlane
import com.psmobile.psd.model.Compression

/**
 * Decodes individual scanlines of one channel, with a sliding read-ahead window.
 *
 * Tiles are decoded row by row, and at full zoom those rows are contiguous in the file. Rather
 * than issuing one positioned read per scanline, the reader pulls a window of consecutive rows
 * in a single read and decodes out of it. When rows are far apart — a zoomed-out level that
 * samples every 64th line — the window naturally collapses to a single row and nothing is wasted.
 */
class RowReader(
    private val source: SeekableSource,
    private val plane: ChannelPlane,
    windowBytes: Int = DEFAULT_WINDOW,
) {
    private val rowBytes = plane.rowBytes
    private val maxWindow = windowBytes.coerceAtLeast(rowBytes + 8)

    private var window = ByteArray(0)
    private var windowStart = -1L
    private var windowEnd = -1L

    /** Decodes source row [y] into [dst]. */
    fun readRow(y: Int, dst: ByteArray) {
        val row = y.coerceIn(0, plane.height - 1)
        when (plane.compression) {
            Compression.RAW -> {
                val offset = plane.dataOffset + row.toLong() * rowBytes
                val slice = fetch(offset, rowBytes)
                System.arraycopy(window, slice, dst, 0, rowBytes)
            }
            Compression.RLE -> {
                val offset = plane.rowOffset(row)
                val length = plane.rowLength(row)
                if (length <= 0) {
                    java.util.Arrays.fill(dst, 0, rowBytes, 0)
                    return
                }
                val slice = fetch(offset, length)
                PackBits.decode(window, slice, length, dst, 0, rowBytes)
            }
            else -> java.util.Arrays.fill(dst, 0, rowBytes, 0)
        }
    }

    /**
     * Makes `[offset, offset + length)` available inside [window] and returns its index there.
     * Extends the window forward when possible so that the following rows are already resident.
     */
    private fun fetch(offset: Long, length: Int): Int {
        if (windowStart >= 0 && offset >= windowStart && offset + length <= windowEnd) {
            return (offset - windowStart).toInt()
        }
        val available = source.size - offset
        val want = minOf(maxOf(length.toLong(), maxWindow.toLong()), available).toInt()
        if (window.size < want) window = ByteArray(want)
        var got = 0
        while (got < want) {
            val n = source.read(offset + got, window, got, want - got)
            if (n <= 0) break
            got += n
        }
        if (got < length) {
            java.util.Arrays.fill(window, got, minOf(window.size, length), 0)
            got = length
        }
        windowStart = offset
        windowEnd = offset + got
        return 0
    }

    companion object {
        private const val DEFAULT_WINDOW = 1 shl 20
    }
}
