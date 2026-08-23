package com.psmobile.ui.newdoc

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.psmobile.R
import com.psmobile.psd.PsdWriter
import com.psmobile.psd.model.ColorMode
import com.psmobile.ui.theme.PsColors
import com.psmobile.util.formatMegapixels

/** What "New document" hands to the writer. */
data class NewDocumentSpec(
    val name: String,
    val width: Int,
    val height: Int,
    val resolution: Double,
    val colorMode: ColorMode,
    val background: PsdWriter.Background,
    val customColor: Int = 0xFFFFFFFF.toInt(),
)

private data class Preset(@param:StringRes val label: Int, val width: Int, val height: Int, val dpi: Int)

private val PRESETS = listOf(
    Preset(R.string.preset_phone, 1080, 2340, 72),
    Preset(R.string.preset_square, 1080, 1080, 72),
    Preset(R.string.preset_fullhd, 1920, 1080, 72),
    Preset(R.string.preset_webtoon, 800, 1280, 96),
    Preset(R.string.preset_a4, 2480, 3508, 300),
    Preset(R.string.preset_4k, 3840, 2160, 72),
)

private val COLOR_MODES = listOf(ColorMode.RGB, ColorMode.GRAYSCALE)

private val BACKGROUNDS = listOf(
    PsdWriter.Background.WHITE to R.string.background_white,
    PsdWriter.Background.BLACK to R.string.background_black,
    PsdWriter.Background.TRANSPARENT to R.string.background_transparent,
)

/**
 * Photoshop's New Document dialog, pared down to what the current build can honour.
 *
 * The result is a genuine document rather than a placeholder screen, so the canvas that opens
 * behaves like any other — it can be panned, zoomed and inspected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewDocumentSheet(
    onDismiss: () -> Unit,
    onCreate: (NewDocumentSpec) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val defaultName = stringResource(R.string.new_doc_default_name)
    var name by remember { mutableStateOf(defaultName) }
    var width by remember { mutableStateOf("1080") }
    var height by remember { mutableStateOf("2340") }
    var dpi by remember { mutableStateOf("72") }
    var colorMode by remember { mutableStateOf(ColorMode.RGB) }
    var background by remember { mutableStateOf(PsdWriter.Background.WHITE) }

    val parsedWidth = width.toIntOrNull() ?: 0
    val parsedHeight = height.toIntOrNull() ?: 0
    val valid = parsedWidth in 1..300_000 && parsedHeight in 1..300_000 && name.isNotBlank()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PsColors.Ink700,
        contentColor = PsColors.TextPrimary,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(PsColors.Ink400),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.new_doc_title),
                style = MaterialTheme.typography.headlineSmall,
                color = PsColors.TextPrimary,
            )

            Spacer(Modifier.height(18.dp))

            FieldLabel(stringResource(R.string.new_doc_presets))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PRESETS.forEach { preset ->
                    val selected = parsedWidth == preset.width && parsedHeight == preset.height
                    PresetChip(stringResource(preset.label), selected) {
                        width = preset.width.toString()
                        height = preset.height.toString()
                        dpi = preset.dpi.toString()
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            SheetField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.new_doc_name),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetField(
                    value = width,
                    onValueChange = { width = it.filter(Char::isDigit).take(6) },
                    label = stringResource(R.string.new_doc_width),
                    numeric = true,
                    modifier = Modifier.weight(1f),
                )
                SheetField(
                    value = height,
                    onValueChange = { height = it.filter(Char::isDigit).take(6) },
                    label = stringResource(R.string.new_doc_height),
                    numeric = true,
                    modifier = Modifier.weight(1f),
                )
                SheetField(
                    value = dpi,
                    onValueChange = { dpi = it.filter(Char::isDigit).take(4) },
                    label = stringResource(R.string.new_doc_resolution),
                    numeric = true,
                    modifier = Modifier.weight(0.8f),
                )
            }

            Spacer(Modifier.height(16.dp))

            FieldLabel(stringResource(R.string.new_doc_color_mode))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                COLOR_MODES.forEach { mode ->
                    PresetChip(mode.label, colorMode == mode) { colorMode = mode }
                }
            }

            Spacer(Modifier.height(16.dp))

            FieldLabel(stringResource(R.string.new_doc_background))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BACKGROUNDS.forEach { (value, label) ->
                    PresetChip(stringResource(label), background == value) { background = value }
                }
            }

            Spacer(Modifier.height(18.dp))

            if (valid) {
                Text(
                    text = "${parsedWidth} × ${parsedHeight} px · ${formatMegapixels(parsedWidth, parsedHeight)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PsColors.TextSecondary,
                )
                Spacer(Modifier.height(6.dp))
            }

            Text(
                text = stringResource(R.string.new_doc_preview_note),
                style = MaterialTheme.typography.bodySmall,
                color = PsColors.TextTertiary,
            )

            Spacer(Modifier.height(18.dp))

            Button(
                onClick = {
                    onCreate(
                        NewDocumentSpec(
                            name = name.trim(),
                            width = parsedWidth,
                            height = parsedHeight,
                            resolution = (dpi.toIntOrNull() ?: 72).coerceIn(1, 4000).toDouble(),
                            colorMode = colorMode,
                            background = background,
                        ),
                    )
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PsColors.Accent,
                    contentColor = PsColors.Ink900,
                    disabledContainerColor = PsColors.Ink500,
                    disabledContentColor = PsColors.TextTertiary,
                ),
            ) {
                Text(
                    text = stringResource(R.string.new_doc_create),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = PsColors.TextTertiary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) PsColors.Accent.copy(alpha = 0.18f) else PsColors.Ink600)
            .border(
                width = 1.dp,
                color = if (selected) PsColors.Accent.copy(alpha = 0.55f) else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) PsColors.Accent else PsColors.TextSecondary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PsColors.Accent,
            unfocusedBorderColor = PsColors.Ink400,
            focusedLabelColor = PsColors.Accent,
            unfocusedLabelColor = PsColors.TextTertiary,
            focusedTextColor = PsColors.TextPrimary,
            unfocusedTextColor = PsColors.TextPrimary,
            cursorColor = PsColors.Accent,
        ),
    )
}
