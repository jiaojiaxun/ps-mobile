package com.psmobile.document

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.psmobile.psd.PsdDocument
import com.psmobile.psd.model.Layer
import com.psmobile.psd.model.LayerNode
import com.psmobile.psd.render.LayerRenderer
import com.psmobile.psd.render.LayerVisibility
import java.io.Closeable

/** A rectangle in document coordinates. */
data class DocumentRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/**
 * Everything that stays alive while a document is on screen: the parsed structure, the open
 * file handle behind it, and the tile engine that reads from it.
 *
 * Closing the session closes the file, so nothing may outlive it — in particular the bitmaps
 * handed to the canvas are dropped by [TileEngine.release] first.
 */
class DocumentSession(
    val document: PsdDocument,
    val uri: Uri?,
    val displayName: String,
    /** Set for documents synthesised by "New document" rather than read from disk. */
    val isScratch: Boolean = false,
    supersample: Int = 4,
) : Closeable {

    val tiles = TileEngine(document, supersample = supersample)

    /** Only built when something actually asks for a per-layer preview. */
    val layerRenderer: LayerRenderer by lazy { LayerRenderer(document) }

    val width: Int get() = document.width
    val height: Int get() = document.height

    /**
     * Layers the viewer has been told to show or hide, keyed by record index.
     *
     * Only entries that disagree with the document are kept, so an empty map means "exactly as
     * saved" — which is what lets the viewer go back to the fast flattened preview.
     */
    private val visibilityOverrides = mutableStateMapOf<Int, Boolean>()

    /** Bumped on every visibility change so the panel and canvas recompose. */
    var visibilityRevision by mutableStateOf(0)
        private set

    val hasVisibilityOverrides: Boolean get() = visibilityOverrides.isNotEmpty()

    fun isVisible(layer: Layer): Boolean = visibilityOverrides[layer.index] ?: layer.isVisible

    /**
     * Flips one layer and rebuilds the render source.
     *
     * The flattened preview in the file can only show the state the document was saved in, so
     * the first change moves rendering onto the live layer compositor; undoing every change
     * moves it back.
     */
    suspend fun toggleVisibility(layer: Layer) {
        val next = !isVisible(layer)
        if (next == layer.isVisible) visibilityOverrides.remove(layer.index)
        else visibilityOverrides[layer.index] = next

        val override = if (visibilityOverrides.isEmpty()) {
            null
        } else {
            val snapshot = visibilityOverrides.toMap()
            LayerVisibility { snapshot[it.index] ?: it.isVisible }
        }
        tiles.setVisibilityOverride(override)
        visibilityRevision++
        tiles.primeOverview(force = true)
    }

    suspend fun resetVisibility() {
        if (visibilityOverrides.isEmpty()) return
        visibilityOverrides.clear()
        tiles.setVisibilityOverride(null)
        visibilityRevision++
        tiles.primeOverview(force = true)
    }

    /**
     * Where a layer sits on the canvas. Groups carry no bounds of their own, so they report the
     * union of everything inside them.
     */
    fun boundsOf(node: LayerNode): DocumentRect? {
        if (!node.isGroup) {
            val l = node.layer
            return if (l.isEmpty) null else DocumentRect(l.left, l.top, l.right, l.bottom)
        }
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        fun walk(nodes: List<LayerNode>) {
            for (child in nodes) {
                val l = child.layer
                if (!child.isGroup && !l.isEmpty) {
                    left = minOf(left, l.left)
                    top = minOf(top, l.top)
                    right = maxOf(right, l.right)
                    bottom = maxOf(bottom, l.bottom)
                }
                walk(child.children)
            }
        }
        walk(node.children)
        if (left > right || top > bottom) return null
        return DocumentRect(left, top, right, bottom)
    }

    fun layerThumbnail(layer: Layer, maxSize: Int): LayerRenderer.Thumbnail? =
        runCatching { layerRenderer.renderLayerThumbnail(layer, maxSize) }.getOrNull()

    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        tiles.release()
        runCatching { document.close() }
    }
}
