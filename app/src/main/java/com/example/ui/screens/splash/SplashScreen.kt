package com.example.ui.screens.splash
import androidx.compose.material.icons.filled.Movie
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Face

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.repository.UserPreferencesRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SplashScreen(
    onNavigateToOnboarding: () -> Unit,
    onNavigateToAuth: () -> Unit,
    onNavigateToMain: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val userPrefs = remember { UserPreferencesRepository(context) }
    
    var startAnimation by remember { mutableStateOf(false) }
    var isMaintenance by remember { mutableStateOf(false) }
    var maintenanceTitle by remember { mutableStateOf("") }
    var maintenanceMessage by remember { mutableStateOf("") }
    var mandatoryUpdateInfo by remember { mutableStateOf<com.example.data.repository.AppUpdateInfo?>(null) }
    var retryTrigger by remember { mutableIntStateOf(0) }

    val alphaAnim = animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1200),
        label = "alphaAnim"
    )
    
    LaunchedEffect(retryTrigger) {
        startAnimation = true
        isMaintenance = false
        mandatoryUpdateInfo = null

        // Initialize and refresh managed extensions
        com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(context).refreshRemote(force = false)

        com.example.data.repository.AppStartupManager.checkAppConfig(
            context = context,
            onProceed = {
                scope.launch {
                    delay(1800)
                    val hasSeenOnboarding = userPrefs.onboardingCompleted.first()
                    val isGuest = userPrefs.isGuest.first()
                    val isLoggedIn = userPrefs.isLoggedIn.first()
                    val startScreen = userPrefs.startScreen.first()
                    
                    if (hasSeenOnboarding) {
                        if (isGuest) {
                            if (com.example.data.repository.AuthRepository.auth.currentUser == null) {
                                try {
                                    com.example.data.repository.AuthRepository.signInAnonymously()
                                } catch (e: Exception) {
                                    android.util.Log.w("SplashScreen", "Failed to ensure guest session on launch: ${e.message}")
                                }
                            }
                            onNavigateToMain(startScreen)
                        } else if (isLoggedIn) {
                            onNavigateToMain(startScreen)
                        } else {
                            onNavigateToAuth()
                        }
                    } else {
                        onNavigateToOnboarding()
                    }
                }
            },
            onMaintenance = { title, message ->
                isMaintenance = true
                maintenanceTitle = title
                maintenanceMessage = message
            },
            onUpdateAvailable = { isMandatory, apkUrl, notes, versionName ->
                if (isMandatory) {
                    val currentVersionCode = com.example.data.repository.AppStartupManager.getCurrentVersionCode(context)
                    mandatoryUpdateInfo = com.example.data.repository.AppUpdateInfo(
                        versionCode = currentVersionCode + 1L,
                        versionName = versionName,
                        releaseNotes = notes,
                        downloadUrl = apkUrl,
                        isMandatory = true
                    )
                }
            }
        )
    }

    if (isMaintenance) {
        com.example.ui.screens.maintenance.MaintenanceScreen(
            title = maintenanceTitle,
            message = maintenanceMessage,
            onMaintenanceLifted = {
                isMaintenance = false
                retryTrigger++
            },
            onRetry = {
                isMaintenance = false
                retryTrigger++
            }
        )
        return
    }

    mandatoryUpdateInfo?.let { updateInfo ->
        com.example.ui.components.AppUpdateDialog(
            updateInfo = updateInfo,
            onDismiss = { }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Red glows at top corners
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(200.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(200.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), Color.Transparent)
                    )
                )
        )
        
        // Theater seats background
        AsyncImage(
            model = "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?q=80&w=1000&auto=format&fit=crop",
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .align(Alignment.BottomCenter)
                .alpha(0.4f)
        )
        
        // Gradient fade to black over the image
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.2f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 180.dp)
                .alpha(alphaAnim.value)
        ) {
            // Play Button
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .border(4.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    // Inner red glow
                    .background(
                        Brush.radialGradient(
                            colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), Color.Transparent)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(50.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // CineStream Text
            Text(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                        append("Cine")
                    }
                    withStyle(style = SpanStyle(color = Color.White)) {
                        append("Stream")
                    }
                },
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Tagline
            Text(
                text = stringResource(R.string.slogan1),
                color = Color.Gray,
                fontSize = 16.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 24.sp
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Three Cards
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SplashCard(icon = Icons.Default.Movie, text = stringResource(R.string.movies))
                SplashCard(icon = Icons.Default.Tv, text = stringResource(R.string.category_series))
                SplashCard(icon = Icons.Default.Face, text = stringResource(R.string.category_anime))
            }
        }
        
        // Bottom Text
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .alpha(alphaAnim.value),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(modifier = Modifier.width(32.dp).height(1.dp).background(Color.DarkGray))
            Text(
                text = stringResource(R.string.good_stories_never_end),
                color = Color.Gray,
                fontSize = 10.sp,
                letterSpacing = 2.sp
            )
            Box(modifier = Modifier.width(32.dp).height(1.dp).background(Color.DarkGray))
        }
    }
}

@Composable
fun SplashCard(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Box(
        modifier = Modifier
            .size(80.dp, 90.dp)
            .border(1.dp, Color.DarkGray, RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = text,
                color = Color.LightGray,
                fontSize = 12.sp
            )
        }
    }
}
