package com.psmobile.psd.model

/** One `8BIM` block from the image-resources section. */
class ImageResource(
    val id: Int,
    val name: String,
    val data: ByteArray,
) {
    override fun toString(): String = "ImageResource(0x%04X, %d bytes)".format(id, data.size)
}

/** The document thumbnail Photoshop embeds, already in JPEG form. */
class EmbeddedThumbnail(
    val width: Int,
    val height: Int,
    val jpeg: ByteArray,
)

/** Physical resolution, in pixels per inch. */
data class Resolution(val horizontal: Double, val vertical: Double) {
    companion object {
        val DEFAULT = Resolution(72.0, 72.0)
    }
}

object ResourceId {
    const val RESOLUTION_INFO = 0x03ED
    const val ALPHA_CHANNEL_NAMES = 0x03EE
    const val DISPLAY_INFO = 0x03EF
    const val CAPTION = 0x03F0
    const val ICC_PROFILE = 0x040F
    const val THUMBNAIL_V4 = 0x0409
    const val THUMBNAIL = 0x040C
    const val GLOBAL_ANGLE = 0x040D
    const val INDEXED_COLOR_TABLE_COUNT = 0x0416
    const val TRANSPARENCY_INDEX = 0x0417
    const val VERSION_INFO = 0x0421
    const val XMP_METADATA = 0x0424
    const val PRINT_SCALE = 0x0426
    const val PIXEL_ASPECT_RATIO = 0x0428
    const val LAYER_COMPS = 0x0429
    const val ALPHA_IDENTIFIERS = 0x041D
    const val UNICODE_ALPHA_NAMES = 0x0415
}
