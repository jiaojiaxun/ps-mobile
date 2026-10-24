#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Layer move: psdcore part.

DrawItem carries dx/dy; LayerRegionDecoder rejects and blends against shifted
bounds; LayerImage folds the shift into left/top while its scanline reads stay
at the layer's own rows.

buildDrawOrder gets a two-argument OVERLOAD rather than a default argument: a
`fun interface` last parameter captures trailing-lambda calls that mean the
visibility rule (this is what broke run #6).

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


LR = "psdcore/src/main/kotlin/com/psmobile/psd/render/LayerRenderer.kt"
LI = "psdcore/src/main/kotlin/com/psmobile/psd/render/LayerImage.kt"

# 1. DrawItem + packed offsets.
replace(
    "draw-item",
    LR,
    "class DrawItem(val layer: Layer, val opacity: Int, val blendMode: BlendMode, val clipping: Boolean)",
    "class DrawItem(\n"
    "    val layer: Layer,\n"
    "    val opacity: Int,\n"
    "    val blendMode: BlendMode,\n"
    "    val clipping: Boolean,\n"
    "    /** Extra translation applied by the move tool, in document pixels. */\n"
    "    val dx: Int = 0,\n"
    "    val dy: Int = 0,\n"
    ")\n"
    "\n"
    "/** Per-layer translation, packed as `dx shl 32 or dy` to avoid an allocation per layer. */\n"
    "fun interface LayerOffsets {\n"
    "    fun offsetOf(layer: Layer): Long?\n"
    "\n"
    "    companion object {\n"
    "        val NONE = LayerOffsets { null }\n"
    "\n"
    "        fun pack(dx: Int, dy: Int): Long = (dx.toLong() shl 32) or (dy.toLong() and 0xFFFFFFFFL)\n"
    "        fun dxOf(packed: Long): Int = (packed shr 32).toInt()\n"
    "        fun dyOf(packed: Long): Int = packed.toInt()\n"
    "    }\n"
    "}",
)

# 2. As-saved order, no offsets.
replace(
    "draw-order-init",
    LR,
    "    val drawOrder: List<DrawItem> = buildDrawOrder(LayerVisibility.AS_SAVED)",
    "    val drawOrder: List<DrawItem> = buildDrawOrder(LayerVisibility.AS_SAVED, LayerOffsets.NONE)",
)

# 3. Overload instead of a default argument, so `buildDrawOrder { ... }` still means visibility.
replace(
    "build-draw-order",
    LR,
    """    fun buildDrawOrder(visibility: LayerVisibility): List<DrawItem> = buildList {
        flatten(doc.layerTree, parentVisible = true, parentOpacity = 255, visibility = visibility, out = this)
    }""",
    """    fun buildDrawOrder(visibility: LayerVisibility): List<DrawItem> =
        buildDrawOrder(visibility, LayerOffsets.NONE)

    /** Same order with per-layer translations applied. */
    fun buildDrawOrder(
        visibility: LayerVisibility,
        offsets: LayerOffsets,
    ): List<DrawItem> = buildList {
        flatten(
            nodes = doc.layerTree,
            parentVisible = true,
            parentOpacity = 255,
            visibility = visibility,
            offsets = offsets,
            out = this,
        )
    }""",
)

# 4. flatten signature.
replace(
    "flatten-signature",
    LR,
    """        parentOpacity: Int,
        visibility: LayerVisibility,
        out: MutableList<DrawItem>,
    ) {""",
    """        parentOpacity: Int,
        visibility: LayerVisibility,
        offsets: LayerOffsets,
        out: MutableList<DrawItem>,
    ) {""",
)

# 5. Recursion.
replace(
    "flatten-recursion",
    LR,
    "                flatten(node.children, visible, opacity, visibility, out)",
    "                flatten(node.children, visible, opacity, visibility, offsets, out)",
)

# 6. Item construction.
replace(
    "flatten-item",
    LR,
    "                out += DrawItem(layer, opacity * layer.fillOpacity / 255, layer.blendMode, layer.clipping)",
    "                val packed = offsets.offsetOf(layer)\n"
    "                out += DrawItem(\n"
    "                    layer = layer,\n"
    "                    opacity = opacity * layer.fillOpacity / 255,\n"
    "                    blendMode = layer.blendMode,\n"
    "                    clipping = layer.clipping,\n"
    "                    dx = if (packed == null) 0 else LayerOffsets.dxOf(packed),\n"
    "                    dy = if (packed == null) 0 else LayerOffsets.dyOf(packed),\n"
    "                )",
)

# 7. Rejection against shifted bounds.
replace(
    "decode-region-bounds",
    LR,
    """            val l = item.layer
            // Cheap rejection first: most layers of a long document miss any given tile.
            if (l.right <= srcX || l.left >= regionRight || l.bottom <= srcY || l.top >= regionBottom) continue""",
    """            val l = item.layer
            val dx = item.dx
            val dy = item.dy
            // Cheap rejection first: most layers of a long document miss any given tile.
            if (l.right + dx <= srcX || l.left + dx >= regionRight ||
                l.bottom + dy <= srcY || l.top + dy >= regionBottom
            ) {
                continue
            }""",
)

# 8. Offset down to the image.
replace(
    "decode-region-blend",
    LR,
    """                clipping = item.clipping,
                isCancelled = isCancelled,
            )""",
    """                clipping = item.clipping,
                offsetX = item.dx,
                offsetY = item.dy,
                isCancelled = isCancelled,
            )""",
)

# 9. blendInto takes the offset and works in shifted coordinates.
replace(
    "blend-into-signature",
    LI,
    """        blendMode: BlendMode,
        opacity: Int,
        clipping: Boolean,
        isCancelled: () -> Boolean,
    ): Boolean {
        if (opacity <= 0) return true

        // Output pixels whose source coordinate falls inside the layer.""",
    """        blendMode: BlendMode,
        opacity: Int,
        clipping: Boolean,
        offsetX: Int = 0,
        offsetY: Int = 0,
        isCancelled: () -> Boolean,
    ): Boolean {
        if (opacity <= 0) return true

        // Shifted bounds: the scanlines stay at the layer's own rows, but every coordinate
        // that decides *where* the pixels land carries the move offset.
        val left = layer.left + offsetX
        val right = layer.right + offsetX
        val top = layer.top + offsetY
        val bottom = layer.bottom + offsetY

        // Output pixels whose source coordinate falls inside the layer.""",
)

# 10. Bounds arithmetic.
replace(
    "blend-into-bounds",
    LI,
    """        val firstRow = maxOf(0, ceilDiv(layer.top - srcY, step))
        val lastRow = minOf(outH - 1, floorDiv(layer.bottom - 1 - srcY, step))
        val firstCol = maxOf(0, ceilDiv(layer.left - srcX, step))
        val lastCol = minOf(outW - 1, floorDiv(layer.right - 1 - srcX, step))""",
    """        val firstRow = maxOf(0, ceilDiv(top - srcY, step))
        val lastRow = minOf(outH - 1, floorDiv(bottom - 1 - srcY, step))
        val firstCol = maxOf(0, ceilDiv(left - srcX, step))
        val lastCol = minOf(outW - 1, floorDiv(right - 1 - srcX, step))""",
)

# 11. Dispatch: 15 args to blendExact, 18 to blendFiltered.
replace(
    "blend-into-dispatch",
    LI,
    """        return if (step <= 1 || maxSupersample <= 1) {
            blendExact(out, outW, srcX, srcY, step, firstRow, lastRow, firstCol, lastCol, blendMode, opacity, clipping, isCancelled)
        } else {
            blendFiltered(out, outW, srcX, srcY, step, firstRow, lastRow, firstCol, lastCol, blendMode, opacity, clipping, isCancelled)
        }""",
    """        return if (step <= 1 || maxSupersample <= 1) {
            blendExact(
                out, outW, srcX, srcY, step, firstRow, lastRow, firstCol, lastCol,
                blendMode, opacity, clipping, left, offsetY, isCancelled,
            )
        } else {
            blendFiltered(
                out, outW, srcX, srcY, step, firstRow, lastRow, firstCol, lastCol,
                blendMode, opacity, clipping, left, right, top, bottom, offsetY, isCancelled,
            )
        }""",
)

# 12. blendExact signature.
replace(
    "blend-exact-signature",
    LI,
    """        blendMode: BlendMode,
        opacity: Int,
        clipping: Boolean,
        isCancelled: () -> Boolean,
    ): Boolean {
        val simple = blendMode == BlendMode.NORMAL && opacity == 255 && !clipping

        for (oy in firstRow..lastRow) {
            if (isCancelled()) return false
            val sy = srcY + oy * step
            readRows(sy)""",
    """        blendMode: BlendMode,
        opacity: Int,
        clipping: Boolean,
        left: Int,
        offsetY: Int,
        isCancelled: () -> Boolean,
    ): Boolean {
        val simple = blendMode == BlendMode.NORMAL && opacity == 255 && !clipping

        for (oy in firstRow..lastRow) {
            if (isCancelled()) return false
            val sy = srcY + oy * step
            readRows(sy, offsetY)""",
)

# 13. blendExact local column.
replace(
    "blend-exact-local-x",
    LI,
    """                val lx = sx - layer.left
                val a = alphaAt(lx, sx)""",
    """                val lx = sx - left
                val a = alphaAt(lx, sx)""",
)

# 14. blendFiltered signature.
replace(
    "blend-filtered-signature",
    LI,
    """    private fun blendFiltered(
        out: IntArray,
        outW: Int,
        srcX: Int,
        srcY: Int,
        step: Int,
        firstRow: Int,
        lastRow: Int,
        firstCol: Int,
        lastCol: Int,
        blendMode: BlendMode,
        opacity: Int,
        clipping: Boolean,
        isCancelled: () -> Boolean,
    ): Boolean {""",
    """    private fun blendFiltered(
        out: IntArray,
        outW: Int,
        srcX: Int,
        srcY: Int,
        step: Int,
        firstRow: Int,
        lastRow: Int,
        firstCol: Int,
        lastCol: Int,
        blendMode: BlendMode,
        opacity: Int,
        clipping: Boolean,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
        offsetY: Int,
        isCancelled: () -> Boolean,
    ): Boolean {""",
)

# 15. blendFiltered rows.
replace(
    "blend-filtered-rows",
    LI,
    """                val sy = srcY + oy * step + k * sub
                if (sy < layer.top || sy >= layer.bottom) continue
                readRows(sy)""",
    """                val sy = srcY + oy * step + k * sub
                if (sy < top || sy >= bottom) continue
                readRows(sy, offsetY)""",
)

# 16. blendFiltered columns.
replace(
    "blend-filtered-cols",
    LI,
    """                            if (sx < layer.left || sx >= layer.right) continue
                            val lx = sx - layer.left""",
    """                            if (sx < left || sx >= right) continue
                            val lx = sx - left""",
)

# 17. Fast RGB loop takes the bounds as parameters.
replace(
    "accumulate-signature",
    LI,
    """        ss: Int,
        sub: Int,
    ) {
        val red = colourRows[0]
        val green = colourRows[1]
        val blue = colourRows[2]
        val alphaBytes = alphaRow
        val left = layer.left
        val right = layer.right""",
    """        ss: Int,
        sub: Int,
        left: Int,
        right: Int,
    ) {
        val red = colourRows[0]
        val green = colourRows[1]
        val blue = colourRows[2]
        val alphaBytes = alphaRow""",
)

# 18. Its call site: 11 arguments.
replace(
    "accumulate-call",
    LI,
    "                    accumulateRgb8(acc, n, stride, firstCol, lastCol, srcX, step, ss, sub)",
    "                    accumulateRgb8(acc, n, stride, firstCol, lastCol, srcX, step, ss, sub, left, right)",
)

# 19. readRows carries the vertical shift.
replace(
    "read-rows-signature",
    LI,
    """    private fun readRows(sy: Int) {
        val ly = sy - layer.top""",
    """    private fun readRows(sy: Int, offsetY: Int = 0) {
        val ly = sy - layer.top - offsetY""",
)

# 20. The mask travels with its layer.
replace(
    "read-rows-mask",
    LI,
    "            val my = sy - maskTop",
    "            val my = sy - maskTop - offsetY",
)

if failures:
    print(f"::error::layer move patch failed: {failures}")
    sys.exit(1)
print("layer move patch: all steps ok")
