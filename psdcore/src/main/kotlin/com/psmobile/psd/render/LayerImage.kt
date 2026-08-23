package com.psmobile.psd.render

import com.psmobile.psd.PsdDocument
import com.psmobile.psd.color.PixelPacker
import com.psmobile.psd.color.PixelPackers
import com.psmobile.psd.color.Sampler
import com.psmobile.psd.model.BlendMode
import com.psmobile.psd.model.ChannelPlane
import com.psmobile.psd.model.ColorMode
import com.psmobile.psd.model.Layer

/**
 * One layer, ready to draw, with the per-thread state that decoding needs.
 *
 * Built once per layer per worker and reused for every tile afterwards. That matters more than
 * it sounds: the scanline buffers and the file read-ahead windows are the expensive part, and
 * creating them per tile meant a single overview of a 150-layer document churned through tens of
 * megabytes of garbage before drawing anything.
 *
 * Not thread-safe — the readers hold a sliding window each. One instance per decoder.
 */
class LayerImage internal constructor(
    document: PsdDocument,
    private val layer: Layer,
    private val colour: List<ResolvedChannel>,
    private val alpha: ResolvedChannel?,
    private val mask: ResolvedChannel?,
    private val maxSupersample: Int = DEFAULT_SUPERSAMPLE,
) {
    private val colourCount = colour.size
    private val sampler: Sampler = Sampler.forDepth(document.depth)
    private val packer: PixelPacker = PixelPackers.of(document.colorMode, document.indexedPalette)

    private val colourReaders =
        Array(colourCount) { RowReader(colour[it].source, colour[it].plane, windowFor(colour[it])) }
    private val colourRows = Array(colourCount) { ByteArray(colour[it].plane.rowBytes) }
    private val alphaReader = alpha?.let { RowReader(it.source, it.plane, windowFor(it)) }
    private val alphaRow = alpha?.let { ByteArray(it.plane.rowBytes) }
    private val maskReader = mask?.let { RowReader(it.source, it.plane, windowFor(it)) }
    private val maskRow = mask?.let { ByteArray(it.plane.rowBytes) }

    private val maskDefault = layer.mask?.defaultColor ?: 255
    private val maskTop = layer.mask?.top ?: 0
    private val maskLeft = layer.mask?.left ?: 0
    private val maskWidth = mask?.plane?.width ?: 0
    private val maskHeight = mask?.plane?.height ?: 0

    private val samples = IntArray(colourCount.coerceAtLeast(1))

    /**
     * True for plain 8-bit RGB.
     *
     * That is the overwhelmingly common case, and there a sample is just a masked array read;
     * going through the sampler interface for every sub-sample of every pixel of every layer
     * costs more than the work it does. The hot loops read the bytes directly instead.
     */
    private val fastRgb = document.colorMode == ColorMode.RGB && document.depth == 8 && colourCount >= 3

    /** Reused box-filter accumulators; sized on first use at a reduced level. */
    private var accumulator = IntArray(0)
    private var counts = IntArray(0)

    /**
     * Composites this layer onto [out], touching only the pixels it actually covers.
     *
     * Decoding and blending happen in the same pass. An intermediate full-tile buffer would mean
     * clearing and walking 262 144 pixels for a caption that occupies fifty of them, which is
     * where most of the old cost went.
     */
    fun blendInto(
        out: IntArray,
        outW: Int,
        outH: Int,
        srcX: Int,
        srcY: Int,
        step: Int,
        blendMode: BlendMode,
        opacity: Int,
        clipping: Boolean,
        isCancelled: () -> Boolean,
    ): Boolean {
        if (opacity <= 0) return true

        // Output pixels whose source coordinate falls inside the layer.
        val firstRow = maxOf(0, ceilDiv(layer.top - srcY, step))
        val lastRow = minOf(outH - 1, floorDiv(layer.bottom - 1 - srcY, step))
        val firstCol = maxOf(0, ceilDiv(layer.left - srcX, step))
        val lastCol = minOf(outW - 1, floorDiv(layer.right - 1 - srcX, step))
        if (firstRow > lastRow || firstCol > lastCol) return true

        return if (step <= 1 || maxSupersample <= 1) {
            blendExact(out, outW, srcX, srcY, step, firstRow, lastRow, firstCol, lastCol, blendMode, opacity, clipping, isCancelled)
        } else {
            blendFiltered(out, outW, srcX, srcY, step, firstRow, lastRow, firstCol, lastCol, blendMode, opacity, clipping, isCancelled)
        }
    }

    /** One source pixel per output pixel. */
    private fun blendExact(
        out: IntArray,
        outW: Int,
        srcX: Int,
        srcY: Int,
        step: Int,
        firstRow: Int,
        lastRow: Int,
        firstCol: Int,
        lastCol: Int,
        blendMode: BlendMode,
        opacity: Int,
        clipping: Boolean,
        isCancelled: () -> Boolean,
    ): Boolean {
        val simple = blendMode == BlendMode.NORMAL && opacity == 255 && !clipping

        for (oy in firstRow..lastRow) {
            if (isCancelled()) return false
            val sy = srcY + oy * step
            readRows(sy)
            val rowBase = oy * outW

            for (ox in firstCol..lastCol) {
                val sx = srcX + ox * step
                val lx = sx - layer.left
                val a = alphaAt(lx, sx)
                if (a == 0) continue

                val source = packAt(lx, a)
                val index = rowBase + ox
                out[index] = if (simple && a == 255) {
                    source
                } else {
                    Blending.composite(source, out[index], blendMode, opacity, clipping)
                }
            }
        }
        return true
    }

    /**
     * Box-filters the layer down to the output grid.
     *
     * Point sampling a reduced level turns antialiased artwork into speckle — captions lose their
     * outlines entirely — so each output pixel averages up to `maxSupersample` samples per axis,
     * matching what the flattened-composite renderer does. Colour is accumulated premultiplied
     * and divided by the summed alpha, which is what keeps edges from fringing.
     */
    private fun blendFiltered(
        out: IntArray,
        outW: Int,
        srcX: Int,
        srcY: Int,
        step: Int,
        firstRow: Int,
        lastRow: Int,
        firstCol: Int,
        lastCol: Int,
        blendMode: BlendMode,
        opacity: Int,
        clipping: Boolean,
        isCancelled: () -> Boolean,
    ): Boolean {
        val ss = minOf(step, maxSupersample)
        val sub = maxOf(1, step / ss)
        val span = lastCol - firstCol + 1
        val stride = colourCount + 1
        if (accumulator.size < span * stride) accumulator = IntArray(span * stride)
        if (counts.size < span) counts = IntArray(span)
        val acc = accumulator
        val n = counts
        val simple = blendMode == BlendMode.NORMAL && opacity == 255 && !clipping

        for (oy in firstRow..lastRow) {
            if (isCancelled()) return false
            java.util.Arrays.fill(acc, 0, span * stride, 0)
            java.util.Arrays.fill(n, 0, span, 0)

            for (k in 0 until ss) {
                val sy = srcY + oy * step + k * sub
                if (sy < layer.top || sy >= layer.bottom) continue
                readRows(sy)

                if (fastRgb && maskRow == null) {
                    accumulateRgb8(acc, n, stride, firstCol, lastCol, srcX, step, ss, sub)
                } else {
                    for (ox in firstCol..lastCol) {
                        val base = (ox - firstCol) * stride
                        val sx0 = srcX + ox * step
                        for (j in 0 until ss) {
                            val sx = sx0 + j * sub
                            if (sx < layer.left || sx >= layer.right) continue
                            val lx = sx - layer.left
                            val a = alphaAt(lx, sx)
                            n[ox - firstCol]++
                            if (a == 0) continue
                            for (c in 0 until colourCount) {
                                acc[base + c] += sampler.sample(colourRows[c], lx) * a
                            }
                            acc[base + colourCount] += a
                        }
                    }
                }
            }

            val rowBase = oy * outW
            for (ox in firstCol..lastCol) {
                val taken = n[ox - firstCol]
                if (taken == 0) continue
                val base = (ox - firstCol) * stride
                val summedAlpha = acc[base + colourCount]
                if (summedAlpha == 0) continue

                val a = summedAlpha / taken
                if (a == 0) continue
                val source = if (fastRgb) {
                    (a shl 24) or
                        ((acc[base] / summedAlpha) shl 16) or
                        ((acc[base + 1] / summedAlpha) shl 8) or
                        (acc[base + 2] / summedAlpha)
                } else {
                    for (c in 0 until colourCount) samples[c] = acc[base + c] / summedAlpha
                    packer.pack(samples, a)
                }

                val index = rowBase + ox
                out[index] = if (simple && a == 255) {
                    source
                } else {
                    Blending.composite(source, out[index], blendMode, opacity, clipping)
                }
            }
        }
        return true
    }

    /** The 8-bit RGB inner loop, with the channel reads inlined and no interface dispatch. */
    private fun accumulateRgb8(
        acc: IntArray,
        n: IntArray,
        stride: Int,
        firstCol: Int,
        lastCol: Int,
        srcX: Int,
        step: Int,
        ss: Int,
        sub: Int,
    ) {
        val red = colourRows[0]
        val green = colourRows[1]
        val blue = colourRows[2]
        val alphaBytes = alphaRow
        val left = layer.left
        val right = layer.right

        for (ox in firstCol..lastCol) {
            val base = (ox - firstCol) * stride
            val sx0 = srcX + ox * step
            var taken = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0
            var sumA = 0
            for (j in 0 until ss) {
                val sx = sx0 + j * sub
                if (sx < left || sx >= right) continue
                val lx = sx - left
                taken++
                val a = if (alphaBytes != null) alphaBytes[lx].toInt() and 0xFF else 255
                if (a == 0) continue
                sumR += (red[lx].toInt() and 0xFF) * a
                sumG += (green[lx].toInt() and 0xFF) * a
                sumB += (blue[lx].toInt() and 0xFF) * a
                sumA += a
            }
            n[ox - firstCol] += taken
            acc[base] += sumR
            acc[base + 1] += sumG
            acc[base + 2] += sumB
            acc[base + 3] += sumA
        }
    }

    /**
     * Decodes the layer on its own into straight ARGB, for the per-layer previews in the panel.
     * Pixels outside the layer come back transparent.
     */
    fun decodeInto(
        out: IntArray,
        outW: Int,
        outH: Int,
        srcX: Int,
        srcY: Int,
        step: Int,
    ): Boolean {
        java.util.Arrays.fill(out, 0, outW * outH, 0)
        return blendInto(out, outW, outH, srcX, srcY, step, BlendMode.NORMAL, 255, false) { false }
    }

    private fun alphaAt(lx: Int, sx: Int): Int {
        var a = if (alphaRow != null) sampler.sample(alphaRow, lx) else 255
        if (maskRow != null) {
            val mx = sx - maskLeft
            val m = if (mx in 0 until maskWidth) sampler.sample(maskRow, mx) else maskDefault
            a = a * m / 255
        }
        return a
    }

    private fun packAt(lx: Int, a: Int): Int {
        if (fastRgb) {
            return (a shl 24) or
                ((colourRows[0][lx].toInt() and 0xFF) shl 16) or
                ((colourRows[1][lx].toInt() and 0xFF) shl 8) or
                (colourRows[2][lx].toInt() and 0xFF)
        }
        for (c in 0 until colourCount) samples[c] = sampler.sample(colourRows[c], lx)
        return packer.pack(samples, a)
    }

    private fun readRows(sy: Int) {
        val ly = sy - layer.top
        for (c in 0 until colourCount) colourReaders[c].readRow(ly, colourRows[c])
        alphaReader?.readRow(ly, alphaRow!!)
        if (maskReader != null) {
            val my = sy - maskTop
            if (my in 0 until maskHeight) {
                maskReader.readRow(my, maskRow!!)
            } else {
                java.util.Arrays.fill(maskRow!!, maskDefault.toByte())
            }
        }
    }

    internal companion object {
        const val DEFAULT_SUPERSAMPLE = 4

        /**
         * Read-ahead sized to the channel rather than a flat block.
         *
         * A full-canvas layer benefits from reading many scanlines at once; a caption forty
         * pixels wide does not, and giving it a quarter-megabyte window was pure waste.
         */
        fun windowFor(channel: ResolvedChannel): Int =
            (channel.plane.rowBytes * 48).coerceIn(1 shl 13, 1 shl 18)

        fun floorDiv(a: Int, b: Int): Int = Math.floorDiv(a, b)
        fun ceilDiv(a: Int, b: Int): Int = -Math.floorDiv(-a, b)
    }
}
