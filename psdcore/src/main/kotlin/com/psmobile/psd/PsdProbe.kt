package com.psmobile.psd

import com.psmobile.psd.io.ByteCursor
import com.psmobile.psd.io.SeekableSource
import com.psmobile.psd.model.ColorMode
import com.psmobile.psd.model.EmbeddedThumbnail
import com.psmobile.psd.model.PsdHeader
import com.psmobile.psd.model.ResourceId

/** What a file browser can learn about a document without opening it properly. */
class PsdQuickInfo(
    val width: Int,
    val height: Int,
    val isPsb: Boolean,
    val depth: Int,
    val channels: Int,
    val colorMode: ColorMode,
    val thumbnail: EmbeddedThumbnail?,
)

/**
 * Reads just enough of a document to describe it in a list.
 *
 * The header is 26 bytes and the resource block is usually tens of kilobytes, so this stays
 * cheap even for an 800 MB PSB — it never touches the layer or pixel sections. That is what
 * makes it safe to run over a whole folder while the user scrolls.
 */
object PsdProbe {

    /** Returns null when the file is not a readable Photoshop document. */
    fun read(source: SeekableSource, withThumbnail: Boolean = true): PsdQuickInfo? = runCatching {
        if (source.size < 26) return null
        val c = ByteCursor(source.readFully(0, 26))
        if (c.signature() != PsdHeader.SIGNATURE) return null
        val version = c.u16()
        if (version != 1 && version != 2) return null
        c.skip(6)
        val channels = c.u16()
        val height = c.i32()
        val width = c.i32()
        val depth = c.u16()
        // Read once: a predicate that consumes from the cursor would advance it per candidate.
        val modeId = c.u16()
        val mode = ColorMode.entries.firstOrNull { it.id == modeId } ?: return null

        val limit = if (version == 2) PsdHeader.MAX_PSB_DIMENSION else PsdHeader.MAX_PSD_DIMENSION
        if (width !in 1..limit || height !in 1..limit || channels !in 1..56) return null

        val thumbnail = if (withThumbnail) runCatching { readThumbnail(source) }.getOrNull() else null

        PsdQuickInfo(
            width = width,
            height = height,
            isPsb = version == 2,
            depth = depth,
            channels = channels,
            colorMode = mode,
            thumbnail = thumbnail,
        )
    }.getOrNull()

    private fun readThumbnail(source: SeekableSource): EmbeddedThumbnail? {
        val colorModeLength = readU32(source, 26)
        if (colorModeLength < 0 || colorModeLength > MAX_COLOR_MODE_DATA) return null
        val resourcesAt = 26L + 4 + colorModeLength
        val resourcesLength = readU32(source, resourcesAt)
        if (resourcesLength <= 0 || resourcesLength > MAX_RESOURCES) return null

        val block = source.readFully(resourcesAt + 4, resourcesLength.toInt())
        val c = ByteCursor(block)
        while (c.remaining > 10) {
            if (c.signature() != "8BIM") return null
            val id = c.u16()
            c.pascalString(2)
            val length = c.i32()
            if (length < 0 || length > c.remaining) return null
            val start = c.pos
            if ((id == ResourceId.THUMBNAIL || id == ResourceId.THUMBNAIL_V4) && length >= 28) {
                val format = c.i32()
                val w = c.i32()
                val h = c.i32()
                c.skip(8)
                val compressed = c.i32()
                c.skip(4)
                if (format == 1 && w > 0 && h > 0 && compressed in 1..(c.remaining)) {
                    return EmbeddedThumbnail(w, h, c.bytes(compressed))
                }
                return null
            }
            c.seek(start + length + (length and 1))
        }
        return null
    }

    private fun readU32(source: SeekableSource, pos: Long): Long {
        if (pos + 4 > source.size) return -1
        val b = source.readFully(pos, 4)
        return ((b[0].toInt() and 0xFF).toLong() shl 24) or
            ((b[1].toInt() and 0xFF).toLong() shl 16) or
            ((b[2].toInt() and 0xFF).toLong() shl 8) or
            (b[3].toInt() and 0xFF).toLong()
    }

    private const val MAX_COLOR_MODE_DATA = 1L shl 24
    private const val MAX_RESOURCES = 1L shl 26
}
