package com.psmobile.psd.model

/** How a layer record participates in the group hierarchy (`lsct` additional info). */
enum class SectionType {
    /** An ordinary pixel layer. */
    CONTENT,
    /** A group whose record carries the group's name; `1` = expanded, `2` = collapsed. */
    GROUP_OPEN,
    GROUP_CLOSED,
    /** The hidden marker Photoshop writes at the bottom of a group's contents. */
    DIVIDER,
    ;

    val isGroup: Boolean get() = this == GROUP_OPEN || this == GROUP_CLOSED
}

/** Location and length of one channel's payload inside the file, resolved lazily. */
data class LayerChannelRef(
    val id: Int,
    /** Offset of the 2-byte compression tag that opens the channel's data. */
    val offset: Long,
    /** Total bytes for this channel, compression tag included. */
    val length: Long,
)

data class LayerMask(
    val top: Int,
    val left: Int,
    val bottom: Int,
    val right: Int,
    val defaultColor: Int,
    val flags: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isDisabled: Boolean get() = flags and 0x02 != 0
}

/**
 * A single record from the layer section. Pixels are *not* held here — only the file offsets
 * needed to fetch them on demand.
 */
class Layer(
    val index: Int,
    val name: String,
    val top: Int,
    val left: Int,
    val bottom: Int,
    val right: Int,
    val opacity: Int,
    val fillOpacity: Int,
    val blendMode: BlendMode,
    val clipping: Boolean,
    val flags: Int,
    val sectionType: SectionType,
    val channels: List<LayerChannelRef>,
    val mask: LayerMask?,
    val colorLabel: Int,
    val layerId: Int,
    val hasText: Boolean,
    val isAdjustment: Boolean,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    /** Bit 1 of the flags is *hidden*, so it reads inverted. */
    val isVisible: Boolean get() = flags and 0x02 == 0

    val opacityPercent: Int get() = Math.round(opacity * 100f / 255f)

    fun channel(id: Int): LayerChannelRef? = channels.firstOrNull { it.id == id }

    override fun toString(): String =
        "Layer#$index('$name', $left,$top..$right,$bottom, ${blendMode.label}, ${opacityPercent}%)"
}

/** A layer plus its children, as shown in the layers panel. */
class LayerNode(
    val layer: Layer,
    val children: MutableList<LayerNode> = mutableListOf(),
    val depth: Int = 0,
) {
    val isGroup: Boolean get() = layer.sectionType.isGroup
}
