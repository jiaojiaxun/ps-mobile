package com.psmobile.ui.error

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.psmobile.R
import com.psmobile.ui.components.AuroraBackground
import com.psmobile.ui.theme.PsColors

@Composable
fun FailureScreen(
    title: String,
    message: String,
    onRetry: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AuroraBackground(modifier = modifier.fillMaxSize(), animated = false) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(PsColors.Danger.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = PsColors.Danger,
                    modifier = Modifier.size(30.dp),
                )
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = PsColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = PsColors.TextSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onRetry != null) {
                    Button(
                        onClick = onRetry,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PsColors.Accent,
                            contentColor = PsColors.Ink900,
                        ),
                    ) {
                        Text(stringResource(R.string.error_retry))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.error_dismiss), color = PsColors.TextSecondary)
                }
            }
        }
    }
}
