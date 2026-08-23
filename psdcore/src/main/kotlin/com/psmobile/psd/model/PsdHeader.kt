package com.psmobile.psd.model

/** Thrown for anything that is not a readable Photoshop document. */
class PsdFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class ColorMode(val id: Int, val label: String) {
    BITMAP(0, "Bitmap"),
    GRAYSCALE(1, "Grayscale"),
    INDEXED(2, "Indexed"),
    RGB(3, "RGB"),
    CMYK(4, "CMYK"),
    MULTICHANNEL(7, "Multichannel"),
    DUOTONE(8, "Duotone"),
    LAB(9, "Lab");

    companion object {
        fun from(id: Int): ColorMode = entries.firstOrNull { it.id == id }
            ?: throw PsdFormatException("Unsupported colour mode: $id")
    }
}

enum class Compression(val id: Int) {
    RAW(0),
    RLE(1),
    ZIP(2),
    ZIP_PREDICTED(3);

    companion object {
        fun from(id: Int): Compression = entries.firstOrNull { it.id == id }
            ?: throw PsdFormatException("Unsupported compression: $id")
    }
}

/**
 * The 26-byte file header.
 *
 * `version` is what separates the two containers: 1 is PSD (max 30 000 px, 32-bit section
 * lengths) and 2 is PSB (max 300 000 px, 64-bit section lengths). Every later parsing step
 * branches on [isPsb].
 */
data class PsdHeader(
    val version: Int,
    val channels: Int,
    val height: Int,
    val width: Int,
    val depth: Int,
    val colorMode: ColorMode,
) {
    val isPsb: Boolean get() = version == 2

    /** Bytes occupied by one sample. */
    val bytesPerSample: Int get() = when (depth) {
        1 -> 1
        8 -> 1
        16 -> 2
        32 -> 4
        else -> throw PsdFormatException("Unsupported bit depth: $depth")
    }

    /** Bytes in one uncompressed scanline of a single channel. */
    val rowBytes: Int get() = if (depth == 1) (width + 7) / 8 else width * bytesPerSample

    val pixelCount: Long get() = width.toLong() * height.toLong()

    val formatName: String get() = if (isPsb) "PSB" else "PSD"

    companion object {
        const val SIGNATURE = "8BPS"
        const val MAX_PSD_DIMENSION = 30_000
        const val MAX_PSB_DIMENSION = 300_000
    }
}
