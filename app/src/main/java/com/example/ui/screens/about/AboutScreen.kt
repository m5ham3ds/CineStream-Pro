package com.example.ui.screens.about

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

import androidx.compose.runtime.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val (versionCode, versionName) = remember { com.example.data.repository.AppUpdateManager.getCurrentVersionInfo(context) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<com.example.data.repository.AppUpdateInfo?>(null) }
    var showUpToDateDialog by remember { mutableStateOf(false) }
    var upToDateDetails by remember { mutableStateOf("") }

    var transitionFinished by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(400)
        transitionFinished = true
    }

    if (!transitionFinished) {
        com.example.ui.components.AboutScreenSkeleton()
        return
    }

    if (showUpToDateDialog) {
        val isAr = java.util.Locale.getDefault().language == "ar"
        AlertDialog(
            onDismissRequest = { showUpToDateDialog = false },
            icon = {
                Icon(
                    Icons.Outlined.Verified,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = if (isAr) "أنت تستخدم أحدث إصدار" else "You're Up to Date",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = if (isAr)
                        "تطبيقك محدث بالكامل إلى الإصدار ($upToDateDetails). لا توجد تحديثات جديدة متوفرة حالياً."
                    else
                        "Your app is fully up to date ($upToDateDetails). No new updates are available right now.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = { showUpToDateDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (isAr) "حسناً" else "OK")
                }
            }
        )
    }

    availableUpdate?.let { updateInfo ->
        com.example.ui.components.AppUpdateDialog(
            updateInfo = updateInfo,
            onDismiss = { availableUpdate = null }
        )
    }

    val scrollState = rememberScrollState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Button(
                        onClick = {
                            if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.no_internet_check_connection),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                return@Button
                            }
                            if (!isCheckingUpdate) {
                                isCheckingUpdate = true
                                scope.launch {
                                    val startTime = System.currentTimeMillis()
                                    val result = com.example.data.repository.AppUpdateManager.checkForUpdateResult(context)
                                    val elapsed = System.currentTimeMillis() - startTime
                                    if (elapsed < 800) {
                                        kotlinx.coroutines.delay(800 - elapsed)
                                    }
                                    isCheckingUpdate = false
                                    when (result) {
                                        is com.example.data.repository.UpdateCheckResult.UpdateAvailable -> {
                                            availableUpdate = result.info
                                        }
                                        is com.example.data.repository.UpdateCheckResult.UpToDate -> {
                                            upToDateDetails = result.currentVersion
                                            showUpToDateDialog = true
                                        }
                                        is com.example.data.repository.UpdateCheckResult.NoInternet -> {
                                            android.widget.Toast.makeText(
                                                context,
                                                context.getString(R.string.no_internet_check_connection),
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        is com.example.data.repository.UpdateCheckResult.Error -> {
                                            android.widget.Toast.makeText(
                                                context,
                                                result.message.ifBlank { context.getString(R.string.update_check_failed) },
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isCheckingUpdate
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                Icons.Default.SystemUpdate,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.check_for_updates),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 24.dp)
        ) {
            // Top section with logo and text
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(0.6f)) {
                    Text(
                        text = stringResource(R.string.about_app),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.app_desc_long),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(modifier = Modifier.width(32.dp).height(2.dp).background(MaterialTheme.colorScheme.primary))
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Version Chip
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("v$versionName ($versionCode)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.last_updated), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }

                // Logo Image placeholder
                Box(modifier = Modifier.weight(0.4f).padding(start = 16.dp), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = "https://images.unsplash.com/photo-1485846234645-a62644f84728?q=80&w=300&auto=format&fit=crop",
                        contentDescription = stringResource(R.string.logo),
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp)),
                        contentScale = ContentScale.Crop,
                        alpha = 0.8f
                    )
                    // Red C placeholder
                    Text("C", color = MaterialTheme.colorScheme.primary, fontSize = 60.sp, fontWeight = FontWeight.Black, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Our Mission Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(0.7f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Target icon placeholder
                            Icon(Icons.Outlined.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.our_mission), color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.about_mission_desc),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }
                    // Placeholder image for director's chair
                    AsyncImage(
                        model = "https://images.unsplash.com/photo-1594909122845-11baa439b7bf?q=80&w=200&auto=format&fit=crop",
                        contentDescription = null,
                        modifier = Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                        alpha = 0.6f
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Features Grid
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureCard(modifier = Modifier.weight(1f), icon = Icons.Outlined.Verified, title = stringResource(R.string.feature_reliable_title), subtitle = stringResource(R.string.feature_reliable_desc))
                FeatureCard(modifier = Modifier.weight(1f), icon = Icons.Outlined.Verified, title = stringResource(R.string.feature_fast_title), subtitle = stringResource(R.string.feature_fast_desc))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureCard(modifier = Modifier.weight(1f), icon = Icons.Outlined.Diamond, title = stringResource(R.string.feature_premium_title), subtitle = stringResource(R.string.feature_premium_desc))
                FeatureCard(modifier = Modifier.weight(1f), icon = Icons.Outlined.FavoriteBorder, title = stringResource(R.string.feature_made_for_you_title), subtitle = stringResource(R.string.feature_made_for_you_desc))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Links list
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            ) {
                AboutLinkItem(icon = Icons.Outlined.Groups, title = stringResource(R.string.link_meet_team), isLast = false)
                AboutLinkItem(icon = Icons.Outlined.Policy, title = stringResource(R.string.link_terms), isLast = false)
                AboutLinkItem(icon = Icons.Outlined.Lock, title = stringResource(R.string.link_privacy), isLast = false)
                AboutLinkItem(icon = Icons.Outlined.ChatBubbleOutline, title = stringResource(R.string.link_contact), isLast = true)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun FeatureCard(modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 16.sp)
    }
}

@Composable
fun AboutLinkItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, isLast: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!isLast) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.surfaceVariant))
    }

    



}

