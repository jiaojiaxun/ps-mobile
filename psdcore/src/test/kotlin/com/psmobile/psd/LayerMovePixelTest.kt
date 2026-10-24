package com.psmobile.psd

import com.psmobile.psd.io.ByteArraySource
import com.psmobile.psd.model.ColorMode
import com.psmobile.psd.render.DrawItem
import com.psmobile.psd.render.LayerOffsets
import com.psmobile.psd.render.LayerRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pixel-level proof that a move actually lands.
 *
 * The earlier offset tests only established that a move does not read out of bounds; the bug a
 * user reported — the layer vanishing — needs the stronger statement that pixels show up where
 * the shift says they should.
 *
 * `PsdWriter` emits a transparent canvas as a solid white layer with an all-zero alpha channel,
 * each scanline a PackBits run `0xC1 0x00` for width 64. Rewriting the run value to 0xFF turns
 * it opaque, giving the compositor real pixels to move.
 */
class LayerMovePixelTest {

    private fun solidDocument(size: Int): PsdDocument {
        val bytes = PsdWriter.createBlank(size, size, ColorMode.RGB, PsdWriter.Background.TRANSPARENT)
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == 0xC1.toByte() && bytes[i + 1] == 0x00.toByte()) {
                bytes[i + 1] = 0xFF.toByte()
            }
        }
        return PsdParser(ByteArraySource(bytes), "test").parse()
    }

    private fun alpha(pixels: IntArray, index: Int) = (pixels[index] ushr 24) and 0xFF

    @Test
    fun `a moved layer lands exactly at the shifted position`() {
        val size = 64
        solidDocument(size).use { doc ->
            val renderer = LayerRenderer(doc)
            val saved = renderer.drawOrder
            assertTrue("the document must carry a layer to move", saved.isNotEmpty())
            val layer = saved.first().layer

            val unshifted = IntArray(16 * 16)
            renderer.newDecoder(listOf(DrawItem(layer, 255, layer.blendMode, false)))
                .decodeRegion(0, 0, 1, 16, 16, unshifted) { false }
            assertTrue("unshifted layer covers the region", alpha(unshifted, 0) == 255 && alpha(unshifted, 15) == 255)

            // Shift right by 8: columns 0..7 of the region must clear, 8..15 must stay solid.
            val moved = IntArray(16 * 16)
            renderer.newDecoder(listOf(DrawItem(layer, 255, layer.blendMode, false, dx = 8, dy = 0)))
                .decodeRegion(0, 0, 1, 16, 16, moved) { false }
            assertEquals("column 0 must clear after +8 shift", 0, alpha(moved, 0))
            assertEquals("column 7 must clear after +8 shift", 0, alpha(moved, 7))
            assertEquals("column 8 must stay solid after +8 shift", 255, alpha(moved, 8))
            assertEquals("column 15 must stay solid after +8 shift", 255, alpha(moved, 15))
        }
    }

    @Test
    fun `a vertical move clears the top rows and fills from below`() {
        val size = 64
        solidDocument(size).use { doc ->
            val renderer = LayerRenderer(doc)
            val layer = renderer.drawOrder.first().layer

            val moved = IntArray(16 * 16)
            renderer.newDecoder(listOf(DrawItem(layer, 255, layer.blendMode, false, dx = 0, dy = 8)))
                .decodeRegion(0, 0, 1, 16, 16, moved) { false }
            assertEquals("row 0 must clear after +8 vertical shift", 0, alpha(moved, 0))
            assertEquals("row 8 must stay solid after +8 vertical shift", 255, alpha(moved, 8 * 16))
        }
    }

    /**
     * The failure mode behind "the layer vanished": shifting a layer off the canvas must clear
     * its pixels rather than clamping the scanline read back onto the visible area.
     */
    @Test
    fun `a layer moved fully out of the canvas leaves nothing behind`() {
        val size = 64
        solidDocument(size).use { doc ->
            val renderer = LayerRenderer(doc)
            val layer = renderer.drawOrder.first().layer
            val order = listOf(DrawItem(layer, 255, layer.blendMode, false, dx = 500, dy = 500))
            val out = IntArray(16 * 16)
            val ok = renderer.newDecoder(order).decodeRegion(0, 0, 1, 16, 16, out) { false }
            assertTrue("decode should succeed", ok)
            assertTrue("the region must be empty", out.all { it == 0 })
        }
    }

    /** A reduced level samples every step-th pixel; the shift must survive that too. */
    @Test
    fun `a move survives downsampled levels`() {
        val size = 64
        solidDocument(size).use { doc ->
            val renderer = LayerRenderer(doc)
            val layer = renderer.drawOrder.first().layer
            val moved = IntArray(8 * 8)
            renderer.newDecoder(listOf(DrawItem(layer, 255, layer.blendMode, false, dx = 16, dy = 0)))
                .decodeRegion(0, 0, 4, 8, 8, moved) { false }
            assertEquals("leftmost columns must clear at step 4", 0, alpha(moved, 0))
            assertEquals("the shifted layer must fill column 7", 255, alpha(moved, 7))
        }
    }
}
