package com.psmobile.psd

import com.psmobile.psd.model.ColorMode
import java.io.ByteArrayOutputStream

/**
 * Builds a valid, minimal Photoshop document in memory.
 *
 * This is what "New document" produces. Synthesising a real file rather than a special-case
 * in-memory canvas means the new document travels the exact same path as an opened one —
 * same parser, same tile pyramid, same layers panel — and gives the eventual save path a head
 * start.
 *
 * Solid fills cost almost nothing to store: a constant scanline collapses to a couple of bytes
 * per 128 pixels under RLE, so even a 8000 x 8000 canvas is a few hundred kilobytes.
 */
object PsdWriter {

    enum class Background { WHITE, BLACK, TRANSPARENT, CUSTOM }

    fun createBlank(
        width: Int,
        height: Int,
        colorMode: ColorMode = ColorMode.RGB,
        background: Background = Background.WHITE,
        customColor: Int = 0xFFFFFFFF.toInt(),
        resolution: Double = 72.0,
    ): ByteArray {
        require(width in 1..300_000 && height in 1..300_000) { "Canvas out of range" }

        val transparent = background == Background.TRANSPARENT
        val colorChannels = when (colorMode) {
            ColorMode.GRAYSCALE -> 1
            ColorMode.CMYK -> 4
            else -> 3
        }
        val channels = colorChannels + if (transparent) 1 else 0
        val psb = width > 30_000 || height > 30_000

        val fill = when (background) {
            Background.WHITE -> intArrayOf(255, 255, 255, 255)
            Background.BLACK -> intArrayOf(0, 0, 0, 0)
            Background.TRANSPARENT -> intArrayOf(255, 255, 255, 255)
            Background.CUSTOM -> intArrayOf(
                (customColor shr 16) and 0xFF,
                (customColor shr 8) and 0xFF,
                customColor and 0xFF,
                255,
            )
        }
        // Grayscale keeps a single value; CMYK is stored inverted, so a white canvas is all 255.
        fun channelValue(index: Int): Int = when {
            transparent && index == colorChannels -> 0
            colorMode == ColorMode.GRAYSCALE -> luminance(fill)
            colorMode == ColorMode.CMYK -> if (index < 3) 255 else 255
            else -> fill.getOrElse(index) { 255 }
        }

        val out = ByteArrayOutputStream(1 shl 16)

        // ---- header ----
        out.write("8BPS".toByteArray(Charsets.ISO_8859_1))
        out.u16(if (psb) 2 else 1)
        repeat(6) { out.write(0) }
        out.u16(channels)
        out.u32(height.toLong())
        out.u32(width.toLong())
        out.u16(8)
        out.u16(colorMode.id)

        // ---- colour mode data ----
        out.u32(0)

        // ---- image resources: resolution only ----
        val resources = ByteArrayOutputStream()
        resources.write("8BIM".toByteArray(Charsets.ISO_8859_1))
        resources.u16(0x03ED)
        resources.write(0); resources.write(0) // empty padded Pascal name
        resources.u32(16)
        resources.u32((resolution * 65536.0).toLong())
        resources.u16(1); resources.u16(1)
        resources.u32((resolution * 65536.0).toLong())
        resources.u16(1); resources.u16(1)
        val resourceBytes = resources.toByteArray()
        out.u32(resourceBytes.size.toLong())
        out.write(resourceBytes)

        // ---- layer and mask information ----
        // A transparent canvas needs a real layer so that the negative layer count can flag the
        // merged image as carrying alpha; an opaque one is a flat background with no layers.
        val layerSection = if (transparent) {
            buildTransparentLayerSection(width, height, colorChannels, psb)
        } else {
            ByteArray(0)
        }
        if (psb) out.u64(layerSection.size.toLong()) else out.u32(layerSection.size.toLong())
        out.write(layerSection)

        // ---- merged image data, RLE ----
        val rowData = IntArray(channels) { channelValue(it) }
        writeRleImage(out, width, height, channels, rowData, psb)

        return out.toByteArray()
    }

    private fun buildTransparentLayerSection(width: Int, height: Int, colorChannels: Int, psb: Boolean): ByteArray {
        val channelIds = IntArray(colorChannels + 1) { if (it == 0) -1 else it - 1 }
        val channelPayloads = channelIds.map { id ->
            val body = ByteArrayOutputStream()
            body.u16(1) // RLE
            writeRleChannel(body, width, height, if (id == -1) 0 else 255, psb)
            body.toByteArray()
        }

        val record = ByteArrayOutputStream()
        record.u32(0); record.u32(0)                       // top, left
        record.u32(height.toLong()); record.u32(width.toLong()) // bottom, right
        record.u16(channelIds.size)
        channelIds.forEachIndexed { i, id ->
            record.u16(id and 0xFFFF)
            if (psb) record.u64(channelPayloads[i].size.toLong()) else record.u32(channelPayloads[i].size.toLong())
        }
        record.write("8BIM".toByteArray(Charsets.ISO_8859_1))
        record.write("norm".toByteArray(Charsets.ISO_8859_1))
        record.write(255)  // opacity
        record.write(0)    // clipping
        record.write(0)    // flags: visible
        record.write(0)    // filler

        val extra = ByteArrayOutputStream()
        extra.u32(0) // no mask
        extra.u32(0) // no blending ranges
        val name = "Layer 1".toByteArray(Charsets.ISO_8859_1)
        extra.write(name.size)
        extra.write(name)
        var pad = (name.size + 1) % 4
        if (pad != 0) repeat(4 - pad) { extra.write(0) }
        val extraBytes = extra.toByteArray()
        record.u32(extraBytes.size.toLong())
        record.write(extraBytes)

        val layerInfo = ByteArrayOutputStream()
        layerInfo.u16(0xFFFF) // layer count -1: merged image carries transparency
        layerInfo.write(record.toByteArray())
        channelPayloads.forEach { layerInfo.write(it) }
        var body = layerInfo.toByteArray()
        if (body.size % 2 == 1) body += 0

        val section = ByteArrayOutputStream()
        if (psb) section.u64(body.size.toLong()) else section.u32(body.size.toLong())
        section.write(body)
        section.u32(0) // empty global layer mask info
        return section.toByteArray()
    }

    /** Writes the merged image: compression tag, scanline table, then the runs themselves. */
    private fun writeRleImage(
        out: ByteArrayOutputStream,
        width: Int,
        height: Int,
        channels: Int,
        values: IntArray,
        psb: Boolean,
    ) {
        out.u16(1) // RLE
        val rows = values.map { encodeConstantRow(width, it) }
        for (c in 0 until channels) {
            val len = rows[c].size
            repeat(height) { if (psb) out.u32(len.toLong()) else out.u16(len) }
        }
        for (c in 0 until channels) {
            repeat(height) { out.write(rows[c]) }
        }
    }

    /** Same layout as [writeRleImage] but for a single channel of a layer. */
    private fun writeRleChannel(out: ByteArrayOutputStream, width: Int, height: Int, value: Int, psb: Boolean) {
        val row = encodeConstantRow(width, value)
        repeat(height) { if (psb) out.u32(row.size.toLong()) else out.u16(row.size) }
        repeat(height) { out.write(row) }
    }

    /**
     * PackBits for a scanline of one repeated value. Runs cap at 128 bytes, and a run of one
     * encodes to the same two bytes as a single literal, so no special case is needed.
     */
    private fun encodeConstantRow(width: Int, value: Int): ByteArray {
        val out = ByteArrayOutputStream(width / 64 + 4)
        var remaining = width
        while (remaining > 0) {
            val run = minOf(remaining, 128)
            out.write((1 - run) and 0xFF)
            out.write(value and 0xFF)
            remaining -= run
        }
        return out.toByteArray()
    }

    private fun luminance(rgb: IntArray): Int =
        ((rgb[0] * 299 + rgb[1] * 587 + rgb[2] * 114) / 1000).coerceIn(0, 255)
}

private fun ByteArrayOutputStream.u16(v: Int) {
    write((v ushr 8) and 0xFF)
    write(v and 0xFF)
}

private fun ByteArrayOutputStream.u32(v: Long) {
    write(((v ushr 24) and 0xFF).toInt())
    write(((v ushr 16) and 0xFF).toInt())
    write(((v ushr 8) and 0xFF).toInt())
    write((v and 0xFF).toInt())
}

private fun ByteArrayOutputStream.u64(v: Long) {
    for (i in 7 downTo 0) write(((v ushr (i * 8)) and 0xFF).toInt())
}
