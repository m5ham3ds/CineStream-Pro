package com.example.ui.screens.profile

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.*
import com.example.ui.components.AuthDiagnosticDialog
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.R
import com.example.data.repository.AccountDeletionResult
import com.example.ui.screens.auth.AuthViewModel
import com.example.utils.NetworkUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    onBack: () -> Unit,
    onNavigateToEditProfile: () -> Unit,
    onNavigateToSecurity: () -> Unit,
    onNavigateToSubscription: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToHelpSupport: () -> Unit,
    onNavigateToNotifications: () -> Unit = {},
    onNavigateToShare: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToDownloads: () -> Unit = {},
    onNavigateToAuth: () -> Unit = {},
    onSignOut: () -> Unit = {},
    authViewModel: AuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val currentUser by authViewModel.currentUser.collectAsStateWithLifecycle()
    val restrictions by com.example.data.repository.UserSecurityManager.restrictionsFlow.collectAsState()
    val isAdFree = restrictions.isAdFree
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showDeleteAccountConfirm by remember { mutableStateOf(false) }
    var isDeletingAccount by remember { mutableStateOf(false) }
    var showAuthDiagnosticDialog by remember { mutableStateOf(false) }

    val bgColor = MaterialTheme.colorScheme.background
    val cardBg = MaterialTheme.colorScheme.surfaceVariant
    val redPrimary = MaterialTheme.colorScheme.primary
    val textGrey = MaterialTheme.colorScheme.onSurfaceVariant

    if (showDeleteAccountConfirm) {
        AlertDialog(
            onDismissRequest = { if (!isDeletingAccount) showDeleteAccountConfirm = false },
            title = { Text(stringResource(R.string.delete_account_warning_title), color = Color.White) },
            text = {
                Column {
                    Text(stringResource(R.string.delete_account_warning_desc), color = textGrey, fontSize = 13.sp)
                    if (isDeletingAccount) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = redPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Deleting account...", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isDeletingAccount) return@TextButton
                        if (!NetworkUtils.isInternetAvailable(context)) {
                            Toast.makeText(context, context.getString(R.string.cannot_logout_offline), Toast.LENGTH_SHORT).show()
                            showDeleteAccountConfirm = false
                        } else {
                            isDeletingAccount = true
                            authViewModel.deleteAccount { result ->
                                isDeletingAccount = false
                                showDeleteAccountConfirm = false
                                when (result) {
                                    is AccountDeletionResult.Success -> {
                                        Toast.makeText(context, context.getString(R.string.delete_account_success), Toast.LENGTH_LONG).show()
                                        onSignOut()
                                        onNavigateToAuth()
                                    }
                                    is AccountDeletionResult.ReauthRequired -> {
                                        Toast.makeText(context, context.getString(R.string.delete_account_reauth_required), Toast.LENGTH_LONG).show()
                                    }
                                    is AccountDeletionResult.Failed -> {
                                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                    }
                                    is AccountDeletionResult.PartialFailure -> {
                                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                        onSignOut()
                                        onNavigateToAuth()
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isDeletingAccount,
                    modifier = Modifier.testTag("confirm_delete_account_button")
                ) {
                    Text(stringResource(R.string.delete_account_confirm_btn), color = redPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAccountConfirm = false },
                    enabled = !isDeletingAccount,
                    modifier = Modifier.testTag("cancel_delete_account_button")
                ) {
                    Text(stringResource(R.string.cancel), color = Color.White)
                }
            },
            containerColor = cardBg
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(stringResource(R.string.sign_out), color = Color.White) },
            text = { Text(stringResource(R.string.confirm_sign_out), color = textGrey) },
            confirmButton = {
                TextButton(onClick = {
                    if (!NetworkUtils.isInternetAvailable(context)) {
                        Toast.makeText(context, context.getString(R.string.cannot_logout_offline), Toast.LENGTH_SHORT).show()
                        showLogoutConfirm = false
                    } else {
                        showLogoutConfirm = false
                        onSignOut()
                        authViewModel.signOut()
                        onNavigateToAuth()
                    }
                }) {
                    Text(stringResource(R.string.sign_out), color = redPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text(stringResource(R.string.cancel), color = Color.White)
                }
            },
            containerColor = cardBg
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.account_settings),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bgColor)
            )
        },
        containerColor = bgColor
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // User Header Preview Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardBg)
                    .clickable { onNavigateToEditProfile() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .border(2.dp, redPrimary, CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    if (!currentUser?.photoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = currentUser?.photoUrl,
                            contentDescription = stringResource(R.string.profile_picture),
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        val initial = currentUser?.firstName?.firstOrNull()?.toString()
                            ?: currentUser?.username?.firstOrNull()?.toString()?.uppercase()
                            ?: "M"
                        Text(initial, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    val name = if (currentUser != null) {
                        "${currentUser?.firstName} ${currentUser?.lastName}".trim().takeIf { it.isNotBlank() }
                            ?: currentUser?.username ?: "User"
                    } else {
                        stringResource(R.string.guest_user)
                    }
                    Text(name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        currentUser?.email ?: "@${currentUser?.username ?: "guest"}",
                        color = textGrey,
                        fontSize = 12.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (restrictions.isAdFree) redPrimary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (restrictions.isAdFree) redPrimary else MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = when (restrictions.canonicalTier) {
                            com.example.data.model.CanonicalSubscriptionTier.PRO -> "PRO"
                            com.example.data.model.CanonicalSubscriptionTier.PRO_LITE -> "PRO LITE"
                            com.example.data.model.CanonicalSubscriptionTier.FREE -> "Free"
                        },
                        color = if (restrictions.isAdFree) redPrimary else textGrey,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section 1: Account & Security
            Text(
                text = stringResource(R.string.account),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardBg)
            ) {
                AccountOptionRow(
                    icon = Icons.Default.Person,
                    title = stringResource(R.string.account_info),
                    subtitle = "Update personal details, username, email",
                    isLast = false,
                    tintColor = redPrimary,
                    onClick = onNavigateToEditProfile
                )
                AccountOptionRow(
                    icon = Icons.Outlined.Security,
                    title = stringResource(R.string.security),
                    subtitle = "Password, active devices, sessions",
                    isLast = false,
                    tintColor = redPrimary,
                    onClick = onNavigateToSecurity
                )
                AccountOptionRow(
                    icon = Icons.Outlined.CreditCard,
                    title = stringResource(R.string.subscription),
                    subtitle = if (restrictions.isAdFree) "Manage active subscription" else "Upgrade for ad-free experience",
                    isLast = true,
                    tintColor = redPrimary,
                    onClick = onNavigateToSubscription
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section 2: App & Preferences
            Text(
                text = stringResource(R.string.preferences),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardBg)
            ) {
                AccountOptionRow(
                    icon = Icons.Outlined.Settings,
                    title = stringResource(R.string.settings),
                    subtitle = "Playback quality, theme, language",
                    isLast = false,
                    tintColor = redPrimary,
                    onClick = onNavigateToSettings
                )
                AccountOptionRow(
                    icon = Icons.Outlined.Notifications,
                    title = stringResource(R.string.notifications),
                    subtitle = "Alerts for new episodes, recommendations",
                    isLast = false,
                    tintColor = redPrimary,
                    onClick = onNavigateToNotifications
                )
                AccountOptionRow(
                    icon = Icons.Outlined.Download,
                    title = stringResource(R.string.downloads),
                    subtitle = "Storage usage, offline downloads",
                    isLast = false,
                    tintColor = redPrimary,
                    onClick = onNavigateToDownloads
                )
                AccountOptionRow(
                    icon = Icons.Outlined.Share,
                    title = stringResource(R.string.offline_share),
                    subtitle = "Transfer media to nearby devices without internet",
                    isLast = true,
                    tintColor = redPrimary,
                    onClick = onNavigateToShare
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section 3: Support & About
            Text(
                text = stringResource(R.string.general),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardBg)
            ) {
                AccountOptionRow(
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                    title = stringResource(R.string.help_support),
                    subtitle = "Report an issue, contact support team",
                    isLast = false,
                    tintColor = redPrimary,
                    onClick = onNavigateToHelpSupport
                )
                AccountOptionRow(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.about),
                    subtitle = "Version, legal terms, privacy policy",
                    isLast = true,
                    tintColor = redPrimary,
                    onClick = onNavigateToAbout
                )
            }

            if (com.example.BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.developer_diagnostics),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cardBg)
                ) {
                    AccountOptionRow(
                        icon = Icons.Outlined.Security,
                        title = stringResource(R.string.auth_diagnostic_title),
                        subtitle = stringResource(R.string.auth_diagnostic_desc),
                        isLast = true,
                        tintColor = redPrimary,
                        onClick = { showAuthDiagnosticDialog = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Sign Out Option
            if (currentUser != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cardBg)
                        .testTag("sign_out_button")
                        .clickable {
                            if (!NetworkUtils.isInternetAvailable(context)) {
                                Toast.makeText(context, context.getString(R.string.cannot_logout_offline), Toast.LENGTH_SHORT).show()
                            } else {
                                showLogoutConfirm = true
                            }
                        }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Logout,
                        contentDescription = null,
                        tint = redPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.sign_out),
                        color = redPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Delete Account Option (Google Play Policy Compliant In-App Deletion)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cardBg)
                        .testTag("delete_account_button")
                        .clickable {
                            if (!NetworkUtils.isInternetAvailable(context)) {
                                Toast.makeText(context, context.getString(R.string.cannot_logout_offline), Toast.LENGTH_SHORT).show()
                            } else {
                                showDeleteAccountConfirm = true
                            }
                        }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.DeleteForever,
                        contentDescription = stringResource(R.string.delete_account),
                        tint = redPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.delete_account),
                        color = redPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }

    if (com.example.BuildConfig.DEBUG && showAuthDiagnosticDialog) {
        AuthDiagnosticDialog(onDismiss = { showAuthDiagnosticDialog = false })
    }
}

@Composable
private fun AccountOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isLast: Boolean,
    tintColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tintColor, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, color = Color.Gray, fontSize = 11.sp, maxLines = 1)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
    }
    if (!isLast) {
        HorizontalDivider(
            modifier = Modifier.padding(start = 68.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 0.5.dp
        )
    }
}
