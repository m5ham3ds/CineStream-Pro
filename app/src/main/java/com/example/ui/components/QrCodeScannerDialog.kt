package com.example.ui.components

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.util.concurrent.Executors

@Composable
fun QrCodeScannerDialog(
    onDismiss: () -> Unit,
    onCodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isFlashOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var hasScanned by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    val cameraExecutor = Executors.newSingleThreadExecutor()

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        val reader = MultiFormatReader().apply {
                            val hints = mapOf(
                                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                                DecodeHintType.CHARACTER_SET to "UTF-8",
                                DecodeHintType.TRY_HARDER to java.lang.Boolean.TRUE
                            )
                            setHints(hints)
                        }

                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            if (hasScanned) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            try {
                                val plane = imageProxy.planes[0]
                                val buffer = plane.buffer
                                val rowStride = plane.rowStride
                                val width = imageProxy.width
                                val height = imageProxy.height
                                val rotation = imageProxy.imageInfo.rotationDegrees

                                val yBytes = ByteArray(width * height)
                                val rowBuf = ByteArray(rowStride)
                                buffer.rewind()
                                for (row in 0 until height) {
                                    val toRead = minOf(rowStride, buffer.remaining())
                                    buffer.get(rowBuf, 0, toRead)
                                    System.arraycopy(rowBuf, 0, yBytes, row * width, width)
                                }

                                val (procBytes, finalWidth, finalHeight) = when (rotation) {
                                    90 -> {
                                        val rot = ByteArray(width * height)
                                        var k = 0
                                        for (x in 0 until width) {
                                            for (y in height - 1 downTo 0) {
                                                rot[k++] = yBytes[y * width + x]
                                            }
                                        }
                                        Triple(rot, height, width)
                                    }
                                    180 -> {
                                        val rot = ByteArray(width * height)
                                        for (i in 0 until width * height) {
                                            rot[i] = yBytes[width * height - 1 - i]
                                        }
                                        Triple(rot, width, height)
                                    }
                                    270 -> {
                                        val rot = ByteArray(width * height)
                                        var k = 0
                                        for (x in width - 1 downTo 0) {
                                            for (y in 0 until height) {
                                                rot[k++] = yBytes[y * width + x]
                                            }
                                        }
                                        Triple(rot, height, width)
                                    }
                                    else -> Triple(yBytes, width, height)
                                }

                                val source = PlanarYUVLuminanceSource(
                                    procBytes, finalWidth, finalHeight, 0, 0, finalWidth, finalHeight, false
                                )

                                var decodedText: String? = null
                                try {
                                    val bitmap = BinaryBitmap(HybridBinarizer(source))
                                    decodedText = reader.decodeWithState(bitmap)?.text
                                } catch (_: Exception) {
                                    try {
                                        val bitmap = BinaryBitmap(GlobalHistogramBinarizer(source))
                                        decodedText = reader.decodeWithState(bitmap)?.text
                                    } catch (_: Exception) {}
                                }

                                if (!decodedText.isNullOrBlank() && !hasScanned) {
                                    hasScanned = true
                                    ContextCompat.getMainExecutor(ctx).execute {
                                        onCodeScanned(decodedText)
                                    }
                                }
                            } catch (e: Exception) {
                                // Ignore frame parsing errors
                            } finally {
                                reader.reset()
                                imageProxy.close()
                            }
                        }

                        try {
                            cameraProvider.unbindAll()
                            val cam = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis
                            )
                            camera = cam
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Reticle Frame
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .align(Alignment.Center)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
            )

            // Header Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
                }

                Text(
                    text = stringResource(R.string.scan_qr),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                IconButton(
                    onClick = {
                        isFlashOn = !isFlashOn
                        camera?.cameraControl?.enableTorch(isFlashOn)
                    },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(
                        if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = stringResource(R.string.cd_flash),
                        tint = if (isFlashOn) Color.Yellow else Color.White
                    )
                }
            }

            // Subtitle
            Text(
                text = stringResource(R.string.point_camera_qr_hint),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 48.dp, start = 32.dp, end = 32.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}
