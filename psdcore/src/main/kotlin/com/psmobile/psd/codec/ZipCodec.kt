package com.psmobile.psd.codec

import java.util.zip.Inflater

/**
 * ZIP (zlib) channel decompression, with and without the horizontal delta predictor that
 * Photoshop applies to 16- and 32-bit channels.
 */
object ZipCodec {

    fun inflate(src: ByteArray, srcOff: Int, srcLen: Int, dst: ByteArray, dstOff: Int, dstLen: Int): Int {
        val inflater = Inflater()
        try {
            inflater.setInput(src, srcOff, srcLen)
            var written = 0
            while (written < dstLen && !inflater.finished()) {
                val n = inflater.inflate(dst, dstOff + written, dstLen - written)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                written += n
            }
            return written
        } finally {
            inflater.end()
        }
    }

    /**
     * Undoes the per-scanline horizontal predictor. For 8/16-bit data each sample is stored as a
     * delta from its left neighbour; 32-bit data is additionally byte-planar within a row.
     */
    fun undoPrediction(data: ByteArray, width: Int, height: Int, depth: Int) {
        when (depth) {
            8 -> {
                val stride = width
                for (y in 0 until height) {
                    val base = y * stride
                    for (x in 1 until width) {
                        data[base + x] = (data[base + x] + data[base + x - 1]).toByte()
                    }
                }
            }
            16 -> {
                val stride = width * 2
                for (y in 0 until height) {
                    val base = y * stride
                    var prev = ((data[base].toInt() and 0xFF) shl 8) or (data[base + 1].toInt() and 0xFF)
                    for (x in 1 until width) {
                        val i = base + x * 2
                        val cur = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                        val v = (cur + prev) and 0xFFFF
                        data[i] = (v ushr 8).toByte()
                        data[i + 1] = v.toByte()
                        prev = v
                    }
                }
            }
            32 -> {
                // 32-bit rows are stored byte-plane by byte-plane; accumulate deltas then interleave.
                val stride = width * 4
                val row = ByteArray(stride)
                for (y in 0 until height) {
                    val base = y * stride
                    for (i in 1 until stride) {
                        data[base + i] = (data[base + i] + data[base + i - 1]).toByte()
                    }
                    for (x in 0 until width) {
                        row[x * 4] = data[base + x]
                        row[x * 4 + 1] = data[base + width + x]
                        row[x * 4 + 2] = data[base + width * 2 + x]
                        row[x * 4 + 3] = data[base + width * 3 + x]
                    }
                    System.arraycopy(row, 0, data, base, stride)
                }
            }
        }
    }
}
