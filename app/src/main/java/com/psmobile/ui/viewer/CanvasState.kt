package com.psmobile.ui.viewer

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.psmobile.psd.render.TilePyramid
import com.psmobile.settings.InitialZoom
import kotlin.math.max
import kotlin.math.min

/**
 * Pan and zoom for the canvas.
 *
 * The mapping is `screen = image * scale + translation`, kept in screen pixels so the tile
 * renderer can turn it straight into destination rectangles.
 */
@Stable
class CanvasState(
    val imageWidth: Int,
    val imageHeight: Int,
    private val pyramid: TilePyramid,
    private val zoomMode: InitialZoom = InitialZoom.AUTO,
) {
    var scale by mutableFloatStateOf(1f)
        private set
    var translationX by mutableFloatStateOf(0f)
        private set
    var translationY by mutableFloatStateOf(0f)
        private set

    private var viewWidth = 0f
    private var viewHeight = 0f
    private var initialised = false

    val displayWidth: Float get() = imageWidth * scale
    val displayHeight: Float get() = imageHeight * scale

    /** The scale at which the whole canvas is visible at once. */
    val fitScale: Float
        get() = if (viewWidth <= 0f || viewHeight <= 0f) 1f
        else min(viewWidth / imageWidth, viewHeight / imageHeight)

    /** The scale at which the canvas covers the viewport in both axes. */
    private val coverScale: Float
        get() = if (viewWidth <= 0f || viewHeight <= 0f) 1f
        else max(viewWidth / imageWidth, viewHeight / imageHeight)

    val minScale: Float get() = min(fitScale * 0.75f, 1f)
    val maxScale: Float get() = max(24f, fitScale * 4f)

    fun onViewportChanged(size: Size) {
        if (size.width <= 0f || size.height <= 0f) return
        val changed = size.width != viewWidth || size.height != viewHeight
        viewWidth = size.width
        viewHeight = size.height
        if (!initialised) {
            initialised = true
            reset()
        } else if (changed) {
            clamp()
        }
    }

    /**
     * The scale the document opens at, and the one both "Fit" and double-tap return to.
     *
     * Fitting the whole canvas is right for ordinary aspect ratios, but a 800 x 247 517 webtoon
     * strip fitted whole is six pixels wide — technically correct and completely useless. When
     * one axis collapses like that the canvas fills the viewport instead, anchored at the top,
     * which is how such a document is actually read.
     */
    val initialScale: Float
        get() {
            if (viewWidth <= 0f || viewHeight <= 0f) return 1f
            return when (zoomMode) {
                InitialZoom.FIT -> fitScale
                InitialZoom.ACTUAL -> 1f.coerceIn(minScale, maxScale)
                InitialZoom.FILL_WIDTH -> min(viewWidth / imageWidth, MAX_INITIAL_SCALE)
                InitialZoom.AUTO -> {
                    val fit = fitScale
                    val degenerate = imageWidth * fit < viewWidth * DEGENERATE_RATIO ||
                        imageHeight * fit < viewHeight * DEGENERATE_RATIO
                    if (degenerate) min(coverScale, MAX_INITIAL_SCALE) else fit
                }
            }
        }

    fun reset() {
        if (viewWidth <= 0f || viewHeight <= 0f) return
        scale = initialScale
        translationX = (viewWidth - displayWidth) / 2f
        translationY = if (displayHeight <= viewHeight) (viewHeight - displayHeight) / 2f else 0f
        clamp()
    }

    fun setTranslation(x: Float, y: Float) {
        translationX = x
        translationY = y
        clamp()
    }

    fun panBy(dx: Float, dy: Float) = setTranslation(translationX + dx, translationY + dy)

    /** Zooms around [focus], which stays put on screen. */
    fun zoomBy(factor: Float, focus: Offset) {
        val next = (scale * factor).coerceIn(minScale, maxScale)
        if (next == scale) return
        val k = next / scale
        translationX = focus.x - (focus.x - translationX) * k
        translationY = focus.y - (focus.y - translationY) * k
        scale = next
        clamp()
    }

    suspend fun animateZoomTo(target: Float, focus: Offset, durationMillis: Int = 280) {
        val from = scale
        val to = target.coerceIn(minScale, maxScale)
        if (from == to) return
        val fromX = translationX
        val fromY = translationY
        val k = to / from
        val toX = focus.x - (focus.x - fromX) * k
        val toY = focus.y - (focus.y - fromY) * k
        animate(0f, 1f, animationSpec = tween(durationMillis)) { t, _ ->
            scale = from + (to - from) * t
            translationX = fromX + (toX - fromX) * t
            translationY = fromY + (toY - fromY) * t
            clamp()
        }
    }

    /**
     * The "Fit" action.
     *
     * First press returns to the opening framing. For a canvas whose opening framing is not the
     * whole thing — a long strip fills the width instead — a second press pulls all the way out
     * so the entire document is on screen at once.
     */
    suspend fun animateToFit(durationMillis: Int = 320) {
        if (viewWidth <= 0f) return
        val atInitial = kotlin.math.abs(scale - initialScale) < initialScale * 0.02f
        val to = if (atInitial && fitScale < initialScale) fitScale else initialScale
        val from = scale
        val fromX = translationX
        val fromY = translationY
        val toX = (viewWidth - imageWidth * to) / 2f
        // Anchor on whatever is currently centred rather than jumping back to the top.
        val centreImageY = (viewHeight / 2f - fromY) / from
        val toY = (viewHeight / 2f - centreImageY * to)
            .let { if (imageHeight * to <= viewHeight) (viewHeight - imageHeight * to) / 2f else it }

        animate(0f, 1f, animationSpec = tween(durationMillis)) { t, _ ->
            scale = from + (to - from) * t
            translationX = fromX + (toX - fromX) * t
            translationY = fromY + (toY - fromY) * t
            clamp()
        }
    }

    /**
     * Brings a region of the document into view — used when a layer is picked from the panel.
     *
     * The zoom is chosen to frame the region with a margin, but is never allowed below the
     * opening framing: a full-canvas layer would otherwise pull the view out to a useless
     * sliver. Regions too large to fit are anchored at their top-left instead of centred, which
     * is what "take me to this layer" means for something that spans the whole canvas.
     */
    suspend fun focusOn(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        durationMillis: Int = 420,
    ) {
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val rectWidth = (right - left).coerceAtLeast(1)
        val rectHeight = (bottom - top).coerceAtLeast(1)

        val framing = initialScale
        val fitRegion = min(
            viewWidth / (rectWidth * FOCUS_MARGIN),
            viewHeight / (rectHeight * FOCUS_MARGIN),
        )
        val to = fitRegion.coerceIn(framing, min(maxScale, framing * MAX_FOCUS_MAGNIFICATION))

        val regionScreenWidth = rectWidth * to
        val regionScreenHeight = rectHeight * to
        val toX = if (regionScreenWidth <= viewWidth) {
            (viewWidth - regionScreenWidth) / 2f - left * to
        } else {
            FOCUS_EDGE_PADDING - left * to
        }
        val toY = if (regionScreenHeight <= viewHeight) {
            (viewHeight - regionScreenHeight) / 2f - top * to
        } else {
            FOCUS_EDGE_PADDING - top * to
        }

        val from = scale
        val fromX = translationX
        val fromY = translationY
        animate(0f, 1f, animationSpec = tween(durationMillis)) { t, _ ->
            scale = from + (to - from) * t
            translationX = fromX + (toX - fromX) * t
            translationY = fromY + (toY - fromY) * t
            clamp()
        }
    }

    /** Where a double-tap should land: in to a useful working zoom, or back out to the framing. */
    fun doubleTapTarget(): Float {
        val base = initialScale
        val zoomedIn = scale > base * 1.05f
        return if (zoomedIn) base else max(1f, base * 2.5f).coerceAtMost(maxScale)
    }

    /** Keeps the canvas anchored to the viewport, centring it whenever it is smaller. */
    fun clamp() {
        val dw = displayWidth
        val dh = displayHeight
        translationX = if (dw <= viewWidth) (viewWidth - dw) / 2f
        else translationX.coerceIn(viewWidth - dw, 0f)
        translationY = if (dh <= viewHeight) (viewHeight - dh) / 2f
        else translationY.coerceIn(viewHeight - dh, 0f)
    }

    /** Level of detail matching the current zoom. */
    val level: Int get() = pyramid.levelForScale(scale)

    private companion object {
        /** Below this share of the viewport, a fitted axis has collapsed to a useless sliver. */
        const val DEGENERATE_RATIO = 0.25f

        /** Filling the viewport must not blow a narrow canvas up past a sensible working zoom. */
        const val MAX_INITIAL_SCALE = 4f

        /** Breathing room around a focused region, as a multiple of its size. */
        const val FOCUS_MARGIN = 1.3f

        /** A small text layer should get closer, but not so close it loses all context. */
        const val MAX_FOCUS_MAGNIFICATION = 2.5f

        /** Gap left at the edge when a focused region is too big to centre. */
        const val FOCUS_EDGE_PADDING = 24f
    }
}
