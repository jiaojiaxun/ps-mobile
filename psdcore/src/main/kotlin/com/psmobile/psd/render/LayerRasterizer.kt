package com.psmobile.psd.render

import com.psmobile.psd.PsdDocument
import com.psmobile.psd.codec.ZipCodec
import com.psmobile.psd.color.PixelPackers
import com.psmobile.psd.color.Sampler
import com.psmobile.psd.io.ByteArraySource
import com.psmobile.psd.io.SeekableSource
import com.psmobile.psd.model.ChannelPlane
import com.psmobile.psd.model.Compression
import com.psmobile.psd.model.Layer
import com.psmobile.psd.model.LayerChannelRef

/** A channel resolved to something that can be read scanline by scanline. */
class ResolvedChannel(val plane: ChannelPlane, val source: SeekableSource)

/**
 * Turns the raw channel references on a [Layer] into decodable planes.
 *
 * Layer channels each carry their own compression tag and, for RLE, their own scanline table.
 * Resolving is deferred until a layer is actually drawn or thumbnailed — a document with two
 * hundred full-canvas layers would otherwise spend its whole budget on tables nobody looks at.
 */
class LayerRasterizer(private val doc: PsdDocument) {

    private val psb = doc.header.isPsb
    private val depth = doc.header.depth
    private val cache = LinkedHashMap<Long, ResolvedChannel?>(64, 0.75f, true)

    @Synchronized
    fun resolve(layer: Layer, ref: LayerChannelRef): ResolvedChannel? {
        cache[ref.offset]?.let { return it }
        if (cache.containsKey(ref.offset)) return null
        val resolved = runCatching { load(layer, ref) }.getOrNull()
        if (cache.size > MAX_CACHED_PLANES) {
            val oldest = cache.keys.firstOrNull()
            if (oldest != null) cache.remove(oldest)
        }
        cache[ref.offset] = resolved
        return resolved
    }

    private fun load(layer: Layer, ref: LayerChannelRef): ResolvedChannel? {
        val isMask = ref.id == ChannelPlane.ID_USER_MASK || ref.id == ChannelPlane.ID_REAL_USER_MASK
        val mask = layer.mask
        val width = if (isMask && mask != null) mask.width else layer.width
        val height = if (isMask && mask != null) mask.height else layer.height
        if (width <= 0 || height <= 0 || ref.length < 2) return null

        val src = doc.source
        val tag = src.readFully(ref.offset, 2)
        val compression = Compression.from(((tag[0].toInt() and 0xFF) shl 8) or (tag[1].toInt() and 0xFF))
        val body = ref.offset + 2
        val bodyLength = ref.length - 2
        val rowBytes = if (depth == 1) (width + 7) / 8 else width * doc.header.bytesPerSample

        return when (compression) {
            Compression.RAW -> ResolvedChannel(
                ChannelPlane(ref.id, Compression.RAW, width, height, depth, body, bodyLength, null),
                src,
            )

            Compression.RLE -> {
                val entrySize = if (psb) 4 else 2
                val tableBytes = height.toLong() * entrySize
                if (tableBytes > bodyLength) return null
                val table = src.readFully(body, tableBytes.toInt())
                val offsets = LongArray(height + 1)
                var pos = body + tableBytes
                offsets[0] = pos
                for (y in 0 until height) {
                    val len = if (entrySize == 4) {
                        ((table[y * 4].toInt() and 0xFF) shl 24) or
                            ((table[y * 4 + 1].toInt() and 0xFF) shl 16) or
                            ((table[y * 4 + 2].toInt() and 0xFF) shl 8) or
                            (table[y * 4 + 3].toInt() and 0xFF)
                    } else {
                        ((table[y * 2].toInt() and 0xFF) shl 8) or (table[y * 2 + 1].toInt() and 0xFF)
                    }
                    pos += len.toLong() and 0xFFFFFFFFL
                    offsets[y + 1] = pos
                }
                ResolvedChannel(
                    ChannelPlane(ref.id, Compression.RLE, width, height, depth, offsets[0], pos - offsets[0], offsets),
                    src,
                )
            }

            // ZIP channels are not seekable, so they get inflated once into memory.
            Compression.ZIP, Compression.ZIP_PREDICTED -> {
                val plain = rowBytes.toLong() * height
                if (plain > MAX_INFLATED_BYTES) return null
                val compressed = src.readFully(body, bodyLength.coerceAtMost(MAX_INFLATED_BYTES).toInt())
                val out = ByteArray(plain.toInt())
                ZipCodec.inflate(compressed, 0, compressed.size, out, 0, out.size)
                if (compression == Compression.ZIP_PREDICTED) {
                    ZipCodec.undoPrediction(out, width, height, depth)
                }
                ResolvedChannel(
                    ChannelPlane(ref.id, Compression.RAW, width, height, depth, 0L, plain, null),
                    ByteArraySource(out),
                )
            }
        }
    }

    /**
     * Decodes a layer into straight ARGB, sampling the source with a stride of [step].
     * Pixels outside the layer's own bounds come back fully transparent.
     */
    fun decodeLayer(
        layer: Layer,
        srcX: Int,
        srcY: Int,
        step: Int,
        outW: Int,
        outH: Int,
        out: IntArray,
        isCancelled: () -> Boolean = { false },
    ): Boolean {
        if (layer.isEmpty) return false
        val colorCount = PixelPackers.colorChannels(doc.header.colorMode)
        val colorRefs = (0 until colorCount).mapNotNull { layer.channel(it) }
        if (colorRefs.size < colorCount) return false

        val color = colorRefs.map { resolve(layer, it) ?: return false }
        val alpha = layer.channel(ChannelPlane.ID_TRANSPARENCY)?.let { resolve(layer, it) }
        val maskChannel = layer.mask
            ?.takeIf { !it.isDisabled && it.width > 0 && it.height > 0 }
            ?.let { layer.channel(ChannelPlane.ID_REAL_USER_MASK) ?: layer.channel(ChannelPlane.ID_USER_MASK) }
            ?.let { resolve(layer, it) }

        val sampler = Sampler.forDepth(depth)
        val packer = PixelPackers.of(doc.header.colorMode, doc.indexedPalette)
        val colorReaders = color.map { RowReader(it.source, it.plane, WINDOW) }
        val colorRows = color.map { ByteArray(it.plane.rowBytes) }
        val alphaReader = alpha?.let { RowReader(it.source, it.plane, WINDOW) }
        val alphaRow = alpha?.let { ByteArray(it.plane.rowBytes) }
        val maskReader = maskChannel?.let { RowReader(it.source, it.plane, WINDOW) }
        val maskRow = maskChannel?.let { ByteArray(it.plane.rowBytes) }
        val maskDefault = layer.mask?.defaultColor ?: 255
        val maskTop = layer.mask?.top ?: 0
        val maskLeft = layer.mask?.left ?: 0
        val maskWidth = maskChannel?.plane?.width ?: 0
        val maskHeight = maskChannel?.plane?.height ?: 0

        val scratch = IntArray(colorCount)
        val lw = layer.width
        val lh = layer.height

        java.util.Arrays.fill(out, 0, outW * outH, 0)

        for (oy in 0 until outH) {
            if (isCancelled()) return false
            val sy = srcY + oy * step
            val ly = sy - layer.top
            if (ly < 0 || ly >= lh) continue

            for (i in colorReaders.indices) colorReaders[i].readRow(ly, colorRows[i])
            alphaReader?.readRow(ly, alphaRow!!)
            if (maskReader != null) {
                val my = sy - maskTop
                if (my in 0 until maskHeight) maskReader.readRow(my, maskRow!!)
                else java.util.Arrays.fill(maskRow!!, maskDefault.toByte())
            }

            val rowOut = oy * outW
            for (ox in 0 until outW) {
                val sx = srcX + ox * step
                val lx = sx - layer.left
                if (lx < 0 || lx >= lw) continue

                for (c in 0 until colorCount) scratch[c] = sampler.sample(colorRows[c], lx)
                var a = if (alphaRow != null) sampler.sample(alphaRow, lx) else 255
                if (maskRow != null) {
                    val mx = sx - maskLeft
                    val m = if (mx in 0 until maskWidth) sampler.sample(maskRow, mx) else maskDefault
                    a = a * m / 255
                }
                if (a == 0) continue
                out[rowOut + ox] = packer.pack(scratch, a)
            }
        }
        return true
    }

    companion object {
        private const val MAX_CACHED_PLANES = 96
        private const val MAX_INFLATED_BYTES = 96L * 1024 * 1024
        private const val WINDOW = 1 shl 18
    }
}
