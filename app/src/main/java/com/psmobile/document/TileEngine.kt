package com.psmobile.document

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.psmobile.psd.PsdDocument
import com.psmobile.psd.render.CompositeRenderer
import com.psmobile.psd.render.DrawItem
import com.psmobile.psd.render.LayerRenderer
import com.psmobile.psd.render.LayerVisibility
import com.psmobile.psd.render.RegionDecoder
import com.psmobile.psd.render.TileDecoder
import com.psmobile.psd.render.TileKey
import com.psmobile.psd.render.TilePyramid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Decodes and caches the tiles the viewer draws.
 *
 * Tiles are produced on a small pool of worker threads, each with its own decoder because the
 * decoders keep scanline buffers and file read-ahead windows. Results land in a byte-budgeted
 * LRU; the coarsest level is pinned so there is always something to paint under a pending tile
 * instead of a hole.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TileEngine(
    private val document: PsdDocument,
    memoryBudgetBytes: Long = defaultBudget(),
    supersample: Int = RegionDecoder.DEFAULT_SUPERSAMPLE,
) {
    /** Where tiles come from. The engine can move between these while a document is open. */
    enum class Source {
        /** The flattened preview stored in the file: fast, and exactly what Photoshop saved. */
        COMPOSITE,

        /** Layers blended on demand — needed once visibility differs from the saved state. */
        LAYERS,
    }

    private val compositeRenderer = CompositeRenderer(document)
    private val layerRenderer: LayerRenderer by lazy { LayerRenderer(document) }

    /** Non-null in [Source.LAYERS] mode; the bottom-to-top list currently being composited. */
    private var drawOrder: List<DrawItem>? = null

    /** Sub-samples per axis for zoomed-out tiles; see the quality setting. */
    @Volatile
    private var supersample: Int = supersample

    var source: Source by mutableStateOf(
        if (compositeRenderer.isAvailable) Source.COMPOSITE else Source.LAYERS,
    )
        private set

    /** True when the file itself carries no flattened preview, so layers are the only option. */
    val compositeUnavailable: Boolean = !compositeRenderer.isAvailable

    val pyramid: TilePyramid = compositeRenderer.pyramid

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(WORKERS))
    private val gate = Semaphore(WORKERS)

    @Volatile
    private var decoders = newDecoderPool()
    private val buffers = ArrayBlockingQueue<IntArray>(WORKERS).apply {
        repeat(WORKERS) { add(IntArray(pyramid.tileSize * pyramid.tileSize)) }
    }

    private fun newDecoderPool() = ArrayBlockingQueue<TileDecoder>(WORKERS).apply {
        repeat(WORKERS) { add(newDecoder()) }
    }

    private val cache = object : LruCache<TileKey, Bitmap>(memoryBudgetBytes.toInt()) {
        override fun sizeOf(key: TileKey, value: Bitmap): Int = value.allocationByteCount
    }
    private val pinned = ConcurrentHashMap<TileKey, Bitmap>()
    private val inFlight = ConcurrentHashMap<TileKey, Job>()

    /** Bumped whenever a tile becomes available; the canvas reads it to schedule a redraw. */
    var generation by mutableIntStateOf(0)
        private set

    private fun newDecoder(): TileDecoder {
        val order = drawOrder
        return if (order != null) layerRenderer.newDecoder(order) else compositeRenderer.newDecoder(supersample)
    }

    fun tile(key: TileKey): Bitmap? = pinned[key] ?: cache[key]

    /**
     * Switches to compositing layers live under [visibility], or back to the file's flattened
     * preview when [visibility] is null.
     *
     * Every cached tile was produced under the old rules, so the caches are dropped wholesale.
     * The caller is expected to re-prime the overview afterwards.
     */
    fun setVisibilityOverride(visibility: LayerVisibility?) {
        // With no flattened preview in the file there is nothing to fall back to, so the
        // as-saved visibility still has to go through the layer compositor.
        val effective = visibility ?: if (compositeUnavailable) LayerVisibility.AS_SAVED else null
        val nextOrder = effective?.let { layerRenderer.buildDrawOrder(it) }
        val nextSource = if (nextOrder != null) Source.LAYERS else Source.COMPOSITE
        if (nextSource == Source.COMPOSITE && source == Source.COMPOSITE) return

        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        drawOrder = nextOrder
        source = nextSource
        // A fresh pool: decoders hold the previous draw order and their own scanline buffers.
        decoders = newDecoderPool()
        cache.evictAll()
        // The pinned coarse level is deliberately kept: it is now stale, but showing the old
        // blurry version while the new one decodes reads far better than a blank canvas.
        // primeOverview(force = true) replaces it.
        generation++
    }

    /** Applies a new zoomed-out quality; already-decoded tiles were made under the old one. */
    fun setSupersample(value: Int) {
        if (value == supersample) return
        supersample = value
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        decoders = newDecoderPool()
        cache.evictAll()
        generation++
    }

    /**
     * Renders the coarsest level synchronously so the viewer has a complete, if blurry, image
     * from the very first frame. There are only a handful of tiles at the top of the pyramid.
     */
    suspend fun primeOverview(force: Boolean = false) = withContext(Dispatchers.IO) {
        val level = pyramid.maxLevel
        for (row in 0 until pyramid.rows(level)) {
            for (col in 0 until pyramid.cols(level)) {
                coroutineContext.ensureActive()
                val key = TileKey(level, col, row)
                if (!force && pinned.containsKey(key)) continue
                decodeBlocking(key)?.let { pinned[key] = it }
            }
        }
        generation++
    }

    /** Queues [keys] for decoding, nearest-first, and drops work for anything no longer wanted. */
    fun request(keys: List<TileKey>) {
        val wanted = keys.toHashSet()
        inFlight.entries.removeIf { (key, job) ->
            if (key !in wanted) {
                job.cancel()
                true
            } else {
                false
            }
        }
        for (key in keys) {
            if (tile(key) != null || inFlight.containsKey(key)) continue
            val job = scope.launch {
                try {
                    gate.withPermit {
                        coroutineContext.ensureActive()
                        val bitmap = decodeBlocking(key)
                        if (bitmap != null) {
                            cache.put(key, bitmap)
                            generation++
                        }
                    }
                } finally {
                    inFlight.remove(key)
                }
            }
            inFlight[key] = job
        }
    }

    private suspend fun decodeBlocking(key: TileKey): Bitmap? {
        val w = pyramid.tileWidth(key.level, key.col)
        val h = pyramid.tileHeight(key.level, key.row)
        if (w <= 0 || h <= 0) return null

        val job = coroutineContext[Job]
        // Read the pool once: switching source swaps it, and a decoder must go back where it
        // came from rather than into a pool built for different draw rules.
        val pool = decoders
        val decoder = pool.poll() ?: newDecoder()
        val buffer = buffers.poll() ?: IntArray(pyramid.tileSize * pyramid.tileSize)
        try {
            val step = pyramid.step(key.level)
            val ok = decoder.decodeRegion(
                srcX = key.col * pyramid.tileSize * step,
                srcY = key.row * pyramid.tileSize * step,
                step = step,
                outW = w,
                outH = h,
                out = buffer,
                isCancelled = { job?.isActive == false },
            )
            if (!ok) return null
            return Bitmap.createBitmap(buffer, 0, w, w, h, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return null
        } finally {
            pool.offer(decoder)
            buffers.offer(buffer)
        }
    }

    /**
     * Renders the preview shown in the recents list.
     *
     * A 1:300 webtoon strip scaled to fit a thumbnail box is a two-pixel sliver, so documents
     * with an extreme aspect ratio get a square crop of their opening instead — the part a
     * person would actually recognise.
     */
    suspend fun renderThumbnail(maxSize: Int): Bitmap? = withContext(Dispatchers.IO) {
        val docWidth = document.width
        val docHeight = document.height
        val longest = maxOf(docWidth, docHeight)
        val shortest = minOf(docWidth, docHeight)

        val cropSize = if (longest > shortest * EXTREME_ASPECT) shortest else 0
        val srcW = if (cropSize > 0) cropSize else docWidth
        val srcH = if (cropSize > 0) cropSize else docHeight

        val step = maxOf(
            1,
            maxOf(TilePyramid.ceilDiv(srcW, maxSize), TilePyramid.ceilDiv(srcH, maxSize)),
        )
        val w = TilePyramid.ceilDiv(srcW, step)
        val h = TilePyramid.ceilDiv(srcH, step)
        if (w <= 0 || h <= 0) return@withContext null

        val pixels = IntArray(w * h)
        val ok = newDecoder().decodeRegion(0, 0, step, w, h, pixels) { false }
        if (!ok) null else Bitmap.createBitmap(pixels, 0, w, w, h, Bitmap.Config.ARGB_8888)
    }

    fun release() {
        scope.cancel()
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        cache.evictAll()
        pinned.clear()
    }

    companion object {
        private const val WORKERS = 3

        /** Past this width-to-height ratio a fitted thumbnail stops being legible. */
        private const val EXTREME_ASPECT = 2.5f

        fun defaultBudget(): Long {
            val max = Runtime.getRuntime().maxMemory()
            return (max / 4).coerceIn(24L * 1024 * 1024, 192L * 1024 * 1024)
        }
    }
}
