package com.psmobile.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.psmobile.R
import com.psmobile.settings.AppSettings
import com.psmobile.settings.CanvasBackground
import com.psmobile.settings.InitialZoom
import com.psmobile.settings.Language
import com.psmobile.settings.RenderQuality
import com.psmobile.update.UpdateState
import com.psmobile.ui.components.AuroraBackground
import com.psmobile.ui.theme.PsColors

/**
 * App settings.
 *
 * Every row here changes something the renderer or the browser actually does — there are no
 * decorative toggles. Values apply immediately; only the language needs the screen rebuilt,
 * which the caller handles.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    hasAllFilesAccess: Boolean,
    appVersion: String,
    updateState: UpdateState,
    onBack: () -> Unit,
    onChange: (AppSettings) -> Unit,
    onGrantAccess: () -> Unit,
    onClearRecents: () -> Unit,
    onClearPreviewCache: () -> Unit,
    onCheckUpdates: () -> Unit,
    onOpenRelease: () -> Unit,
    onDismissUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AuroraBackground(modifier = modifier.fillMaxSize(), animated = false) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        stringResource(R.string.viewer_back),
                        tint = PsColors.TextPrimary,
                    )
                }
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = PsColors.TextPrimary,
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            ) {
                item {
                    Section(stringResource(R.string.settings_section_appearance))
                    ChoiceRow(
                        title = stringResource(R.string.settings_language),
                        options = Language.entries,
                        selected = settings.language,
                        labelOf = { stringResource(it.label) },
                        onSelect = { onChange(settings.copy(language = it)) },
                    )
                    ChoiceRow(
                        title = stringResource(R.string.settings_background),
                        options = CanvasBackground.entries,
                        selected = settings.canvasBackground,
                        labelOf = { stringResource(it.label) },
                        swatchOf = { it.color },
                        onSelect = { onChange(settings.copy(canvasBackground = it)) },
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_checkerboard),
                        subtitle = stringResource(R.string.settings_checkerboard_hint),
                        checked = settings.showCheckerboard,
                        onChange = { onChange(settings.copy(showCheckerboard = it)) },
                    )
                }

                item {
                    Section(stringResource(R.string.settings_section_viewer))
                    ChoiceRow(
                        title = stringResource(R.string.settings_zoom),
                        subtitle = stringResource(R.string.settings_zoom_hint),
                        options = InitialZoom.entries,
                        selected = settings.initialZoom,
                        labelOf = { stringResource(it.label) },
                        onSelect = { onChange(settings.copy(initialZoom = it)) },
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_smooth),
                        subtitle = stringResource(R.string.settings_smooth_hint),
                        checked = settings.smoothScaling,
                        onChange = { onChange(settings.copy(smoothScaling = it)) },
                    )
                    ChoiceRow(
                        title = stringResource(R.string.settings_quality),
                        subtitle = stringResource(R.string.settings_quality_hint),
                        options = RenderQuality.entries,
                        selected = settings.renderQuality,
                        labelOf = { stringResource(it.label) },
                        onSelect = { onChange(settings.copy(renderQuality = it)) },
                    )
                }

                item {
                    Section(stringResource(R.string.settings_section_files))
                    SwitchRow(
                        title = stringResource(R.string.settings_scan),
                        subtitle = stringResource(R.string.settings_scan_hint),
                        checked = settings.scanOnOpen,
                        onChange = { onChange(settings.copy(scanOnOpen = it)) },
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_previews),
                        subtitle = stringResource(R.string.settings_previews_hint),
                        checked = settings.filePreviews,
                        onChange = { onChange(settings.copy(filePreviews = it)) },
                    )
                    ActionRow(
                        title = stringResource(R.string.settings_access),
                        subtitle = stringResource(
                            if (hasAllFilesAccess) R.string.settings_access_granted
                            else R.string.settings_access_denied,
                        ),
                        icon = if (hasAllFilesAccess) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                        tint = if (hasAllFilesAccess) PsColors.Success else PsColors.Accent,
                        enabled = !hasAllFilesAccess,
                        onClick = onGrantAccess,
                    )
                }

                item {
                    Section(stringResource(R.string.settings_section_updates))
                    UpdateRow(
                        state = updateState,
                        currentVersion = appVersion,
                        onCheck = onCheckUpdates,
                        onOpenRelease = onOpenRelease,
                        onDismiss = onDismissUpdate,
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_auto_updates),
                        subtitle = stringResource(R.string.settings_auto_updates_hint),
                        checked = settings.autoCheckUpdates,
                        onChange = { onChange(settings.copy(autoCheckUpdates = it)) },
                    )
                }

                item {
                    Section(stringResource(R.string.settings_section_data))
                    ActionRow(
                        title = stringResource(R.string.settings_clear_recents),
                        icon = Icons.Rounded.DeleteSweep,
                        tint = PsColors.TextSecondary,
                        onClick = onClearRecents,
                    )
                    ActionRow(
                        title = stringResource(R.string.settings_clear_cache),
                        icon = Icons.Rounded.Folder,
                        tint = PsColors.TextSecondary,
                        onClick = onClearPreviewCache,
                    )
                }

                item {
                    Section(stringResource(R.string.settings_section_about))
                    InfoRow(stringResource(R.string.settings_version), appVersion)
                    InfoRow(
                        stringResource(R.string.settings_formats),
                        stringResource(R.string.settings_formats_value),
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = PsColors.TextTertiary,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun RowShell(content: RowContent) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PsColors.Ink700),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) { content() }
    }
}

private typealias RowContent = @Composable () -> Unit

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PsColors.Ink700)
            .clickable { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = PsColors.TextPrimary)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = PsColors.TextTertiary,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PsColors.Ink900,
                    checkedTrackColor = PsColors.Accent,
                    uncheckedThumbColor = PsColors.TextTertiary,
                    uncheckedTrackColor = PsColors.Ink500,
                    uncheckedBorderColor = PsColors.Ink400,
                ),
            )
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    subtitle: String? = null,
    options: List<T>,
    selected: T,
    labelOf: @Composable (T) -> String,
    swatchOf: ((T) -> Color)? = null,
    onSelect: (T) -> Unit,
) {
    RowShell {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = PsColors.TextPrimary)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = PsColors.TextTertiary)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) PsColors.Accent.copy(alpha = 0.18f) else PsColors.Ink600)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) PsColors.Accent.copy(alpha = 0.55f) else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelect(option) }
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (swatchOf != null) {
                        Box(
                            modifier = Modifier
                                .size(13.dp)
                                .clip(CircleShape)
                                .background(swatchOf(option))
                                .border(1.dp, PsColors.Ink400, CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = labelOf(option),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) PsColors.Accent else PsColors.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PsColors.Ink700)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = PsColors.TextPrimary)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = PsColors.TextTertiary)
            }
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    RowShell {
        Text(title, style = MaterialTheme.typography.bodySmall, color = PsColors.TextTertiary)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = PsColors.TextPrimary,
            fontWeight = FontWeight.Medium,
        )
    }
}
