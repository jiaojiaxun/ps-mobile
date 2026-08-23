package com.psmobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.psmobile.R
import com.psmobile.update.UpdateState
import com.psmobile.ui.theme.PsColors
import com.psmobile.util.formatFileSize

/**
 * The update row in settings.
 *
 * Reports what is published and hands off to the browser. The app deliberately does not install
 * the APK itself: doing so requires the install permission, and a sideloaded app that downloads
 * and installs an APK trips Play Protect's dropper heuristic, putting a malware warning in front
 * of every user for an update that is perfectly legitimate.
 */
@Composable
fun UpdateRow(
    state: UpdateState,
    currentVersion: String,
    onCheck: () -> Unit,
    onOpenRelease: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PsColors.Ink700)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(state)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = statusText(state, currentVersion),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PsColors.TextPrimary,
                )
                Text(
                    text = "${stringResource(R.string.settings_version)} $currentVersion",
                    style = MaterialTheme.typography.bodySmall,
                    color = PsColors.TextTertiary,
                )
            }
            if (state !is UpdateState.Checking) {
                TextButton(onClick = onCheck) {
                    Text(
                        text = stringResource(R.string.update_check),
                        style = MaterialTheme.typography.labelSmall,
                        color = PsColors.Accent,
                    )
                }
            }
        }

        if (state is UpdateState.Available) {
            val release = state.release
            if (release.notes.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.update_notes),
                    style = MaterialTheme.typography.labelSmall,
                    color = PsColors.TextTertiary,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = release.notes.lineSequence().take(6).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = PsColors.TextSecondary,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onOpenRelease,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PsColors.Accent,
                        contentColor = PsColors.Ink900,
                    ),
                ) {
                    Text(stringResource(R.string.update_download), fontWeight = FontWeight.SemiBold)
                }
                if (release.sizeBytes > 0) {
                    Text(
                        text = formatFileSize(release.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = PsColors.TextTertiary,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(
                        stringResource(R.string.update_dismiss),
                        style = MaterialTheme.typography.labelSmall,
                        color = PsColors.TextTertiary,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.update_open_hint),
                style = MaterialTheme.typography.bodySmall,
                color = PsColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun StatusIcon(state: UpdateState) {
    when (state) {
        is UpdateState.Checking ->
            CircularProgressIndicator(
                modifier = Modifier.size(19.dp),
                strokeWidth = 2.dp,
                color = PsColors.Accent,
            )

        is UpdateState.UpToDate ->
            Icon(Icons.Rounded.CheckCircle, null, tint = PsColors.Success, modifier = Modifier.size(19.dp))

        is UpdateState.Failed ->
            Icon(Icons.Rounded.ErrorOutline, null, tint = PsColors.Danger, modifier = Modifier.size(19.dp))

        is UpdateState.Available ->
            Icon(Icons.Rounded.CloudDownload, null, tint = PsColors.Accent, modifier = Modifier.size(19.dp))

        UpdateState.Idle ->
            Icon(Icons.Rounded.Refresh, null, tint = PsColors.TextSecondary, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun statusText(state: UpdateState, currentVersion: String): String = when (state) {
    UpdateState.Idle -> stringResource(R.string.settings_section_updates)
    UpdateState.Checking -> stringResource(R.string.update_checking)
    UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
    is UpdateState.Available -> stringResource(R.string.update_available, state.release.versionName)
    is UpdateState.Failed -> when (state.reason) {
        UpdateState.Reason.NETWORK -> stringResource(R.string.update_failed_network)
    }
}
