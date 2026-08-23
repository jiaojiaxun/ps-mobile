package com.psmobile.document

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.psmobile.psd.PsdDocument
import com.psmobile.psd.PsdParser
import com.psmobile.psd.ProgressListener
import com.psmobile.psd.io.ChannelSource
import com.psmobile.psd.io.SeekableSource
import java.io.File
import java.io.FileNotFoundException

/**
 * Turns a platform [Uri] into something the parser can read at random offsets.
 *
 * The whole design depends on seeking, so the descriptor is opened once and kept alive for as
 * long as the document is on screen. Nothing is ever copied into the cache — an 800 MB PSB
 * would not survive that.
 */
object DocumentOpener {

    fun open(context: Context, uri: Uri, listener: ProgressListener?): PsdDocument {
        val name = displayName(context, uri)
        val source = openSeekable(context, uri)
        return try {
            PsdParser(source, name).parse(listener)
        } catch (t: Throwable) {
            source.close()
            throw t
        }
    }

    /** Opens a random-access view of [uri]. The caller owns the result and must close it. */
    fun openSeekable(context: Context, uri: Uri): SeekableSource {
        if (uri.scheme == ContentResolver.SCHEME_FILE || uri.scheme == null) {
            val path = uri.path ?: throw FileNotFoundException("Empty path")
            val file = File(path)
            if (!file.isFile) throw FileNotFoundException(path)
            return ChannelSource.of(file)
        }

        val descriptor: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw FileNotFoundException(uri.toString())
        return try {
            ChannelSource.of(descriptor.fileDescriptor, descriptor.statSize, descriptor)
        } catch (t: Throwable) {
            descriptor.close()
            throw t
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_FILE || uri.scheme == null) {
            return uri.lastPathSegment ?: "document"
        }
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "document"
    }

    /**
     * A stable identity for a document, independent of how it was reached.
     *
     * The same file yields a different [Uri] depending on the route — `file://` from the in-app
     * browser, `content://media/...` from a file manager, a documents-provider URI from the
     * system picker — so the recents list would otherwise collect one entry per route. Resolving
     * to a real path where possible collapses those onto one another; where it is not possible,
     * name and exact byte size are a good enough match in practice.
     */
    fun identityOf(context: Context, uri: Uri, displayName: String, size: Long): String {
        resolvePath(context, uri)?.let { return "path:$it" }
        return "meta:$displayName:$size"
    }

    private fun resolvePath(context: Context, uri: Uri): String? = runCatching {
        if (uri.scheme == ContentResolver.SCHEME_FILE || uri.scheme == null) return uri.path

        if (uri.authority == EXTERNAL_STORAGE_PROVIDER && DocumentsContract.isDocumentUri(context, uri)) {
            val documentId = DocumentsContract.getDocumentId(uri)
            val volume = documentId.substringBefore(':', "")
            val relative = documentId.substringAfter(':', "")
            if (volume.equals("primary", ignoreCase = true) && relative.isNotEmpty()) {
                return Environment.getExternalStorageDirectory()?.absolutePath?.let { "$it/$relative" }
            }
            return null
        }

        if (uri.authority == MediaStore.AUTHORITY) {
            @Suppress("DEPRECATION")
            val column = MediaStore.MediaColumns.DATA
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(column)
                if (index >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(index)?.takeIf { it.isNotBlank() }
                }
            }
        }
        null
    }.getOrNull()

    private const val EXTERNAL_STORAGE_PROVIDER = "com.android.externalstorage.documents"

    /** Best-effort: lets a document picked through the picker survive an app restart. */
    fun persistAccess(context: Context, uri: Uri, flags: Int) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        val readable = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (readable == 0) return
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    val PICKER_MIME_TYPES = arrayOf(
        "image/vnd.adobe.photoshop",
        "image/psd",
        "image/x-psd",
        "application/x-photoshop",
        "application/octet-stream",
        "*/*",
    )
}
