package com.psmobile.psd.tools

import com.psmobile.psd.ParsePhase
import com.psmobile.psd.PsdDocument
import com.psmobile.psd.PsdParser
import com.psmobile.psd.ProgressListener
import com.psmobile.psd.io.ChannelSource
import com.psmobile.psd.model.LayerNode
import com.psmobile.psd.render.CompositeRenderer
import com.psmobile.psd.render.LayerRenderer
import com.psmobile.psd.render.TileKey
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Desktop harness. Parses a document and writes PNG crops so the decoder can be verified
 * against real files without deploying to a device.
 *
 * `./gradlew :psdcore:renderPsd -Ppsd=C:/path/to/file.psb`
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: renderPsd <file.psd|file.psb> [outDir]")
        return
    }
    val input = File(args[0])
    val outDir = File(args.getOrElse(1) { "psd-out" }).apply { mkdirs() }

    println("File     : ${input.absolutePath}")
    println("Size     : ${"%.1f".format(input.length() / 1024.0 / 1024.0)} MB")

    val parseStart = System.nanoTime()
    val source = ChannelSource.of(input)
    var lastPhase: ParsePhase? = null
    val doc = PsdParser(source, input.name).parse(ProgressListener { p ->
        if (p.phase != lastPhase) {
            lastPhase = p.phase
            println("  [${p.phase}] ${p.detail}")
        }
    })
    val parseMs = (System.nanoTime() - parseStart) / 1_000_000

    println()
    println("Parsed in ${parseMs} ms")
    println("Document : $doc")
    println("Merged   : composite=${doc.hasComposite} alpha=${doc.mergedHasAlpha} channels=${doc.composite.size}")
    println("Resolution: ${doc.resolution.horizontal} x ${doc.resolution.vertical} dpi")
    println("Thumbnail: ${doc.embeddedThumbnail?.let { "${it.width}x${it.height}, ${it.jpeg.size} B" } ?: "none"}")

    doc.embeddedThumbnail?.let { File(outDir, "embedded-thumbnail.jpg").writeBytes(it.jpeg) }

    printLayerTree(doc)

    val renderer = CompositeRenderer(doc)
    println()
    println("Pyramid  : ${renderer.pyramid.levelCount} levels, tile ${renderer.pyramid.tileSize}px")
    for (l in 0 until renderer.pyramid.levelCount) {
        println(
            "  L$l  step=${renderer.pyramid.step(l)}  " +
                "${renderer.pyramid.levelWidth(l)}x${renderer.pyramid.levelHeight(l)}  " +
                "tiles=${renderer.pyramid.cols(l)}x${renderer.pyramid.rows(l)}",
        )
    }

    if (!renderer.isAvailable) {
        println()
        println("No random-access composite; rendering from layers instead.")
        renderFromLayers(doc, outDir)
        doc.close()
        return
    }

    val decoder = renderer.newDecoder()

    // A single tile at native resolution, to time the hot path.
    run {
        val key = TileKey(0, 0, 1)
        val buf = IntArray(renderer.pyramid.tileSize * renderer.pyramid.tileSize)
        val t = System.nanoTime()
        renderer.decodeTile(decoder, key, buf)
        println()
        println("Tile L0(0,1) decoded in ${(System.nanoTime() - t) / 1_000_000} ms")
    }

    // Full-resolution crops from the start, middle and end: proves random access works at depth.
    val cropH = minOf(1600, doc.height)
    listOf(
        "crop-top" to 0,
        "crop-middle" to (doc.height / 2).coerceAtMost(doc.height - cropH),
        "crop-bottom" to (doc.height - cropH).coerceAtLeast(0),
    ).forEach { (name, y) ->
        writeRegion(decoder, outDir, name, doc, srcX = 0, srcY = y, step = 1, outW = minOf(doc.width, 900), outH = cropH)
    }

    // Zoomed-out views to exercise the supersampling path.
    val overviewLevel = renderer.pyramid.levelCount - 1
    val ovStep = renderer.pyramid.step(overviewLevel)
    writeRegion(
        decoder, outDir, "overview-L$overviewLevel", doc,
        srcX = 0, srcY = 0, step = ovStep,
        outW = renderer.pyramid.levelWidth(overviewLevel),
        outH = renderer.pyramid.levelHeight(overviewLevel),
    )

    val midLevel = (renderer.pyramid.levelCount / 2).coerceAtLeast(1)
    val midStep = renderer.pyramid.step(midLevel)
    writeRegion(
        decoder, outDir, "zoomed-L$midLevel", doc,
        srcX = 0, srcY = 0, step = midStep,
        outW = minOf(TilePyramidCeil(doc.width, midStep), 1200),
        outH = minOf(TilePyramidCeil(doc.height, midStep), 2400),
    )

    // The same regions through the layer compositor, so the two paths can be compared by eye.
    if (LayerRenderer(doc).isAvailable) {
        val layerDecoder = LayerRenderer(doc).newDecoder()
        writeRegion(layerDecoder, outDir, "layers-crop-top", doc, 0, 0, 1, minOf(doc.width, 900), cropH)
        writeRegion(
            layerDecoder, outDir, "layers-zoomed-L$midLevel", doc,
            srcX = 0, srcY = 0, step = midStep,
            outW = minOf(TilePyramidCeil(doc.width, midStep), 1200),
            outH = minOf(TilePyramidCeil(doc.height, midStep), 2400),
        )
    }

    println()
    println("Wrote PNGs to ${outDir.absolutePath}")
    doc.close()
}

private fun TilePyramidCeil(a: Int, b: Int) = (a + b - 1) / b

private fun writeRegion(
    decoder: com.psmobile.psd.render.TileDecoder,
    outDir: File,
    name: String,
    doc: PsdDocument,
    srcX: Int,
    srcY: Int,
    step: Int,
    outW: Int,
    outH: Int,
) {
    if (outW <= 0 || outH <= 0) return
    val pixels = IntArray(outW * outH)
    val t = System.nanoTime()
    decoder.decodeRegion(srcX, srcY, step, outW, outH, pixels) { false }
    val ms = (System.nanoTime() - t) / 1_000_000
    val img = BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, outW, outH, pixels, 0, outW)
    val file = File(outDir, "$name.png")
    ImageIO.write(img, "png", file)
    println("  $name  ${outW}x$outH  step=$step  src=($srcX,$srcY)  ${ms} ms  -> ${file.name}")
}

private fun printLayerTree(doc: PsdDocument) {
    println()
    println("Layers   : ${doc.layers.size} records, ${doc.visibleLayerCount} real")
    val budget = intArrayOf(40)

    fun walk(nodes: List<LayerNode>, indent: String) {
        for (n in nodes) {
            if (budget[0]-- <= 0) return
            val l = n.layer
            val kind = when {
                l.sectionType.isGroup -> "[grp]"
                l.hasText -> "[txt]"
                l.isAdjustment -> "[adj]"
                else -> "     "
            }
            val visibility = if (l.isVisible) "" else " (hidden)"
            println(
                "$indent$kind ${l.name.take(44)}  " +
                    "(${l.left},${l.top})-(${l.right},${l.bottom}) " +
                    "${l.opacityPercent}% ${l.blendMode.label}$visibility",
            )
            walk(n.children, "$indent  ")
        }
    }

    walk(doc.layerTree, "  ")
    if (doc.visibleLayerCount > 40) println("  ... truncated")
}

private fun renderFromLayers(doc: PsdDocument, outDir: File) {
    val renderer = LayerRenderer(doc)
    val step = renderer.pyramid.step(renderer.pyramid.levelCount - 1)
    val w = (doc.width + step - 1) / step
    val h = (doc.height + step - 1) / step
    val pixels = IntArray(w * h)
    val t = System.nanoTime()
    renderer.newDecoder().decodeRegion(0, 0, step, w, h, pixels)
    println("  layer composite ${w}x$h in ${(System.nanoTime() - t) / 1_000_000} ms")
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, w, h, pixels, 0, w)
    ImageIO.write(img, "png", File(outDir, "layer-composite.png"))
}
