package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.diagnostic.AuthDiagnosticResult
import com.example.data.diagnostic.AuthGatewayDiagnostic
import kotlinx.coroutines.launch

@Composable
fun AuthDiagnosticDialog(
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var isRunning by remember { mutableStateOf(false) }
    var diagnosticResult by remember { mutableStateOf<AuthDiagnosticResult?>(null) }

    fun runVerification() {
        if (isRunning) return
        isRunning = true
        coroutineScope.launch {
            try {
                diagnosticResult = AuthGatewayDiagnostic.runDiagnostic()
            } finally {
                isRunning = false
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val primaryRed = MaterialTheme.colorScheme.primary
        val surfaceColor = MaterialTheme.colorScheme.surface
        val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
        val onSurfaceColor = MaterialTheme.colorScheme.onSurface
        val textMuted = MaterialTheme.colorScheme.onSurfaceVariant

        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, surfaceVariantColor, RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(primaryRed.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Security,
                                contentDescription = null,
                                tint = primaryRed,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.auth_diagnostic_title),
                                color = onSurfaceColor,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.auth_diagnostic_target_endpoint),
                                color = textMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        enabled = !isRunning,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(surfaceVariantColor)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = onSurfaceColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(weight = 1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (isRunning) {
                        // In-Flight Loading State
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(surfaceColor)
                                .border(1.dp, surfaceVariantColor, RoundedCornerShape(16.dp))
                                .padding(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    color = primaryRed,
                                    strokeWidth = 3.dp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = stringResource(R.string.auth_diagnostic_running),
                                    color = onSurfaceColor,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    } else if (diagnosticResult == null) {
                        // Initial Ready State
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(surfaceColor)
                                .border(1.dp, surfaceVariantColor, RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.Info,
                                        contentDescription = null,
                                        tint = primaryRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Developer Verification",
                                        color = onSurfaceColor,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.diagnostic_not_run_yet),
                                    color = textMuted,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    } else {
                        val result = diagnosticResult!!
                        val isVerified = result.resultCode == "AUTHENTICATION_VERIFIED"
                        val statusBannerColor = if (isVerified) Color(0xFF1B5E20) else Color(0xFF5C1D1D)
                        val statusIcon = if (isVerified) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline
                        val statusIconTint = if (isVerified) Color(0xFF66BB6A) else Color(0xFFEF5350)

                        // Result Status Banner
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(statusBannerColor.copy(alpha = 0.35f))
                                .border(1.dp, statusIconTint.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                statusIcon,
                                contentDescription = null,
                                tint = statusIconTint,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = result.resultCode,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = if (isVerified) "Verification Successful" else "Verification Failed",
                                    color = statusIconTint,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Safe Diagnostic Information Card
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(surfaceColor)
                                .border(1.dp, surfaceVariantColor, RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            DiagnosticItemRow(
                                label = stringResource(R.string.diagnostic_field_http_status),
                                value = result.httpStatus?.toString() ?: "N/A",
                                isMonospace = true,
                                valueColor = when (result.httpStatus) {
                                    200 -> Color(0xFF66BB6A)
                                    null -> textMuted
                                    else -> Color(0xFFEF5350)
                                }
                            )

                            HorizontalDivider(color = surfaceVariantColor.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))

                            DiagnosticItemRow(
                                label = stringResource(R.string.diagnostic_field_firebase_user),
                                value = if (result.hasFirebaseUser) "Present" else "None",
                                valueColor = if (result.hasFirebaseUser) Color(0xFF66BB6A) else Color(0xFFEF5350)
                            )

                            HorizontalDivider(color = surfaceVariantColor.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))

                            DiagnosticItemRow(
                                label = stringResource(R.string.diagnostic_field_is_anonymous),
                                value = if (result.hasFirebaseUser) (if (result.isAnonymous) "Yes (Guest)" else "No (Registered)") else "N/A"
                            )

                            HorizontalDivider(color = surfaceVariantColor.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))

                            DiagnosticItemRow(
                                label = stringResource(R.string.diagnostic_field_uid_match),
                                value = result.uidMatchResult,
                                isMonospace = true,
                                valueColor = when (result.uidMatchResult) {
                                    "MATCH" -> Color(0xFF66BB6A)
                                    "MISMATCH" -> Color(0xFFEF5350)
                                    else -> textMuted
                                }
                            )

                            HorizontalDivider(color = surfaceVariantColor.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))

                            DiagnosticItemRow(
                                label = stringResource(R.string.diagnostic_field_project_id),
                                value = result.returnedProjectId ?: "N/A",
                                isMonospace = true,
                                valueColor = if (result.returnedProjectId == AuthGatewayDiagnostic.EXPECTED_PROJECT_ID) Color(0xFF66BB6A) else textMuted
                            )

                            if (!result.workerErrorCode.isNullOrBlank()) {
                                HorizontalDivider(color = surfaceVariantColor.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                                DiagnosticItemRow(
                                    label = stringResource(R.string.diagnostic_field_worker_error),
                                    value = result.workerErrorCode,
                                    isMonospace = true,
                                    valueColor = Color(0xFFEF5350)
                                )
                            }

                            HorizontalDivider(color = surfaceVariantColor.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))

                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(
                                    text = stringResource(R.string.diagnostic_field_message),
                                    color = textMuted,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = result.message,
                                    color = onSurfaceColor,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, surfaceVariantColor)
                    ) {
                        Text(
                            text = stringResource(R.string.close),
                            color = onSurfaceColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = { runVerification() },
                        enabled = !isRunning,
                        modifier = Modifier
                            .weight(1.3f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryRed),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (diagnosticResult == null) stringResource(R.string.run_auth_diagnostic) else stringResource(R.string.diagnostic_btn_retry),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticItemRow(
    label: String,
    value: String,
    isMonospace: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default
        )
    }
}
