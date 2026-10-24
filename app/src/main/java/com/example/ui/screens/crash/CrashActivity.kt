package com.example.ui.screens.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val stackTrace = intent.getStringExtra("EXTRA_STACK_TRACE") ?: "Unknown Crash"

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF090C12)
                ) {
                    val scrollState = rememberScrollState()
                    var isDetailsExpanded by remember { mutableStateOf(true) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 20.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(12.dp))

                        // Broken screen phone illustration
                        CrashPhoneIllustration(
                            modifier = Modifier
                                .size(140.dp)
                                .padding(vertical = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        // Headline: "App Crashed!"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = stringResource(R.string.crash_title_app),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.crash_title_crashed),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFFF334B)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Subtitle
                        Text(
                            text = stringResource(R.string.crash_subtitle),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFD1D5DB),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Description text
                        Text(
                            text = stringResource(R.string.crash_description),
                            fontSize = 13.5.sp,
                            lineHeight = 20.sp,
                            color = Color(0xFF9CA3AF),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )

                        Spacer(modifier = Modifier.height(26.dp))

                        // Action Buttons: Restart App & Copy Error
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Restart App Button
                            Button(
                                onClick = {
                                    val intent = Intent(this@CrashActivity, com.example.MainActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    }
                                    startActivity(intent)
                                    finish()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                                shape = RoundedCornerShape(percent = 50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFE50914)
                                ),
                                contentPadding = PaddingValues(horizontal = 16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.restart_app),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }

                            // Copy Error Button
                            Button(
                                onClick = {
                                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText(getString(R.string.crash_error), stackTrace)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(this@CrashActivity, getString(R.string.error_copied), Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                                shape = RoundedCornerShape(percent = 50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1B2130)
                                ),
                                border = BorderStroke(1.dp, Color(0xFF2B3448)),
                                contentPadding = PaddingValues(horizontal = 16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.copy_error),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Error Details Card
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp)),
                            color = Color(0xFF131722),
                            border = BorderStroke(1.dp, Color(0xFF1F2536)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                // Header row (toggle expansion)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isDetailsExpanded = !isDetailsExpanded },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF2B1924)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Description,
                                            contentDescription = null,
                                            tint = Color(0xFFFF334B),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.error_details),
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = stringResource(R.string.error_details_subtitle),
                                            fontSize = 12.sp,
                                            color = Color(0xFF7E8698)
                                        )
                                    }
                                    Icon(
                                        imageVector = if (isDetailsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = Color(0xFF8E95A5),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                // Collapsible code block
                                AnimatedVisibility(
                                    visible = isDetailsExpanded,
                                    enter = fadeIn() + expandVertically(),
                                    exit = fadeOut() + shrinkVertically()
                                ) {
                                    Column {
                                        Spacer(modifier = Modifier.height(14.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFF0A0D15))
                                                .border(1.dp, Color(0xFF1B2232), RoundedCornerShape(10.dp))
                                                .padding(12.dp)
                                        ) {
                                            val codeScrollState = rememberScrollState()
                                            Text(
                                                text = stackTrace,
                                                fontSize = 12.sp,
                                                lineHeight = 17.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = Color(0xFFD1D5DB),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 240.dp)
                                                    .verticalScroll(codeScrollState)
                                                    .padding(end = 28.dp)
                                            )

                                            IconButton(
                                                onClick = {
                                                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    val clip = ClipData.newPlainText(getString(R.string.crash_error), stackTrace)
                                                    clipboard.setPrimaryClip(clip)
                                                    Toast.makeText(this@CrashActivity, getString(R.string.error_copied), Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .size(28.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.ContentCopy,
                                                    contentDescription = stringResource(R.string.copy),
                                                    tint = Color(0xFF8E95A5),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // "Need Help?" Card
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    try {
                                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                                            data = Uri.parse("mailto:support@cinestream.com")
                                            putExtra(Intent.EXTRA_SUBJECT, "CineStream Crash Report")
                                            putExtra(
                                                Intent.EXTRA_TEXT,
                                                "Hello CineStream Support team,\n\nI encountered the following crash in the app:\n\n$stackTrace"
                                            )
                                        }
                                        startActivity(intent)
                                    } catch (_: Exception) {
                                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText(getString(R.string.crash_error), stackTrace)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(this@CrashActivity, getString(R.string.error_copied), Toast.LENGTH_SHORT).show()
                                    }
                                },
                            color = Color(0xFF131722),
                            border = BorderStroke(1.dp, Color(0xFF1F2536)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1C2230)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Lightbulb,
                                        contentDescription = null,
                                        tint = Color(0xFFFF334B),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.need_help),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.need_help_subtitle),
                                        fontSize = 12.sp,
                                        color = Color(0xFF7E8698)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = Color(0xFFFF334B),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}

/**
 * Custom Canvas drawing representing the broken screen phone illustration with sad face and sparks.
 */
@Composable
fun CrashPhoneIllustration(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)

        // 1. Soft red glow behind phone
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x35FF334B), Color(0x00FF334B)),
                center = center,
                radius = size.width * 0.48f
            ),
            radius = size.width * 0.48f,
            center = center
        )

        // Rotate canvas slightly for dynamic tilt matching the user mockup
        withTransform({
            rotate(degrees = -7f, pivot = center)
        }) {
            val phoneWidth = 82.dp.toPx()
            val phoneHeight = 102.dp.toPx()
            val cornerRadius = 18.dp.toPx()
            val phoneLeft = center.x - phoneWidth / 2f
            val phoneTop = center.y - phoneHeight / 2f

            // Phone Body Fill
            drawRoundRect(
                color = Color(0xFF242838),
                topLeft = Offset(phoneLeft, phoneTop),
                size = Size(phoneWidth, phoneHeight),
                cornerRadius = CornerRadius(cornerRadius, cornerRadius)
            )

            // Phone Body Border
            drawRoundRect(
                color = Color(0xFF3A4358),
                topLeft = Offset(phoneLeft, phoneTop),
                size = Size(phoneWidth, phoneHeight),
                cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                style = Stroke(width = 2.5.dp.toPx())
            )

            // Broken screen cracks (top-right corner)
            val crackPath = Path().apply {
                moveTo(phoneLeft + phoneWidth - 22.dp.toPx(), phoneTop)
                lineTo(phoneLeft + phoneWidth - 26.dp.toPx(), phoneTop + 14.dp.toPx())
                lineTo(phoneLeft + phoneWidth - 12.dp.toPx(), phoneTop + 22.dp.toPx())
                lineTo(phoneLeft + phoneWidth - 22.dp.toPx(), phoneTop + 36.dp.toPx())
                lineTo(phoneLeft + phoneWidth, phoneTop + 28.dp.toPx())
            }
            drawPath(
                path = crackPath,
                color = Color(0xFF0F121B),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Additional crack splinter
            val chipCrack = Path().apply {
                moveTo(phoneLeft + phoneWidth - 26.dp.toPx(), phoneTop + 14.dp.toPx())
                lineTo(phoneLeft + phoneWidth - 6.dp.toPx(), phoneTop + 8.dp.toPx())
            }
            drawPath(
                path = chipCrack,
                color = Color(0xFF0F121B),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )

            // Sad Face: Two Eyes
            val eyeRadius = 4.5.dp.toPx()
            val eyeY = center.y - 4.dp.toPx()
            val eyeSpacing = 13.dp.toPx()
            drawCircle(
                color = Color(0xFFFF3B56),
                radius = eyeRadius,
                center = Offset(center.x - eyeSpacing, eyeY)
            )
            drawCircle(
                color = Color(0xFFFF3B56),
                radius = eyeRadius,
                center = Offset(center.x + eyeSpacing, eyeY)
            )

            // Sad Face: Mouth (Frown)
            val mouthWidth = 26.dp.toPx()
            val mouthHeight = 16.dp.toPx()
            val mouthRect = Rect(
                offset = Offset(center.x - mouthWidth / 2f, center.y + 11.dp.toPx()),
                size = Size(mouthWidth, mouthHeight)
            )
            drawArc(
                color = Color(0xFFFF3B56),
                startAngle = 195f,
                sweepAngle = 150f,
                useCenter = false,
                topLeft = mouthRect.topLeft,
                size = mouthRect.size,
                style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Sparks / exclamation marks around the phone
        val sparkColor = Color(0xFFFF334B)
        val sparkWidth = 3.5.dp.toPx()

        // Top Left Sparks
        drawLine(
            color = sparkColor,
            start = Offset(center.x - 48.dp.toPx(), center.y - 42.dp.toPx()),
            end = Offset(center.x - 38.dp.toPx(), center.y - 52.dp.toPx()),
            strokeWidth = sparkWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = sparkColor,
            start = Offset(center.x - 56.dp.toPx(), center.y - 18.dp.toPx()),
            end = Offset(center.x - 42.dp.toPx(), center.y - 22.dp.toPx()),
            strokeWidth = sparkWidth,
            cap = StrokeCap.Round
        )

        // Right Sparks
        drawLine(
            color = sparkColor,
            start = Offset(center.x + 44.dp.toPx(), center.y - 10.dp.toPx()),
            end = Offset(center.x + 56.dp.toPx(), center.y - 16.dp.toPx()),
            strokeWidth = sparkWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = sparkColor,
            start = Offset(center.x + 42.dp.toPx(), center.y + 18.dp.toPx()),
            end = Offset(center.x + 54.dp.toPx(), center.y + 26.dp.toPx()),
            strokeWidth = sparkWidth,
            cap = StrokeCap.Round
        )
    }
}
