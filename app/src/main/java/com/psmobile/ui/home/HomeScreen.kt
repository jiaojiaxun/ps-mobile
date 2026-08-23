package com.psmobile.ui.home

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.psmobile.R
import com.psmobile.data.RecentFile
import com.psmobile.ui.components.AuroraBackground
import com.psmobile.ui.components.LayerStackMark
import com.psmobile.ui.theme.PsColors
import com.psmobile.util.formatDimensions
import com.psmobile.util.formatFileSize
import com.psmobile.util.formatMegapixels
import com.psmobile.util.formatRelativeTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    recents: List<RecentFile>,
    updateVersion: String?,
    onOpenFile: () -> Unit,
    onNewDocument: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenRecent: (RecentFile) -> Unit,
    onForgetRecent: (RecentFile) -> Unit,
    onClearRecents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    AuroraBackground(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                StaggeredAppear(appeared, index = 0) { Header(onOpenSettings = onOpenSettings) }
            }

            if (updateVersion != null) {
                item(key = "update") {
                    StaggeredAppear(appeared, index = 1) {
                        UpdateBanner(version = updateVersion, onClick = onOpenUpdate)
                    }
                }
            }

            item(key = "actions") {
                StaggeredAppear(appeared, index = 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionCard(
                            title = stringResource(R.string.action_open),
                            subtitle = stringResource(R.string.action_open_hint),
                            icon = { tint -> Icon(Icons.Rounded.FolderOpen, null, tint = tint) },
                            highlighted = true,
                            onClick = onOpenFile,
                        )
                        ActionCard(
                            title = stringResource(R.string.action_new),
                            subtitle = stringResource(R.string.action_new_hint),
                            icon = { tint -> Icon(Icons.Rounded.AddPhotoAlternate, null, tint = tint) },
                            highlighted = false,
                            onClick = onNewDocument,
                        )
                    }
                }
            }

            item(key = "recent-title") {
                StaggeredAppear(appeared, index = 2) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.recent_title),
                            style = MaterialTheme.typography.labelSmall,
                            color = PsColors.TextTertiary,
                        )
                        Spacer(Modifier.weight(1f))
                        if (recents.isNotEmpty()) {
                            TextButton(onClick = onClearRecents) {
                                Text(
                                    text = stringResource(R.string.recent_clear),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PsColors.TextTertiary,
                                )
                            }
                        }
                    }
                }
            }

            if (recents.isEmpty()) {
                item(key = "recent-empty") {
                    StaggeredAppear(appeared, index = 3) { EmptyRecents() }
                }
            } else {
                items(recents, key = { it.id }) { entry ->
                    RecentCard(
                        entry = entry,
                        onClick = { onOpenRecent(entry) },
                        onForget = { onForgetRecent(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StaggeredAppear(visible: Boolean, index: Int, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(durationMillis = 320, delayMillis = index * 70)) +
            slideInVertically(
                animationSpec = tween(
                    durationMillis = 400,
                    delayMillis = index * 70,
                    easing = LinearOutSlowInEasing,
                ),
                initialOffsetY = { it / 4 },
            ),
    ) {
        content()
    }
}

/** A quiet nudge that a newer build is published; the details live in settings. */
@Composable
private fun UpdateBanner(version: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PsColors.Accent.copy(alpha = 0.12f))
            .border(1.dp, PsColors.Accent.copy(alpha = 0.32f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.CloudDownload,
            contentDescription = null,
            tint = PsColors.Accent,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.update_banner, version),
            style = MaterialTheme.typography.bodyMedium,
            color = PsColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.update_banner_action),
            style = MaterialTheme.typography.labelSmall,
            color = PsColors.Accent,
        )
    }
}

@Composable
private fun Header(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LayerStackMark(size = 46.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.home_greeting),
                style = MaterialTheme.typography.displaySmall,
                color = PsColors.TextPrimary,
            )
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = PsColors.TextTertiary,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.home_settings),
                tint = PsColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    subtitle: String,
    icon: @Composable (Color) -> Unit,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (highlighted) {
                    Brush.horizontalGradient(
                        listOf(PsColors.Accent.copy(alpha = 0.20f), PsColors.Accent.copy(alpha = 0.06f)),
                    )
                } else {
                    Brush.horizontalGradient(listOf(PsColors.Ink600, PsColors.Ink600))
                },
            )
            .border(
                width = 1.dp,
                color = if (highlighted) PsColors.Accent.copy(alpha = 0.36f) else PsColors.Ink500,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (highlighted) PsColors.Accent.copy(alpha = 0.20f) else PsColors.Ink500),
            contentAlignment = Alignment.Center,
        ) {
            icon(if (highlighted) PsColors.Accent else PsColors.TextSecondary)
        }
        Spacer(Modifier.width(15.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = PsColors.TextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = PsColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun EmptyRecents(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PsColors.Ink700.copy(alpha = 0.6f))
            .border(1.dp, PsColors.Ink600, RoundedCornerShape(18.dp))
            .padding(vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.Layers,
            contentDescription = null,
            tint = PsColors.Ink300,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.recent_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = PsColors.TextSecondary,
        )
        Text(
            text = stringResource(R.string.recent_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = PsColors.TextTertiary,
        )
    }
}

@Composable
private fun RecentCard(
    entry: RecentFile,
    onClick: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PsColors.Ink700)
            .border(1.dp, PsColors.Ink600, shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecentThumbnail(entry)

        Spacer(Modifier.width(13.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
                color = PsColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${formatDimensions(entry.width, entry.height)}  ·  ${formatMegapixels(entry.width, entry.height)}",
                style = MaterialTheme.typography.bodySmall,
                color = PsColors.TextSecondary,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            val resources = LocalContext.current.resources
            Text(
                text = buildString {
                    append(if (entry.isPsb) "PSB" else "PSD")
                    append("  ·  ")
                    append(formatFileSize(entry.fileSize))
                    if (entry.layerCount > 0) {
                        append("  ·  ")
                        append(resources.getString(R.string.recent_layers, entry.layerCount))
                    }
                    append("  ·  ")
                    append(formatRelativeTime(resources, entry.openedAt))
                },
                style = MaterialTheme.typography.bodySmall,
                color = PsColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(onClick = onForget) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.recent_remove),
                tint = PsColors.Ink300,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun RecentThumbnail(entry: RecentFile, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(11.dp)
    val bitmap by produceState<ImageBitmap?>(initialValue = null, entry.thumbnail) {
        val path = entry.thumbnail ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }

    Box(
        modifier = modifier
            .size(58.dp)
            .clip(shape)
            .background(PsColors.Ink500),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Layers,
                contentDescription = null,
                tint = PsColors.Ink300,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
