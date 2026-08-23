package com.psmobile.psd.codec

/**
 * Apple PackBits run-length decoder — the compression Photoshop uses for scanline data.
 *
 * The format is byte oriented: a signed control byte `n` means "the next `n + 1` bytes are
 * literal" when non-negative, or "repeat the next byte `1 - n` times" when negative.
 * `-128` is a no-op.
 */
object PackBits {

    /**
     * Decodes [srcLen] bytes starting at [srcOff] into [dst], stopping once [dstLen] bytes are
     * produced. Returns the number of bytes written, which is [dstLen] for well-formed input.
     *
     * Truncated or corrupt runs stop decoding rather than throwing: a partially readable
     * scanline is far better than losing the whole document.
     */
    fun decode(
        src: ByteArray,
        srcOff: Int,
        srcLen: Int,
        dst: ByteArray,
        dstOff: Int,
        dstLen: Int,
    ): Int {
        var s = srcOff
        val sEnd = srcOff + srcLen
        var d = dstOff
        val dEnd = dstOff + dstLen

        while (s < sEnd && d < dEnd) {
            val n = src[s++].toInt()
            when {
                n >= 0 -> {
                    val count = minOf(n + 1, sEnd - s, dEnd - d)
                    if (count <= 0) break
                    System.arraycopy(src, s, dst, d, count)
                    s += count
                    d += count
                }
                n != -128 -> {
                    if (s >= sEnd) break
                    val value = src[s++]
                    val count = minOf(1 - n, dEnd - d)
                    java.util.Arrays.fill(dst, d, d + count, value)
                    d += count
                }
            }
        }
        // Corrupt tail: leave the rest of the scanline transparent/black instead of failing.
        if (d < dEnd) java.util.Arrays.fill(dst, d, dEnd, 0)
        return d - dstOff
    }
}
