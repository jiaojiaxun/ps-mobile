package com.psmobile.psd.render

import com.psmobile.psd.PsdDocument
import com.psmobile.psd.model.BlendMode
import com.psmobile.psd.model.Layer
import com.psmobile.psd.model.LayerNode

/** One entry in the flattened draw order, with group visibility and opacity already folded in. */
class DrawItem(val layer: Layer, val opacity: Int, val blendMode: BlendMode, val clipping: Boolean)

/** Decides whether a layer participates in the composite; the panel's eye icons drive this. */
fun interface LayerVisibility {
    fun isVisible(layer: Layer): Boolean

    companion object {
        /** Visibility exactly as the document was saved. */
        val AS_SAVED = LayerVisibility { it.isVisible }
    }
}

/**
 * Composites the document from its layers.
 *
 * Two jobs. It stands in for files saved without "Maximize Compatibility", which carry no
 * flattened preview at all; and it takes over from the flattened preview the moment a layer is
 * shown or hidden, since the preview in the file can only ever reflect the state it was saved
 * in. It also backs the per-layer previews in the panel.
 *
 * Groups are flattened into a single draw order with their opacity folded into the children,
 * which matches Photoshop for pass-through groups and is a close approximation otherwise.
 */
class LayerRenderer(private val doc: PsdDocument) {

    val pyramid = TilePyramid(doc.width, doc.height)
    private val rasterizer = LayerRasterizer(doc)

    /** Draw order for the document as saved. */
    val drawOrder: List<DrawItem> = buildDrawOrder(LayerVisibility.AS_SAVED)

    val isAvailable: Boolean get() = drawOrder.isNotEmpty()

    /**
     * Bottom-to-top list of what to paint, with [visibility] applied at every level — hiding a
     * group takes its whole subtree with it.
     */
    fun buildDrawOrder(visibility: LayerVisibility): List<DrawItem> = buildList {
        flatten(doc.layerTree, parentVisible = true, parentOpacity = 255, visibility = visibility, out = this)
    }

    /**
     * @param maxSupersample samples per axis when a level covers more than one source pixel per
     *   output pixel. Matches the flattened-composite renderer so the two paths look alike.
     */
    fun newDecoder(
        order: List<DrawItem> = drawOrder,
        maxSupersample: Int = LayerImage.DEFAULT_SUPERSAMPLE,
    ): LayerRegionDecoder = LayerRegionDecoder(rasterizer, order, maxSupersample)

    /** Renders a single layer on its own, used for the layer-panel previews. */
    fun renderLayerThumbnail(layer: Layer, maxSize: Int): Thumbnail? {
        if (layer.isEmpty) return null
        val step = maxOf(
            1,
            maxOf(
                TilePyramid.ceilDiv(layer.width, maxSize),
                TilePyramid.ceilDiv(layer.height, maxSize),
            ),
        )
        val w = TilePyramid.ceilDiv(layer.width, step)
        val h = TilePyramid.ceilDiv(layer.height, step)
        if (w <= 0 || h <= 0) return null
        val image = rasterizer.imageFor(layer) ?: return null
        val pixels = IntArray(w * h)
        val ok = image.decodeInto(pixels, w, h, layer.left, layer.top, step)
        return if (ok) Thumbnail(w, h, pixels) else null
    }

    class Thumbnail(val width: Int, val height: Int, val pixels: IntArray)

    private fun flatten(
        nodes: List<LayerNode>,
        parentVisible: Boolean,
        parentOpacity: Int,
        visibility: LayerVisibility,
        out: MutableList<DrawItem>,
    ) {
        // Children are stored top-first, so walking backwards yields bottom-to-top draw order.
        for (node in nodes.asReversed()) {
            val layer = node.layer
            val visible = parentVisible && visibility.isVisible(layer)
            val opacity = parentOpacity * layer.opacity / 255
            if (node.isGroup) {
                flatten(node.children, visible, opacity, visibility, out)
            } else if (visible && !layer.isEmpty && !layer.isAdjustment) {
                out += DrawItem(layer, opacity * layer.fillOpacity / 255, layer.blendMode, layer.clipping)
            }
        }
    }
}

/**
 * Stateful decoder for [LayerRenderer]; one per worker thread.
 *
 * Holds a [LayerImage] per layer so that the scanline buffers and read-ahead windows survive
 * from tile to tile instead of being rebuilt for each one.
 */
class LayerRegionDecoder internal constructor(
    private val rasterizer: LayerRasterizer,
    private val drawOrder: List<DrawItem>,
    private val maxSupersample: Int = LayerImage.DEFAULT_SUPERSAMPLE,
) : TileDecoder {

    private val images = HashMap<Int, LayerImage?>(drawOrder.size.coerceAtMost(256))

    override fun decodeRegion(
        srcX: Int,
        srcY: Int,
        step: Int,
        outW: Int,
        outH: Int,
        out: IntArray,
        isCancelled: () -> Boolean,
    ): Boolean {
        if (outW <= 0 || outH <= 0) return false
        java.util.Arrays.fill(out, 0, outW * outH, 0)

        val regionRight = srcX + outW * step
        val regionBottom = srcY + outH * step

        for (item in drawOrder) {
            if (isCancelled()) return false
            val l = item.layer
            // Cheap rejection first: most layers of a long document miss any given tile.
            if (l.right <= srcX || l.left >= regionRight || l.bottom <= srcY || l.top >= regionBottom) continue

            val image = images.getOrPut(l.index) { rasterizer.imageFor(l, maxSupersample) } ?: continue
            image.blendInto(
                out = out,
                outW = outW,
                outH = outH,
                srcX = srcX,
                srcY = srcY,
                step = step,
                blendMode = item.blendMode,
                opacity = item.opacity,
                clipping = item.clipping,
                isCancelled = isCancelled,
            )
        }
        return true
    }
}
