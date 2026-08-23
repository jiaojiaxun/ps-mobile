package com.psmobile.ui.loading

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.psmobile.R
import com.psmobile.psd.ParsePhase
import com.psmobile.psd.ParseProgress
import com.psmobile.ui.components.AuroraBackground
import com.psmobile.ui.components.LayerStackMark
import com.psmobile.ui.theme.PsColors

private val PHASE_ORDER = listOf(
    ParsePhase.OPENING,
    ParsePhase.HEADER,
    ParsePhase.RESOURCES,
    ParsePhase.LAYERS,
    ParsePhase.INDEXING,
    ParsePhase.PREVIEW,
)

@Composable
private fun phaseLabel(phase: ParsePhase): String = stringResource(
    when (phase) {
        ParsePhase.OPENING -> R.string.loading_opening
        ParsePhase.HEADER -> R.string.loading_header
        ParsePhase.RESOURCES -> R.string.loading_resources
        ParsePhase.LAYERS -> R.string.loading_layers
        ParsePhase.INDEXING -> R.string.loading_indexing
        ParsePhase.PREVIEW -> R.string.loading_preview
        ParsePhase.DONE -> R.string.loading_done
    },
)

/**
 * The open screen.
 *
 * Parsing an 800 MB PSB usually finishes in a fraction of a second, so this is often a flash —
 * hence the smoothed progress value, which keeps a very fast open from looking like a glitch,
 * and the phase checklist, which explains what took the time when a file really is slow.
 */
@Composable
fun LoadingScreen(
    title: String,
    progress: ParseProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = progress.overall,
        animationSpec = tween(durationMillis = 420),
        label = "progress",
    )

    AuroraBackground(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LayerStackMark(size = 108.dp, animated = true, progress = animated)

            Spacer(Modifier.height(28.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = PsColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))

            ProgressTrack(fraction = animated)

            Spacer(Modifier.height(14.dp))

            AnimatedContent(
                targetState = progress.phase,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                label = "phase",
            ) { phase ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = phaseLabel(phase),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PsColors.TextSecondary,
                    )
                }
            }

            if (progress.detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = progress.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = PsColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(32.dp))

            PhaseChecklist(current = progress.phase)

            Spacer(Modifier.height(36.dp))

            TextButton(onClick = onCancel) {
                Text(
                    text = stringResource(R.string.loading_cancel),
                    color = PsColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ProgressTrack(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(PsColors.Ink500),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(CircleShape)
                .background(
                    Brush.horizontalGradient(
                        listOf(PsColors.AccentDim, PsColors.Accent, PsColors.AccentGlow),
                    ),
                ),
        )
    }
}

@Composable
private fun PhaseChecklist(current: ParsePhase, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        PHASE_ORDER.forEach { phase ->
            val done = current.order > phase.order
            val active = current == phase
            val alpha by animateFloatAsState(
                targetValue = if (done || active) 1f else 0.34f,
                animationSpec = tween(260),
                label = "step",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                done -> PsColors.Accent
                                active -> PsColors.AccentDim
                                else -> PsColors.Ink500
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = PsColors.Ink900,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = phaseLabel(phase),
                    style = MaterialTheme.typography.bodySmall,
                    color = PsColors.TextSecondary.copy(alpha = alpha),
                )
            }
        }
    }
}
