package com.psmobile.psd

import com.psmobile.psd.io.ByteArraySource
import com.psmobile.psd.model.ColorMode
import com.psmobile.psd.render.DrawItem
import com.psmobile.psd.render.LayerOffsets
import com.psmobile.psd.render.LayerRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The move tool shifts a layer without touching its pixels.
 *
 * The dangerous part is not the translation itself but the arithmetic that decides which output
 * pixels a shifted layer covers: an off-by-one there reads past the layer's scanline buffers or
 * smears the edge across the canvas. These tests pin the packing, the rejection of regions the
 * shifted layer no longer touches, and the invariant that a move by zero is a no-op.
 */
class LayerOffsetTest {

    private fun transparentDocument(width: Int, height: Int): PsdDocument = PsdParser(
        ByteArraySource(
            PsdWriter.createBlank(width, height, ColorMode.RGB, PsdWriter.Background.TRANSPARENT),
        ),
        "test",
    ).parse()

    @Test
    fun `packing survives negative and large offsets`() {
        for ((dx, dy) in listOf(0 to 0, 17 to -9, -300 to 4211, Int.MAX_VALUE to Int.MIN_VALUE)) {
            val packed = LayerOffsets.pack(dx, dy)
            assertEquals("dx for $dx,$dy", dx, LayerOffsets.dxOf(packed))
            assertEquals("dy for $dx,$dy", dy, LayerOffsets.dyOf(packed))
        }
    }

    @Test
    fun `no offsets means the saved draw order`() {
        transparentDocument(40, 30).use { doc ->
            val saved = LayerRenderer(doc).drawOrder
            val rebuilt = LayerRenderer(doc).buildDrawOrder(
                com.psmobile.psd.render.LayerVisibility.AS_SAVED,
                LayerOffsets.NONE,
            )
            assertEquals(saved.size, rebuilt.size)
            assertTrue(rebuilt.all { it.dx == 0 && it.dy == 0 })
        }
    }

    @Test
    fun `an offset reaches every draw item for that layer`() {
        transparentDocument(40, 30).use { doc ->
            val saved = LayerRenderer(doc).drawOrder
            if (saved.isEmpty()) return@use
            val target = saved.first().layer.index
            val moved = LayerRenderer(doc).buildDrawOrder(
                com.psmobile.psd.render.LayerVisibility.AS_SAVED,
                LayerOffsets { if (it.index == target) LayerOffsets.pack(12, -5) else null },
            )
            val item = moved.first { it.layer.index == target }
            assertEquals(12, item.dx)
            assertEquals(-5, item.dy)
        }
    }

    /**
     * A layer moved entirely off a region must leave that region untouched. Before the shift was
     * folded into the rejection test this walked the scanline buffers at a clamped row, which is
     * both wrong and a crash risk at the extremes.
     */
    @Test
    fun `a layer moved away from a region does not paint into it`() {
        transparentDocument(64, 64).use { doc ->
            val renderer = LayerRenderer(doc)
            val saved = renderer.drawOrder
            if (saved.isEmpty()) return@use
            val layer = saved.first().layer

            val far = listOf(DrawItem(layer, 255, layer.blendMode, false, dx = 100_000, dy = 100_000))
            val out = IntArray(16 * 16)
            val decoded = renderer.newDecoder(far).decodeRegion(0, 0, 1, 16, 16, out) { false }
            assertTrue("decode should still succeed", decoded)
            assertTrue("nothing may be painted here", out.all { it == 0 })
        }
    }

    /** Extremes must not read outside the layer's own buffers. */
    @Test
    fun `extreme offsets decode without reading out of bounds`() {
        transparentDocument(32, 32).use { doc ->
            val renderer = LayerRenderer(doc)
            val saved = renderer.drawOrder
            if (saved.isEmpty()) return@use
            val layer = saved.first().layer
            for (dy in intArrayOf(-1_000_000, -33, 0, 33, 1_000_000)) {
                for (step in intArrayOf(1, 4)) {
                    val order = listOf(DrawItem(layer, 255, layer.blendMode, false, dx = 0, dy = dy))
                    val out = IntArray(8 * 8)
                    val ok = renderer.newDecoder(order).decodeRegion(0, 0, step, 8, 8, out) { false }
                    assertTrue("step $step dy $dy should decode", ok)
                    assertTrue("step $step dy $dy should stay transparent", out.all { it == 0 })
                }
            }
        }
    }

    @Test
    fun `null offsets are distinguishable from zero`() {
        val none = LayerOffsets.NONE
        val doc = transparentDocument(20, 20)
        doc.use {
            assertNull("NONE reports no offset at all", none.offsetOf(it.layerTree.firstOrNull()?.layer ?: return@use))
        }
    }
}
