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

    fun newDecoder(order: List<DrawItem> = drawOrder): LayerRegionDecoder =
        LayerRegionDecoder(doc, rasterizer, order)

    /** Renders a single layer on its own, used for the layer-panel thumbnails. */
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
        val pixels = IntArray(w * h)
        val ok = rasterizer.decodeLayer(layer, layer.left, layer.top, step, w, h, pixels)
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

/** Stateful decoder for [LayerRenderer]; one per worker thread. */
class LayerRegionDecoder internal constructor(
    private val doc: PsdDocument,
    private val rasterizer: LayerRasterizer,
    private val drawOrder: List<DrawItem>,
) : TileDecoder {
    private var scratch = IntArray(0)

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
        if (scratch.size < outW * outH) scratch = IntArray(outW * outH)

        val regionRight = srcX + outW * step
        val regionBottom = srcY + outH * step

        for (item in drawOrder) {
            if (isCancelled()) return false
            val l = item.layer
            if (l.right <= srcX || l.left >= regionRight || l.bottom <= srcY || l.top >= regionBottom) continue
            if (!rasterizer.decodeLayer(l, srcX, srcY, step, outW, outH, scratch, isCancelled)) continue

            val n = outW * outH
            val mode = item.blendMode
            val opacity = item.opacity
            if (mode == BlendMode.NORMAL && opacity == 255 && !item.clipping) {
                for (i in 0 until n) {
                    val s = scratch[i]
                    if (s ushr 24 == 0) continue
                    out[i] = if (s ushr 24 == 0xFF) s else Blending.composite(s, out[i], mode, 255, false)
                }
            } else {
                for (i in 0 until n) {
                    val s = scratch[i]
                    if (s ushr 24 == 0) continue
                    out[i] = Blending.composite(s, out[i], mode, opacity, item.clipping)
                }
            }
        }
        return true
    }
}
