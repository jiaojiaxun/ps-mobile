package com.psmobile.psd

import com.psmobile.psd.io.ByteArraySource
import com.psmobile.psd.model.ColorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe is what the file browser runs over every file it lists, so it has to be right for
 * every colour mode — not just the one that happens to sort first.
 */
class PsdProbeTest {

    private fun probe(bytes: ByteArray) = PsdProbe.read(ByteArraySource(bytes))

    @Test
    fun `every colour mode is identified`() {
        for (mode in listOf(ColorMode.RGB, ColorMode.GRAYSCALE)) {
            val info = probe(PsdWriter.createBlank(20, 12, mode, PsdWriter.Background.WHITE))
            assertNotNull("probe failed for ${mode.label}", info)
            assertEquals(mode, info!!.colorMode)
            assertEquals(20, info.width)
            assertEquals(12, info.height)
            assertEquals(8, info.depth)
        }
    }

    @Test
    fun `psb is reported as such`() {
        val info = probe(PsdWriter.createBlank(30_001, 4, ColorMode.RGB, PsdWriter.Background.WHITE))
        assertNotNull(info)
        assertTrue(info!!.isPsb)
        assertEquals(30_001, info.width)
    }

    @Test
    fun `transparency shows up as an extra channel`() {
        val info = probe(PsdWriter.createBlank(16, 16, ColorMode.RGB, PsdWriter.Background.TRANSPARENT))
        assertNotNull(info)
        assertEquals(4, info!!.channels)
    }

    @Test
    fun `non photoshop files are rejected`() {
        assertNull(probe(ByteArray(512) { it.toByte() }))
        assertNull(probe(ByteArray(4)))
        assertNull(probe("not a psd at all, just some text".toByteArray()))
    }

    @Test
    fun `probing does not read past the resource block`() {
        // A header followed by nothing must still describe the document rather than throwing.
        val full = PsdWriter.createBlank(64, 64, ColorMode.RGB, PsdWriter.Background.BLACK)
        val truncated = full.copyOf(40)
        val info = probe(truncated)
        assertNotNull(info)
        assertEquals(64, info!!.width)
        assertNull("a truncated file has no usable thumbnail", info.thumbnail)
    }
}
