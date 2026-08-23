package com.psmobile.psd

import com.psmobile.psd.io.ByteArraySource
import com.psmobile.psd.model.ColorMode
import com.psmobile.psd.render.CompositeRenderer
import com.psmobile.psd.render.TilePyramid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Writes documents with [PsdWriter] and reads them back with [PsdParser].
 *
 * The writer and parser were built from the same specification, so agreeing with each other is
 * not proof of correctness on its own — but it does pin down the parts that are easy to get
 * subtly wrong and hard to notice: PSB's widened length fields, the RLE scanline index, channel
 * ordering, and the sign of the layer count that flags transparency.
 */
class PsdRoundTripTest {

    private fun parse(bytes: ByteArray): PsdDocument =
        PsdParser(ByteArraySource(bytes), "test").parse()

    private fun pixelsOf(doc: PsdDocument, w: Int, h: Int): IntArray {
        val renderer = CompositeRenderer(doc)
        assertTrue("composite should be randomly accessible", renderer.isAvailable)
        val out = IntArray(w * h)
        assertTrue(renderer.newDecoder().decodeRegion(0, 0, 1, w, h, out) { false })
        return out
    }

    @Test
    fun `white rgb canvas round trips`() {
        val doc = parse(PsdWriter.createBlank(64, 40, ColorMode.RGB, PsdWriter.Background.WHITE))
        assertEquals(64, doc.width)
        assertEquals(40, doc.height)
        assertEquals(ColorMode.RGB, doc.colorMode)
        assertEquals(8, doc.depth)
        assertFalse(doc.header.isPsb)
        assertTrue(doc.hasComposite)

        val pixels = pixelsOf(doc, 64, 40)
        assertEquals(0xFFFFFFFF.toInt(), pixels[0])
        assertEquals(0xFFFFFFFF.toInt(), pixels[pixels.lastIndex])
    }

    @Test
    fun `custom colour survives the rle round trip`() {
        val teal = 0xFF1E7FCB.toInt()
        val doc = parse(
            PsdWriter.createBlank(
                width = 37, // deliberately not a multiple of the 128-byte run cap
                height = 9,
                colorMode = ColorMode.RGB,
                background = PsdWriter.Background.CUSTOM,
                customColor = teal,
            ),
        )
        val pixels = pixelsOf(doc, 37, 9)
        assertTrue(pixels.all { it == teal })
    }

    @Test
    fun `transparent canvas reports alpha and a layer`() {
        val doc = parse(PsdWriter.createBlank(48, 24, ColorMode.RGB, PsdWriter.Background.TRANSPARENT))
        assertEquals(4, doc.header.channels)
        assertTrue("negative layer count should flag merged alpha", doc.mergedHasAlpha)
        assertEquals(1, doc.layers.size)
        assertEquals("Layer 1", doc.layers.first().name)

        val pixels = pixelsOf(doc, 48, 24)
        assertTrue("every pixel should be fully transparent", pixels.all { (it ushr 24) == 0 })
    }

    @Test
    fun `psb layout is used past the psd dimension limit`() {
        // 30 001 px forces version 2, which widens every section length and RLE table entry.
        val doc = parse(PsdWriter.createBlank(30_001, 4, ColorMode.RGB, PsdWriter.Background.BLACK))
        assertTrue(doc.header.isPsb)
        assertEquals(30_001, doc.width)

        val pixels = pixelsOf(doc, 30_001, 4)
        assertTrue(pixels.all { it == 0xFF000000.toInt() })
    }

    @Test
    fun `grayscale canvas maps to neutral rgb`() {
        val doc = parse(PsdWriter.createBlank(16, 16, ColorMode.GRAYSCALE, PsdWriter.Background.WHITE))
        assertEquals(ColorMode.GRAYSCALE, doc.colorMode)
        assertEquals(1, doc.header.channels)
        val pixels = pixelsOf(doc, 16, 16)
        assertTrue(pixels.all { it == 0xFFFFFFFF.toInt() })
    }

    @Test
    fun `reduced levels average to the same solid colour`() {
        val doc = parse(PsdWriter.createBlank(300, 200, ColorMode.RGB, PsdWriter.Background.CUSTOM, 0xFF804020.toInt()))
        val renderer = CompositeRenderer(doc)
        val decoder = renderer.newDecoder()
        for (step in intArrayOf(2, 4, 8)) {
            val w = TilePyramid.ceilDiv(300, step)
            val h = TilePyramid.ceilDiv(200, step)
            val out = IntArray(w * h)
            assertTrue(decoder.decodeRegion(0, 0, step, w, h, out) { false })
            assertTrue("step=$step should stay the solid colour", out.all { it == 0xFF804020.toInt() })
        }
    }

    @Test
    fun `garbage is rejected rather than misread`() {
        val junk = ByteArray(4096) { (it * 7).toByte() }
        val failure = runCatching { parse(junk) }.exceptionOrNull()
        assertTrue("expected a format error, got $failure", failure is com.psmobile.psd.model.PsdFormatException)
    }
}

class TilePyramidTest {

    @Test
    fun `top level fits a single tile`() {
        val pyramid = TilePyramid(800, 247_517, tileSize = 512)
        assertEquals(1, pyramid.tileCount(pyramid.maxLevel))
        assertTrue(pyramid.levelWidth(pyramid.maxLevel) <= 512)
        assertTrue(pyramid.levelHeight(pyramid.maxLevel) <= 512)
    }

    @Test
    fun `level tracks zoom without going coarser than the zoom deserves`() {
        val pyramid = TilePyramid(4096, 4096, tileSize = 512)
        assertEquals(0, pyramid.levelForScale(1f))
        assertEquals(0, pyramid.levelForScale(0.6f))
        assertEquals(1, pyramid.levelForScale(0.5f))
        assertEquals(2, pyramid.levelForScale(0.25f))
        assertEquals(pyramid.maxLevel, pyramid.levelForScale(0.0001f))
    }

    @Test
    fun `edge tiles report their real size`() {
        val pyramid = TilePyramid(600, 700, tileSize = 512)
        assertEquals(512, pyramid.tileWidth(0, 0))
        assertEquals(88, pyramid.tileWidth(0, 1))
        assertEquals(188, pyramid.tileHeight(0, 1))
    }
}

/**
 * The draw order is what the viewer composites when a layer is shown or hidden, so it has to
 * honour the visibility it is handed rather than the one baked into the file.
 */
class DrawOrderTest {

    private fun documentWithOneLayer(): PsdDocument = PsdParser(
        com.psmobile.psd.io.ByteArraySource(
            PsdWriter.createBlank(32, 24, ColorMode.RGB, PsdWriter.Background.TRANSPARENT),
        ),
        "test",
    ).parse()

    @Test
    fun `saved visibility is the default`() {
        val renderer = com.psmobile.psd.render.LayerRenderer(documentWithOneLayer())
        assertEquals(1, renderer.drawOrder.size)
    }

    @Test
    fun `hiding a layer removes it from the draw order`() {
        val renderer = com.psmobile.psd.render.LayerRenderer(documentWithOneLayer())
        val hidden = renderer.buildDrawOrder { false }
        assertTrue("nothing should be drawn", hidden.isEmpty())

        val shown = renderer.buildDrawOrder { true }
        assertEquals(1, shown.size)
    }
}
