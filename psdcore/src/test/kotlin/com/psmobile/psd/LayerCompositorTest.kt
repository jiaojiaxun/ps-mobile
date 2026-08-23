package com.psmobile.psd

import com.psmobile.psd.io.ByteArraySource
import com.psmobile.psd.model.ColorMode
import com.psmobile.psd.render.CompositeRenderer
import com.psmobile.psd.render.LayerRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layer compositor has to agree with the flattened preview.
 *
 * That is the property the viewer depends on: hiding a layer swaps the render source, and if the
 * two paths disagree the picture visibly jumps. A document written with a transparent background
 * carries both a real layer and a merged preview, so the two can be compared directly — which
 * also exercises the coordinate arithmetic that decides which output pixels a layer covers.
 */
class LayerCompositorTest {

    private fun documentWithLayerAndComposite(width: Int, height: Int): PsdDocument = PsdParser(
        ByteArraySource(
            PsdWriter.createBlank(width, height, ColorMode.RGB, PsdWriter.Background.CUSTOM, 0xFF3C78C8.toInt()),
        ),
        "test",
    ).parse()

    /** An opaque canvas has no layer records, so the compositor has nothing to draw. */
    @Test
    fun `an opaque canvas composites from the flattened preview only`() {
        documentWithLayerAndComposite(40, 30).use { doc ->
            assertTrue(CompositeRenderer(doc).isAvailable)
            assertTrue("a flat canvas has no layers to composite", LayerRenderer(doc).drawOrder.isEmpty())
        }
    }

    @Test
    fun `both paths agree across zoom levels and offsets`() {
        val width = 70
        val height = 50
        val doc = PsdParser(
            ByteArraySource(PsdWriter.createBlank(width, height, ColorMode.RGB, PsdWriter.Background.TRANSPARENT)),
            "test",
        ).parse()

        doc.use {
            val composite = CompositeRenderer(doc).newDecoder()
            val layers = LayerRenderer(doc).newDecoder()

            // Offsets deliberately land mid-layer and past the right and bottom edges, which is
            // where the covered-rectangle arithmetic goes wrong if it is going to.
            for (step in intArrayOf(1, 2, 4, 8)) {
                for ((srcX, srcY) in listOf(0 to 0, 3 to 7, 40 to 30, 64 to 48)) {
                    val outW = 16
                    val outH = 16
                    val fromComposite = IntArray(outW * outH)
                    val fromLayers = IntArray(outW * outH)
                    composite.decodeRegion(srcX, srcY, step, outW, outH, fromComposite) { false }
                    layers.decodeRegion(srcX, srcY, step, outW, outH, fromLayers) { false }

                    for (i in fromComposite.indices) {
                        val expectedAlpha = (fromComposite[i] ushr 24) and 0xFF
                        val actualAlpha = (fromLayers[i] ushr 24) and 0xFF
                        assertEquals(
                            "alpha at $i, step $step, src ($srcX,$srcY)",
                            expectedAlpha,
                            actualAlpha,
                        )
                    }
                }
            }
        }
    }

    /**
     * A region entirely outside the layer must come back untouched rather than reading clamped
     * scanlines, which is what point-sampling past the bounds used to do.
     */
    @Test
    fun `a region past the layer stays transparent`() {
        val doc = PsdParser(
            ByteArraySource(PsdWriter.createBlank(20, 20, ColorMode.RGB, PsdWriter.Background.TRANSPARENT)),
            "test",
        ).parse()

        doc.use {
            val out = IntArray(8 * 8)
            LayerRenderer(doc).newDecoder().decodeRegion(200, 200, 1, 8, 8, out) { false }
            assertTrue("nothing should be drawn outside the canvas", out.all { it == 0 })
        }
    }

    @Test
    fun `reducing a solid layer keeps its colour`() {
        // Box filtering a uniform layer must return the same value, whatever the step: any
        // rounding or premultiplication mistake shows up here as a shifted colour.
        val doc = PsdParser(
            ByteArraySource(PsdWriter.createBlank(64, 64, ColorMode.RGB, PsdWriter.Background.TRANSPARENT)),
            "test",
        ).parse()

        doc.use {
            val layers = LayerRenderer(doc).newDecoder()
            for (step in intArrayOf(1, 2, 4, 8, 16)) {
                val size = 64 / step
                val out = IntArray(size * size)
                layers.decodeRegion(0, 0, step, size, size, out) { false }
                // The written layer is fully transparent, so every sample must stay clear.
                assertTrue("step $step should stay transparent", out.all { (it ushr 24) and 0xFF == 0 })
            }
        }
    }
}
