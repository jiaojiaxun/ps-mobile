package com.psmobile.ui.viewer

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import com.psmobile.document.DocumentRect
import com.psmobile.document.TileEngine
import com.psmobile.psd.render.TileKey
import com.psmobile.ui.components.drawCheckerboard
import com.psmobile.ui.theme.PsColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.launch

/**
 * The document surface.
 *
 * Nothing here holds the whole image. Every frame works out which tiles of which pyramid level
 * cover the viewport, paints the ones already decoded — coarsest first, so a finer tile simply
 * lands on top when it arrives — and asks the engine for the rest. A 198-megapixel canvas and a
 * 2-megapixel one cost the same to draw.
 */
@Composable
fun DocumentCanvas(
    tiles: TileEngine,
    state: CanvasState,
    hasTransparency: Boolean,
    surroundColor: Color,
    smoothScaling: Boolean,
    onInteraction: () -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
    /** Region to outline briefly, set when the canvas jumps to a layer. */
    highlight: DocumentRect? = null,
) {
    val scope = rememberCoroutineScope()

    // Keep the last region around so the outline can fade out rather than vanish.
    var lastHighlight by remember { mutableStateOf<DocumentRect?>(null) }
    if (highlight != null) lastHighlight = highlight
    val highlightAlpha by animateFloatAsState(
        targetValue = if (highlight != null) 1f else 0f,
        animationSpec = tween(durationMillis = if (highlight != null) 140 else 500),
        label = "highlight",
    )
    val paint = remember {
        Paint().apply {
            isAntiAlias = false
            isDither = false
        }
    }
    val fling = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val decay = remember { exponentialDecay<Offset>(frictionMultiplier = 1.4f) }

    Canvas(
        modifier = modifier
            .onSizeChanged { state.onViewportChanged(Size(it.width.toFloat(), it.height.toFloat())) }
            .pointerInput(state) {
                detectTapGestures(
                    onTap = { onToggleChrome() },
                    onDoubleTap = { position ->
                        onInteraction()
                        scope.launch { state.animateZoomTo(state.doubleTapTarget(), position) }
                    },
                )
            }
            .pointerInput(state) {
                awaitEachGesture {
                    val tracker = VelocityTracker()
                    val first = awaitFirstDown(requireUnconsumed = false)
                    scope.launch { fling.stop() }
                    tracker.addPosition(first.uptimeMillis, first.position)
                    onInteraction()

                    var pointerCount = 1
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val active = event.changes.filter { it.pressed }
                        pressed = active.isNotEmpty()
                        pointerCount = active.size

                        if (event.changes.any { it.positionChanged() }) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f && zoom > 0f) {
                                state.zoomBy(zoom, event.calculateCentroid(useCurrent = true))
                            }
                            if (pan != Offset.Zero) state.panBy(pan.x, pan.y)

                            // Momentum only makes sense for a single dragging finger.
                            if (pointerCount == 1) {
                                active.firstOrNull()?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                            } else {
                                tracker.resetTracking()
                            }
                            event.changes.forEach { it.consume() }
                        }
                    }

                    val velocity = runCatching { tracker.calculateVelocity() }.getOrDefault(Velocity.Zero)
                    if (pointerCount <= 1 && (abs(velocity.x) > FLING_THRESHOLD || abs(velocity.y) > FLING_THRESHOLD)) {
                        scope.launch {
                            fling.snapTo(Offset(state.translationX, state.translationY))
                            fling.animateDecay(Offset(velocity.x, velocity.y), decay) {
                                state.setTranslation(value.x, value.y)
                            }
                        }
                    }
                }
            },
    ) {
        // Reading the counter makes a freshly decoded tile invalidate this draw pass.
        @Suppress("UNUSED_EXPRESSION")
        tiles.generation

        drawRect(surroundColor)
        // Everything the document paints is confined to the canvas rectangle. Coarse pyramid
        // levels round their edge tiles up — level 9 of an 800 px-wide canvas is two tile
        // pixels, i.e. 1024 source pixels — so without this clip the last tile would smear
        // past the right and bottom edges when zoomed out.
        withDocumentClip(state) {
            drawDocumentSurface(hasTransparency)
            drawTiles(tiles, state, paint, smoothScaling)
        }
        // Drawn outside the clip so the stroke is not halved at the canvas edge.
        if (highlightAlpha > 0.01f) {
            lastHighlight?.let { drawHighlight(state, it, highlightAlpha) }
        }
    }
}

/** Photoshop-style marquee around the region the viewer was just sent to. */
private fun DrawScope.drawHighlight(state: CanvasState, region: DocumentRect, alpha: Float) {
    val left = region.left * state.scale + state.translationX
    val top = region.top * state.scale + state.translationY
    val width = region.width * state.scale
    val height = region.height * state.scale
    if (width <= 0f || height <= 0f) return

    drawRect(
        color = PsColors.Accent.copy(alpha = 0.14f * alpha),
        topLeft = Offset(left, top),
        size = Size(width, height),
    )
    drawRect(
        color = PsColors.Accent.copy(alpha = alpha),
        topLeft = Offset(left, top),
        size = Size(width, height),
        style = Stroke(width = 2f),
    )
}

/** Runs [block] clipped to the on-screen rectangle the canvas actually occupies. */
private inline fun DrawScope.withDocumentClip(state: CanvasState, block: DrawScope.() -> Unit) {
    val left = state.translationX
    val top = state.translationY
    val right = left + state.displayWidth
    val bottom = top + state.displayHeight
    if (right <= 0f || bottom <= 0f || left >= size.width || top >= size.height) return

    clipRect(
        left = left.coerceAtLeast(0f),
        top = top.coerceAtLeast(0f),
        right = right.coerceAtMost(size.width),
        bottom = bottom.coerceAtMost(size.height),
    ) {
        block()
    }
}

private const val FLING_THRESHOLD = 80f

/** What shows through wherever the artwork is transparent, or has not decoded yet. */
private fun DrawScope.drawDocumentSurface(hasTransparency: Boolean) {
    if (hasTransparency) drawCheckerboard(cell = 11f) else drawRect(PsColors.Ink600)
}

/**
 * Paints every level from the coarsest fallback up to the level that matches the current zoom.
 *
 * Finer tiles overpaint coarser ones, so a pending tile shows a blurrier version of itself
 * rather than a hole, and the picture sharpens in place as decoding catches up.
 */
private fun DrawScope.drawTiles(
    tiles: TileEngine,
    state: CanvasState,
    paint: Paint,
    smoothScaling: Boolean,
) {
    val pyramid = tiles.pyramid
    val target = state.level
    val fallback = minOf(target + FALLBACK_GAP, pyramid.maxLevel)
    val tileSize = pyramid.tileSize
    val scale = state.scale
    val tx = state.translationX
    val ty = state.translationY

    // Photoshop interpolates smoothly at ordinary zoom levels and only shows hard pixel edges
    // once you are far enough in to be inspecting individual pixels. Filtering has to stay on
    // through magnification too: a 800 px canvas filling a 1440 px screen is already 1.8x, and
    // nearest-neighbour there produces exactly the staircased edges the artwork does not have.
    paint.isFilterBitmap = smoothScaling && scale < PIXEL_INSPECTION_ZOOM

    val order = when {
        target >= pyramid.maxLevel -> intArrayOf(pyramid.maxLevel)
        fallback >= pyramid.maxLevel -> intArrayOf(pyramid.maxLevel, target)
        else -> intArrayOf(pyramid.maxLevel, fallback, target)
    }

    val requested = ArrayList<TileKey>(24)
    val fallbackRequests = ArrayList<TileKey>(8)
    val destination = RectF()
    val canvas = drawContext.canvas.nativeCanvas

    for (level in order) {
        val step = pyramid.step(level)
        val cols = pyramid.cols(level)
        val rows = pyramid.rows(level)
        if (cols <= 0 || rows <= 0) continue

        // One tile of margin on the working level keeps panning ahead of the edge.
        val margin = if (level == target) 1 else 0
        val span = tileSize * step * scale
        if (span <= 0f) continue
        val col0 = (floor(-tx / span).toInt() - margin).coerceIn(0, cols - 1)
        val col1 = (ceil((size.width - tx) / span).toInt() + margin).coerceIn(0, cols - 1)
        val row0 = (floor(-ty / span).toInt() - margin).coerceIn(0, rows - 1)
        val row1 = (ceil((size.height - ty) / span).toInt() + margin).coerceIn(0, rows - 1)

        for (row in row0..row1) {
            for (col in col0..col1) {
                val key = TileKey(level, col, row)
                when (level) {
                    target -> requested += key
                    fallback -> fallbackRequests += key
                }
                val bitmap = tiles.tile(key) ?: continue

                val left = col * tileSize * step * scale + tx
                val top = row * tileSize * step * scale + ty
                // Neighbouring tiles derive their shared edge from the same expression, so
                // the rectangles meet exactly and need no overlap to hide a gap.
                destination.set(
                    left,
                    top,
                    left + bitmap.width * step * scale,
                    top + bitmap.height * step * scale,
                )
                canvas.drawBitmap(bitmap, null, destination, paint)
            }
        }
    }

    // Detail first, the blurrier safety net after it.
    requested += fallbackRequests
    tiles.request(requested)
}

/** Levels between the working level and its safety net; two means a 4x blurrier stand-in. */
private const val FALLBACK_GAP = 2

/** Past this zoom the point is to look at the pixels themselves, so interpolation stops. */
private const val PIXEL_INSPECTION_ZOOM = 5f
