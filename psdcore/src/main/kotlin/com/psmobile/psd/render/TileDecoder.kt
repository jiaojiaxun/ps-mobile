package com.psmobile.psd.render

/**
 * Anything that can fill an ARGB block by sampling the document.
 *
 * Both paths implement it — the flattened composite and the layer compositor — so the tile
 * engine above does not care which one a given file needs.
 */
interface TileDecoder {
    fun decodeRegion(
        srcX: Int,
        srcY: Int,
        step: Int,
        outW: Int,
        outH: Int,
        out: IntArray,
        isCancelled: () -> Boolean = { false },
    ): Boolean
}
