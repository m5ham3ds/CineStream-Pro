package com.example.ui.screens.maintenance

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.AppStartupManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class MaintenanceFeedbackStatus {
    LIFTED,
    STILL_ACTIVE,
    ERROR
}

@Composable
fun MaintenanceScreen(
    title: String,
    message: String,
    onMaintenanceLifted: () -> Unit = {},
    onRetry: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isChecking by remember { mutableStateOf(false) }
    val defaultMaintenanceTitle = stringResource(R.string.app_in_maintenance_mode)
    var currentTitle by remember(title) { mutableStateOf(if (title.isNotBlank()) title else defaultMaintenanceTitle) }
    val defaultMaintenanceMessage = stringResource(R.string.maintenance_default_message)
    var currentMessage by remember(message) { mutableStateOf(if (message.isNotBlank()) message else defaultMaintenanceMessage) }

    var feedbackStatus by remember { mutableStateOf<MaintenanceFeedbackStatus?>(null) }
    var feedbackText by remember { mutableStateOf("") }

    val performCheck: () -> Unit = {
        if (!isChecking) {
            isChecking = true
            feedbackStatus = null
            feedbackText = ""
            scope.launch {
                val startTime = System.currentTimeMillis()
                val result = AppStartupManager.checkMaintenanceStatus(context)
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < 600) {
                    delay(600 - elapsed)
                }

                when (result) {
                    is AppStartupManager.MaintenanceCheckResult.Lifted -> {
                        feedbackStatus = MaintenanceFeedbackStatus.LIFTED
                        feedbackText = context.getString(R.string.maintenance_lifted_success)
                        delay(650)
                        isChecking = false
                        onMaintenanceLifted()
                        onRetry?.invoke()
                    }
                    is AppStartupManager.MaintenanceCheckResult.StillActive -> {
                        isChecking = false
                        currentTitle = result.title
                        currentMessage = result.message
                        feedbackStatus = MaintenanceFeedbackStatus.STILL_ACTIVE
                        feedbackText = context.getString(R.string.maintenance_still_active)
                    }
                    is AppStartupManager.MaintenanceCheckResult.Error -> {
                        isChecking = false
                        feedbackStatus = MaintenanceFeedbackStatus.ERROR
                        feedbackText = context.getString(R.string.failed_to_connect_to_server, result.message)
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        // Subtle background radial glow
        Box(
            modifier = Modifier
                .size(340.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
        ) {
            // Icon with glowing circle and subtle pulse
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(110.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E1E24))
                    .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(54.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Title
            Text(
                text = currentTitle,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Message
            Text(
                text = currentMessage,
                color = Color.Gray,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center
            )

            // Animated Feedback Banner
            AnimatedVisibility(
                visible = feedbackStatus != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                feedbackStatus?.let { status ->
                    val (bgColor, borderColor, iconTint, icon) = when (status) {
                        MaintenanceFeedbackStatus.LIFTED -> Quadruple(
                            Color(0xFF0E3820),
                            Color(0xFF4CAF50),
                            Color(0xFF4CAF50),
                            Icons.Default.CheckCircle
                        )
                        MaintenanceFeedbackStatus.STILL_ACTIVE -> Quadruple(
                            Color(0xFF3E2723),
                            Color(0xFFFF9800),
                            Color(0xFFFF9800),
                            Icons.Default.Info
                        )
                        MaintenanceFeedbackStatus.ERROR -> Quadruple(
                            Color(0xFF3E1A1A),
                            Color(0xFFE57373),
                            Color(0xFFE57373),
                            Icons.Default.Warning
                        )
                    }

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = bgColor),
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .padding(top = 22.dp)
                            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = iconTint,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = feedbackText,
                                color = Color.White,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(30.dp))

            // Retry Button
            Button(
                onClick = performCheck,
                enabled = !isChecking,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .height(50.dp)
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.checking_server),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.retry),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
