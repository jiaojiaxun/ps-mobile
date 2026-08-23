package com.psmobile.psd.tools

import com.psmobile.psd.PsdParser
import com.psmobile.psd.io.ChannelSource
import com.psmobile.psd.render.CompositeRenderer
import com.psmobile.psd.render.LayerRenderer
import com.psmobile.psd.render.TileDecoder
import com.psmobile.psd.render.TileKey
import com.psmobile.psd.render.TilePyramid
import java.io.File

/**
 * Times tile decoding for both render paths.
 *
 * The flattened composite is the fast path and the baseline; compositing from layers is what the
 * viewer falls back to when a layer is hidden, and is the one worth optimising. Reporting them
 * side by side keeps the target honest — the layer path will never match the composite, but the
 * gap should be a small multiple, not a thousandfold.
 *
 * Every figure is a median of repeated runs after a warm-up, because a single cold measurement
 * mostly reports JIT compilation and one-off setup, which is exactly the noise that makes an
 * optimisation look like it worked when it did not.
 *
 * `./gradlew :psdcore:benchmarkPsd -Ppsd=<path>`
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: benchmarkPsd <file.psd|file.psb>")
        return
    }
    val input = File(args[0])
    val document = PsdParser(ChannelSource.of(input), input.name).parse()

    println("Document : $document")
    println("Size     : ${"%.1f".format(input.length() / 1024.0 / 1024.0)} MB")

    val composite = CompositeRenderer(document)
    val pyramid = composite.pyramid
    val layers = LayerRenderer(document)
    println("Draw list: ${layers.drawOrder.size} of ${document.layers.size} records participate")
    println("Heap     : ${"%.0f".format(Runtime.getRuntime().maxMemory() / 1024.0 / 1024.0)} MB max")
    println()

    val buffer = IntArray(pyramid.tileSize * pyramid.tileSize)

    // Correctness before speed: for a document whose layers are mostly normal-blend pixels, the
    // layer composite should land close to the flattened preview Photoshop saved. A large
    // difference means the fast path is fast and wrong.
    if (composite.isAvailable && layers.isAvailable) {
        compare(composite, layers, pyramid)
        println()
    }

    // The overview is the first thing the viewer needs after a visibility change, and it is the
    // worst case: one tile spanning the entire canvas, so nothing is culled by bounds.
    run {
        val level = pyramid.maxLevel
        val work = { decoder: TileDecoder ->
            decoder.decodeRegion(
                srcX = 0,
                srcY = 0,
                step = pyramid.step(level),
                outW = pyramid.tileWidth(level, 0),
                outH = pyramid.tileHeight(level, 0),
                out = buffer,
            ) { false }
            Unit
        }
        println("--- overview, level $level (whole canvas in one tile) ---")
        report("composite", composite.newDecoder(), work, repeats = 5)
        report("layers   ", layers.newDecoder(), work, repeats = 5)
        println()
    }

    for (level in intArrayOf(0, 3, 6)) {
        val keys = sampleTiles(pyramid, level, count = 6)
        if (keys.isEmpty()) continue
        val work = { decoder: TileDecoder ->
            for (key in keys) {
                val step = pyramid.step(key.level)
                decoder.decodeRegion(
                    srcX = key.col * pyramid.tileSize * step,
                    srcY = key.row * pyramid.tileSize * step,
                    step = step,
                    outW = pyramid.tileWidth(key.level, key.col),
                    outH = pyramid.tileHeight(key.level, key.row),
                    out = buffer,
                ) { false }
            }
            Unit
        }
        println("--- level $level, ${keys.size} tiles (step ${pyramid.step(level)}) ---")
        report("composite", composite.newDecoder(), work, repeats = 5, divisor = keys.size)
        report("layers   ", layers.newDecoder(), work, repeats = 5, divisor = keys.size)
        println()
    }

    document.close()
}

private fun compare(composite: CompositeRenderer, layers: LayerRenderer, pyramid: TilePyramid) {
    println("--- agreement between the two paths ---")
    val compositeDecoder = composite.newDecoder()
    val layerDecoder = layers.newDecoder()

    for (level in intArrayOf(0, 5)) {
        val step = pyramid.step(level)
        val keys = sampleTiles(pyramid, level, count = 4)
        var totalDelta = 0.0
        var worstDelta = 0
        var samples = 0L

        for (key in keys) {
            val w = pyramid.tileWidth(key.level, key.col)
            val h = pyramid.tileHeight(key.level, key.row)
            if (w <= 0 || h <= 0) continue
            val x = key.col * pyramid.tileSize * step
            val y = key.row * pyramid.tileSize * step
            val a = IntArray(w * h)
            val b = IntArray(w * h)
            compositeDecoder.decodeRegion(x, y, step, w, h, a) { false }
            layerDecoder.decodeRegion(x, y, step, w, h, b) { false }

            for (i in a.indices) {
                // Compare over an opaque backdrop; the layer path leaves uncovered pixels clear.
                for (shift in intArrayOf(16, 8, 0)) {
                    val delta = Math.abs(((a[i] shr shift) and 0xFF) - ((b[i] shr shift) and 0xFF))
                    totalDelta += delta
                    if (delta > worstDelta) worstDelta = delta
                    samples++
                }
            }
        }
        if (samples > 0) {
            println("  level $level  mean |delta| ${"%.2f".format(totalDelta / samples)} of 255, worst $worstDelta")
        }
    }
}

private fun sampleTiles(pyramid: TilePyramid, level: Int, count: Int): List<TileKey> {
    val rows = pyramid.rows(level)
    val cols = pyramid.cols(level)
    if (rows <= 0 || cols <= 0) return emptyList()
    return (0 until count).map { index ->
        // Spread through the document so the read pattern is not artificially sequential.
        val row = (rows.toLong() * index / count).toInt().coerceIn(0, rows - 1)
        TileKey(level, index % cols, row)
    }
}

private fun report(
    label: String,
    decoder: TileDecoder,
    work: (TileDecoder) -> Unit,
    repeats: Int,
    divisor: Int = 1,
) {
    // One untimed pass: it pays for JIT and for resolving whatever the decoder caches.
    val firstRun = measure { work(decoder) }

    val samples = LongArray(repeats) { measure { work(decoder) } }
    samples.sort()
    val median = samples[repeats / 2]
    val best = samples.first()

    val allocated = allocatedBytes { work(decoder) }

    println(
        "  $label  median ${format(median / divisor)}  best ${format(best / divisor)}" +
            "  first ${format(firstRun / divisor)}  alloc ${formatBytes(allocated / divisor)}",
    )
}

private inline fun measure(block: () -> Unit): Long {
    val start = System.nanoTime()
    block()
    return System.nanoTime() - start
}

/**
 * Rough allocation cost of one pass. Not exact — other threads allocate too — but large enough
 * differences are real, and allocation is often what actually hurts on a phone.
 */
private inline fun allocatedBytes(block: () -> Unit): Long {
    val runtime = Runtime.getRuntime()
    System.gc()
    Thread.sleep(30)
    val before = runtime.totalMemory() - runtime.freeMemory()
    block()
    val after = runtime.totalMemory() - runtime.freeMemory()
    return (after - before).coerceAtLeast(0)
}

private fun format(nanos: Long): String = when {
    nanos < 1_000_000 -> "%6.2f ms".format(nanos / 1_000_000.0)
    else -> "%6.0f ms".format(nanos / 1_000_000.0)
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 * 1024 -> "%5.0f KB".format(bytes / 1024.0)
    else -> "%5.0f MB".format(bytes / 1024.0 / 1024.0)
}
