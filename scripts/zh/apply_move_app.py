#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Layer move: app part.

Tiles/DocumentSession own the state, DocumentCanvas splits the gesture,
ViewerScreen shows the move bar, LayersPanel gains the entry button.

A committed move must force the LAYERS path: with a flattened preview in the
file and no visibility override, `effective` would otherwise collapse to null
and the composite would silently discard the offsets (caught in review).

Every anchor must match exactly once; anything else prints ::error and exits 1.
"""
import pathlib
import sys

failures = []


def replace(name, path, anchor, replacement):
    p = pathlib.Path(path)
    text = p.read_text(encoding="utf-8")
    if replacement in text and anchor not in text:
        print(f"[{name}] already applied")
        return
    count = text.count(anchor)
    if count != 1:
        print(f"::error file={path}::[{name}] anchor matched {count} times (expected 1)")
        failures.append(name)
        return
    p.write_text(text.replace(anchor, replacement, 1), encoding="utf-8")
    print(f"[{name}] patched")


TE = "app/src/main/java/com/psmobile/document/TileEngine.kt"
DS = "app/src/main/java/com/psmobile/document/DocumentSession.kt"
DC = "app/src/main/java/com/psmobile/ui/viewer/DocumentCanvas.kt"
VS = "app/src/main/java/com/psmobile/ui/viewer/ViewerScreen.kt"
PN = "app/src/main/java/com/psmobile/ui/viewer/Panels.kt"

# --- TileEngine -----------------------------------------------------------

replace(
    "te-import",
    TE,
    """import com.psmobile.psd.render.LayerRenderer
import com.psmobile.psd.render.LayerVisibility""",
    """import com.psmobile.psd.render.LayerOffsets
import com.psmobile.psd.render.LayerRenderer
import com.psmobile.psd.render.LayerVisibility""",
)

replace(
    "te-set-visibility",
    TE,
    """    fun setVisibilityOverride(visibility: LayerVisibility?) {
        // With no flattened preview in the file there is nothing to fall back to, so the
        // as-saved visibility still has to go through the layer compositor.
        val effective = visibility ?: if (compositeUnavailable) LayerVisibility.AS_SAVED else null
        val nextOrder = effective?.let { layerRenderer.buildDrawOrder(it) }
        val nextSource = if (nextOrder != null) Source.LAYERS else Source.COMPOSITE
        if (nextSource == Source.COMPOSITE && source == Source.COMPOSITE) return

        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        drawOrder = nextOrder
        source = nextSource
        // A fresh pool: decoders hold the previous draw order and their own scanline buffers.
        decoders = newDecoderPool()
        cache.evictAll()
        // The pinned coarse level is deliberately kept: it is now stale, but showing the old
        // blurry version while the new one decodes reads far better than a blank canvas.
        // primeOverview(force = true) replaces it.
        generation++
    }""",
    """    /** Current visibility rule, kept so an offset change can rebuild from the same state. */
    private var visibilityOverride: LayerVisibility? = null

    /** Current per-layer translations, packed as `dx shl 32 or dy`. */
    private var layerOffsets: Map<Int, Long> = emptyMap()

    fun setVisibilityOverride(visibility: LayerVisibility?) {
        visibilityOverride = visibility
        rebuildDrawOrder()
    }

    /** Replaces the per-layer translations; an empty map means "exactly as saved". */
    fun setLayerOffsets(offsets: Map<Int, Long>) {
        layerOffsets = offsets
        rebuildDrawOrder()
    }

    private fun rebuildDrawOrder() {
        // With no flattened preview in the file there is nothing to fall back to, so the
        // as-saved visibility still has to go through the layer compositor. A committed move
        // has the same requirement: the flattened preview cannot show it.
        val effective = when {
            visibilityOverride != null -> visibilityOverride
            layerOffsets.isNotEmpty() -> LayerVisibility.AS_SAVED
            compositeUnavailable -> LayerVisibility.AS_SAVED
            else -> null
        }
        val offsets: LayerOffsets = if (layerOffsets.isEmpty()) {
            LayerOffsets.NONE
        } else {
            val snapshot = layerOffsets
            LayerOffsets { snapshot[it.index] }
        }
        val nextOrder = effective?.let { layerRenderer.buildDrawOrder(it, offsets) }
        val nextSource = if (nextOrder != null) Source.LAYERS else Source.COMPOSITE
        if (nextSource == Source.COMPOSITE && source == Source.COMPOSITE) return

        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        drawOrder = nextOrder
        source = nextSource
        // A fresh pool: decoders hold the previous draw order and their own scanline buffers.
        decoders = newDecoderPool()
        cache.evictAll()
        // The pinned coarse level is deliberately kept: it is now stale, but showing the old
        // blurry version while the new one decodes reads far better than a blank canvas.
        // primeOverview(force = true) replaces it.
        generation++
    }""",
)

# --- DocumentSession ------------------------------------------------------

replace(
    "ds-import-float",
    DS,
    "import androidx.compose.runtime.mutableStateMapOf",
    "import androidx.compose.runtime.mutableFloatStateOf\nimport androidx.compose.runtime.mutableStateMapOf",
)

replace(
    "ds-import-offsets",
    DS,
    """import com.psmobile.psd.render.LayerRenderer
import com.psmobile.psd.render.LayerVisibility""",
    """import com.psmobile.psd.render.LayerOffsets
import com.psmobile.psd.render.LayerRenderer
import com.psmobile.psd.render.LayerVisibility""",
)

replace(
    "ds-import-round",
    DS,
    "import java.io.Closeable",
    "import java.io.Closeable\nimport kotlin.math.roundToInt",
)

replace(
    "ds-state",
    DS,
    """    /** Bumped on every visibility change so the panel and canvas recompose. */
    var visibilityRevision by mutableStateOf(0)
        private set""",
    """    /** Bumped on every visibility change so the panel and canvas recompose. */
    var visibilityRevision by mutableStateOf(0)
        private set

    /** Committed translations by layer record index, packed as `dx shl 32 or dy`. */
    private val layerOffsets = mutableStateMapOf<Int, Long>()

    /** The layer the move tool is dragging, or null when no move is in progress. */
    var moveTarget: Layer? by mutableStateOf(null)
        private set

    /** Live drag distance for [moveTarget], in document pixels, not yet committed. */
    var moveDeltaX by mutableFloatStateOf(0f)
        private set
    var moveDeltaY by mutableFloatStateOf(0f)
        private set

    val hasLayerOffsets: Boolean get() = layerOffsets.isNotEmpty()""",
)

replace(
    "ds-apply-render",
    DS,
    """    private fun applyVisibility() {
        val override = if (visibilityOverrides.isEmpty()) {
            null
        } else {
            val snapshot = visibilityOverrides.toMap()
            LayerVisibility { snapshot[it.index] ?: it.isVisible }
        }
        visibilityRevision++

        visibilityJob?.cancel()
        visibilityJob = scope.launch {
            // Rebuilding the draw order and re-priming both belong off the main thread; the
            // caller only ever sees the state flip, which is what keeps the panel responsive.
            tiles.setVisibilityOverride(override)
            tiles.primeOverview(force = true)
        }
    }""",
    """    private fun applyVisibility() = applyRender()

    /**
     * Re-composites with the current visibility rule and layer translations.
     *
     * The panel flips immediately; the re-render runs off the main thread and cancels whatever
     * the previous edit started, so several edits in a row never pile up work.
     */
    private fun applyRender() {
        val override = if (visibilityOverrides.isEmpty()) {
            null
        } else {
            val snapshot = visibilityOverrides.toMap()
            LayerVisibility { snapshot[it.index] ?: it.isVisible }
        }
        val offsets = layerOffsets.toMap()
        visibilityRevision++

        visibilityJob?.cancel()
        visibilityJob = scope.launch {
            // Rebuilding the draw order and re-priming both belong off the main thread.
            tiles.setVisibilityOverride(override)
            tiles.setLayerOffsets(offsets)
            tiles.primeOverview(force = true)
        }
    }""",
)

replace(
    "ds-move-api",
    DS,
    """    fun resetVisibility() {
        if (visibilityOverrides.isEmpty()) return
        visibilityOverrides.clear()
        applyVisibility()
    }""",
    """    fun resetVisibility() {
        if (visibilityOverrides.isEmpty()) return
        visibilityOverrides.clear()
        applyVisibility()
    }

    /** Starts dragging [layer]; the canvas shows a frame and the drag commits on release. */
    fun beginMove(layer: Layer) {
        moveTarget = layer
        moveDeltaX = 0f
        moveDeltaY = 0f
    }

    /** Accumulates a drag, in document pixels. */
    fun dragMoveBy(dx: Float, dy: Float) {
        if (moveTarget == null) return
        moveDeltaX += dx
        moveDeltaY += dy
    }

    /** Commits the drag and re-composites once. */
    fun commitMove() {
        val layer = moveTarget ?: return
        val existing = layerOffsets[layer.index]
        val dx = (if (existing == null) 0 else LayerOffsets.dxOf(existing)) + moveDeltaX.roundToInt()
        val dy = (if (existing == null) 0 else LayerOffsets.dyOf(existing)) + moveDeltaY.roundToInt()
        if (dx == 0 && dy == 0) layerOffsets.remove(layer.index)
        else layerOffsets[layer.index] = LayerOffsets.pack(dx, dy)
        moveTarget = null
        moveDeltaX = 0f
        moveDeltaY = 0f
        applyRender()
    }

    /** Drops an uncommitted drag. */
    fun cancelMove() {
        moveTarget = null
        moveDeltaX = 0f
        moveDeltaY = 0f
    }

    /** Puts every layer back where the file has it. */
    fun resetLayerOffsets() {
        if (layerOffsets.isEmpty()) return
        layerOffsets.clear()
        moveTarget = null
        moveDeltaX = 0f
        moveDeltaY = 0f
        applyRender()
    }""",
)

# --- DocumentCanvas -------------------------------------------------------

replace(
    "dc-import-state",
    DC,
    """import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope""",
    """import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState""",
)

replace(
    "dc-import-layer",
    DC,
    """import com.psmobile.document.DocumentRect
import com.psmobile.document.TileEngine""",
    """import com.psmobile.document.DocumentRect
import com.psmobile.document.TileEngine
import com.psmobile.psd.model.Layer""",
)

replace(
    "dc-params",
    DC,
    """    modifier: Modifier = Modifier,
    /** Region to outline briefly, set when the canvas jumps to a layer. */
    highlight: DocumentRect? = null,
) {""",
    """    modifier: Modifier = Modifier,
    /** Region to outline briefly, set when the canvas jumps to a layer. */
    highlight: DocumentRect? = null,
    /** When set, a one-finger drag moves this layer instead of panning the canvas. */
    moveTarget: Layer? = null,
    moveDeltaX: Float = 0f,
    moveDeltaY: Float = 0f,
    onDragLayer: (Float, Float) -> Unit = { _, _ -> },
) {""",
)

replace(
    "dc-scope",
    DC,
    "    val scope = rememberCoroutineScope()",
    """    val scope = rememberCoroutineScope()

    // The gesture detector is keyed on the canvas state, so these two have to be read through
    // a state holder: otherwise the detector keeps the values from when it was first attached.
    val activeMoveTarget by rememberUpdatedState(moveTarget)
    val activeDragLayer by rememberUpdatedState(onDragLayer)""",
)

replace(
    "dc-gesture",
    DC,
    "                            if (pan != Offset.Zero) state.panBy(pan.x, pan.y)",
    """                            if (pan != Offset.Zero) {
                                val moving = activeMoveTarget
                                if (moving != null && pointerCount == 1) {
                                    activeDragLayer(pan.x / state.scale, pan.y / state.scale)
                                } else {
                                    state.panBy(pan.x, pan.y)
                                }
                            }""",
)

replace(
    "dc-fling",
    DC,
    "                    if (pointerCount <= 1 && (abs(velocity.x) > FLING_THRESHOLD || abs(velocity.y) > FLING_THRESHOLD)) {",
    """                    if (activeMoveTarget == null && pointerCount <= 1 &&
                        (abs(velocity.x) > FLING_THRESHOLD || abs(velocity.y) > FLING_THRESHOLD)
                    ) {""",
)

replace(
    "dc-draw",
    DC,
    """        // Drawn outside the clip so the stroke is not halved at the canvas edge.
        if (highlightAlpha > 0.01f) {
            lastHighlight?.let { drawHighlight(state, it, highlightAlpha) }
        }""",
    """        // Drawn outside the clip so the stroke is not halved at the canvas edge.
        if (highlightAlpha > 0.01f) {
            lastHighlight?.let { drawHighlight(state, it, highlightAlpha) }
        }
        // The move frame follows the finger; the pixels only move on release.
        moveTarget?.let { drawMoveOutline(state, it, moveDeltaX, moveDeltaY) }""",
)

replace(
    "dc-outline-fn",
    DC,
    "/** Runs [block] clipped to the on-screen rectangle the canvas actually occupies. */",
    """/** Outline around a layer being dragged, drawn where it will land. */
private fun DrawScope.drawMoveOutline(state: CanvasState, layer: Layer, dx: Float, dy: Float) {
    val left = (layer.left + dx) * state.scale + state.translationX
    val top = (layer.top + dy) * state.scale + state.translationY
    val width = layer.width * state.scale
    val height = layer.height * state.scale
    if (width <= 0f || height <= 0f) return

    drawRect(
        color = PsColors.Accent.copy(alpha = 0.10f),
        topLeft = Offset(left, top),
        size = Size(width, height),
    )
    drawRect(
        color = PsColors.Accent,
        topLeft = Offset(left, top),
        size = Size(width, height),
        style = Stroke(width = 2f),
    )
}

/** Runs [block] clipped to the on-screen rectangle the canvas actually occupies. */""",
)

# --- ViewerScreen ---------------------------------------------------------

replace(
    "vs-import-openwith",
    VS,
    """import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers""",
    """import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.OpenWith""",
)

replace(
    "vs-canvas-call",
    VS,
    """            highlight = highlight,
            onInteraction = { zoomBadgeToken++ },""",
    """            highlight = highlight,
            moveTarget = session.moveTarget,
            moveDeltaX = session.moveDeltaX,
            moveDeltaY = session.moveDeltaY,
            onDragLayer = session::dragMoveBy,
            onInteraction = { zoomBadgeToken++ },""",
)

replace(
    "vs-move-bar",
    VS,
    """        AnimatedVisibility(
            visible = zoomBadgeVisible,""",
    """        val movingLayer = session.moveTarget
        if (movingLayer != null) {
            MoveBar(
                layerName = movingLayer.name,
                onDone = session::commitMove,
                onCancel = session::cancelMove,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 60.dp, start = 16.dp, end = 16.dp),
            )
        }

        AnimatedVisibility(
            visible = zoomBadgeVisible,""",
)

replace(
    "vs-panel-call",
    VS,
    """            onToggleVisibility = session::toggleVisibility,
            onResetVisibility = session::resetVisibility,
            onDismiss = { panel = ViewerPanel.NONE },""",
    """            onToggleVisibility = session::toggleVisibility,
            onResetVisibility = session::resetVisibility,
            onStartMove = {
                panel = ViewerPanel.NONE
                session.beginMove(it)
            },
            onDismiss = { panel = ViewerPanel.NONE },""",
)

replace(
    "vs-movebar-fn",
    VS,
    """@Composable
private fun Notice(text: String) {""",
    """/** Shown while a layer is being dragged: what is moving, and how to finish. */
@Composable
private fun MoveBar(
    layerName: String,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(PsColors.Ink800.copy(alpha = 0.94f))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.OpenWith, null, tint = PsColors.Accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.move_hint, layerName),
            style = MaterialTheme.typography.bodySmall,
            color = PsColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        TextButton(onClick = onCancel) {
            Text(
                stringResource(R.string.move_cancel),
                style = MaterialTheme.typography.labelSmall,
                color = PsColors.TextTertiary,
            )
        }
        TextButton(onClick = onDone) {
            Text(
                stringResource(R.string.move_done),
                style = MaterialTheme.typography.labelSmall,
                color = PsColors.Accent,
            )
        }
    }
}

@Composable
private fun Notice(text: String) {""",
)

# --- LayersPanel ----------------------------------------------------------

replace(
    "pn-import-openwith",
    PN,
    """import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.TextFields""",
    """import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.TextFields""",
)

replace(
    "pn-panel-signature",
    PN,
    """fun LayersPanel(
    session: DocumentSession,
    onFocusLayer: (LayerNode) -> Unit,
    onToggleVisibility: (Layer) -> Unit,
    onResetVisibility: () -> Unit,
    onDismiss: () -> Unit,
) {""",
    """fun LayersPanel(
    session: DocumentSession,
    onFocusLayer: (LayerNode) -> Unit,
    onToggleVisibility: (Layer) -> Unit,
    onResetVisibility: () -> Unit,
    onStartMove: (Layer) -> Unit,
    onDismiss: () -> Unit,
) {""",
)

replace(
    "pn-row-call",
    PN,
    """                    LayerRowItem(
                        session = session,
                        row = row,
                        collapsed = collapsed[row.node.layer.index] == true,""",
    """                    LayerRowItem(
                        session = session,
                        row = row,
                        collapsed = collapsed[row.node.layer.index] == true,
                        onStartMove = { onStartMove(row.node.layer) },""",
)

replace(
    "pn-row-signature",
    PN,
    """private fun LayerRowItem(
    session: DocumentSession,
    row: LayerRow,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onFocus: () -> Unit,
    onToggleVisibility: () -> Unit,
) {""",
    """private fun LayerRowItem(
    session: DocumentSession,
    row: LayerRow,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onFocus: () -> Unit,
    onToggleVisibility: () -> Unit,
    onStartMove: () -> Unit,
) {""",
)

replace(
    "pn-move-button",
    PN,
    """        if (row.node.isGroup) {
            IconButton(onClick = onToggleCollapse, modifier = Modifier.size(34.dp)) {""",
    """        if (!row.node.isGroup && !layer.isEmpty) {
            IconButton(onClick = onStartMove, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Rounded.OpenWith,
                    stringResource(R.string.move_layer),
                    tint = PsColors.TextTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        if (row.node.isGroup) {
            IconButton(onClick = onToggleCollapse, modifier = Modifier.size(34.dp)) {""",
)

# --- Strings in all three locales ----------------------------------------

RU_MOVE = {
    "move_layer": "\u041f\u0435\u0440\u0435\u043c\u0435\u0441\u0442\u0438\u0442\u044c \u0441\u043b\u043e\u0439",
    "move_hint": "\u041f\u0435\u0440\u0435\u043c\u0435\u0449\u0435\u043d\u0438\u0435: %1$s",
    "move_done": "\u0413\u043e\u0442\u043e\u0432\u043e",
    "move_cancel": "\u041e\u0442\u043c\u0435\u043d\u0430",
}
EN_MOVE = {
    "move_layer": "Move layer",
    "move_hint": "Moving: %1$s",
    "move_done": "Done",
    "move_cancel": "Cancel",
}
ZH_MOVE = {
    "move_layer": "\u79fb\u52a8\u56fe\u5c42",
    "move_hint": "\u79fb\u52a8\u56fe\u5c42\uff1a%1$s",
    "move_done": "\u5b8c\u6210",
    "move_cancel": "\u53d6\u6d88",
}
for path, new_keys in (
    ("app/src/main/res/values/strings.xml", RU_MOVE),
    ("app/src/main/res/values-en/strings.xml", EN_MOVE),
    ("app/src/main/res/values-zh/strings.xml", ZH_MOVE),
):
    p = pathlib.Path(path)
    text = p.read_text(encoding="utf-8")
    last = list(new_keys)[-1]
    if f'name="{last}"' in text:
        print(f"[strings {path}] already applied")
        continue
    if text.count("</resources>") != 1:
        print(f"::error file={path}::</resources> is not unique")
        failures.append(path)
        continue
    block = "".join(
        f'    <string name="{k}">{v}</string>\n' for k, v in new_keys.items()
    )
    p.write_text(
        text.replace("</resources>", block + "</resources>", 1),
        encoding="utf-8",
    )
    print(f"[strings {path}] added {len(new_keys)} keys")

if failures:
    print(f"::error::layer move app patch failed: {failures}")
    sys.exit(1)
print("layer move app patch: all steps ok")
