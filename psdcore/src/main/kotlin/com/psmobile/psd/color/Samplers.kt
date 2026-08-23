package com.psmobile.psd.color

import com.psmobile.psd.model.ColorMode

/**
 * Pulls one 0..255 sample out of a raw channel scanline, hiding the four bit depths
 * Photoshop can store.
 */
fun interface Sampler {
    fun sample(row: ByteArray, x: Int): Int

    companion object {
        fun forDepth(depth: Int): Sampler = when (depth) {
            1 -> Sampler { row, x ->
                // 1-bit documents are ink coverage: a set bit means black.
                val bit = (row[x ushr 3].toInt() shr (7 - (x and 7))) and 1
                if (bit == 1) 0 else 255
            }
            8 -> Sampler { row, x -> row[x].toInt() and 0xFF }
            16 -> Sampler { row, x -> row[x shl 1].toInt() and 0xFF }
            32 -> Sampler { row, x ->
                val i = x shl 2
                val bits = ((row[i].toInt() and 0xFF) shl 24) or
                    ((row[i + 1].toInt() and 0xFF) shl 16) or
                    ((row[i + 2].toInt() and 0xFF) shl 8) or
                    (row[i + 3].toInt() and 0xFF)
                linearToSrgb8(Float.fromBits(bits))
            }
            else -> Sampler { _, _ -> 0 }
        }
    }
}

/** 32-bit channels hold linear scene values; bring them into display space. */
fun linearToSrgb8(value: Float): Int {
    if (value.isNaN()) return 0
    val v = value.coerceIn(0f, 1f)
    val s = if (v <= 0.0031308f) v * 12.92f else 1.055f * Math.pow(v.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
    return (s * 255f + 0.5f).toInt().coerceIn(0, 255)
}

/**
 * Converts a set of channel samples into packed ARGB.
 *
 * Each colour mode gets its own implementation so the per-pixel path in the tile renderer
 * stays a single virtual call with no branching on the mode.
 */
fun interface PixelPacker {
    /** [c] holds one sample per channel, in file order. */
    fun pack(c: IntArray, alpha: Int): Int
}

object PixelPackers {

    fun of(mode: ColorMode, palette: ByteArray?): PixelPacker = when (mode) {
        ColorMode.RGB -> PixelPacker { c, a -> argb(a, c[0], c[1], c[2]) }

        ColorMode.GRAYSCALE, ColorMode.DUOTONE, ColorMode.MULTICHANNEL ->
            PixelPacker { c, a -> argb(a, c[0], c[0], c[0]) }

        ColorMode.BITMAP -> PixelPacker { c, a -> argb(a, c[0], c[0], c[0]) }

        // CMYK is stored inverted: 255 means "no ink".
        ColorMode.CMYK -> PixelPacker { c, a ->
            val k = 255 - c[3]
            val r = (255 - c[0]) * (255 - k) / 255
            val g = (255 - c[1]) * (255 - k) / 255
            val b = (255 - c[2]) * (255 - k) / 255
            argb(a, 255 - r, 255 - g, 255 - b)
        }

        ColorMode.LAB -> PixelPacker { c, a -> labToArgb(a, c[0], c[1], c[2]) }

        ColorMode.INDEXED -> {
            val lut = palette
            if (lut == null || lut.size < 768) {
                PixelPacker { c, a -> argb(a, c[0], c[0], c[0]) }
            } else {
                PixelPacker { c, a ->
                    val i = c[0]
                    argb(a, lut[i].toInt() and 0xFF, lut[256 + i].toInt() and 0xFF, lut[512 + i].toInt() and 0xFF)
                }
            }
        }
    }

    /** Number of channels that carry colour, before any alpha. */
    fun colorChannels(mode: ColorMode): Int = when (mode) {
        ColorMode.BITMAP, ColorMode.GRAYSCALE, ColorMode.INDEXED, ColorMode.DUOTONE -> 1
        ColorMode.RGB, ColorMode.LAB -> 3
        ColorMode.CMYK -> 4
        ColorMode.MULTICHANNEL -> 1
    }
}

private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
    (a shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

private fun labToArgb(alpha: Int, lRaw: Int, aRaw: Int, bRaw: Int): Int {
    val l = lRaw * 100.0 / 255.0
    val a = aRaw - 128.0
    val bb = bRaw - 128.0

    val fy = (l + 16.0) / 116.0
    val fx = fy + a / 500.0
    val fz = fy - bb / 200.0

    val x = 0.95047 * finv(fx)
    val y = 1.00000 * finv(fy)
    val z = 1.08883 * finv(fz)

    val r = 3.2406 * x - 1.5372 * y - 0.4986 * z
    val g = -0.9689 * x + 1.8758 * y + 0.0415 * z
    val bl = 0.0557 * x - 0.2040 * y + 1.0570 * z

    return argb(alpha, gamma(r), gamma(g), gamma(bl))
}

private fun finv(t: Double): Double = if (t > 6.0 / 29.0) t * t * t else 3.0 * (6.0 / 29.0) * (6.0 / 29.0) * (t - 4.0 / 29.0)

private fun gamma(v: Double): Int {
    val c = v.coerceIn(0.0, 1.0)
    val s = if (c <= 0.0031308) 12.92 * c else 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055
    return (s * 255.0 + 0.5).toInt().coerceIn(0, 255)
}
