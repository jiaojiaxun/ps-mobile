package com.psmobile.browser

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class BrowserState(
    val hasAllFilesAccess: Boolean = false,
    val roots: List<BrowseTarget> = emptyList(),
    val quickLocations: List<BrowseTarget.LocalDir> = emptyList(),
    /** Breadcrumb from a root down to the folder on screen; empty means the roots list. */
    val path: List<BrowseTarget> = emptyList(),
    val entries: List<BrowseEntry> = emptyList(),
    val loading: Boolean = false,
    val scanning: Boolean = false,
    val scanResults: List<BrowseEntry> = emptyList(),
    val query: String = "",
) {
    val current: BrowseTarget? get() = path.lastOrNull()
    val isSearching: Boolean get() = scanning || scanResults.isNotEmpty()

    /** What the list actually shows, after the name filter. */
    val visible: List<BrowseEntry>
        get() {
            val source = if (isSearching) scanResults else entries
            if (query.isBlank()) return source
            return source.filter { it.name.contains(query, ignoreCase = true) }
        }
}

/**
 * Drives the in-app browser.
 *
 * Navigation is a plain stack of folders, which keeps "up" honest whether the current folder
 * came from the filesystem or from a granted tree.
 */
class FileBrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FileBrowserRepository(application)

    var state by mutableStateOf(BrowserState())
        private set

    private var listJob: Job? = null
    private var scanJob: Job? = null
    private var scannedThisSession = false

    init {
        refresh()
    }

    /** Re-reads the access state; called on resume because it can change in system settings. */
    fun refresh() {
        val hasAccess = repository.hasAllFilesAccess()
        val roots = repository.roots()
        state = state.copy(
            hasAllFilesAccess = hasAccess,
            roots = roots,
            quickLocations = repository.quickLocations(),
        )
        // A single root is not worth a chooser screen — go straight in.
        if (state.path.isEmpty() && roots.size == 1) {
            open(roots.first())
        } else if (state.path.isNotEmpty()) {
            reload()
        }
    }

    /**
     * Runs the device search the first time the browser is opened in a session.
     *
     * Opening the browser should land on documents, not on a folder tree to dig through. It is
     * deliberately not wired to [refresh], which also fires when the app merely comes back to
     * the foreground — walking the filesystem is not something to do behind the user's back.
     */
    fun autoScanIfNeeded() {
        if (!state.hasAllFilesAccess || scannedThisSession || state.roots.isEmpty()) return
        scanDevice()
    }

    fun open(target: BrowseTarget) {
        clearScan()
        state = state.copy(path = state.path + target, entries = emptyList(), loading = true)
        reload()
    }

    fun navigateUp(): Boolean {
        if (state.isSearching) {
            clearScan()
            return true
        }
        if (state.path.isEmpty()) return false
        clearScan()
        state = state.copy(path = state.path.dropLast(1), entries = emptyList(), loading = true)
        if (state.path.isEmpty()) {
            state = state.copy(loading = false)
        } else {
            reload()
        }
        return true
    }

    fun jumpTo(index: Int) {
        if (index !in state.path.indices) return
        clearScan()
        state = state.copy(path = state.path.take(index + 1), entries = emptyList(), loading = true)
        reload()
    }

    private fun reload() {
        val target = state.current ?: return
        listJob?.cancel()
        listJob = viewModelScope.launch {
            val entries = runCatching { repository.list(target) }.getOrDefault(emptyList())
            state = state.copy(entries = entries, loading = false)
        }
    }

    fun setQuery(query: String) {
        state = state.copy(query = query)
    }

    /** Searches every accessible folder, filling the list as documents are found. */
    fun scanDevice() {
        scannedThisSession = true
        scanJob?.cancel()
        state = state.copy(scanning = true, scanResults = emptyList())
        scanJob = viewModelScope.launch {
            val found = mutableListOf<BrowseEntry>()
            runCatching {
                repository.scanForDocuments().collect { entry ->
                    found += entry
                    // Publish in batches, unsorted: the final ordering is applied once at the
                    // end so a long scan does not re-sort a growing list on every hit.
                    if (found.size <= 8 || found.size % 8 == 0) {
                        state = state.copy(scanResults = found.toList())
                    }
                }
            }
            state = state.copy(
                scanning = false,
                scanResults = found.sortedByDescending { it.lastModified },
            )
        }
    }

    fun clearScan() {
        scanJob?.cancel()
        scanJob = null
        if (state.scanning || state.scanResults.isNotEmpty()) {
            state = state.copy(scanning = false, scanResults = emptyList())
        }
    }

    fun rememberTree(treeUri: Uri) {
        repository.rememberTree(treeUri)
        refresh()
        repository.grantedTrees().firstOrNull { it.treeUri == treeUri }?.let { open(it) }
    }

    fun forgetTree(target: BrowseTarget.SafDir) {
        repository.forgetTree(target.treeUri)
        state = state.copy(path = emptyList(), entries = emptyList())
        refresh()
    }

    fun allFilesAccessIntent() = repository.allFilesAccessIntent()

    override fun onCleared() {
        DocumentPreviews.clear()
        super.onCleared()
    }
}
