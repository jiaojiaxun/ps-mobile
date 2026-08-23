package com.psmobile.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.psmobile.R
import com.psmobile.document.DocumentRect
import com.psmobile.document.DocumentSession
import com.psmobile.document.TileEngine
import com.psmobile.psd.model.ColorMode
import com.psmobile.settings.AppSettings
import com.psmobile.psd.model.LayerNode
import com.psmobile.ui.theme.PsColors
import com.psmobile.util.formatDimensions
import com.psmobile.util.formatZoom
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class ViewerPanel { NONE, LAYERS, INFO }

@Composable
fun ViewerScreen(
    session: DocumentSession,
    settings: AppSettings,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val document = session.document
    val state = remember(session, settings.initialZoom) {
        CanvasState(document.width, document.height, session.tiles.pyramid, settings.initialZoom)
    }
    val scope = rememberCoroutineScope()

    var panel by remember { mutableStateOf(ViewerPanel.NONE) }
    var chromeVisible by remember { mutableStateOf(true) }
    var zoomBadgeToken by remember { mutableIntStateOf(0) }
    var zoomBadgeVisible by remember { mutableStateOf(false) }
    var highlight by remember { mutableStateOf<DocumentRect?>(null) }

    BackHandler(enabled = true) {
        if (panel != ViewerPanel.NONE) panel = ViewerPanel.NONE else onClose()
    }

    // The zoom badge appears while the canvas is being manipulated, then gets out of the way.
    LaunchedEffect(zoomBadgeToken) {
        if (zoomBadgeToken == 0) return@LaunchedEffect
        zoomBadgeVisible = true
        delay(1100)
        zoomBadgeVisible = false
    }

    // A marquee marks where the canvas jumped to, then gets out of the way.
    LaunchedEffect(highlight) {
        if (highlight == null) return@LaunchedEffect
        delay(1600)
        highlight = null
    }

    // Compositing live leaves uncovered areas genuinely empty, so the checkerboard belongs there
    // regardless of what the flattened preview would have shown.
    val transparent = settings.showCheckerboard && (
        session.tiles.source == TileEngine.Source.LAYERS ||
            document.mergedHasAlpha ||
            (session.isScratch && document.header.channels > if (document.colorMode == ColorMode.GRAYSCALE) 1 else 3)
        )

    fun focusOn(node: LayerNode) {
        val bounds = session.boundsOf(node) ?: return
        panel = ViewerPanel.NONE
        highlight = bounds
        scope.launch { state.focusOn(bounds.left, bounds.top, bounds.right, bounds.bottom) }
    }

    Box(modifier = modifier.fillMaxSize().background(settings.canvasBackground.color)) {
        DocumentCanvas(
            tiles = session.tiles,
            state = state,
            hasTransparency = transparent,
            surroundColor = settings.canvasBackground.color,
            smoothScaling = settings.smoothScaling,
            highlight = highlight,
            onInteraction = { zoomBadgeToken++ },
            onToggleChrome = { chromeVisible = !chromeVisible },
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ViewerTopBar(
                title = session.displayName,
                subtitle = buildString {
                    append(if (document.header.isPsb) "PSB" else "PSD")
                    append("  ·  ")
                    append(formatDimensions(document.width, document.height))
                    append("  ·  ")
                    append(document.colorMode.label)
                },
                onBack = onClose,
                onFit = { scope.launch { state.animateToFit() } },
                onLayers = { panel = ViewerPanel.LAYERS },
                onInfo = { panel = ViewerPanel.INFO },
            )
        }

        AnimatedVisibility(
            visible = zoomBadgeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(20.dp),
        ) {
            ZoomBadge(state.scale)
        }

        val notice = when {
            session.hasVisibilityOverrides -> stringResource(R.string.viewer_live_composite)
            session.tiles.compositeUnavailable -> stringResource(R.string.viewer_no_composite)
            else -> null
        }
        if (notice != null) {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
            ) {
                Notice(notice)
            }
        }
    }

    when (panel) {
        ViewerPanel.LAYERS -> LayersPanel(
            session = session,
            onFocusLayer = ::focusOn,
            onToggleVisibility = { layer -> scope.launch { session.toggleVisibility(layer) } },
            onResetVisibility = { scope.launch { session.resetVisibility() } },
            onDismiss = { panel = ViewerPanel.NONE },
        )
        ViewerPanel.INFO -> InfoPanel(session) { panel = ViewerPanel.NONE }
        ViewerPanel.NONE -> Unit
    }
}

@Composable
private fun ViewerTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onFit: () -> Unit,
    onLayers: () -> Unit,
    onInfo: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(PsColors.Ink900.copy(alpha = 0.94f), Color.Transparent),
                ),
            )
            .statusBarsPadding()
            .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.viewer_back),
                tint = PsColors.TextPrimary,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = PsColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = PsColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onFit) {
            Icon(Icons.Rounded.FitScreen, stringResource(R.string.viewer_fit), tint = PsColors.TextSecondary)
        }
        IconButton(onClick = onLayers) {
            Icon(Icons.Rounded.Layers, stringResource(R.string.viewer_layers), tint = PsColors.TextSecondary)
        }
        IconButton(onClick = onInfo) {
            Icon(Icons.Rounded.Info, stringResource(R.string.viewer_info), tint = PsColors.TextSecondary)
        }
    }
}

@Composable
private fun ZoomBadge(scale: Float) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(PsColors.Ink900.copy(alpha = 0.82f))
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(
            text = formatZoom(scale),
            style = MaterialTheme.typography.labelSmall,
            color = PsColors.TextSecondary,
        )
    }
}

@Composable
private fun Notice(text: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(PsColors.Ink800.copy(alpha = 0.92f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = PsColors.Accent,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = PsColors.TextSecondary,
        )
    }
}

@Composable
internal fun PanelHeader(
    title: String,
    trailing: String? = null,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp)
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = PsColors.TextPrimary,
        )
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodySmall,
                color = PsColors.TextTertiary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (action != null) {
            TextButton(onClick = onAction) {
                Text(
                    text = action,
                    style = MaterialTheme.typography.labelSmall,
                    color = PsColors.Accent,
                )
            }
        }
    }
}

@Composable
internal fun PanelDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(PsColors.Ink400),
        )
    }
}
