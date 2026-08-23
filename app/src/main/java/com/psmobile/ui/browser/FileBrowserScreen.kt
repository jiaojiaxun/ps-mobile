package com.psmobile.ui.browser

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.psmobile.R
import com.psmobile.browser.BrowseEntry
import com.psmobile.browser.BrowseTarget
import com.psmobile.browser.BrowserState
import com.psmobile.browser.DocumentPreview
import com.psmobile.browser.DocumentPreviews
import com.psmobile.ui.components.AuroraBackground
import com.psmobile.ui.theme.PsColors
import com.psmobile.util.formatDimensions
import com.psmobile.util.formatFileSize
import com.psmobile.util.formatRelativeTime

/**
 * The in-app file browser.
 *
 * The system picker was the obvious choice and the wrong one: it lists every file on the device
 * and cannot filter by extension, so finding a PSD among photos and downloads is a chore. This
 * shows folders and Photoshop documents and nothing else, previews them, and can search the
 * whole device in one go.
 */
@Composable
fun FileBrowserScreen(
    state: BrowserState,
    showPreviews: Boolean,
    onBack: () -> Unit,
    onOpenTarget: (BrowseTarget) -> Unit,
    onJumpTo: (Int) -> Unit,
    onOpenFile: (Uri) -> Unit,
    onQueryChange: (String) -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onGrantAllFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onSystemPicker: () -> Unit,
    onForgetTree: (BrowseTarget.SafDir) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchOpen by remember { mutableStateOf(false) }

    AuroraBackground(modifier = modifier.fillMaxSize(), animated = false) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            BrowserTopBar(
                title = state.current?.title ?: stringResource(R.string.browser_title),
                searchOpen = searchOpen,
                query = state.query,
                onBack = onBack,
                onToggleSearch = {
                    searchOpen = !searchOpen
                    if (!searchOpen) onQueryChange("")
                },
                onQueryChange = onQueryChange,
            )

            if (state.path.size > 1 && !state.isSearching) {
                Breadcrumbs(state.path, onJumpTo)
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!state.hasAllFilesAccess) {
                    item(key = "access") {
                        AccessCard(
                            onGrantAllFiles = onGrantAllFiles,
                            onPickFolder = onPickFolder,
                            onSystemPicker = onSystemPicker,
                        )
                    }
                }

                if (state.path.size <= 1 && !state.isSearching) {
                    if (state.roots.size > 1) {
                        item(key = "roots-title") { SectionLabel(stringResource(R.string.browser_roots)) }
                        items(state.roots, key = { it.title + it.hashCode() }) { root ->
                            RootRow(
                                target = root,
                                onClick = { onOpenTarget(root) },
                                onForget = (root as? BrowseTarget.SafDir)?.let { { onForgetTree(it) } },
                            )
                        }
                    }
                    if (state.quickLocations.isNotEmpty()) {
                        item(key = "quick") {
                            SectionLabel(stringResource(R.string.browser_quick))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                state.quickLocations.forEach { location ->
                                    Chip(location.title) { onOpenTarget(location) }
                                }
                            }
                        }
                    }
                }

                item(key = "scan") {
                    ScanRow(
                        scanning = state.scanning,
                        resultCount = state.scanResults.size,
                        enabled = state.roots.isNotEmpty(),
                        onScan = onScan,
                        onStop = onStopScan,
                    )
                }

                val visible = state.visible
                if (state.loading) {
                    item(key = "loading") { LoadingRow() }
                } else if (visible.isEmpty() && (state.path.isNotEmpty() || state.isSearching)) {
                    item(key = "empty") { EmptyState() }
                }

                items(visible, key = { it.openUri?.toString() ?: it.name + it.lastModified }) { entry ->
                    if (entry.isDirectory) {
                        FolderRow(entry) { entry.target?.let(onOpenTarget) }
                    } else {
                        DocumentRow(entry, showPreviews) { entry.openUri?.let(onOpenFile) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserTopBar(
    title: String,
    searchOpen: Boolean,
    query: String,
    onBack: () -> Unit,
    onToggleSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.viewer_back), tint = PsColors.TextPrimary)
        }
        if (searchOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(stringResource(R.string.browser_search_hint), style = MaterialTheme.typography.bodyMedium)
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PsColors.Accent,
                    unfocusedBorderColor = PsColors.Ink400,
                    focusedTextColor = PsColors.TextPrimary,
                    unfocusedTextColor = PsColors.TextPrimary,
                    cursorColor = PsColors.Accent,
                ),
            )
        } else {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = PsColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f),
            )
        }
        IconButton(onClick = onToggleSearch) {
            Icon(
                imageVector = if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                contentDescription = stringResource(R.string.browser_search_hint),
                tint = PsColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun Breadcrumbs(path: List<BrowseTarget>, onJumpTo: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        path.forEachIndexed { index, target ->
            if (index > 0) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = PsColors.Ink300,
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(
                text = target.title,
                style = MaterialTheme.typography.bodySmall,
                color = if (index == path.lastIndex) PsColors.TextSecondary else PsColors.TextTertiary,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onJumpTo(index) }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = PsColors.TextTertiary,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PsColors.Ink600)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = PsColors.TextSecondary)
    }
}

@Composable
private fun AccessCard(
    onGrantAllFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onSystemPicker: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PsColors.Accent.copy(alpha = 0.10f))
            .border(1.dp, PsColors.Accent.copy(alpha = 0.30f), shape)
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.browser_access_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = PsColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.browser_access_body),
            style = MaterialTheme.typography.bodySmall,
            color = PsColors.TextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onGrantAllFiles,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PsColors.Accent,
                contentColor = PsColors.Ink900,
            ),
        ) {
            Text(stringResource(R.string.browser_access_grant), fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onPickFolder) {
                Text(
                    stringResource(R.string.browser_pick_folder),
                    style = MaterialTheme.typography.bodySmall,
                    color = PsColors.TextSecondary,
                )
            }
            TextButton(onClick = onSystemPicker) {
                Text(
                    stringResource(R.string.browser_system_picker),
                    style = MaterialTheme.typography.bodySmall,
                    color = PsColors.TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun ScanRow(
    scanning: Boolean,
    resultCount: Int,
    enabled: Boolean,
    onScan: () -> Unit,
    onStop: () -> Unit,
) {
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp)
            .clip(shape)
            .background(PsColors.Ink700)
            .border(1.dp, PsColors.Ink600, shape)
            .clickable(enabled = enabled) { if (scanning) onStop() else onScan() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (scanning) {
            CircularProgressIndicator(
                modifier = Modifier.size(17.dp),
                strokeWidth = 2.dp,
                color = PsColors.Accent,
            )
        } else {
            Icon(Icons.Rounded.Search, null, tint = PsColors.Accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(11.dp))
        Text(
            text = if (scanning) {
                stringResource(R.string.browser_scanning)
            } else if (resultCount > 0) {
                stringResource(R.string.browser_scan_results, resultCount)
            } else {
                stringResource(R.string.browser_scan)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) PsColors.TextPrimary else PsColors.TextTertiary,
            modifier = Modifier.weight(1f),
        )
        if (scanning) {
            Text(
                text = stringResource(R.string.browser_stop),
                style = MaterialTheme.typography.labelSmall,
                color = PsColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun RootRow(target: BrowseTarget, onClick: () -> Unit, onForget: (() -> Unit)?) {
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PsColors.Ink700)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Storage, null, tint = PsColors.Accent, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = target.title,
            style = MaterialTheme.typography.bodyMedium,
            color = PsColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (onForget != null) {
            IconButton(onClick = onForget, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    stringResource(R.string.browser_forget_folder),
                    tint = PsColors.Ink300,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun FolderRow(entry: BrowseEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PsColors.Ink600),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Folder, null, tint = PsColors.TextTertiary, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            color = PsColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            null,
            tint = PsColors.Ink300,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun DocumentRow(entry: BrowseEntry, showPreviews: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val resources = context.resources
    val uri = entry.openUri

    val preview by produceState<DocumentPreview?>(initialValue = null, uri, showPreviews) {
        val target = uri ?: return@produceState
        value = DocumentPreviews.load(context, target, renderPreview = showPreviews)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PsColors.Ink700)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DocumentThumbnail(preview, entry.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                color = PsColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Spacer(Modifier.height(2.dp))
            val info = preview?.info
            Text(
                text = buildString {
                    if (info != null) {
                        append(formatDimensions(info.width, info.height))
                        append("  ·  ")
                    }
                    append(formatFileSize(entry.size))
                    if (entry.lastModified > 0) {
                        append("  ·  ")
                        append(formatRelativeTime(resources, entry.lastModified))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = PsColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DocumentThumbnail(preview: DocumentPreview?, name: String) {
    val badge = if (name.endsWith(".psb", ignoreCase = true)) "PSB" else "PSD"
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PsColors.Ink500),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = preview?.bitmap
        AnimatedVisibility(visible = bitmap != null, enter = fadeIn(), exit = fadeOut()) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        if (bitmap == null) {
            if (preview == null) {
                Icon(Icons.Rounded.Layers, null, tint = PsColors.Ink300, modifier = Modifier.size(17.dp))
            } else {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = PsColors.Accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun LoadingRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = PsColors.Accent,
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.CreateNewFolder,
            null,
            tint = PsColors.Ink300,
            modifier = Modifier.size(30.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.browser_empty),
            style = MaterialTheme.typography.titleMedium,
            color = PsColors.TextSecondary,
        )
        Text(
            text = stringResource(R.string.browser_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = PsColors.TextTertiary,
        )
    }
}
