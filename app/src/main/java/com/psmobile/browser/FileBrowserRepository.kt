package com.psmobile.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.psmobile.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/** A folder the browser can list. */
sealed interface BrowseTarget {
    val title: String

    /** A real directory on the filesystem; needs all-files access. */
    data class LocalDir(val path: String, override val title: String) : BrowseTarget {
        val file: File get() = File(path)
    }

    /** A directory inside a folder the user granted through the system picker. */
    data class SafDir(
        val treeUri: Uri,
        val documentId: String,
        override val title: String,
    ) : BrowseTarget
}

/** One row in the browser. Directories carry where to go next; files carry how to open them. */
data class BrowseEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val openUri: Uri?,
    val target: BrowseTarget?,
)

/**
 * Lists folders for the in-app browser.
 *
 * Two backends, because Android offers no single good one. With all-files access granted the
 * filesystem is walked directly, which is fast and can search the whole device. Without it, the
 * user grants individual folders through the system picker and those are listed over the
 * documents provider. Either way the browser only ever shows folders and Photoshop documents —
 * which is the point, since the system picker shows everything.
 */
class FileBrowserRepository(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val internalStorageLabel = appContext.getString(R.string.browser_internal_storage)

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** Intent that takes the user to the system toggle for all-files access. */
    fun allFilesAccessIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:${appContext.packageName}".toUri(),
        )
    }

    /** Storage volumes when browsing directly, otherwise the folders the user has granted. */
    fun roots(): List<BrowseTarget> = if (hasAllFilesAccess()) localRoots() else grantedTrees()

    private fun localRoots(): List<BrowseTarget> {
        val roots = mutableListOf<BrowseTarget>()
        val primary = Environment.getExternalStorageDirectory()
        if (primary != null && primary.isDirectory) {
            roots += BrowseTarget.LocalDir(primary.absolutePath, internalStorageLabel)
        }
        // Secondary volumes are reachable by walking up from the app's own directory on them.
        appContext.getExternalFilesDirs(null)
            .filterNotNull()
            .drop(1)
            .forEach { dir ->
                val volume = dir.absolutePath.substringBefore("/Android/data")
                val file = File(volume)
                if (file.isDirectory && roots.none { it is BrowseTarget.LocalDir && it.path == volume }) {
                    roots += BrowseTarget.LocalDir(volume, file.name)
                }
            }
        return roots
    }

    fun grantedTrees(): List<BrowseTarget.SafDir> = resolver.persistedUriPermissions
        .filter { it.isReadPermission }
        .mapNotNull { permission ->
            val uri = permission.uri
            val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                ?: return@mapNotNull null
            BrowseTarget.SafDir(uri, documentId, prettyTreeName(documentId))
        }

    fun rememberTree(treeUri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun forgetTree(treeUri: Uri) {
        runCatching {
            resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Well-known folders, offered as shortcuts so nobody has to walk the tree by hand. */
    fun quickLocations(): List<BrowseTarget.LocalDir> {
        if (!hasAllFilesAccess()) return emptyList()
        return listOf(
            Environment.DIRECTORY_DOWNLOADS to "Download",
            Environment.DIRECTORY_DOCUMENTS to "Documents",
            Environment.DIRECTORY_PICTURES to "Pictures",
            Environment.DIRECTORY_DCIM to "DCIM",
        ).mapNotNull { (dir, label) ->
            val file = Environment.getExternalStoragePublicDirectory(dir)
            if (file != null && file.isDirectory) BrowseTarget.LocalDir(file.absolutePath, label) else null
        }
    }

    suspend fun list(target: BrowseTarget): List<BrowseEntry> = withContext(Dispatchers.IO) {
        when (target) {
            is BrowseTarget.LocalDir -> listLocal(target)
            is BrowseTarget.SafDir -> listSaf(target)
        }
    }

    private fun listLocal(target: BrowseTarget.LocalDir): List<BrowseEntry> {
        val children = target.file.listFiles() ?: return emptyList()
        return children
            .asSequence()
            .filter { !it.name.startsWith('.') }
            .filter { it.isDirectory || it.name.isPsdLike() }
            .map { file ->
                BrowseEntry(
                    name = file.name,
                    isDirectory = file.isDirectory,
                    size = if (file.isDirectory) 0L else file.length(),
                    lastModified = file.lastModified(),
                    openUri = if (file.isDirectory) null else Uri.fromFile(file),
                    target = if (file.isDirectory) {
                        BrowseTarget.LocalDir(file.absolutePath, file.name)
                    } else {
                        null
                    },
                )
            }
            .sortedWith(ENTRY_ORDER)
            .toList()
    }

    private fun listSaf(target: BrowseTarget.SafDir): List<BrowseEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(target.treeUri, target.documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = mutableListOf<BrowseEntry>()
        // One cursor for the whole folder: DocumentFile.listFiles() would query per child.
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2).orEmpty()
                val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                if (name.startsWith('.')) continue
                if (!isDirectory && !name.isPsdLike()) continue
                out += BrowseEntry(
                    name = name,
                    isDirectory = isDirectory,
                    size = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                    lastModified = if (cursor.isNull(4)) 0L else cursor.getLong(4),
                    openUri = if (isDirectory) {
                        null
                    } else {
                        DocumentsContract.buildDocumentUriUsingTree(target.treeUri, documentId)
                    },
                    target = if (isDirectory) BrowseTarget.SafDir(target.treeUri, documentId, name) else null,
                )
            }
        }
        return out.sortedWith(ENTRY_ORDER)
    }

    /**
     * Walks every accessible folder looking for Photoshop documents, emitting them as they turn
     * up so the list fills in while the search continues.
     */
    fun scanForDocuments(): Flow<BrowseEntry> = flow {
        val roots = roots()
        for (root in roots) {
            when (root) {
                is BrowseTarget.LocalDir -> walkLocal(root.file, 0) { emit(it) }
                is BrowseTarget.SafDir -> walkSaf(root, 0) { emit(it) }
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend inline fun walkLocal(dir: File, depth: Int, crossinline emit: suspend (BrowseEntry) -> Unit) {
        if (depth > MAX_SCAN_DEPTH) return
        val pending = ArrayDeque<Pair<File, Int>>()
        pending += dir to depth
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (current, level) = pending.removeFirst()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith('.')) continue
                if (child.isDirectory) {
                    // App-private sandboxes hold nothing a person put there on purpose.
                    if (child.absolutePath.endsWith("/Android/data")) continue
                    if (child.absolutePath.endsWith("/Android/obb")) continue
                    if (level < MAX_SCAN_DEPTH) pending += child to (level + 1)
                } else if (child.name.isPsdLike()) {
                    emit(
                        BrowseEntry(
                            name = child.name,
                            isDirectory = false,
                            size = child.length(),
                            lastModified = child.lastModified(),
                            openUri = Uri.fromFile(child),
                            target = null,
                        ),
                    )
                }
            }
        }
    }

    private suspend inline fun walkSaf(
        root: BrowseTarget.SafDir,
        depth: Int,
        crossinline emit: suspend (BrowseEntry) -> Unit,
    ) {
        val pending = ArrayDeque<Pair<BrowseTarget.SafDir, Int>>()
        pending += root to depth
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (current, level) = pending.removeFirst()
            for (entry in listSaf(current)) {
                if (entry.isDirectory) {
                    val next = entry.target as? BrowseTarget.SafDir ?: continue
                    if (level < MAX_SCAN_DEPTH) pending += next to (level + 1)
                } else {
                    emit(entry)
                }
            }
        }
    }

    private fun prettyTreeName(documentId: String): String {
        val path = documentId.substringAfter(':', "")
        if (path.isBlank()) return internalStorageLabel
        return path.trimEnd('/').substringAfterLast('/')
    }

    companion object {
        private const val MAX_SCAN_DEPTH = 12

        /** Folders first, then newest documents first. */
        private val ENTRY_ORDER = compareByDescending<BrowseEntry> { it.isDirectory }
            .thenByDescending { if (it.isDirectory) 0L else it.lastModified }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }
}

/** The two extensions this app claims. */
fun String.isPsdLike(): Boolean {
    val dot = lastIndexOf('.')
    if (dot < 0) return false
    val ext = substring(dot + 1).lowercase()
    return ext == "psd" || ext == "psb"
}
