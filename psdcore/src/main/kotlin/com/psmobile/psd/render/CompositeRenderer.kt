package com.psmobile.psd.render

import com.psmobile.psd.PsdDocument
import com.psmobile.psd.color.PixelPackers
import com.psmobile.psd.color.Sampler
import com.psmobile.psd.model.ChannelPlane
import com.psmobile.psd.model.ColorMode

/**
 * Renders tiles from the flattened preview Photoshop stores at the end of the file.
 *
 * This is the fast path and the one that runs for virtually every document: the composite is
 * already the finished artwork, so a tile is just "decode these scanlines, sample them, pack
 * to ARGB". No blending, no layer traversal.
 */
class CompositeRenderer(private val doc: PsdDocument) {

    val pyramid = TilePyramid(doc.width, doc.height)

    /** False when the file was saved without "Maximize Compatibility". */
    val isAvailable: Boolean =
        doc.composite.isNotEmpty() && doc.composite.all { it.isRandomAccess }

    /**
     * @param maxSupersample sub-samples per axis when a level covers more than one source pixel
     *   per output pixel. 1 is point sampling, 4 is the smoothest the renderer offers.
     */
    fun newDecoder(maxSupersample: Int = RegionDecoder.DEFAULT_SUPERSAMPLE): RegionDecoder =
        RegionDecoder(doc, maxSupersample)

    /** Decodes one whole tile into [out], which must hold `tileWidth * tileHeight` pixels. */
    fun decodeTile(decoder: RegionDecoder, key: TileKey, out: IntArray, isCancelled: () -> Boolean = { false }): Boolean {
        val step = pyramid.step(key.level)
        val w = pyramid.tileWidth(key.level, key.col)
        val h = pyramid.tileHeight(key.level, key.row)
        if (w <= 0 || h <= 0) return false
        return decoder.decodeRegion(
            srcX = key.col * pyramid.tileSize * step,
            srcY = key.row * pyramid.tileSize * step,
            step = step,
            outW = w,
            outH = h,
            out = out,
            isCancelled = isCancelled,
        )
    }
}

/**
 * Stateful, single-threaded decoder. Holds the scanline buffers and read-ahead windows, so one
 * instance per worker thread — never share.
 */
class RegionDecoder internal constructor(
    private val doc: PsdDocument,
    maxSupersample: Int = DEFAULT_SUPERSAMPLE,
) : TileDecoder {

    private val supersample = maxSupersample.coerceIn(1, DEFAULT_SUPERSAMPLE)

    private val header = doc.header
    private val width = header.width
    private val height = header.height
    private val depth = header.depth

    private val colorCount = minOf(PixelPackers.colorChannels(header.colorMode), doc.composite.size)
    private val alphaPlane: ChannelPlane? =
        if (doc.mergedHasAlpha && doc.composite.size > colorCount) doc.composite[colorCount] else null

    private val planes: List<ChannelPlane> = doc.composite.take(colorCount)
    private val readers = planes.map { RowReader(doc.source, it) }
    private val alphaReader = alphaPlane?.let { RowReader(doc.source, it) }

    private val rowBytes = if (depth == 1) (width + 7) / 8 else width * header.bytesPerSample
    private val rows = Array(planes.size) { ByteArray(rowBytes) }
    private val alphaRow = if (alphaReader != null) ByteArray(rowBytes) else null

    private val sampler: Sampler = Sampler.forDepth(depth)
    private val packer = PixelPackers.of(header.colorMode, doc.indexedPalette)
    private val scratch = IntArray(colorCount.coerceAtLeast(1))

    /** True for plain 8-bit RGB without transparency, which gets a specialised inner loop. */
    private val fastRgb = header.colorMode == ColorMode.RGB && depth == 8 && colorCount >= 3

    private var accumulator = IntArray(0)

    /**
     * Samples the source rectangle starting at ([srcX], [srcY]) with a stride of [step] into an
     * [outW] x [outH] ARGB block.
     *
     * Steps greater than one are box-averaged over up to `maxSupersample` sub-samples per axis,
     * which keeps zoomed-out views from shimmering without paying for a full downscale.
     */
    override fun decodeRegion(
        srcX: Int,
        srcY: Int,
        step: Int,
        outW: Int,
        outH: Int,
        out: IntArray,
        isCancelled: () -> Boolean,
    ): Boolean {
        if (outW <= 0 || outH <= 0 || planes.isEmpty()) return false
        if (step <= 1) return decodeExact(srcX, srcY, outW, outH, out, isCancelled)

        val ss = minOf(step, supersample)
        val sub = maxOf(1, step / ss)
        val nch = planes.size
        val stride = nch + 1
        if (accumulator.size < outW * stride) accumulator = IntArray(outW * stride)
        val acc = accumulator

        for (oy in 0 until outH) {
            if (isCancelled()) return false
            java.util.Arrays.fill(acc, 0, outW * stride, 0)
            var taken = 0

            for (k in 0 until ss) {
                val sy = srcY + oy * step + k * sub
                if (sy >= height) break
                for (c in 0 until nch) readers[c].readRow(sy, rows[c])
                alphaReader?.readRow(sy, alphaRow!!)

                for (ox in 0 until outW) {
                    val base = ox * stride
                    val sx0 = srcX + ox * step
                    for (j in 0 until ss) {
                        val sx = (sx0 + j * sub).coerceAtMost(width - 1)
                        if (sx < 0) continue
                        for (c in 0 until nch) acc[base + c] += sampler.sample(rows[c], sx)
                        acc[base + nch] += if (alphaRow != null) sampler.sample(alphaRow, sx) else 255
                    }
                }
                taken += ss
            }

            if (taken == 0) taken = 1
            val rowOut = oy * outW
            for (ox in 0 until outW) {
                val base = ox * stride
                for (c in 0 until scratch.size) scratch[c] = acc[base + c] / taken
                out[rowOut + ox] = packer.pack(scratch, acc[base + nch] / taken)
            }
        }
        return true
    }

    /** Step of one: every source pixel maps to exactly one output pixel. */
    private fun decodeExact(
        srcX: Int,
        srcY: Int,
        outW: Int,
        outH: Int,
        out: IntArray,
        isCancelled: () -> Boolean,
    ): Boolean {
        val nch = planes.size
        val maxX = width - 1

        for (oy in 0 until outH) {
            if (isCancelled()) return false
            val sy = srcY + oy
            if (sy >= height) {
                java.util.Arrays.fill(out, oy * outW, oy * outW + outW, 0)
                continue
            }
            for (c in 0 until nch) readers[c].readRow(sy, rows[c])
            alphaReader?.readRow(sy, alphaRow!!)
            val rowOut = oy * outW

            if (fastRgb && alphaRow == null) {
                val r = rows[0]
                val g = rows[1]
                val b = rows[2]
                for (ox in 0 until outW) {
                    val sx = (srcX + ox).coerceAtMost(maxX)
                    out[rowOut + ox] = -0x1000000 or
                        ((r[sx].toInt() and 0xFF) shl 16) or
                        ((g[sx].toInt() and 0xFF) shl 8) or
                        (b[sx].toInt() and 0xFF)
                }
            } else {
                for (ox in 0 until outW) {
                    val sx = (srcX + ox).coerceAtMost(maxX)
                    for (c in 0 until nch) scratch[c] = sampler.sample(rows[c], sx)
                    val a = if (alphaRow != null) sampler.sample(alphaRow, sx) else 255
                    out[rowOut + ox] = packer.pack(scratch, a)
                }
            }
        }
        return true
    }

    companion object {
        /** The smoothest setting, and the default: 4x4 samples per output pixel. */
        const val DEFAULT_SUPERSAMPLE = 4
    }
}
