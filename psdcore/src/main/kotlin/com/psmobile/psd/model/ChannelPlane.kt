package com.psmobile.psd.model

/**
 * Where a single channel's pixels live and how to get at any one scanline of them.
 *
 * For RLE-compressed data the per-scanline byte counts are read up front and turned into
 * absolute file offsets ([rowOffsets]). That is the whole trick behind opening a 800 MB PSB
 * instantly: any row of any channel becomes a direct seek instead of a decode from the start.
 */
class ChannelPlane(
    val id: Int,
    val compression: Compression,
    val width: Int,
    val height: Int,
    val depth: Int,
    /** Offset of the first compressed byte (after the compression tag and any scanline table). */
    val dataOffset: Long,
    /** Total compressed length available from [dataOffset]. */
    val dataLength: Long,
    /** `rowOffsets[y]` .. `rowOffsets[y + 1]` bounds row `y`; only present for [Compression.RLE]. */
    val rowOffsets: LongArray?,
) {
    val rowBytes: Int get() = if (depth == 1) (width + 7) / 8 else width * when (depth) {
        16 -> 2
        32 -> 4
        else -> 1
    }

    fun rowLength(y: Int): Int {
        val offs = rowOffsets ?: return rowBytes
        return (offs[y + 1] - offs[y]).toInt()
    }

    fun rowOffset(y: Int): Long {
        val offs = rowOffsets ?: return dataOffset + y.toLong() * rowBytes
        return offs[y]
    }

    /** True when scanlines can be fetched independently, which is what tiled rendering needs. */
    val isRandomAccess: Boolean
        get() = compression == Compression.RAW || (compression == Compression.RLE && rowOffsets != null)

    companion object {
        /** Channel ids below zero carry masks and transparency rather than colour. */
        const val ID_TRANSPARENCY = -1
        const val ID_USER_MASK = -2
        const val ID_REAL_USER_MASK = -3
    }
}
