package com.psmobile.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.psmobile.R
import com.psmobile.document.DocumentSession
import com.psmobile.psd.model.Layer
import com.psmobile.psd.model.LayerNode
import com.psmobile.ui.components.drawCheckerboard
import com.psmobile.ui.theme.PsColors
import com.psmobile.util.formatDimensions
import com.psmobile.util.formatFileSize
import com.psmobile.util.formatMegapixels
import com.psmobile.util.formatPixels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class LayerRow(val node: LayerNode, val depth: Int)

private fun flatten(
    nodes: List<LayerNode>,
    depth: Int,
    collapsed: Set<Int>,
    out: MutableList<LayerRow>,
) {
    for (node in nodes) {
        out += LayerRow(node, depth)
        if (node.isGroup && node.layer.index !in collapsed) {
            flatten(node.children, depth + 1, collapsed, out)
        }
    }
}

/**
 * The layers panel.
 *
 * Photoshop's own ordering — topmost layer first, groups nesting inwards — with the same
 * per-layer facts it shows: visibility, blend mode, opacity and the colour label. Previews are
 * rendered on demand as rows scroll into view, because a document can carry hundreds of
 * full-canvas layers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayersPanel(
    session: DocumentSession,
    onFocusLayer: (LayerNode) -> Unit,
    onToggleVisibility: (Layer) -> Unit,
    onResetVisibility: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val collapsed = remember { mutableStateMapOf<Int, Boolean>() }

    val rows = remember(session, collapsed.size, collapsed.entries.count { it.value }) {
        val hidden = collapsed.filterValues { it }.keys
        mutableListOf<LayerRow>().also { flatten(session.document.layerTree, 0, hidden, it) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PsColors.Ink700,
        contentColor = PsColors.TextPrimary,
        dragHandle = { PanelDragHandle() },
    ) {
        PanelHeader(
            title = stringResource(R.string.viewer_layers),
            trailing = if (rows.isEmpty()) null else "${session.document.visibleLayerCount}",
            action = if (session.hasVisibilityOverrides) stringResource(R.string.viewer_layers_reset) else null,
            onAction = onResetVisibility,
        )

        if (rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.viewer_layers_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PsColors.TextTertiary,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.86f)
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(rows, key = { it.node.layer.index }) { row ->
                    LayerRowItem(
                        session = session,
                        row = row,
                        collapsed = collapsed[row.node.layer.index] == true,
                        onToggleCollapse = {
                            val key = row.node.layer.index
                            collapsed[key] = !(collapsed[key] ?: false)
                        },
                        onFocus = { onFocusLayer(row.node) },
                        onToggleVisibility = { onToggleVisibility(row.node.layer) },
                    )
                    HorizontalDivider(color = PsColors.Ink600, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun LayerRowItem(
    session: DocumentSession,
    row: LayerRow,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onFocus: () -> Unit,
    onToggleVisibility: () -> Unit,
) {
    val layer = row.node.layer
    val label = PsColors.LayerLabels.getOrElse(layer.colorLabel) { PsColors.LayerLabels[0] }
    val hiddenLabel = stringResource(R.string.viewer_layer_hidden)

    // Reading the revision keeps every row in step with the canvas after a visibility change.
    @Suppress("UNUSED_EXPRESSION")
    session.visibilityRevision
    val visible = session.isVisible(layer)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onFocus)
            .padding(start = (2 + row.depth * 14).dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(label),
        )

        // A full touch target: tapping the row jumps the canvas to the layer, so an eye that is
        // any smaller turns a near miss into an unexpected navigation.
        IconButton(onClick = onToggleVisibility, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                contentDescription = stringResource(
                    if (visible) R.string.viewer_layer_hide else R.string.viewer_layer_show,
                ),
                tint = if (visible) PsColors.TextSecondary else PsColors.Ink300,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(2.dp))

        Box(Modifier.alpha(if (visible) 1f else 0.4f)) {
            LayerPreview(session, layer, row.node.isGroup)
        }
        Spacer(Modifier.width(11.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = layer.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (visible) PsColors.TextPrimary else PsColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(layer.blendMode.label)
                    if (layer.opacity < 255) append("  ·  ${layer.opacityPercent}%")
                    if (!layer.isEmpty) {
                        append("  ·  ")
                        append("${formatPixels(layer.width)}×${formatPixels(layer.height)}")
                    }
                    if (!visible) append("  ·  $hiddenLabel")
                },
                style = MaterialTheme.typography.bodySmall,
                color = PsColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (row.node.isGroup) {
            IconButton(onClick = onToggleCollapse, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                    contentDescription = null,
                    tint = PsColors.Ink300,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun LayerPreview(session: DocumentSession, layer: Layer, isGroup: Boolean) {
    val shape = RoundedCornerShape(6.dp)

    if (isGroup || layer.isEmpty) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(shape)
                .background(PsColors.Ink600),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when {
                    isGroup -> Icons.Rounded.Folder
                    layer.isAdjustment -> Icons.Rounded.Tune
                    layer.hasText -> Icons.Rounded.TextFields
                    else -> Icons.Rounded.Folder
                },
                contentDescription = null,
                tint = PsColors.Ink300,
                modifier = Modifier.size(17.dp),
            )
        }
        return
    }

    val preview by produceState<ImageBitmap?>(initialValue = null, layer.index) {
        value = withContext(Dispatchers.IO) {
            val thumb = session.layerThumbnail(layer, PREVIEW_SIZE) ?: return@withContext null
            runCatching {
                Bitmap.createBitmap(thumb.pixels, 0, thumb.width, thumb.width, thumb.height, Bitmap.Config.ARGB_8888)
                    .asImageBitmap()
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(shape)
            .background(PsColors.Ink600),
        contentAlignment = Alignment.Center,
    ) {
        val image = preview
        if (image != null) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawCheckerboard(cell = 6f) }
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private const val PREVIEW_SIZE = 72

/** Document properties, the mobile equivalent of Image > Image Size plus File Info. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoPanel(session: DocumentSession, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val doc = session.document

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PsColors.Ink700,
        contentColor = PsColors.TextPrimary,
        dragHandle = { PanelDragHandle() },
    ) {
        PanelHeader(title = stringResource(R.string.viewer_info))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            InfoRow(stringResource(R.string.info_format), if (doc.header.isPsb) "PSB (Large Document)" else "PSD")
            InfoRow(stringResource(R.string.info_size), formatDimensions(doc.width, doc.height))
            InfoRow(stringResource(R.string.info_megapixels), formatMegapixels(doc.width, doc.height))
            InfoRow(stringResource(R.string.info_color_mode), doc.colorMode.label)
            InfoRow(stringResource(R.string.info_depth), stringResource(R.string.info_depth_value, doc.depth))
            InfoRow(stringResource(R.string.info_channels), doc.header.channels.toString())
            InfoRow(stringResource(R.string.info_layers), doc.visibleLayerCount.toString())
            InfoRow(
                stringResource(R.string.info_resolution),
                "${doc.resolution.horizontal.toInt()} × ${doc.resolution.vertical.toInt()} dpi",
            )
            if (!session.isScratch) {
                InfoRow(stringResource(R.string.info_file_size), formatFileSize(doc.fileSize))
            }
            InfoRow(
                stringResource(R.string.info_composite),
                stringResource(if (doc.hasComposite) R.string.info_yes else R.string.info_no),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = PsColors.TextTertiary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = PsColors.TextPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}
