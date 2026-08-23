package com.psmobile.psd.render

/**
 * Level-of-detail grid over a canvas.
 *
 * A 800 x 247 517 PSB is 198 megapixels — three orders of magnitude past what a single
 * platform bitmap can hold. The viewer therefore never handles the image as a whole: it asks
 * for square tiles at whatever level matches the current zoom, exactly like a map renderer.
 * Level `n` samples every `2^n`-th pixel, and the top level is small enough to fit one tile.
 */
class TilePyramid(
    val width: Int,
    val height: Int,
    val tileSize: Int = DEFAULT_TILE_SIZE,
) {
    val levelCount: Int = run {
        var level = 1
        var w = width
        var h = height
        while (w > tileSize || h > tileSize) {
            w = (w + 1) / 2
            h = (h + 1) / 2
            level++
            if (level > 24) break
        }
        level
    }

    val maxLevel: Int get() = levelCount - 1

    fun step(level: Int): Int = 1 shl level.coerceIn(0, maxLevel)

    fun levelWidth(level: Int): Int = ceilDiv(width, step(level))

    fun levelHeight(level: Int): Int = ceilDiv(height, step(level))

    fun cols(level: Int): Int = ceilDiv(levelWidth(level), tileSize)

    fun rows(level: Int): Int = ceilDiv(levelHeight(level), tileSize)

    fun tileCount(level: Int): Int = cols(level) * rows(level)

    /** Width in pixels of the tile at [col]; edge tiles are narrower. */
    fun tileWidth(level: Int, col: Int): Int =
        (levelWidth(level) - col * tileSize).coerceIn(0, tileSize)

    fun tileHeight(level: Int, row: Int): Int =
        (levelHeight(level) - row * tileSize).coerceIn(0, tileSize)

    /**
     * The coarsest level that still supplies at least one source sample per screen pixel at
     * [scale], where `scale` is screen pixels per image pixel.
     *
     * Level `L` samples every `2^L`-th pixel, so the condition is `2^L * scale <= 1`, which
     * rounds *down* — rounding up would fetch a blurrier level than the zoom deserves.
     */
    fun levelForScale(scale: Float): Int {
        if (scale <= 0f) return maxLevel
        if (scale >= 1f) return 0
        val level = Math.floor(-log2(scale.toDouble())).toInt()
        return level.coerceIn(0, maxLevel)
    }

    private fun log2(v: Double): Double = Math.log(v) / LN2

    companion object {
        const val DEFAULT_TILE_SIZE = 512
        private val LN2 = Math.log(2.0)
        fun ceilDiv(a: Int, b: Int): Int = if (b <= 0) 0 else (a + b - 1) / b
    }
}

/** Identifies one tile. Used as a cache key, so it must stay a cheap value type. */
data class TileKey(val level: Int, val col: Int, val row: Int)
