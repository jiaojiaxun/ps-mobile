package com.psmobile.data

import android.content.Context
import android.graphics.Bitmap
import androidx.core.net.toUri
import com.psmobile.document.DocumentOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One entry in the recents list. Everything needed to draw the card without reopening the file. */
@Serializable
data class RecentFile(
    val uri: String,
    val name: String,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val depth: Int,
    val colorMode: String,
    val layerCount: Int,
    val isPsb: Boolean,
    val openedAt: Long,
    val thumbnail: String? = null,
    /**
     * Identity independent of how the file was reached; see `DocumentOpener.identityOf`.
     * Empty on entries written before this existed, which fall back to name and size.
     */
    val key: String = "",
) {
    val identity: String get() = key.ifEmpty { "meta:$name:$fileSize" }
    val id: String get() = identity
}

/**
 * Recents, persisted as a small JSON file.
 *
 * A datastore would be overkill for a capped list, and keeping it as one file makes the
 * thumbnails easy to garbage-collect alongside their entries.
 */
class RecentFilesStore(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "recent").apply { mkdirs() }
    private val thumbsDir = File(appContext.filesDir, "thumbnails").apply { mkdirs() }
    private val file = File(dir, "recent.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val mutex = Mutex()

    private val _items = MutableStateFlow<List<RecentFile>>(emptyList())
    val items: StateFlow<List<RecentFile>> = _items.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val loaded = runCatching {
            if (!file.exists()) emptyList()
            else json.decodeFromString<List<RecentFile>>(file.readText())
        }.getOrDefault(emptyList())

        // Entries written before identities existed get one now, so duplicates an older build
        // recorded under different Uris for the same file collapse onto a single row.
        val keyed = loaded.map { entry ->
            if (entry.key.isNotEmpty()) {
                entry
            } else {
                val resolved = runCatching {
                    DocumentOpener.identityOf(appContext, entry.uri.toUri(), entry.name, entry.fileSize)
                }.getOrDefault("")
                entry.copy(key = resolved)
            }
        }
        val deduped = keyed.sortedByDescending { it.openedAt }.distinctBy { it.identity }
        _items.value = deduped
        if (deduped != loaded) runCatching { file.writeText(json.encodeToString(deduped)) }
    }

    /**
     * Records an open. Re-opening a document moves the existing entry to the top and refreshes
     * its date rather than adding another row — including when the same file arrives under a
     * different Uri, which is why entries are keyed by identity instead.
     */
    suspend fun remember(entry: RecentFile, thumbnail: Bitmap?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val identity = entry.identity
            val existing = _items.value.firstOrNull { it.identity == identity }
            val thumbPath = thumbnail?.let { saveThumbnail(identity, it) } ?: existing?.thumbnail
            val next = (listOf(entry.copy(thumbnail = thumbPath)) +
                _items.value.filterNot { it.identity == identity })
                .take(MAX_ENTRIES)
            persist(next)
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _items.value.firstOrNull { it.identity == id }?.thumbnail?.let { File(it).delete() }
            persist(_items.value.filterNot { it.identity == id })
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _items.value.mapNotNull { it.thumbnail }.forEach { File(it).delete() }
            persist(emptyList())
        }
    }

    private fun persist(next: List<RecentFile>) {
        _items.value = next
        runCatching { file.writeText(json.encodeToString(next)) }
        // Drop thumbnails whose entry is gone.
        val live = next.mapNotNull { it.thumbnail }.toSet()
        thumbsDir.listFiles()?.forEach { if (it.absolutePath !in live) it.delete() }
    }

    private fun saveThumbnail(identity: String, bitmap: Bitmap): String? = runCatching {
        val target = File(thumbsDir, "${identity.hashCode().toUInt()}.png")
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        target.absolutePath
    }.getOrNull()

    companion object {
        private const val MAX_ENTRIES = 24
    }
}
