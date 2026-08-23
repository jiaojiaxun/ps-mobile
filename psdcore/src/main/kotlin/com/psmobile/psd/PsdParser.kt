package com.psmobile.psd

import com.psmobile.psd.io.ByteCursor
import com.psmobile.psd.io.SeekableSource
import com.psmobile.psd.io.SequentialReader
import com.psmobile.psd.model.BlendMode
import com.psmobile.psd.model.ChannelPlane
import com.psmobile.psd.model.ColorMode
import com.psmobile.psd.model.Compression
import com.psmobile.psd.model.ImageResource
import com.psmobile.psd.model.Layer
import com.psmobile.psd.model.LayerChannelRef
import com.psmobile.psd.model.LayerMask
import com.psmobile.psd.model.LayerNode
import com.psmobile.psd.model.PsdFormatException
import com.psmobile.psd.model.PsdHeader
import com.psmobile.psd.model.SectionType

/**
 * Reads the structure of a PSD or PSB document.
 *
 * The parser never materialises pixel data. It walks the header, resource and layer sections,
 * and for the flattened composite it builds a scanline offset index so that the renderer can
 * later pull any individual row straight out of the file.
 */
class PsdParser(
    private val source: SeekableSource,
    private val displayName: String = "Untitled",
) {

    private var psb = false
    private var mergedHasAlpha = false

    fun parse(listener: ProgressListener? = null): PsdDocument {
        fun report(phase: ParsePhase, fraction: Float = -1f, detail: String = "") {
            listener?.onProgress(ParseProgress(phase, fraction, detail))
        }

        report(ParsePhase.OPENING, -1f, displayName)

        val header = readHeader()
        psb = header.isPsb
        report(
            ParsePhase.HEADER, 1f,
            "${header.width} x ${header.height}, ${header.depth}-bit ${header.colorMode.label}",
        )

        var pos = HEADER_SIZE

        val colorModeLength = readU32(pos)
        val colorModeData = if (colorModeLength in 1..MAX_COLOR_MODE_DATA) {
            source.readFully(pos + 4, colorModeLength.toInt())
        } else {
            ByteArray(0)
        }
        pos += 4 + colorModeLength

        val resourcesLength = readU32(pos)
        val resources = if (resourcesLength in 1..MAX_RESOURCES) {
            parseImageResources(source.readFully(pos + 4, resourcesLength.toInt()))
        } else {
            emptyMap()
        }
        pos += 4 + resourcesLength
        report(ParsePhase.RESOURCES, 1f, "${resources.size} resource blocks")

        val layerSectionLength = readLength(pos)
        val layerSectionStart = pos + (if (psb) 8 else 4)
        val layers = if (layerSectionLength > 0) {
            runCatching {
                parseLayerAndMaskInfo(layerSectionStart, layerSectionLength) { done, total ->
                    report(ParsePhase.LAYERS, done.toFloat() / total.coerceAtLeast(1), "Layer $done of $total")
                }
            }.getOrElse { emptyList() }
        } else {
            emptyList()
        }
        report(ParsePhase.LAYERS, 1f, "${layers.count { it.sectionType != SectionType.DIVIDER }} layers")

        val imageDataStart = layerSectionStart + layerSectionLength
        val composite = if (imageDataStart + 2 < source.size) {
            runCatching {
                parseCompositeChannels(imageDataStart, header) { done, total ->
                    report(ParsePhase.INDEXING, done.toFloat() / total.coerceAtLeast(1), "Indexing scanlines")
                }
            }.getOrElse { emptyList() }
        } else {
            emptyList()
        }
        report(
            ParsePhase.INDEXING, 1f,
            if (composite.isEmpty()) "No flattened preview" else "${composite.size} channels",
        )

        return PsdDocument(
            header = header,
            resources = resources,
            layers = layers,
            layerTree = buildTree(layers),
            composite = composite,
            colorModeData = colorModeData,
            source = source,
            fileSize = source.size,
            displayName = displayName,
            mergedHasAlpha = mergedHasAlpha,
        )
    }

    // ---------------------------------------------------------------- header

    private fun readHeader(): PsdHeader {
        if (source.size < HEADER_SIZE) throw PsdFormatException("File is too small to be a Photoshop document")
        val c = ByteCursor(source.readFully(0, HEADER_SIZE.toInt()))
        val signature = c.signature()
        if (signature != PsdHeader.SIGNATURE) {
            throw PsdFormatException("Not a Photoshop document (signature " + signature + ")")
        }
        val version = c.u16()
        if (version != 1 && version != 2) throw PsdFormatException("Unknown Photoshop version: $version")
        c.skip(6)
        val channels = c.u16()
        val height = c.i32()
        val width = c.i32()
        val depth = c.u16()
        val mode = c.u16()

        val limit = if (version == 2) PsdHeader.MAX_PSB_DIMENSION else PsdHeader.MAX_PSD_DIMENSION
        if (width !in 1..limit || height !in 1..limit) {
            throw PsdFormatException("Implausible canvas size: ${width}x$height")
        }
        if (channels !in 1..56) throw PsdFormatException("Implausible channel count: $channels")
        if (depth != 1 && depth != 8 && depth != 16 && depth != 32) {
            throw PsdFormatException("Unsupported bit depth: $depth")
        }
        return PsdHeader(version, channels, height, width, depth, ColorMode.from(mode))
    }

    // ------------------------------------------------------- image resources

    private fun parseImageResources(block: ByteArray): Map<Int, ImageResource> {
        val out = LinkedHashMap<Int, ImageResource>()
        val c = ByteCursor(block)
        while (c.remaining > 10) {
            val sig = c.signature()
            if (sig != "8BIM" && sig != "MeSa" && sig != "PHUT" && sig != "AgHg") break
            val id = c.u16()
            val name = c.pascalString(2)
            val length = c.i32()
            if (length < 0 || length > c.remaining) break
            val data = c.bytes(length)
            if (length % 2 == 1) c.skip(1)
            out.putIfAbsent(id, ImageResource(id, name, data))
        }
        return out
    }

    // ------------------------------------------------------------ layer info

    private fun parseLayerAndMaskInfo(
        start: Long,
        length: Long,
        onLayer: (Int, Int) -> Unit,
    ): List<Layer> {
        val reader = SequentialReader(source, start)
        val layerInfoLength = reader.length(psb)
        val layerInfoStart = reader.position

        if (layerInfoLength >= 2) {
            val layers = parseLayerRecords(reader, onLayer)
            if (layers.isNotEmpty()) return layers
        }

        // 16- and 32-bit documents park their layers in an Lr16 / Lr32 additional block
        // instead of the classic layer-info section.
        val additionalStart = layerInfoStart + layerInfoLength
        return findLayersInAdditionalInfo(additionalStart, start + length, onLayer)
    }

    private fun parseLayerRecords(
        reader: SequentialReader,
        onLayer: (Int, Int) -> Unit,
    ): List<Layer> {
        val rawCount = reader.i16()
        // A negative count is how the format flags that the merged image ends with alpha.
        if (rawCount < 0) mergedHasAlpha = true
        val count = kotlin.math.abs(rawCount)
        if (count == 0 || count > MAX_LAYERS) return emptyList()

        val records = ArrayList<PendingLayer>(count)
        for (i in 0 until count) {
            records += readLayerRecord(reader, i)
            if (i % 16 == 0 || i == count - 1) onLayer(i + 1, count)
        }

        // Channel payloads follow every record, in declaration order.
        var offset = reader.position
        val layers = ArrayList<Layer>(count)
        for (p in records) {
            val refs = ArrayList<LayerChannelRef>(p.channelLengths.size)
            for ((id, len) in p.channelLengths) {
                refs += LayerChannelRef(id, offset, len)
                offset += len
            }
            layers += p.toLayer(refs)
        }
        return layers
    }

    private class PendingLayer(
        val index: Int,
        val top: Int,
        val left: Int,
        val bottom: Int,
        val right: Int,
        val channelLengths: List<Pair<Int, Long>>,
        val blendMode: BlendMode,
        val opacity: Int,
        val clipping: Boolean,
        val flags: Int,
        var name: String,
        var unicodeName: String?,
        var sectionType: SectionType,
        var mask: LayerMask?,
        var colorLabel: Int,
        var layerId: Int,
        var fillOpacity: Int,
        var hasText: Boolean,
        var isAdjustment: Boolean,
    ) {
        fun toLayer(refs: List<LayerChannelRef>) = Layer(
            index = index,
            name = (unicodeName?.takeIf { it.isNotBlank() } ?: name).ifBlank { "Layer ${index + 1}" },
            top = top, left = left, bottom = bottom, right = right,
            opacity = opacity, fillOpacity = fillOpacity,
            blendMode = blendMode, clipping = clipping, flags = flags,
            sectionType = sectionType, channels = refs, mask = mask,
            colorLabel = colorLabel, layerId = layerId,
            hasText = hasText, isAdjustment = isAdjustment,
        )
    }

    private fun readLayerRecord(reader: SequentialReader, index: Int): PendingLayer {
        val top = reader.i32()
        val left = reader.i32()
        val bottom = reader.i32()
        val right = reader.i32()

        val channelCount = reader.u16()
        if (channelCount > 64) throw PsdFormatException("Layer $index declares $channelCount channels")
        val channelLengths = ArrayList<Pair<Int, Long>>(channelCount)
        for (c in 0 until channelCount) {
            val id = reader.i16()
            val len = reader.length(psb)
            channelLengths += id to len
        }

        val blendSig = reader.signature()
        if (blendSig != "8BIM") throw PsdFormatException("Bad blend signature on layer $index")
        val blendMode = BlendMode.from(reader.signature())
        val opacity = reader.u8()
        val clipping = reader.u8() != 0
        val flags = reader.u8()
        reader.u8() // filler

        val extraLength = reader.u32()
        val extraEnd = reader.position + extraLength

        val pending = PendingLayer(
            index = index, top = top, left = left, bottom = bottom, right = right,
            channelLengths = channelLengths, blendMode = blendMode, opacity = opacity,
            clipping = clipping, flags = flags, name = "", unicodeName = null,
            sectionType = SectionType.CONTENT, mask = null, colorLabel = 0, layerId = 0,
            fillOpacity = 255, hasText = false, isAdjustment = false,
        )

        if (extraLength > 0) {
            runCatching { readLayerExtra(reader, pending, extraEnd) }
        }
        reader.position = extraEnd
        return pending
    }

    private fun readLayerExtra(reader: SequentialReader, layer: PendingLayer, extraEnd: Long) {
        val maskLength = reader.u32()
        val maskEnd = reader.position + maskLength
        if (maskLength >= 16) {
            val mt = reader.i32()
            val ml = reader.i32()
            val mb = reader.i32()
            val mr = reader.i32()
            val defaultColor = reader.u8()
            val maskFlags = reader.u8()
            layer.mask = LayerMask(mt, ml, mb, mr, defaultColor, maskFlags)
        }
        reader.position = maskEnd

        val blendingRangesLength = reader.u32()
        reader.skip(blendingRangesLength)

        layer.name = readPascalString(reader, padTo = 4)

        while (reader.position + 12 <= extraEnd) {
            val entryStart = reader.position
            val sig = reader.signature()
            if (sig != "8BIM" && sig != "8B64") {
                reader.position = entryStart
                break
            }
            val key = reader.signature()
            val big = psb && key in BIG_LENGTH_KEYS
            val length = reader.length(big)
            if (length < 0 || reader.position + length > extraEnd) break
            val dataStart = reader.position

            when {
                key == "luni" -> layer.unicodeName = readUnicodeString(reader)
                key == "lsct" || key == "lsdk" -> layer.sectionType = sectionTypeOf(reader.i32())
                key == "lclr" -> layer.colorLabel = reader.u16()
                key == "lyid" -> layer.layerId = reader.i32()
                key == "iOpa" -> layer.fillOpacity = reader.u8()
                key == "TySh" || key == "TxLr" -> layer.hasText = true
                key in ADJUSTMENT_KEYS -> layer.isAdjustment = true
            }

            var next = dataStart + length
            if (!big && length % 2 == 1L) next += 1
            reader.position = next
        }
    }

    private fun findLayersInAdditionalInfo(
        start: Long,
        end: Long,
        onLayer: (Int, Int) -> Unit,
    ): List<Layer> {
        if (start >= end) return emptyList()
        val reader = SequentialReader(source, start)

        // Skip the global layer mask block.
        val skipped = runCatching {
            val globalMaskLength = reader.u32()
            reader.skip(globalMaskLength)
        }
        if (skipped.isFailure) return emptyList()

        while (reader.position + 12 <= end) {
            val entryStart = reader.position
            val sig = runCatching { reader.signature() }.getOrNull() ?: return emptyList()
            if (sig != "8BIM" && sig != "8B64") {
                reader.position = entryStart
                return emptyList()
            }
            val key = reader.signature()
            val big = psb && key in BIG_LENGTH_KEYS
            val length = reader.length(big)
            if (length <= 0 || reader.position + length > end) return emptyList()
            val dataStart = reader.position

            if (key == "Lr16" || key == "Lr32" || key == "Layr") {
                val nested = runCatching { parseLayerRecords(reader, onLayer) }.getOrDefault(emptyList())
                if (nested.isNotEmpty()) return nested
            }

            var next = dataStart + length
            if (!big && length % 2 == 1L) next += 1
            reader.position = next
        }
        return emptyList()
    }

    // ---------------------------------------------------- composite channels

    private fun parseCompositeChannels(
        start: Long,
        header: PsdHeader,
        onProgress: (Long, Long) -> Unit,
    ): List<ChannelPlane> {
        val compression = Compression.from(readU16(start))
        val channels = header.channels
        val height = header.height
        val width = header.width
        val rowBytes = header.rowBytes

        return when (compression) {
            Compression.RAW -> {
                val needed = rowBytes.toLong() * height * channels
                if (start + 2 + needed > source.size + SLACK) return emptyList()
                List(channels) { c ->
                    ChannelPlane(
                        id = c, compression = Compression.RAW,
                        width = width, height = height, depth = header.depth,
                        dataOffset = start + 2 + c.toLong() * rowBytes * height,
                        dataLength = rowBytes.toLong() * height,
                        rowOffsets = null,
                    )
                }
            }

            Compression.RLE -> buildRleIndex(start + 2, header, onProgress)

            // ZIP for the flattened preview is exotic; the layer compositor covers those files.
            Compression.ZIP, Compression.ZIP_PREDICTED -> emptyList()
        }
    }

    /**
     * Turns the RLE scanline byte-count table into absolute file offsets.
     *
     * This is the one pass that makes the whole viewer work: after it, fetching row `y` of
     * channel `c` costs a single positioned read no matter how deep into the file it sits.
     */
    private fun buildRleIndex(
        tableStart: Long,
        header: PsdHeader,
        onProgress: (Long, Long) -> Unit,
    ): List<ChannelPlane> {
        val channels = header.channels
        val height = header.height
        val entrySize = if (psb) 4 else 2
        val entries = channels.toLong() * height
        val tableBytes = entries * entrySize
        if (tableStart + tableBytes > source.size) return emptyList()

        var dataOffset = tableStart + tableBytes
        val planes = ArrayList<ChannelPlane>(channels)
        val chunk = ByteArray(CHUNK_ENTRIES * entrySize)

        var read = 0L
        var channel = 0
        var row = 0
        var offsets = LongArray(height + 1)
        var channelStart = dataOffset
        offsets[0] = dataOffset

        while (read < entries) {
            val batch = minOf(CHUNK_ENTRIES.toLong(), entries - read).toInt()
            source.readFully(tableStart + read * entrySize, chunk, 0, batch * entrySize)
            for (i in 0 until batch) {
                val len = if (entrySize == 4) {
                    ((chunk[i * 4].toInt() and 0xFF) shl 24) or
                        ((chunk[i * 4 + 1].toInt() and 0xFF) shl 16) or
                        ((chunk[i * 4 + 2].toInt() and 0xFF) shl 8) or
                        (chunk[i * 4 + 3].toInt() and 0xFF)
                } else {
                    ((chunk[i * 2].toInt() and 0xFF) shl 8) or (chunk[i * 2 + 1].toInt() and 0xFF)
                }
                dataOffset += len.toLong() and 0xFFFFFFFFL
                row++
                offsets[row] = dataOffset
                if (row == height) {
                    planes += ChannelPlane(
                        id = channel, compression = Compression.RLE,
                        width = header.width, height = height, depth = header.depth,
                        dataOffset = channelStart, dataLength = dataOffset - channelStart,
                        rowOffsets = offsets,
                    )
                    channel++
                    row = 0
                    channelStart = dataOffset
                    if (channel < channels) {
                        offsets = LongArray(height + 1)
                        offsets[0] = dataOffset
                    }
                }
            }
            read += batch
            onProgress(read, entries)
        }

        // A truncated file would produce offsets past the end; treat that as "no preview".
        if (planes.size != channels || dataOffset > source.size + SLACK) return emptyList()
        return planes
    }

    // ------------------------------------------------------------- utilities

    private fun readU16(pos: Long): Int {
        val b = source.readFully(pos, 2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    private fun readU32(pos: Long): Long {
        val b = source.readFully(pos, 4)
        return ((b[0].toInt() and 0xFF).toLong() shl 24) or
            ((b[1].toInt() and 0xFF).toLong() shl 16) or
            ((b[2].toInt() and 0xFF).toLong() shl 8) or
            (b[3].toInt() and 0xFF).toLong()
    }

    private fun readLength(pos: Long): Long {
        if (!psb) return readU32(pos)
        val b = source.readFully(pos, 8)
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    private fun readPascalString(reader: SequentialReader, padTo: Int): String {
        val len = reader.u8()
        val bytes = reader.bytes(len)
        val total = len + 1
        val rem = total % padTo
        if (rem != 0) reader.skip((padTo - rem).toLong())
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun readUnicodeString(reader: SequentialReader): String {
        val chars = reader.i32()
        if (chars <= 0 || chars > (1 shl 20)) return ""
        return String(reader.bytes(chars * 2), Charsets.UTF_16BE).trimEnd(' ')
    }

    private fun sectionTypeOf(value: Int): SectionType = when (value) {
        1 -> SectionType.GROUP_OPEN
        2 -> SectionType.GROUP_CLOSED
        3 -> SectionType.DIVIDER
        else -> SectionType.CONTENT
    }

    companion object {
        private const val HEADER_SIZE = 26L
        private const val MAX_COLOR_MODE_DATA = 1L shl 24
        private const val MAX_RESOURCES = 1L shl 26
        private const val MAX_LAYERS = 65_536
        private const val CHUNK_ENTRIES = 1 shl 16
        private const val SLACK = 64L

        /** Additional-info keys whose length field widens to 8 bytes inside a PSB. */
        private val BIG_LENGTH_KEYS = setOf(
            "LMsk", "Lr16", "Lr32", "Layr", "Mt16", "Mt32", "Mtrn",
            "Alph", "FMsk", "lnk2", "FEid", "FXid", "PxSD", "cinf",
            "CgEd", "Znmi", "pths", "SoLE",
        )

        private val ADJUSTMENT_KEYS = setOf(
            "levl", "curv", "brit", "mixr", "hue ", "hue2", "blnc", "blwh",
            "phfl", "clrL", "nvrt", "post", "thrs", "grdm", "selc", "SoCo",
            "GdFl", "PtFl", "vibA", "expA",
        )

        /**
         * Rebuilds the group hierarchy. Records are stored bottom-to-top, so the tree is walked
         * from the top: a folder record opens a group and the hidden divider below its contents
         * closes it again.
         */
        fun buildTree(layers: List<Layer>): List<LayerNode> {
            val roots = mutableListOf<LayerNode>()
            val stack = ArrayDeque<LayerNode>()

            for (layer in layers.asReversed()) {
                when (layer.sectionType) {
                    SectionType.GROUP_OPEN, SectionType.GROUP_CLOSED -> {
                        val node = LayerNode(layer, depth = stack.size)
                        if (stack.isEmpty()) roots += node else stack.last().children += node
                        stack.addLast(node)
                    }
                    SectionType.DIVIDER -> if (stack.isNotEmpty()) stack.removeLast()
                    SectionType.CONTENT -> {
                        val node = LayerNode(layer, depth = stack.size)
                        if (stack.isEmpty()) roots += node else stack.last().children += node
                    }
                }
            }
            return roots
        }
    }
}
