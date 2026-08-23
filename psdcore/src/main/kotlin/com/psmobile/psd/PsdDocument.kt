package com.psmobile.psd

import com.psmobile.psd.io.ByteCursor
import com.psmobile.psd.io.SeekableSource
import com.psmobile.psd.model.ChannelPlane
import com.psmobile.psd.model.ColorMode
import com.psmobile.psd.model.EmbeddedThumbnail
import com.psmobile.psd.model.ImageResource
import com.psmobile.psd.model.Layer
import com.psmobile.psd.model.LayerNode
import com.psmobile.psd.model.PsdHeader
import com.psmobile.psd.model.Resolution
import com.psmobile.psd.model.ResourceId
import com.psmobile.psd.model.SectionType
import java.io.Closeable

/**
 * A parsed document: full structure, no pixels.
 *
 * The [source] stays open for as long as the document is on screen because every tile the
 * renderer asks for is fetched straight out of the file.
 */
class PsdDocument(
    val header: PsdHeader,
    val resources: Map<Int, ImageResource>,
    val layers: List<Layer>,
    val layerTree: List<LayerNode>,
    val composite: List<ChannelPlane>,
    val colorModeData: ByteArray,
    val source: SeekableSource,
    val fileSize: Long,
    val displayName: String,
    /** The flattened preview carries a transparency channel (negative layer count in the file). */
    val mergedHasAlpha: Boolean = false,
) : Closeable {

    val width: Int get() = header.width
    val height: Int get() = header.height
    val colorMode: ColorMode get() = header.colorMode
    val depth: Int get() = header.depth

    /** True when Photoshop saved a flattened preview ("Maximize Compatibility"). */
    val hasComposite: Boolean get() = composite.isNotEmpty()

    /** Layer count excluding the hidden group bookkeeping records. */
    val visibleLayerCount: Int get() = layers.count { it.sectionType != SectionType.DIVIDER }

    val resolution: Resolution by lazy {
        val res = resources[ResourceId.RESOLUTION_INFO] ?: return@lazy Resolution.DEFAULT
        if (res.data.size < 16) return@lazy Resolution.DEFAULT
        val c = ByteCursor(res.data)
        val h = c.fixed16()
        c.skip(4)
        val v = c.fixed16()
        Resolution(if (h > 0) h else 72.0, if (v > 0) v else 72.0)
    }

    /** JPEG preview Photoshop stores in the resource block, when present. */
    val embeddedThumbnail: EmbeddedThumbnail? by lazy {
        val res = resources[ResourceId.THUMBNAIL] ?: resources[ResourceId.THUMBNAIL_V4] ?: return@lazy null
        if (res.data.size < 28) return@lazy null
        val c = ByteCursor(res.data)
        val format = c.i32()
        val w = c.i32()
        val h = c.i32()
        c.skip(4)
        c.skip(4)
        val compressed = c.i32()
        if (format != 1 || w <= 0 || h <= 0) return@lazy null
        val len = minOf(compressed, c.remaining - 4)
        if (len <= 0) return@lazy null
        c.skip(4)
        EmbeddedThumbnail(w, h, c.bytes(len))
    }

    /** Colour table for indexed images, as `[r0..r255, g0..g255, b0..b255]`. */
    val indexedPalette: ByteArray? get() =
        colorModeData.takeIf { colorMode == ColorMode.INDEXED && it.size >= 768 }

    override fun close() {
        source.close()
    }

    override fun toString(): String =
        "${header.formatName} ${width}x$height ${depth}-bit ${colorMode.label}, ${layers.size} layers"
}
