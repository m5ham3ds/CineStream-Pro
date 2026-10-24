package com.example.ui.screens.share

import androidx.compose.ui.res.stringResource
import com.example.ui.theme.SuccessGreen
import com.example.R

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.data.model.DownloadItem
import com.example.data.model.P2PTransferRecord
import com.example.data.repository.DownloadRepository
import com.example.data.repository.NearbyDevice
import com.example.data.repository.NearbyDeviceRepository
import com.example.data.repository.P2PTransferRepository
import com.example.ui.components.QrCodeScannerDialog
import com.example.utils.DevicePreparationHelper
import com.example.utils.HotspotManager
import com.example.utils.MediaStorageUtils
import com.example.utils.NetworkUtils
import com.example.utils.P2PManager
import com.example.utils.P2PState
import com.example.utils.QRCodeGenerator
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ShareScreen(
    onBack: () -> Unit,
    onItemClick: (String, Boolean) -> Unit = { _, _ -> },
    onNavigateToRecentTransfers: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val p2pManager = remember { P2PManager.getInstance(context) }
    val hotspotManager = remember { HotspotManager(context) }
    val downloadRepository = remember { DownloadRepository(context) }
    val p2pTransferRepository = remember { P2PTransferRepository(context) }
    val nearbyDeviceRepository = remember { NearbyDeviceRepository(context) }
    
    val p2pState by p2pManager.p2pState.collectAsState()
    val connectedEndpoint by p2pManager.connectedEndpoint.collectAsState()
    val connectedEndpoints by p2pManager.connectedEndpoints.collectAsState()
    val discoveredEndpoints by p2pManager.discoveredEndpoints.collectAsState()
    val transferProgress by p2pManager.transferProgress.collectAsState()
    val transferSpeedMBs by p2pManager.transferSpeedMBs.collectAsState()
    val is5GActive by p2pManager.is5GActive.collectAsState()
    val activeTransfer by p2pManager.activeTransfer.collectAsState()
    val pendingConnectionRequest by p2pManager.pendingConnectionRequest.collectAsState()
    val isWaitingForApproval by p2pManager.isWaitingForApproval.collectAsState()
    val connectingTargetName by p2pManager.connectingTargetName.collectAsState()
    val isScanning by p2pManager.isScanning.collectAsState()
    val rememberedDevices by nearbyDeviceRepository.devices.collectAsState()
    
    val allDownloads by downloadRepository.getDownloadItems().collectAsState(initial = emptyList())
    val completedDownloads = remember(allDownloads) { allDownloads.filter { it.isCompleted } }
    val recentTransfers by p2pTransferRepository.transfers.collectAsState()

    var showSendDialog by remember { mutableStateOf(false) }
    var showReceiveDialog by remember { mutableStateOf(false) }
    var showPrepDialogForSend by remember { mutableStateOf(false) }
    var showPrepDialogForReceive by remember { mutableStateOf(false) }
    var deviceToDelete by remember { mutableStateOf<NearbyDevice?>(null) }
    var showQrScannerDialog by remember { mutableStateOf(false) }
    var showHowItWorksDialog by remember { mutableStateOf(false) }
    var deviceToDisconnect by remember { mutableStateOf<NearbyDevice?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedContentType by remember { mutableStateOf("All Files") }
    var pendingItemsToSend by remember { mutableStateOf<Set<DownloadItem>>(emptySet()) }
    val restrictions by com.example.data.repository.UserSecurityManager.restrictionsFlow.collectAsState()

    if (!restrictions.isP2PAllowed) {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Block,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.share_feature_restricted),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.p2p_sharing_restricted_by_admin),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onBack) {
                    Text(stringResource(R.string.back))
                }
            }
        }
        return
    }

    // Sync callbacks with repositories
    DisposableEffect(Unit) {
        // Start background socket server and UDP responder so device is always discoverable and ready to reconnect
        p2pManager.startBackgroundService(Build.MODEL, 8888)

        p2pManager.onConnectionEstablished = { peerName, peerIp ->
            nearbyDeviceRepository.addOrUpdateDevice(
                NearbyDevice(
                    id = peerIp ?: peerName,
                    name = peerName,
                    ip = peerIp,
                    port = 8888,
                    isConnected = true,
                    lastSeen = System.currentTimeMillis()
                )
            )

            // If user previously tapped Send before connecting, send queued items now
            if (pendingItemsToSend.isNotEmpty()) {
                val itemsToSend = pendingItemsToSend
                pendingItemsToSend = emptySet()
                scope.launch {
                    kotlinx.coroutines.delay(500)
                    itemsToSend.forEach { item ->
                        val file = MediaStorageUtils.findMediaFile(context, item.id)
                        if (file != null && file.exists()) {
                            p2pManager.sendMediaToAll(item, file)
                        } else {
                            val tempFile = File(context.cacheDir, "${item.id}.mp4").apply {
                                if (!exists()) writeBytes(ByteArray(1024 * 512))
                            }
                            p2pManager.sendMediaToAll(item, tempFile)
                        }
                    }
                    Toast.makeText(context, context.getString(R.string.connected_sending_items, peerName, itemsToSend.size.toString()), Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        p2pManager.onDisconnected = { peerName ->
            nearbyDeviceRepository.setDeviceConnected(peerName, false)
        }
        
        p2pManager.onConnectionDeclined = { peerName ->
            Toast.makeText(context, context.getString(R.string.connection_declined_by, peerName), Toast.LENGTH_SHORT).show()
            nearbyDeviceRepository.setDeviceConnected(peerName, false)
        }

        p2pManager.onMediaReceived = { id, mediaId, title, isMovie, posterUrl, quality, contentType ->
            scope.launch {
                downloadRepository.addCompletedDownload(
                    DownloadItem(
                        id = id, mediaId = mediaId,
                        title = title,
                        posterUrl = posterUrl,
                        isMovie = isMovie,
                        quality = quality,
                        progress = 1f,
                        isCompleted = true
                    )
                )
                p2pTransferRepository.recordTransfer(
                    P2PTransferRecord(
                        id = id,
                        mediaId = mediaId,
                        title = title,
                        posterUrl = posterUrl,
                        isMovie = isMovie,
                        isReceived = true,
                        timestamp = System.currentTimeMillis(),
                        deviceName = connectedEndpoint?.name ?: "Nearby Device",
                        quality = quality,
                        contentType = contentType
                    )
                )
            }
        }

        p2pManager.onMediaSent = { item ->
            scope.launch {
                val detectedType = if (item.isMovie) "movie" else "series"
                p2pTransferRepository.recordTransfer(
                    P2PTransferRecord(
                        id = item.id,
                        mediaId = item.mediaId,
                        title = item.title,
                        posterUrl = item.posterUrl,
                        isMovie = item.isMovie,
                        isReceived = false,
                        timestamp = System.currentTimeMillis(),
                        deviceName = connectedEndpoint?.name ?: "Nearby Device",
                        quality = item.quality,
                        contentType = detectedType
                    )
                )
            }
        }

        onDispose {
            p2pManager.stopAll()
        }
    }

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
    } else {
        listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
    
    val permissionsState = rememberMultiplePermissionsState(permissions)

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showQrScannerDialog = true
        } else {
            Toast.makeText(context, context.getString(R.string.camera_permission_needed_qr), Toast.LENGTH_SHORT).show()
        }
    }

    val openCameraScanner = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showQrScannerDialog = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(showReceiveDialog) {
        if (showReceiveDialog) {
            p2pManager.startAdvertising(Build.MODEL, 8888)
            val allIps = NetworkUtils.getAllDeviceIps(context).joinToString(",")
            val localIp = NetworkUtils.getLocalIpAddress(context)
            hotspotManager.startLocalHotspot(
                onStarted = { ssid, key ->
                    val apIp = NetworkUtils.getLocalIpAddress(context)
                    val qrPayload = "cinestream://p2p?ip=$apIp&ips=$allIps&port=8888&name=${Uri.encode(Build.MODEL)}&ssid=${Uri.encode(ssid)}&key=${Uri.encode(key)}"
                    qrBitmap = QRCodeGenerator.generateQRCode(qrPayload, 512)
                },
                onError = {
                    val qrPayload = "cinestream://p2p?ip=$localIp&ips=$allIps&port=8888&name=${Uri.encode(Build.MODEL)}"
                    qrBitmap = QRCodeGenerator.generateQRCode(qrPayload, 512)
                }
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
        ) {
            // 1. Connection Status Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.WifiTethering,
                                    contentDescription = null,
                                    tint = if (p2pState == P2PState.CONNECTED) SuccessGreen else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    "Connection",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp
                                )
                                Text(
                                    "Status",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val statusText = when (p2pState) {
                                    P2PState.CONNECTED -> "CONNECTED"
                                    P2PState.TRANSFERRING -> "TRANSFERRING"
                                    P2PState.ADVERTISING -> "READY TO RECEIVE"
                                    P2PState.DISCOVERING -> "SCANNING"
                                    P2PState.IDLE -> "IDLE"
                                }
                                val statusColor = when (p2pState) {
                                    P2PState.CONNECTED -> SuccessGreen
                                    else -> MaterialTheme.colorScheme.primary
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        statusText,
                                        color = statusColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp
                                    )
                                    if (is5GActive) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = SuccessGreen.copy(alpha = 0.15f),
                                            border = BorderStroke(1.dp, SuccessGreen)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.Bolt,
                                                    contentDescription = null,
                                                    tint = SuccessGreen,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Spacer(modifier = Modifier.width(2.dp))
                                                Text(
                                                    "5G Turbo",
                                                    color = SuccessGreen,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                                val subtitleText = when (p2pState) {
                                    P2PState.CONNECTED -> {
                                        if (connectedEndpoints.size > 1) {
                                            "Connected to ${connectedEndpoints.size} devices"
                                        } else {
                                            "Connected to: ${connectedEndpoint?.name ?: "Nearby Device"}"
                                        }
                                    }
                                    P2PState.TRANSFERRING -> {
                                        val speed = if (transferSpeedMBs > 0f) " • " + String.format(java.util.Locale.US, "%.1f MB/s", transferSpeedMBs) else ""
                                        "Transferring: ${(transferProgress * 100).toInt()}%$speed"
                                    }
                                    else -> "Waiting for action"
                                }
                                Text(
                                    subtitleText,
                                    color = if (p2pState == P2PState.CONNECTED) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        // "How it works? ⓘ" Button
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
                            color = Color.Transparent,
                            modifier = Modifier.clickable { showHowItWorksDialog = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "How it works?",
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 2. Action Cards: Send & Receive
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Send Card (Primary Red)
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .testTag("send_action_card")
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                if (!permissionsState.allPermissionsGranted) {
                                    permissionsState.launchMultiplePermissionRequest()
                                }
                                if (!DevicePreparationHelper.isReadyForSend(context)) {
                                    showPrepDialogForSend = true
                                } else {
                                    showSendDialog = true
                                }
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.22f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = stringResource(R.string.send),
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.send),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Receive Card (Surface Variant / Dark)
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .testTag("receive_action_card")
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                if (!permissionsState.allPermissionsGranted) {
                                    permissionsState.launchMultiplePermissionRequest()
                                }
                                if (!DevicePreparationHelper.isReadyForReceive(context)) {
                                    showPrepDialogForReceive = true
                                } else {
                                    showReceiveDialog = true
                                    p2pManager.startAdvertising(Build.MODEL, 8888)
                                }
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = stringResource(R.string.receive),
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.receive),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // 3. Live Transfer Progress Banner (Active or Failed Transfer)
            if (p2pState == P2PState.TRANSFERRING || activeTransfer != null) {
                item {
                    val transfer = activeTransfer
                    val isFailed = transfer?.isFailed == true
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isFailed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(1.dp, if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Top: Poster + Title + Metadata Badges
                            if (transfer != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (transfer.posterUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = transfer.posterUrl,
                                            contentDescription = transfer.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(width = 54.dp, height = 75.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            transfer.title,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                            ) {
                                                Text(
                                                    transfer.contentType.uppercase(),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                            val cleanQ = com.example.data.model.DownloadItem.cleanQualityName(transfer.quality)
                                            if (cleanQ.isNotBlank()) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                                ) {
                                                    Text(
                                                        cleanQ,
                                                        color = MaterialTheme.colorScheme.secondary,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            if (transfer.fileSize > 0L) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    android.text.format.Formatter.formatFileSize(context, transfer.fileSize),
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        if (transfer.peerName.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                if (transfer.isReceiving) stringResource(R.string.from_peer, transfer.peerName) else stringResource(R.string.to_peer, transfer.peerName),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                            }

                            // Progress info row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isFailed) {
                                        Icon(
                                            Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            transfer?.errorMessage ?: stringResource(R.string.transfer_failed),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    } else {
                                        CircularProgressIndicator(
                                            progress = { transferProgress },
                                            modifier = Modifier.size(18.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            if (transfer?.isReceiving == true) stringResource(R.string.receiving_media)
                                            else if (transfer != null) stringResource(R.string.sending_media)
                                            else stringResource(R.string.transferring_media),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!isFailed) {
                                        if (is5GActive) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = SuccessGreen.copy(alpha = 0.15f),
                                                modifier = Modifier.padding(end = 6.dp)
                                            ) {
                                                Text(
                                                    "5G",
                                                    color = SuccessGreen,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        val speed = if (transferSpeedMBs > 0f) " • " + String.format(java.util.Locale.US, "%.1f MB/s", transferSpeedMBs) else ""
                                        Text(
                                            "${(transferProgress * 100).toInt()}%$speed",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { if (isFailed) 1f else transferProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                            // Action buttons: Cancel or Retry (if failed)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                if (isFailed) {
                                    OutlinedButton(
                                        onClick = { p2pManager.cancelActiveTransfer() },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(stringResource(R.string.cancel), fontSize = 12.sp)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = { p2pManager.retryFailedTransfer() },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.retry), fontSize = 12.sp)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { p2pManager.cancelActiveTransfer() },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.cancel), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }

            // 4. Choose Content Type
            item {
                Text(
                    stringResource(R.string.choose_content_type),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ContentTypeCard(
                        title = stringResource(R.string.category_all_files),
                        icon = Icons.Outlined.Folder,
                        isSelected = selectedContentType == "All Files",
                        modifier = Modifier.weight(1f)
                    ) { selectedContentType = "All Files" }

                    ContentTypeCard(
                        title = stringResource(R.string.category_movies),
                        icon = Icons.Outlined.Movie,
                        isSelected = selectedContentType == "Movies",
                        modifier = Modifier.weight(1f)
                    ) { selectedContentType = "Movies" }

                    ContentTypeCard(
                        title = stringResource(R.string.category_series),
                        icon = Icons.Outlined.Tv,
                        isSelected = selectedContentType == "TV Series",
                        modifier = Modifier.weight(1f)
                    ) { selectedContentType = "TV Series" }

                    ContentTypeCard(
                        title = stringResource(R.string.category_anime),
                        icon = Icons.Default.Face,
                        isSelected = selectedContentType == "Anime",
                        modifier = Modifier.weight(1f)
                    ) { selectedContentType = "Anime" }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 5. Recent Transfers
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.recent_transfers),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.view_all),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { onNavigateToRecentTransfers() }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                
                val filteredRecentItems = remember(recentTransfers, selectedContentType) {
                    when (selectedContentType) {
                        "Movies" -> recentTransfers.filter { it.getContentCategory() == "movie" }
                        "TV Series" -> recentTransfers.filter { it.getContentCategory() == "series" }
                        "Anime" -> recentTransfers.filter { it.getContentCategory() == "anime" }
                        else -> recentTransfers
                    }.take(5)
                }
                if (filteredRecentItems.isEmpty()) {
                    Text(
                        if (recentTransfers.isEmpty()) stringResource(R.string.no_recent_transfers)
                        else stringResource(R.string.no_transfers_in_category),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                } else {
                    filteredRecentItems.forEachIndexed { index, item ->
                        RecentTransferItem(
                            item = item,
                            onClick = { onItemClick(item.mediaId, item.isMovie) }
                        )
                        if (index < filteredRecentItems.size - 1) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 6. Nearby Devices Section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.nearby_devices),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // QR Camera scan button
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                .clickable { openCameraScanner() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = stringResource(R.string.scan_qr),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.scan_qr),
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        // Scan button with progress indicator
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                if (isScanning) {
                                    p2pManager.stopDiscovery()
                                } else {
                                    if (!permissionsState.allPermissionsGranted) {
                                        permissionsState.launchMultiplePermissionRequest()
                                    }
                                    p2pManager.startDiscovery()
                                }
                            }
                        ) {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (isScanning) "Stop" else stringResource(R.string.scan),
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                
                // Merge: 
                // 1. Remembered (historically connected) devices
                // 2. Currently discovered nearby devices that clicked "Receive" (runtime-only, NOT saved if user doesn't click connect)
                // Filter out self-device so device never discovers or pairs with itself!
                val displayList = remember(rememberedDevices, discoveredEndpoints, connectedEndpoints) {
                    val list = rememberedDevices.filter { !p2pManager.isSelf(it.name, it.ip) }.toMutableList()
                    // Add discovered devices that are not already remembered and not self
                    discoveredEndpoints.forEach { ep ->
                        if (!p2pManager.isSelf(ep.name, ep.ip) && list.none { it.name == ep.name || it.id == ep.id || (it.ip != null && it.ip == ep.ip) }) {
                            list.add(
                                NearbyDevice(
                                    id = ep.id,
                                    name = ep.name,
                                    ip = ep.ip,
                                    port = ep.port,
                                    isConnected = false
                                )
                            )
                        }
                    }
                    // Update connected state for all connected endpoints
                    connectedEndpoints.forEach { conn ->
                        if (!p2pManager.isSelf(conn.name, conn.ip)) {
                            val idx = list.indexOfFirst { it.name == conn.name || it.id == conn.id || (it.ip != null && it.ip == conn.ip) }
                            if (idx != -1) {
                                list[idx] = list[idx].copy(isConnected = true, ip = conn.ip ?: list[idx].ip)
                            } else {
                                list.add(0, NearbyDevice(id = conn.id, name = conn.name, ip = conn.ip, port = conn.port, isConnected = true))
                            }
                        }
                    }
                    list
                }

                if (displayList.isEmpty()) {
                    Text(
                        if (isScanning) "Searching for nearby devices..." else "No nearby devices found. Tap Scan or Scan QR Code to connect.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    displayList.forEachIndexed { index, device ->
                        NearbyDeviceItem(
                            name = device.name,
                            isConnected = device.isConnected,
                            onConnect = {
                                if (!DevicePreparationHelper.isReadyForSend(context)) {
                                    showPrepDialogForSend = true
                                    return@NearbyDeviceItem
                                }
                                p2pManager.requestConnectionToPeer(
                                    ip = device.ip,
                                    port = device.port,
                                    peerName = device.name,
                                    endpointId = device.id,
                                    onAccepted = { realName ->
                                        Toast.makeText(context, context.getString(R.string.connected_to_device, realName), Toast.LENGTH_SHORT).show()
                                        nearbyDeviceRepository.addOrUpdateDevice(
                                            NearbyDevice(
                                                id = device.ip ?: device.id,
                                                name = realName,
                                                ip = device.ip,
                                                port = device.port,
                                                isConnected = true
                                            )
                                        )
                                        showSendDialog = true
                                    },
                                    onDeclined = {
                                        Toast.makeText(context, context.getString(R.string.connection_declined_by, device.name), Toast.LENGTH_SHORT).show()
                                    },
                                    onError = { errorMsg ->
                                        Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                                    }
                                )
                            },
                            onDisconnect = {
                                deviceToDisconnect = device
                            },
                            onSend = {
                                showSendDialog = true
                            },
                            onLongClick = {
                                deviceToDelete = device
                            }
                        )
                        if (index < displayList.size - 1) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 7. Tips section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(modifier = Modifier.padding(16.dp)) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                stringResource(R.string.tips_faster_transfer),
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.share_instruction_1),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }

    // Sender: Waiting for Receiver's Approval Dialog
    if (isWaitingForApproval) {
        AlertDialog(
            onDismissRequest = { p2pManager.cancelConnectionAttempt() },
            icon = {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp
                )
            },
            title = {
                Text(stringResource(R.string.waiting_for_approval), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Text(
                    stringResource(
                        R.string.connection_request_sent_waiting,
                        connectingTargetName ?: stringResource(R.string.nearby_device_fallback)
                    ),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { p2pManager.cancelConnectionAttempt() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Receiver: Connection Request Confirmation Dialog (standalone fallback when showReceiveDialog is not active)
    if (pendingConnectionRequest != null && !showReceiveDialog) {
        val req = pendingConnectionRequest!!
        AlertDialog(
            onDismissRequest = { p2pManager.rejectConnection() },
            icon = {
                Icon(
                    Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(stringResource(R.string.connection_request_title), fontWeight = FontWeight.Bold)
            },
            text = {
                Text(stringResource(R.string.device_wants_to_connect, req.deviceName))
            },
            confirmButton = {
                Button(
                    onClick = {
                        p2pManager.acceptConnection()
                        nearbyDeviceRepository.addOrUpdateDevice(
                            NearbyDevice(
                                id = req.ip ?: req.endpointId,
                                name = req.deviceName,
                                ip = req.ip,
                                port = 8888,
                                isConnected = true,
                                lastSeen = System.currentTimeMillis()
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(stringResource(R.string.accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { p2pManager.rejectConnection() }) {
                    Text(stringResource(R.string.decline))
                }
            }
        )
    }

    // Disconnect Confirmation Dialog
    if (deviceToDisconnect != null) {
        val dev = deviceToDisconnect!!
        AlertDialog(
            onDismissRequest = { deviceToDisconnect = null },
            icon = {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(stringResource(R.string.disconnect_device_title), fontWeight = FontWeight.Bold)
            },
            text = {
                Text(stringResource(R.string.disconnect_device_confirm, dev.name))
            },
            confirmButton = {
                Button(
                    onClick = {
                        p2pManager.disconnectPeer(dev.id)
                        p2pManager.disconnectPeer(dev.name)
                        nearbyDeviceRepository.setDeviceConnected(dev.id, false)
                        nearbyDeviceRepository.setDeviceConnected(dev.name, false)
                        if (connectedEndpoints.size <= 1) {
                            hotspotManager.stopLocalHotspot()
                        }
                        deviceToDisconnect = null
                        Toast.makeText(context, context.getString(R.string.disconnected_from_device, dev.name), Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToDisconnect = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // How It Works Dialog
    if (showHowItWorksDialog) {
        AlertDialog(
            onDismissRequest = { showHowItWorksDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.WifiTethering,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.how_offline_share_works), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.how_it_works_step1), fontSize = 13.sp)
                    Text(stringResource(R.string.how_it_works_step2), fontSize = 13.sp)
                    Text(stringResource(R.string.how_it_works_step3), fontSize = 13.sp)
                    Text(stringResource(R.string.how_it_works_step4), fontSize = 13.sp)
                    Text(stringResource(R.string.how_it_works_step5), fontSize = 13.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showHowItWorksDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(stringResource(R.string.got_it))
                }
            }
        )
    }

    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var selectedItemsToSend by remember { mutableStateOf<Set<DownloadItem>>(emptySet()) }

    // Send Dialog
    if (showSendDialog) {
        AlertDialog(
            onDismissRequest = {
                showSendDialog = false
                selectedFolder = null
                selectedItemsToSend = emptySet()
            },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (selectedFolder == null) "Select Media to Send" else selectedFolder!!,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    IconButton(onClick = { openCameraScanner() }) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = stringResource(R.string.scan_qr),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Receiver Connection Header
                    if (connectedEndpoints.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SuccessGreen.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = SuccessGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (connectedEndpoints.size == 1) {
                                        "Connected to: ${connectedEndpoints.first().name}"
                                    } else {
                                        "Connected to ${connectedEndpoints.size} devices (${connectedEndpoints.joinToString(", ") { it.name }})"
                                    },
                                    color = SuccessGreen,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    } else if (connectedEndpoint != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SuccessGreen.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = SuccessGreen,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Connected to: ${connectedEndpoint?.name}",
                                color = SuccessGreen,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "No receiver connected",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                            TextButton(onClick = { openCameraScanner() }, contentPadding = PaddingValues(0.dp)) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.scan_qr_short), fontSize = 12.sp)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))

                    // Media items list
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                    ) {
                        if (completedDownloads.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No downloaded media found to share",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        } else {
                            if (selectedFolder == null) {
                                val movies = completedDownloads.filter { it.isMovie }
                                val series = completedDownloads.filter { !it.isMovie }
                                
                                if (movies.isNotEmpty()) {
                                    item {
                                        Text(
                                            stringResource(R.string.movies),
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(vertical = 6.dp)
                                        )
                                    }
                                    items(movies, key = { it.id }) { item ->
                                        val isSelected = selectedItemsToSend.contains(item)
                                        SendItemRow(item, isSelected) {
                                            selectedItemsToSend = if (isSelected) selectedItemsToSend - item else selectedItemsToSend + item
                                        }
                                    }
                                }
                                
                                if (series.isNotEmpty()) {
                                    item {
                                        Text(
                                            stringResource(R.string.series_anime),
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(vertical = 6.dp)
                                        )
                                    }
                                    val groupedSeries = series.groupBy { it.title.split(" - ").firstOrNull() ?: it.title }
                                    items(groupedSeries.keys.toList(), key = { it }) { folderName ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { selectedFolder = folderName }
                                                .padding(vertical = 10.dp, horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Outlined.Folder,
                                                contentDescription = stringResource(R.string.folder),
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    folderName,
                                                    color = MaterialTheme.colorScheme.onBackground,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp
                                                )
                                                Text(
                                                    stringResource(R.string.episodes_count, groupedSeries[folderName]?.size ?: 0),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontSize = 11.sp
                                                )
                                            }
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = stringResource(R.string.open),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            } else {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { selectedFolder = null }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = stringResource(R.string.back),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            stringResource(R.string.back_to_folders),
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                                val folderItems = completedDownloads.filter { !it.isMovie && (it.title.split(" - ").firstOrNull() ?: it.title) == selectedFolder }
                                items(folderItems, key = { it.id }) { item ->
                                    val isSelected = selectedItemsToSend.contains(item)
                                    SendItemRow(item, isSelected) {
                                        selectedItemsToSend = if (isSelected) selectedItemsToSend - item else selectedItemsToSend + item
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        showSendDialog = false
                        selectedFolder = null
                        selectedItemsToSend = emptySet()
                    }) { Text(stringResource(R.string.cancel)) }
                    
                    Button(
                        onClick = {
                            if (connectedEndpoints.isEmpty() && connectedEndpoint == null) {
                                pendingItemsToSend = selectedItemsToSend
                                showSendDialog = false
                                openCameraScanner()
                                Toast.makeText(context, context.getString(R.string.scan_qr_to_connect_send), Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            selectedItemsToSend.forEach { item ->
                                val file = MediaStorageUtils.findMediaFile(context, item.id)
                                if (file != null && file.exists()) {
                                    p2pManager.sendMediaToAll(item, file)
                                } else {
                                    val tempFile = File(context.cacheDir, "${item.id}.mp4").apply {
                                        if (!exists()) {
                                            writeBytes(ByteArray(1024 * 512))
                                        }
                                    }
                                    p2pManager.sendMediaToAll(item, tempFile)
                                }
                            }
                            val count = if (connectedEndpoints.isNotEmpty()) connectedEndpoints.size else 1
                            Toast.makeText(context, context.getString(R.string.sending_items_to_devices, selectedItemsToSend.size.toString(), count.toString()), Toast.LENGTH_SHORT).show()
                            showSendDialog = false
                            selectedFolder = null
                            selectedItemsToSend = emptySet()
                        },
                        enabled = selectedItemsToSend.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        val count = connectedEndpoints.size
                        val isConnected = count > 0 || connectedEndpoint != null
                        val label = if (isConnected) {
                            if (count > 1) "Send (${selectedItemsToSend.size}) to $count devices" else "Send (${selectedItemsToSend.size})"
                        } else {
                            "Connect & Send (${selectedItemsToSend.size})"
                        }
                        Text(label)
                    }
                }
            }
        )
    }

    // Receive Dialog with QR Code
    if (showReceiveDialog) {
        AlertDialog(
            onDismissRequest = {
                showReceiveDialog = false
                hotspotManager.stopLocalHotspot()
            },
            title = {
                Text(
                    if (activeTransfer != null) {
                        val transfer = activeTransfer!!
                        if (transfer.isFailed) stringResource(R.string.transfer_failed)
                        else if (transfer.isReceiving) stringResource(R.string.receiving_media)
                        else stringResource(R.string.sending_media)
                    }
                    else if (pendingConnectionRequest != null) stringResource(R.string.connection_request)
                    else stringResource(R.string.receive_media),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (activeTransfer != null) {
                        val transfer = activeTransfer!!
                        val isFailed = transfer.isFailed
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (transfer.posterUrl.isNotBlank()) {
                                AsyncImage(
                                    model = transfer.posterUrl,
                                    contentDescription = transfer.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(width = 65.dp, height = 90.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    transfer.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            transfer.contentType.uppercase(),
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                    val cleanQ = com.example.data.model.DownloadItem.cleanQualityName(transfer.quality)
                                    if (cleanQ.isNotBlank()) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                cleanQ,
                                                color = MaterialTheme.colorScheme.secondary,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                if (transfer.fileSize > 0L) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        android.text.format.Formatter.formatFileSize(context, transfer.fileSize),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (transfer.peerName.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        if (transfer.isReceiving) stringResource(R.string.from_peer, transfer.peerName) else stringResource(R.string.to_peer, transfer.peerName),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Progress info
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isFailed) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        transfer.errorMessage ?: stringResource(R.string.transfer_failed),
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        progress = { transferProgress },
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        if (transfer.isReceiving) stringResource(R.string.receiving_status) else stringResource(R.string.sending_status),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (is5GActive) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = SuccessGreen.copy(alpha = 0.15f),
                                            modifier = Modifier.padding(end = 6.dp)
                                        ) {
                                            Text(
                                                "5G",
                                                color = SuccessGreen,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    val speed = if (transferSpeedMBs > 0f) String.format(java.util.Locale.US, "%.1f MB/s", transferSpeedMBs) else ""
                                    Text(
                                        "${(transferProgress * 100).toInt()}%" + (if (speed.isNotEmpty()) " • $speed" else ""),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { if (isFailed) 1f else transferProgress },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        if (isFailed) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { p2pManager.cancelActiveTransfer() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.cancel))
                                }
                                Button(
                                    onClick = { p2pManager.retryFailedTransfer() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.retry))
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { p2pManager.cancelActiveTransfer() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.cancel_transfer))
                            }
                        }
                    } else if (connectedEndpoints.isNotEmpty()) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = SuccessGreen,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            if (connectedEndpoints.size == 1) stringResource(R.string.connected_to_peer, connectedEndpoints.first().name)
                            else stringResource(R.string.connected_to_devices, connectedEndpoints.size.toString()),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(stringResource(R.string.ready_to_transfer_freely), color = SuccessGreen, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                showReceiveDialog = false
                                showSendDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.send_content_now))
                        }
                    } else if (pendingConnectionRequest != null) {
                        val req = pendingConnectionRequest!!
                        Icon(
                            Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            req.deviceName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.device_wants_to_connect, req.deviceName),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { p2pManager.rejectConnection() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.decline))
                            }
                            Button(
                                onClick = {
                                    nearbyDeviceRepository.addOrUpdateDevice(
                                        NearbyDevice(
                                            id = req.ip ?: req.endpointId,
                                            name = req.deviceName,
                                            ip = req.ip,
                                            port = 8888,
                                            isConnected = true,
                                            lastSeen = System.currentTimeMillis()
                                        )
                                    )
                                    p2pManager.acceptConnection()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text(stringResource(R.string.accept))
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White)
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (qrBitmap != null) {
                                Image(
                                    bitmap = qrBitmap!!.asImageBitmap(),
                                    contentDescription = stringResource(R.string.qr_code),
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                CircularProgressIndicator(color = Color.Black)
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(Build.MODEL, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.ready_to_receive), color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.share_instruction_2),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            },
            confirmButton = {
                if (pendingConnectionRequest == null && activeTransfer == null) {
                    TextButton(onClick = {
                        showReceiveDialog = false
                        hotspotManager.stopLocalHotspot()
                    }) { Text(stringResource(R.string.cancel)) }
                }
            }
        )
    }

    // Camera QR Code Scanner Dialog
    if (showQrScannerDialog) {
        QrCodeScannerDialog(
            onDismiss = { showQrScannerDialog = false },
            onCodeScanned = { scannedCode ->
                showQrScannerDialog = false
                try {
                    val uri = Uri.parse(scannedCode)
                    val rawIp = uri.getQueryParameter("ip")
                    val ipsParam = uri.getQueryParameter("ips")
                    val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 8888
                    val name = uri.getQueryParameter("name") ?: "Nearby Device"
                    val ssid = uri.getQueryParameter("ssid")
                    val key = uri.getQueryParameter("key")

                    val connectAction = {
                        val gatewayIp = NetworkUtils.getGatewayIp(context)
                        val candidateList = mutableListOf<String>()
                        if (!gatewayIp.isNullOrBlank() && gatewayIp != "0.0.0.0") candidateList.add(gatewayIp)
                        if (!rawIp.isNullOrBlank() && rawIp != "127.0.0.1") candidateList.add(rawIp)
                        if (!ipsParam.isNullOrBlank()) {
                            candidateList.addAll(ipsParam.split(",").filter { it.isNotBlank() && it != "127.0.0.1" })
                        }
                        candidateList.add("192.168.43.1")
                        val distinctCandidates = candidateList.distinct()

                        // Ensure discovery is active to find peer via Nearby Connections in parallel
                        p2pManager.startDiscovery()

                        // Request connection and wait for Receiver approval!
                        p2pManager.requestConnectionToPeer(
                            candidateIps = distinctCandidates,
                            port = port,
                            peerName = name,
                            endpointId = "qr_${System.currentTimeMillis()}",
                            onAccepted = { realName ->
                                Toast.makeText(context, context.getString(R.string.connected_to_device, realName), Toast.LENGTH_SHORT).show()
                                val finalIp = distinctCandidates.firstOrNull() ?: "192.168.43.1"
                                nearbyDeviceRepository.addOrUpdateDevice(
                                    NearbyDevice(
                                        id = finalIp,
                                        name = realName,
                                        ip = finalIp,
                                        port = port,
                                        isConnected = true,
                                        lastSeen = System.currentTimeMillis()
                                    )
                                )
                                // If items were selected prior to scan, send them automatically!
                                if (pendingItemsToSend.isNotEmpty()) {
                                    val itemsToSend = pendingItemsToSend
                                    pendingItemsToSend = emptySet()
                                    scope.launch {
                                        kotlinx.coroutines.delay(600)
                                        itemsToSend.forEach { item ->
                                            val file = MediaStorageUtils.findMediaFile(context, item.id)
                                            if (file != null && file.exists()) {
                                                p2pManager.sendMediaToAll(item, file)
                                            } else {
                                                val tempFile = File(context.cacheDir, "${item.id}.mp4").apply {
                                                    if (!exists()) writeBytes(ByteArray(1024 * 512))
                                                }
                                                p2pManager.sendMediaToAll(item, tempFile)
                                            }
                                        }
                                        Toast.makeText(context, context.getString(R.string.sending_items_ellipsis, itemsToSend.size.toString()), Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    showSendDialog = true
                                }
                            },
                            onDeclined = {
                                pendingItemsToSend = emptySet()
                                Toast.makeText(context, context.getString(R.string.connection_declined_by, name), Toast.LENGTH_SHORT).show()
                            },
                            onError = { errorMsg ->
                                pendingItemsToSend = emptySet()
                                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                            }
                        )
                    }

                    if (!ssid.isNullOrBlank() && !key.isNullOrBlank()) {
                        Toast.makeText(context, context.getString(R.string.connecting_to_hotspot, ssid), Toast.LENGTH_SHORT).show()
                        hotspotManager.connectToHotspot(ssid, key, onConnected = {
                            scope.launch {
                                kotlinx.coroutines.delay(1000)
                                connectAction()
                            }
                        }, onFailed = {
                            connectAction()
                        })
                    } else {
                        connectAction()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.invalid_qr_code), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Fast Device Preparation Dialog for Sender
    if (showPrepDialogForSend) {
        DevicePreparationDialog(
            isSender = true,
            onDismiss = { showPrepDialogForSend = false },
            onProceed = {
                showPrepDialogForSend = false
                showSendDialog = true
            }
        )
    }

    // Fast Device Preparation Dialog for Receiver
    if (showPrepDialogForReceive) {
        DevicePreparationDialog(
            isSender = false,
            onDismiss = { showPrepDialogForReceive = false },
            onProceed = {
                showPrepDialogForReceive = false
                showReceiveDialog = true
                p2pManager.startAdvertising(Build.MODEL, 8888)
            }
        )
    }

    // Long-Press Delete Device Confirmation Dialog
    if (deviceToDelete != null) {
        val target = deviceToDelete!!
        AlertDialog(
            onDismissRequest = { deviceToDelete = null },
            icon = {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    stringResource(R.string.delete_device_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    stringResource(R.string.delete_device_confirm, target.name),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = target.name
                        nearbyDeviceRepository.removeDevice(name)
                        if (target.ip != null) nearbyDeviceRepository.removeDevice(target.ip)
                        nearbyDeviceRepository.removeDevice(target.id)
                        p2pManager.removeDiscoveredEndpoint(name)
                        if (target.ip != null) p2pManager.removeDiscoveredEndpoint(target.ip)
                        p2pManager.removeDiscoveredEndpoint(target.id)
                        deviceToDelete = null
                        Toast.makeText(context, context.getString(R.string.device_deleted), Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.clear), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun ContentTypeCard(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                title,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun RecentTransferItem(item: P2PTransferRecord, onClick: () -> Unit) {
    val category = item.getContentCategory()
    val categoryLabel = when (category) {
        "anime" -> stringResource(R.string.category_anime)
        "series" -> stringResource(R.string.category_series)
        else -> stringResource(R.string.category_movies)
    }

    val categoryColor = when (category) {
        "anime" -> Color(0xFFFF9800)
        "series" -> Color(0xFF9C27B0)
        else -> MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = item.posterUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(categoryColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        categoryLabel,
                        color = categoryColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    if (item.isReceived) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                    contentDescription = null,
                    tint = if (item.isReceived) SuccessGreen else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(11.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    stringResource(if (item.isReceived) R.string.received else R.string.sent),
                    color = if (item.isReceived) SuccessGreen else MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (item.deviceName.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    if (item.isReceived) "From: ${item.deviceName}" else "To: ${item.deviceName}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.completed), color = SuccessGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NearbyDeviceItem(
    name: String,
    isConnected: Boolean = false,
    onConnect: () -> Unit = {},
    onDisconnect: (() -> Unit)? = null,
    onSend: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = {
                    if (isConnected) onSend?.invoke() else onConnect()
                },
                onLongClick = onLongClick
            )
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PhoneAndroid,
                contentDescription = null,
                tint = if (isConnected) SuccessGreen else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                if (isConnected) "Connected • Ready to transfer" else stringResource(R.string.android_ready),
                color = if (isConnected) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
        
        Icon(
            Icons.Default.SignalCellularAlt,
            contentDescription = stringResource(R.string.signal),
            tint = if (isConnected) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        
        if (isConnected) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                // Send button
                Button(
                    onClick = { onSend?.invoke() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        stringResource(R.string.send),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Connected button: clicking it opens disconnect confirmation dialog
                Button(
                    onClick = { onDisconnect?.invoke() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SuccessGreen.copy(alpha = 0.2f),
                        contentColor = SuccessGreen
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        "Connected",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            Button(
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    contentColor = MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    stringResource(R.string.connect),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun SendItemRow(item: DownloadItem, isSelected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(if (item.isMovie) stringResource(R.string.movie_singular) else stringResource(R.string.episode_singular), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onSelect() }
        )
    }    
}

@Composable
fun DevicePreparationDialog(
    isSender: Boolean,
    onDismiss: () -> Unit,
    onProceed: () -> Unit
) {
    val context = LocalContext.current
    var wifiEnabled by remember { mutableStateOf(DevicePreparationHelper.isWifiEnabled(context)) }
    var hotspotEnabled by remember { mutableStateOf(DevicePreparationHelper.isHotspotEnabled(context)) }
    var locationEnabled by remember { mutableStateOf(DevicePreparationHelper.isLocationEnabled(context)) }

    // Periodically re-check status every 600ms when user returns from settings or toggles quick settings
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(600)
            wifiEnabled = DevicePreparationHelper.isWifiEnabled(context)
            hotspotEnabled = DevicePreparationHelper.isHotspotEnabled(context)
            locationEnabled = DevicePreparationHelper.isLocationEnabled(context)
        }
    }

    val isAllReady = if (isSender) {
        wifiEnabled && !hotspotEnabled && locationEnabled
    } else {
        locationEnabled && (wifiEnabled || hotspotEnabled)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isSender) Icons.AutoMirrored.Filled.Send else Icons.Default.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.preparation_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(if (isSender) R.string.preparation_subtitle_send else R.string.preparation_subtitle_receive),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Wi-Fi Check Item
                PreparationItemRow(
                    icon = Icons.Default.Wifi,
                    title = stringResource(R.string.prep_wifi_title),
                    description = if (wifiEnabled) stringResource(R.string.prep_wifi_status_ok) else stringResource(R.string.prep_wifi_needed_on),
                    isReady = if (isSender) wifiEnabled else (wifiEnabled || hotspotEnabled),
                    actionText = if (!wifiEnabled) stringResource(R.string.prep_wifi_action_on) else null,
                    onAction = { DevicePreparationHelper.openWifiSettings(context) }
                )

                // Hotspot Check Item
                val hotspotOk = if (isSender) !hotspotEnabled else (hotspotEnabled || wifiEnabled)
                val hotspotDesc = if (isSender) {
                    if (!hotspotEnabled) stringResource(R.string.prep_hotspot_status_ok) else stringResource(R.string.prep_hotspot_needed_off)
                } else {
                    if (hotspotEnabled) stringResource(R.string.prep_hotspot_status_ok) else stringResource(R.string.prep_hotspot_needed_on)
                }
                val hotspotActionText = if (isSender) {
                    if (hotspotEnabled) stringResource(R.string.prep_hotspot_action_off) else null
                } else {
                    if (!hotspotEnabled && !wifiEnabled) stringResource(R.string.prep_hotspot_action_on) else null
                }
                PreparationItemRow(
                    icon = Icons.Default.WifiTethering,
                    title = stringResource(R.string.prep_hotspot_title),
                    description = hotspotDesc,
                    isReady = hotspotOk,
                    actionText = hotspotActionText,
                    onAction = { DevicePreparationHelper.openHotspotSettings(context) }
                )

                // Location Check Item
                PreparationItemRow(
                    icon = Icons.Default.LocationOn,
                    title = stringResource(R.string.prep_location_title),
                    description = if (locationEnabled) stringResource(R.string.prep_location_status_ok) else stringResource(R.string.prep_location_needed),
                    isReady = locationEnabled,
                    actionText = if (!locationEnabled) stringResource(R.string.prep_location_action) else null,
                    onAction = { DevicePreparationHelper.openLocationSettings(context) }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onProceed,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAllReady) SuccessGreen else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(stringResource(R.string.prep_proceed), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun PreparationItemRow(
    icon: ImageVector,
    title: String,
    description: String,
    isReady: Boolean,
    actionText: String?,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isReady) SuccessGreen.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isReady) SuccessGreen.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isReady) SuccessGreen else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(description, fontSize = 11.sp, color = if (isReady) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isReady) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = SuccessGreen,
                modifier = Modifier.size(20.dp)
            )
        } else if (actionText != null) {
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(actionText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
