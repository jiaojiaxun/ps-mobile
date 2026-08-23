package com.psmobile.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.psmobile.ui.theme.PsColors
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The ambient backdrop used behind the home and loading screens: two very soft radial washes
 * that drift, so a mostly flat dark UI still feels alive without competing with artwork.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "drift",
    )
    val phase = if (animated) drift else 0.5f

    Box(
        modifier = modifier
            .background(PsColors.Ink800)
            .drawWithCache {
                val glowA = Brush.radialGradient(
                    colors = listOf(PsColors.Accent.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(size.width * (0.18f + 0.12f * phase), size.height * 0.12f),
                    radius = size.minDimension * 0.95f,
                )
                val glowB = Brush.radialGradient(
                    colors = listOf(Color(0xFF7B5CFF).copy(alpha = 0.12f), Color.Transparent),
                    center = Offset(size.width * (0.92f - 0.18f * phase), size.height * (0.68f + 0.08f * phase)),
                    radius = size.minDimension * 1.1f,
                )
                onDrawBehind {
                    drawRect(glowA)
                    drawRect(glowB)
                }
            },
        content = content,
    )
}

/**
 * The app mark: three stacked layer plates.
 *
 * On the loading screen the plates separate and settle back together on a loop, which reads as
 * "assembling a document" and gives the wait a rhythm.
 */
@Composable
fun LayerStackMark(
    size: Dp = 96.dp,
    animated: Boolean = false,
    progress: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "mark")
    val float by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "float",
    )
    val spread = if (animated) float else 0f

    Box(
        modifier = modifier
            .size(size)
            .drawWithCache {
                onDrawBehind { drawLayerStack(spread, progress) }
            },
    )
}

private fun DrawScope.drawLayerStack(spread: Float, progress: Float) {
    val w = size.width
    val h = size.height
    val unit = min(w, h)
    val cx = w / 2f
    val plateW = unit * 0.62f
    val plateH = unit * 0.32f
    val gap = unit * 0.13f
    val lift = unit * 0.07f * spread

    // Bottom to top, so the brightest plate lands on top.
    val plates = listOf(
        Triple(gap + lift, PsColors.AccentDim.copy(alpha = 0.75f), 0),
        Triple(0f, Color(0xFF2A8AD8), 1),
        Triple(-gap - lift, PsColors.Accent, 2),
    )

    plates.forEach { (dy, color, index) ->
        val cy = h / 2f + dy
        val path = Path().apply {
            moveTo(cx, cy - plateH / 2f)
            lineTo(cx + plateW / 2f, cy)
            lineTo(cx, cy + plateH / 2f)
            lineTo(cx - plateW / 2f, cy)
            close()
        }
        val filled = progress <= 0f || progress >= (index + 1) / 3f
        drawPath(path, color.copy(alpha = if (filled) 1f else 0.28f))
        if (index == 2) {
            drawPath(path, PsColors.AccentGlow.copy(alpha = 0.6f), style = Stroke(width = unit * 0.014f))
        }
    }
}

/**
 * Photoshop's transparency checkerboard, drawn in device pixels so it stays a constant size
 * regardless of canvas zoom.
 *
 * One repeating shader, not one rectangle per square. The squares are small by definition, so a
 * per-square loop scales with viewport area: at eleven pixels behind a full-screen canvas it came
 * to roughly eighteen thousand draw commands, recorded and issued on every single frame, for a
 * backdrop that opaque artwork hides completely. That alone cost 4 ms of display-list recording
 * and 12 ms of command issue per frame on a 120 Hz phone — and it only ever switched on when the
 * viewer moved to compositing from layers, which is why hiding one layer appeared to make the
 * whole app heavy.
 */
fun DrawScope.drawCheckerboard(cell: Float = 12f) = drawRect(brush = checkerBrush(cell))

/**
 * Cached one square-pair tile per cell size, keyed in whole pixels.
 *
 * Only ever touched from the UI thread while recording a frame, and there are two call sites with
 * one cell size each, so the map stays at two entries for the life of the process.
 */
private val checkerBrushes = HashMap<Int, ShaderBrush>(4)

private fun checkerBrush(cell: Float): ShaderBrush {
    val size = cell.roundToInt().coerceAtLeast(1)
    return checkerBrushes.getOrPut(size) { ShaderBrush(ImageShader(checkerTile(size), TileMode.Repeated, TileMode.Repeated)) }
}

/** A 2x2 arrangement of squares; repeating it reproduces the full board exactly. */
private fun checkerTile(cell: Int): ImageBitmap {
    val span = cell * 2
    val bitmap = Bitmap.createBitmap(span, span, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(PsColors.CheckerDark.toArgb())
    val paint = android.graphics.Paint().apply { color = PsColors.CheckerLight.toArgb() }
    val edge = cell.toFloat()
    canvas.drawRect(0f, 0f, edge, edge, paint)
    canvas.drawRect(edge, edge, span.toFloat(), span.toFloat(), paint)
    return bitmap.asImageBitmap()
}
