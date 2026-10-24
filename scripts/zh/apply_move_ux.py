#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Layer move UX: never lose the layer.

1. commitMove returns where the layer landed; the viewer focuses there, so a
   committed move can never leave the layer outside the visible area.
2. The layers panel gains a reset entry whenever any layer has been moved.
3. The move button takes the accent colour so it stops looking decorative.

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


DS = "app/src/main/java/com/psmobile/document/DocumentSession.kt"
VS = "app/src/main/java/com/psmobile/ui/viewer/ViewerScreen.kt"
PN = "app/src/main/java/com/psmobile/ui/viewer/Panels.kt"

# 1. Commit returns the landing rect so the caller can keep it on screen.
replace(
    "ds-commit-returns-rect",
    DS,
    """    /** Commits the drag and re-composites once. */
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
    }""",
    """    /** Commits the drag, re-composites once, and returns where the layer ended up. */
    fun commitMove(): DocumentRect? {
        val layer = moveTarget ?: return null
        val existing = layerOffsets[layer.index]
        val dx = (if (existing == null) 0 else LayerOffsets.dxOf(existing)) + moveDeltaX.roundToInt()
        val dy = (if (existing == null) 0 else LayerOffsets.dyOf(existing)) + moveDeltaY.roundToInt()
        if (dx == 0 && dy == 0) layerOffsets.remove(layer.index)
        else layerOffsets[layer.index] = LayerOffsets.pack(dx, dy)
        moveTarget = null
        moveDeltaX = 0f
        moveDeltaY = 0f
        applyRender()
        return if (layer.isEmpty) null
        else DocumentRect(layer.left + dx, layer.top + dy, layer.right + dx, layer.bottom + dy)
    }""",
)

# 2. Finishing a move takes the view to the layer, so it can never vanish off-canvas.
replace(
    "vs-commit-focuses",
    VS,
    """            MoveBar(
                layerName = movingLayer.name,
                onDone = session::commitMove,
                onCancel = session::cancelMove,""",
    """            MoveBar(
                layerName = movingLayer.name,
                onDone = {
                    val landed = session.commitMove()
                    if (landed != null) {
                        scope.launch { state.focusOn(landed.left, landed.top, landed.right, landed.bottom) }
                    }
                },
                onCancel = session::cancelMove,""",
)

# 3. Layers panel: a reset entry whenever something was moved; the move button stops hiding.
replace(
    "pn-panel-reset",
    PN,
    """        PanelHeader(
            title = stringResource(R.string.viewer_layers),
            trailing = if (rows.isEmpty()) null else "${session.document.visibleLayerCount}",
            action = if (session.hasVisibilityOverrides) stringResource(R.string.viewer_layers_reset) else null,
            onAction = onResetVisibility,
        )""",
    """        PanelHeader(
            title = stringResource(R.string.viewer_layers),
            trailing = if (rows.isEmpty()) null else "${session.document.visibleLayerCount}",
            action = when {
                session.hasLayerOffsets -> stringResource(R.string.moves_reset)
                session.hasVisibilityOverrides -> stringResource(R.string.viewer_layers_reset)
                else -> null
            },
            onAction = {
                when {
                    session.hasLayerOffsets -> session.resetLayerOffsets()
                    session.hasVisibilityOverrides -> onResetVisibility()
                    else -> Unit
                }
            },
        )""",
)

replace(
    "pn-move-button-accent",
    PN,
    """                    Icons.Rounded.OpenWith,
                    stringResource(R.string.move_layer),
                    tint = PsColors.TextTertiary,""",
    """                    Icons.Rounded.OpenWith,
                    stringResource(R.string.move_layer),
                    tint = PsColors.Accent,""",
)

# 4. Reset string in all three locales.
RU = {"moves_reset": "\u0421\u0431\u0440\u043e\u0441\u0438\u0442\u044c \u043f\u0435\u0440\u0435\u043c\u0435\u0449\u0435\u043d\u0438\u044f"}
EN = {"moves_reset": "Reset moves"}
ZH = {"moves_reset": "\u91cd\u7f6e\u79fb\u52a8"}
for path, new_keys in (
    ("app/src/main/res/values/strings.xml", RU),
    ("app/src/main/res/values-en/strings.xml", EN),
    ("app/src/main/res/values-zh/strings.xml", ZH),
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
    print(f"::error::move ux patch failed: {failures}")
    sys.exit(1)
print("move ux patch: all steps ok")
