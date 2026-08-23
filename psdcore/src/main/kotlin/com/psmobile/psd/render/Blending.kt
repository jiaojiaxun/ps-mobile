package com.psmobile.psd.render

import com.psmobile.psd.model.BlendMode

/**
 * Separable blend functions from the PDF/Photoshop imaging model, on 0..255 integers.
 *
 * Only the separable modes are implemented — hue, saturation, colour and luminosity need the
 * whole pixel at once and fall back to Normal, which is what the layer compositor uses when it
 * has to stand in for a missing flattened preview.
 */
object Blending {

    fun blend(mode: BlendMode, cs: Int, cb: Int): Int = when (mode) {
        BlendMode.NORMAL, BlendMode.PASS_THROUGH, BlendMode.DISSOLVE -> cs
        BlendMode.MULTIPLY -> cs * cb / 255
        BlendMode.SCREEN -> 255 - (255 - cs) * (255 - cb) / 255
        BlendMode.OVERLAY -> hardLight(cb, cs)
        BlendMode.DARKEN -> minOf(cs, cb)
        BlendMode.LIGHTEN -> maxOf(cs, cb)
        BlendMode.COLOR_DODGE -> if (cs >= 255) 255 else minOf(255, cb * 255 / (255 - cs))
        BlendMode.COLOR_BURN -> if (cs <= 0) 0 else 255 - minOf(255, (255 - cb) * 255 / cs)
        BlendMode.HARD_LIGHT -> hardLight(cs, cb)
        BlendMode.SOFT_LIGHT -> softLight(cs, cb)
        BlendMode.DIFFERENCE -> Math.abs(cs - cb)
        BlendMode.EXCLUSION -> cs + cb - 2 * cs * cb / 255
        BlendMode.LINEAR_DODGE -> minOf(255, cs + cb)
        BlendMode.LINEAR_BURN -> maxOf(0, cs + cb - 255)
        BlendMode.LINEAR_LIGHT -> (cb + 2 * cs - 255).coerceIn(0, 255)
        BlendMode.VIVID_LIGHT -> vividLight(cs, cb)
        BlendMode.PIN_LIGHT -> if (cs < 128) minOf(cb, 2 * cs) else maxOf(cb, 2 * cs - 255)
        BlendMode.HARD_MIX -> if (cs + cb >= 255) 255 else 0
        BlendMode.SUBTRACT -> maxOf(0, cb - cs)
        BlendMode.DIVIDE -> if (cs <= 0) 255 else minOf(255, cb * 255 / cs)
        BlendMode.DARKER_COLOR -> minOf(cs, cb)
        BlendMode.LIGHTER_COLOR -> maxOf(cs, cb)
        else -> cs
    }

    private fun hardLight(cs: Int, cb: Int): Int =
        if (cs < 128) cb * (2 * cs) / 255 else 255 - (255 - cb) * (2 * (255 - cs)) / 255

    private fun softLight(cs: Int, cb: Int): Int {
        val s = cs / 255.0
        val b = cb / 255.0
        val d = if (b <= 0.25) ((16 * b - 12) * b + 4) * b else Math.sqrt(b)
        val r = if (s <= 0.5) b - (1 - 2 * s) * b * (1 - b) else b + (2 * s - 1) * (d - b)
        return (r * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }

    private fun vividLight(cs: Int, cb: Int): Int = when {
        cs < 128 -> if (cs <= 0) 0 else 255 - minOf(255, (255 - cb) * 255 / (2 * cs))
        else -> if (cs >= 255) 255 else minOf(255, cb * 255 / (2 * (255 - cs)))
    }

    /**
     * Composites one straight-alpha source pixel over a straight-alpha backdrop.
     *
     * `Co = (1 - as) * ab * Cb + (1 - ab) * as * Cs + as * ab * B(Cb, Cs)`, which is the general
     * form that reduces to plain source-over when the mode is Normal.
     */
    fun composite(src: Int, dst: Int, mode: BlendMode, opacity: Int, clipToDst: Boolean): Int {
        var sa = (src ushr 24) and 0xFF
        if (sa == 0 || opacity == 0) return dst
        if (opacity < 255) sa = sa * opacity / 255
        val da = (dst ushr 24) and 0xFF
        if (clipToDst) {
            if (da == 0) return dst
            sa = sa * da / 255
        }
        if (sa == 0) return dst
        if (sa == 255 && mode == BlendMode.NORMAL) return (0xFF shl 24) or (src and 0xFFFFFF)

        val outA = sa + da * (255 - sa) / 255
        if (outA == 0) return 0

        var out = outA shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val cs = (src ushr shift) and 0xFF
            val cb = (dst ushr shift) and 0xFF
            val blended = if (da == 0) cs else blend(mode, cs, cb)
            val num = (255 - sa) * da * cb + (255 - da) * sa * cs + sa * da * blended
            val c = (num / (255 * outA)).coerceIn(0, 255)
            out = out or (c shl shift)
        }
        return out
    }
}
