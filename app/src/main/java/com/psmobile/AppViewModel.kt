package com.psmobile

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.psmobile.data.RecentFile
import com.psmobile.data.RecentFilesStore
import com.psmobile.document.DocumentOpener
import com.psmobile.document.DocumentSession
import com.psmobile.psd.ParsePhase
import com.psmobile.psd.ParseProgress
import com.psmobile.psd.PsdParser
import com.psmobile.psd.PsdWriter
import com.psmobile.psd.ProgressListener
import com.psmobile.psd.io.ByteArraySource
import com.psmobile.psd.model.PsdFormatException
import com.psmobile.settings.AppSettings
import com.psmobile.settings.SettingsStore
import com.psmobile.update.UpdateManager
import com.psmobile.ui.newdoc.NewDocumentSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** Top-level destinations. The viewer holds a live session, so this is not a serialisable graph. */
sealed interface AppScreen {
    data object Home : AppScreen
    data object Browser : AppScreen
    data object Settings : AppScreen
    data class Loading(val title: String) : AppScreen
    data class Viewer(val session: DocumentSession) : AppScreen
    class Failure(val title: String, val message: String, val retryUri: Uri?) : AppScreen
}

/**
 * Owns the open document and drives the open/close lifecycle.
 *
 * Opening is a single cancellable job: starting a new one tears down whatever was in flight,
 * which is what makes the loading screen's cancel button honest rather than cosmetic.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    val recents = RecentFilesStore(application)
    val settings = SettingsStore(application)
    val updates = UpdateManager(application, viewModelScope)

    var screen by mutableStateOf<AppScreen>(AppScreen.Home)
        private set

    var progress by mutableStateOf(ParseProgress(ParsePhase.OPENING))
        private set

    private var openJob: Job? = null
    private var session: DocumentSession? = null

    init {
        viewModelScope.launch { recents.load() }
        if (settings.current.autoCheckUpdates) updates.check(force = false)
    }

    fun open(uri: Uri, intentFlags: Int = 0) {
        val context = getApplication<Application>()
        DocumentOpener.persistAccess(context, uri, intentFlags)

        openJob?.cancel()
        val name = runCatching { DocumentOpener.displayName(context, uri) }.getOrDefault("document")
        progress = ParseProgress(ParsePhase.OPENING, -1f, name)
        screen = AppScreen.Loading(name)

        openJob = viewModelScope.launch {
            try {
                val document = withContext(Dispatchers.IO) {
                    DocumentOpener.open(context, uri, ProgressListener { p -> progress = p })
                }
                finishOpen(DocumentSession(document, uri, name, supersample = settings.current.renderQuality.supersample))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                screen = AppScreen.Failure(
                    title = context.getString(R.string.error_title),
                    message = describe(t),
                    retryUri = uri,
                )
            }
        }
    }

    /**
     * Builds a blank document in memory and opens it through the ordinary pipeline, so the
     * viewer, layers panel and info sheet all behave exactly as they do for a real file.
     */
    fun createBlank(spec: NewDocumentSpec) {
        openJob?.cancel()
        progress = ParseProgress(ParsePhase.OPENING, -1f, spec.name)
        screen = AppScreen.Loading(spec.name)

        openJob = viewModelScope.launch {
            try {
                val document = withContext(Dispatchers.IO) {
                    val bytes = PsdWriter.createBlank(
                        width = spec.width,
                        height = spec.height,
                        colorMode = spec.colorMode,
                        background = spec.background,
                        customColor = spec.customColor,
                        resolution = spec.resolution,
                    )
                    PsdParser(ByteArraySource(bytes), spec.name)
                        .parse(ProgressListener { p -> progress = p })
                }
                finishOpen(
                    DocumentSession(
                        document, null, spec.name,
                        isScratch = true,
                        supersample = settings.current.renderQuality.supersample,
                    ),
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val context = getApplication<Application>()
                screen = AppScreen.Failure(
                    title = context.getString(R.string.error_title),
                    message = describe(t),
                    retryUri = null,
                )
            }
        }
    }

    private suspend fun finishOpen(next: DocumentSession) {
        progress = ParseProgress(ParsePhase.PREVIEW, 0f, next.displayName)
        next.tiles.primeOverview()
        progress = ParseProgress(ParsePhase.DONE, 1f, next.displayName)

        session?.close()
        session = next
        screen = AppScreen.Viewer(next)

        if (!next.isScratch && next.uri != null) rememberRecent(next)
    }

    private fun rememberRecent(session: DocumentSession) {
        val uri = session.uri ?: return
        viewModelScope.launch {
            val context = getApplication<Application>()
            val doc = session.document
            val identity = runCatching {
                DocumentOpener.identityOf(context, uri, session.displayName, doc.fileSize)
            }.getOrDefault("")
            val thumbnail = runCatching { session.tiles.renderThumbnail(THUMBNAIL_SIZE) }.getOrNull()
            recents.remember(
                RecentFile(
                    uri = uri.toString(),
                    name = session.displayName,
                    fileSize = doc.fileSize,
                    width = doc.width,
                    height = doc.height,
                    depth = doc.depth,
                    colorMode = doc.colorMode.label,
                    layerCount = doc.visibleLayerCount,
                    isPsb = doc.header.isPsb,
                    openedAt = System.currentTimeMillis(),
                    key = identity,
                ),
                thumbnail,
            )
        }
    }

    fun openBrowser() {
        screen = AppScreen.Browser
    }

    fun openSettings() {
        screen = AppScreen.Settings
    }

    /** Applies a settings change to the open document without making it reopen. */
    fun updateSettings(next: AppSettings) {
        val previous = settings.current
        settings.update { next }
        if (next.renderQuality != previous.renderQuality) {
            session?.tiles?.setSupersample(next.renderQuality.supersample)
        }
    }

    fun goHome() {
        screen = AppScreen.Home
    }

    fun cancelOpen() {
        openJob?.cancel()
        openJob = null
        screen = AppScreen.Home
    }

    fun closeDocument() {
        session?.close()
        session = null
        screen = AppScreen.Home
    }

    fun forgetRecent(uri: String) {
        viewModelScope.launch { recents.remove(uri) }
    }

    fun clearRecents() {
        viewModelScope.launch { recents.clear() }
    }

    private fun describe(t: Throwable): String {
        val context = getApplication<Application>()
        return when (t) {
            is PsdFormatException -> t.message ?: context.getString(R.string.error_unsupported)
            is FileNotFoundException, is SecurityException -> context.getString(R.string.error_no_access)
            is OutOfMemoryError -> "Не хватило памяти для этого документа"
            else -> t.message ?: context.getString(R.string.error_generic)
        }
    }

    override fun onCleared() {
        session?.close()
        session = null
        super.onCleared()
    }

    companion object {
        private const val THUMBNAIL_SIZE = 320
    }
}
