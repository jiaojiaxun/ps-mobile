package com.psmobile.psd.render

import com.psmobile.psd.PsdDocument
import com.psmobile.psd.codec.ZipCodec
import com.psmobile.psd.color.PixelPackers
import com.psmobile.psd.io.ByteArraySource
import com.psmobile.psd.io.SeekableSource
import com.psmobile.psd.model.ChannelPlane
import com.psmobile.psd.model.Compression
import com.psmobile.psd.model.Layer
import com.psmobile.psd.model.LayerChannelRef
import java.util.concurrent.ConcurrentHashMap

/** A channel resolved to something that can be read scanline by scanline. */
class ResolvedChannel(val plane: ChannelPlane, val source: SeekableSource)

/**
 * Turns the raw channel references on a [Layer] into decodable planes.
 *
 * Layer channels each carry their own compression tag and, for RLE, their own scanline table.
 * Resolving is deferred until a layer is actually drawn or previewed — a document with two
 * hundred full-canvas layers would otherwise spend its whole budget on tables nobody looks at.
 *
 * Resolved planes are immutable and shared between worker threads; what is per-thread lives in
 * [LayerImage].
 */
class LayerRasterizer(private val doc: PsdDocument) {

    private val psb = doc.header.isPsb
    private val depth = doc.header.depth

    private val cache = ConcurrentHashMap<Long, ResolvedChannel>()
    private val unreadable = ConcurrentHashMap.newKeySet<Long>()

    @Volatile
    private var cachedBytes = 0L

    fun resolve(layer: Layer, ref: LayerChannelRef): ResolvedChannel? {
        cache[ref.offset]?.let { return it }
        if (ref.offset in unreadable) return null

        val loaded = runCatching { load(layer, ref) }.getOrNull()
        if (loaded == null) {
            unreadable += ref.offset
            return null
        }
        // A duplicate load from another thread is harmless; the planes describe the same bytes.
        val existing = cache.putIfAbsent(ref.offset, loaded)
        if (existing != null) return existing

        cachedBytes += loaded.plane.retainedBytes
        if (cachedBytes > MAX_CACHED_BYTES) evictSomething(keep = ref.offset)
        return loaded
    }

    /**
     * Scanline tables for full-canvas layers run to megabytes each, so the cache is bounded by
     * what it holds rather than by how many entries it has. Eviction only costs a re-read.
     */
    private fun evictSomething(keep: Long) {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext() && cachedBytes > MAX_CACHED_BYTES / 2) {
            val entry = iterator.next()
            if (entry.key == keep) continue
            iterator.remove()
            cachedBytes -= entry.value.plane.retainedBytes
        }
    }

    /** Builds the per-thread decoding state for a layer, or null when its channels are unusable. */
    fun imageFor(layer: Layer, maxSupersample: Int = LayerImage.DEFAULT_SUPERSAMPLE): LayerImage? {
        if (layer.isEmpty) return null
        val colourCount = PixelPackers.colorChannels(doc.colorMode)
        val colour = ArrayList<ResolvedChannel>(colourCount)
        for (id in 0 until colourCount) {
            val ref = layer.channel(id) ?: return null
            colour += resolve(layer, ref) ?: return null
        }
        val alpha = layer.channel(ChannelPlane.ID_TRANSPARENCY)?.let { resolve(layer, it) }
        val mask = layer.mask
            ?.takeIf { !it.isDisabled && it.width > 0 && it.height > 0 }
            ?.let { layer.channel(ChannelPlane.ID_REAL_USER_MASK) ?: layer.channel(ChannelPlane.ID_USER_MASK) }
            ?.let { resolve(layer, it) }
        return LayerImage(doc, layer, colour, alpha, mask, maxSupersample)
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

    private companion object {
        const val MAX_INFLATED_BYTES = 96L * 1024 * 1024
        const val MAX_CACHED_BYTES = 48L * 1024 * 1024
    }
}
