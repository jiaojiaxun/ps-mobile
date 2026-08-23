package com.psmobile.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import com.psmobile.document.DocumentOpener
import com.psmobile.psd.PsdParser
import com.psmobile.psd.PsdProbe
import com.psmobile.psd.PsdQuickInfo
import com.psmobile.psd.render.CompositeRenderer
import com.psmobile.psd.render.TilePyramid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Everything the browser shows about one document, once it has been looked at. */
class DocumentPreview(
    val info: PsdQuickInfo,
    val bitmap: Bitmap?,
)

/**
 * Produces the small previews shown next to each document in the browser.
 *
 * Reading the header costs 26 bytes, so dimensions and format are effectively free. A picture
 * is another matter: Photoshop's own embedded JPEG is used when it is usable, and only when it
 * is missing or degenerate — it is stored with a clamped aspect ratio, so a long strip ends up
 * one pixel wide — is a real preview decoded, and then only for documents small enough that
 * building their scanline index is not itself an expense.
 */
object DocumentPreviews {

    private val cache = object : LruCache<String, DocumentPreview>(CACHE_ENTRIES) {}
    private val gate = Semaphore(2)

    /**
     * @param renderPreview when false, only the header is read — dimensions and format still
     *   appear, but no picture is decoded. That is the "previews off" setting.
     */
    suspend fun load(context: Context, uri: Uri, renderPreview: Boolean = true): DocumentPreview? {
        cache[uri.toString()]?.let { return it }
        return gate.withPermit {
            cache[uri.toString()] ?: withContext(Dispatchers.IO) {
                runCatching { read(context, uri, renderPreview) }.getOrNull()
                    ?.also { if (renderPreview) cache.put(uri.toString(), it) }
            }
        }
    }

    private fun read(context: Context, uri: Uri, renderPreview: Boolean): DocumentPreview? {
        val source = DocumentOpener.openSeekable(context, uri)
        source.use {
            val info = PsdProbe.read(it, withThumbnail = renderPreview) ?: return null
            if (!renderPreview) return DocumentPreview(info, null)
            val embedded = info.thumbnail
            val fromFile = embedded
                ?.takeIf { t -> isUsableAspect(t.width, t.height, info.width, info.height) }
                ?.let { t -> BitmapFactory.decodeByteArray(t.jpeg, 0, t.jpeg.size) }
            if (fromFile != null) return DocumentPreview(info, fromFile)
            if (!worthRendering(info)) return DocumentPreview(info, null)
            return DocumentPreview(info, runCatching { render(context, uri) }.getOrNull())
        }
    }

    /**
     * Photoshop clamps the stored thumbnail to a fixed box, so an extreme aspect ratio collapses
     * to a sliver that says nothing. Fall through to rendering in that case.
     */
    private fun isUsableAspect(thumbWidth: Int, thumbHeight: Int, docWidth: Int, docHeight: Int): Boolean {
        if (thumbWidth < 8 || thumbHeight < 8) return false
        val thumbRatio = thumbWidth.toFloat() / thumbHeight
        val docRatio = docWidth.toFloat() / docHeight
        return thumbRatio in (docRatio * 0.5f)..(docRatio * 2f)
    }

    /**
     * Long strips are cropped to a square before rendering, so their length costs nothing; what
     * does cost is the short edge, which sets how many bytes each decoded scanline carries.
     */
    private fun worthRendering(info: PsdQuickInfo): Boolean =
        minOf(info.width, info.height) <= MAX_RENDER_SHORT_EDGE

    private fun render(context: Context, uri: Uri): Bitmap? {
        val document = PsdParser(DocumentOpener.openSeekable(context, uri), "preview").parse()
        document.use { doc ->
            val renderer = CompositeRenderer(doc)
            if (!renderer.isAvailable) return null
            val longest = maxOf(doc.width, doc.height)
            val shortest = minOf(doc.width, doc.height)
            // Crop long strips to a square so the preview shows something recognisable.
            val src = if (longest > shortest * 2.5f) shortest else 0
            val srcW = if (src > 0) src else doc.width
            val srcH = if (src > 0) src else doc.height
            val step = maxOf(
                1,
                maxOf(TilePyramid.ceilDiv(srcW, TARGET_SIZE), TilePyramid.ceilDiv(srcH, TARGET_SIZE)),
            )
            val w = TilePyramid.ceilDiv(srcW, step)
            val h = TilePyramid.ceilDiv(srcH, step)
            if (w <= 0 || h <= 0) return null
            val pixels = IntArray(w * h)
            if (!renderer.newDecoder().decodeRegion(0, 0, step, w, h, pixels) { false }) return null
            return Bitmap.createBitmap(pixels, 0, w, w, h, Bitmap.Config.ARGB_8888)
        }
    }

    fun clear() = cache.evictAll()

    private const val CACHE_ENTRIES = 96
    private const val TARGET_SIZE = 192
    private const val MAX_RENDER_SHORT_EDGE = 16_000
}
