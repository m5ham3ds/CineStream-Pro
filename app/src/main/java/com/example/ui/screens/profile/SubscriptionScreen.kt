package com.example.ui.screens.profile

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.model.*
import com.example.data.repository.AuthRepository
import com.example.data.repository.EconomyConfigRepository
import com.example.data.repository.PointsRepository
import com.example.data.repository.UserPreferencesRepository
import com.example.data.repository.UserSecurityManager
import com.example.ui.components.LoginRequiredDialog
import com.example.ui.theme.SuccessGreen
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PHASE 05T: PREMIUM POINTS & SUBSCRIPTION UI WITH HORIZONTAL SWIPE NAVIGATION.
 *
 * 4 Sections:
 * 0. سجل النقاط (Points History / Ledger)
 * 1. المتصدرين (Leaderboard)
 * 2. كسب النقاط (Earn Points)
 * 3. الاشتراكات (Subscriptions)
 *
 * Invariant Rules:
 * - Real data only (no mock values, fake users, or fake balances).
 * - Subscription benefit = REMOVE_ADS ONLY (qualities remain decoupled).
 * - Theme-adaptive using MaterialTheme.colorScheme.
 * - Smooth horizontal swipe synchronization with top tabs.
 * - Guest mode protection and login required dialog.
 */
enum class PointsTabSection(
    val titleRes: Int,
    val icon: ImageVector,
    val testTag: String
) {
    POINTS_HISTORY(R.string.tab_points_ledger, Icons.Default.ReceiptLong, "tab_points_ledger"),
    LEADERBOARD(R.string.tab_leaderboard, Icons.Default.Groups, "tab_leaderboard"),
    EARN_POINTS(R.string.tab_earn_points, Icons.Default.CardGiftcard, "tab_earn_points"),
    SUBSCRIPTIONS(R.string.tab_subscriptions, Icons.Default.Stars, "tab_subscriptions")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    onBack: () -> Unit,
    initialTab: Int = 3, // Defaults to Subscriptions (tab 3)
    pointsViewModel: PointsEarningViewModel = viewModel(),
    onNavigateToAuth: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val userPrefs = remember { UserPreferencesRepository(context) }
    val isGuest by userPrefs.isGuest.collectAsState(initial = false)

    var showLoginRequiredDialog by remember { mutableStateOf(false) }

    val restrictions by UserSecurityManager.restrictionsFlow.collectAsState()
    val featuresConfig by EconomyConfigRepository.featuresConfig.collectAsState()
    val economyConfig by EconomyConfigRepository.economyConfig.collectAsState()

    val operationMessage by pointsViewModel.operationMessage.collectAsState()
    LaunchedEffect(operationMessage) {
        operationMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            pointsViewModel.clearMessage()
        }
    }

    val currentTier = restrictions.canonicalTier
    val isAdFree = restrictions.isAdFree
    val isExpired = restrictions.isSubscriptionExpired && currentTier != CanonicalSubscriptionTier.FREE

    val expiryFormatted = remember(restrictions.subscriptionExpiresAt) {
        restrictions.subscriptionExpiresAt?.let {
            SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(it))
        }
    }

    val daysRemaining = remember(restrictions.subscriptionExpiresAt) {
        restrictions.subscriptionExpiresAt?.let { exp ->
            val diff = exp - System.currentTimeMillis()
            if (diff > 0) (diff / (1000 * 60 * 60 * 24)).toInt() else 0
        } ?: 0
    }

    val sourceLabel = remember(restrictions.subscriptionSource) {
        when (restrictions.subscriptionSource.uppercase()) {
            "MONEY" -> context.getString(R.string.source_money)
            "POINTS" -> context.getString(R.string.source_points)
            "ADMIN_GRANT" -> context.getString(R.string.source_admin_grant)
            "LEGACY" -> context.getString(R.string.source_legacy)
            else -> restrictions.subscriptionSource
        }
    }

    // Pro Request submission dialog state
    var selectedPlanForRequest by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var paymentRefText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf("") }
    var isSubmittingRequest by remember { mutableStateOf(false) }

    // Points Subscription Redemption Confirmation Dialog state
    var selectedPlanForRedemption by remember { mutableStateOf<Triple<String, String, Long>?>(null) }
    val isRedeemingSubscription by pointsViewModel.isRedeemingSubscription.collectAsState()
    val walletState by pointsViewModel.wallet.collectAsState()

    // Manage Subscription Info Dialog state
    var showManageSubscriptionDialog by remember { mutableStateOf(false) }

    // Pager state for the 4 sections
    val tabSections = remember { PointsTabSection.values() }
    val safeInitialPage = initialTab.coerceIn(0, tabSections.size - 1)
    val pagerState = rememberPagerState(initialPage = safeInitialPage, pageCount = { tabSections.size })

    // Guest Auth Guard Dialog
    if (showLoginRequiredDialog) {
        LoginRequiredDialog(
            onDismissRequest = { showLoginRequiredDialog = false },
            onNavigateToAuth = { isSignUp ->
                showLoginRequiredDialog = false
                if (onNavigateToAuth != null) {
                    onNavigateToAuth(isSignUp)
                } else {
                    Toast.makeText(context, context.getString(R.string.login_required_desc), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Points Subscription Redemption Confirmation Dialog
    if (selectedPlanForRedemption != null) {
        val (sku, planTitle, cost) = selectedPlanForRedemption!!
        val currentBalance = walletState.pointsBalance
        val balanceAfter = currentBalance - cost
        val hasSufficientBalance = currentBalance >= cost

        AlertDialog(
            onDismissRequest = {
                if (!isRedeemingSubscription) selectedPlanForRedemption = null
            },
            title = {
                Text(
                    text = stringResource(R.string.redeem_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.redeem_dialog_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.redeem_selected_plan), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(planTitle, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.redeem_cost), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.points_cost, cost), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.redeem_current_balance), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.points_cost, currentBalance), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.redeem_balance_after), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = if (hasSufficientBalance) stringResource(R.string.points_cost, balanceAfter) else stringResource(R.string.redeem_insufficient_warning, currentBalance, cost),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hasSufficientBalance) SuccessGreen else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pointsViewModel.redeemSubscription(sku) { success, _ ->
                            if (success) {
                                selectedPlanForRedemption = null
                            }
                        }
                    },
                    enabled = hasSufficientBalance && !isRedeemingSubscription,
                    modifier = Modifier.testTag("confirm_redeem_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (isRedeemingSubscription) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text(stringResource(R.string.redeem_confirm_action), fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { selectedPlanForRedemption = null },
                    enabled = !isRedeemingSubscription
                ) {
                    Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // Pro Request Dialog
    if (selectedPlanForRequest != null) {
        val (planId, durationDays) = selectedPlanForRequest!!
        AlertDialog(
            onDismissRequest = {
                if (!isSubmittingRequest) selectedPlanForRequest = null
            },
            title = {
                Text(
                    text = stringResource(R.string.pro_request_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.pro_request_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = paymentRefText,
                        onValueChange = { paymentRefText = it },
                        label = { Text(stringResource(R.string.payment_ref_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = notesText,
                        onValueChange = { notesText = it },
                        label = { Text(stringResource(R.string.notes_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!com.example.data.repository.UserSecurityManager.canRequest()) {
                            Toast.makeText(context, com.example.data.repository.UserSecurityManager.getRequestRestrictionMessage(), Toast.LENGTH_LONG).show()
                            selectedPlanForRequest = null
                            return@Button
                        }
                        isSubmittingRequest = true
                        coroutineScope.launch {
                            val res = PointsRepository.submitProRequest(
                                planId = planId,
                                durationDays = durationDays,
                                paymentReference = paymentRefText.trim(),
                                notes = notesText.trim()
                            )
                            isSubmittingRequest = false
                            selectedPlanForRequest = null
                            if (res.isSuccess) {
                                Toast.makeText(context, context.getString(R.string.request_submitted_success), Toast.LENGTH_LONG).show()
                            } else {
                                val errMsg = res.exceptionOrNull()?.message ?: "Failed to submit request"
                                Toast.makeText(context, errMsg, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isSubmittingRequest,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (isSubmittingRequest) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text(stringResource(R.string.submit_request), fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { selectedPlanForRequest = null },
                    enabled = !isSubmittingRequest
                ) {
                    Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // Manage Subscription Info Dialog
    if (showManageSubscriptionDialog) {
        AlertDialog(
            onDismissRequest = { showManageSubscriptionDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Stars, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.manage_subscription), fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${stringResource(R.string.current_plan_label)}: ${currentTier.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (expiryFormatted != null && currentTier != CanonicalSubscriptionTier.FREE) {
                        Text(
                            text = stringResource(R.string.expires_in, expiryFormatted, stringResource(R.string.days_remaining, daysRemaining)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isAdFree) SuccessGreen else MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        text = stringResource(R.string.activated_by, sourceLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.quality_all_tiers_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showManageSubscriptionDialog = false }) {
                    Text(stringResource(R.string.ok_button), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                TopAppBar(
                    title = {
                        val currentTabTitle = stringResource(tabSections[pagerState.currentPage].titleRes)
                        Text(
                            text = currentTabTitle,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { pointsViewModel.refreshAll() }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.refresh),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )

                // Top Segmented Navigation Tabs matching reference screenshots
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        tabSections.forEachIndexed { index, section ->
                            val isSelected = pagerState.currentPage == index
                            val animBorderColor by animateColorAsState(
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else Color.Transparent,
                                label = "tab_border"
                            )
                            val animBgColor by animateColorAsState(
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                                label = "tab_bg"
                            )

                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .testTag(section.testTag)
                                    .clickable {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(index)
                                        }
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = animBgColor,
                                border = BorderStroke(1.5.dp, animBorderColor)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = section.icon,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = stringResource(section.titleRes),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> PointsHistoryPage(
                        viewModel = pointsViewModel,
                        isGuest = isGuest,
                        onRequireAuth = { showLoginRequiredDialog = true }
                    )
                    1 -> LeaderboardPage(
                        viewModel = pointsViewModel,
                        featuresConfig = featuresConfig,
                        isGuest = isGuest,
                        onRequireAuth = { showLoginRequiredDialog = true }
                    )
                    2 -> EarnPointsPage(
                        viewModel = pointsViewModel,
                        featuresConfig = featuresConfig,
                        isGuest = isGuest,
                        onRequireAuth = { showLoginRequiredDialog = true }
                    )
                    3 -> SubscriptionsPage(
                        currentTier = currentTier,
                        isAdFree = isAdFree,
                        isExpired = isExpired,
                        expiryFormatted = expiryFormatted,
                        daysRemaining = daysRemaining,
                        sourceLabel = sourceLabel,
                        economyConfig = economyConfig,
                        featuresConfig = featuresConfig,
                        restrictions = restrictions,
                        wallet = walletState,
                        isGuest = isGuest,
                        onRequireAuth = { showLoginRequiredDialog = true },
                        onManageClick = { showManageSubscriptionDialog = true },
                        onPlanRequest = { planId, days ->
                            if (isGuest) {
                                showLoginRequiredDialog = true
                            } else if (!com.example.data.repository.UserSecurityManager.canRequest()) {
                                Toast.makeText(context, com.example.data.repository.UserSecurityManager.getRequestRestrictionMessage(), Toast.LENGTH_LONG).show()
                            } else {
                                selectedPlanForRequest = Pair(planId, days)
                            }
                        },
                        onPlanRedeem = { sku, title, cost ->
                            if (isGuest) {
                                showLoginRequiredDialog = true
                            } else {
                                handlePointsRedeemClicked(context, featuresConfig) {
                                    selectedPlanForRedemption = Triple(sku, title, cost)
                                }
                            }
                        },
                        onNavigateToEarn = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(2) // Jump to Earn Points
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * ------------------------------------------------------------------------
 * PREMIUM POINTS BALANCE HEADER CARD (Used across Points & Subscriptions)
 * ------------------------------------------------------------------------
 */
@Composable
fun PointsBalanceHeaderCard(
    wallet: PointWallet,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        tonalElevation = 4.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.current_points_balance),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "⭐",
                                fontSize = 24.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${wallet.pointsBalance}",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Verified badge or subtle crown graphic
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = SuccessGreen.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = stringResource(R.string.server_verified_account),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = SuccessGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                Spacer(modifier = Modifier.height(14.dp))

                // Metric Pills (Total Earned / Total Spent)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Earned Pill
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.25f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .background(SuccessGreen.copy(alpha = 0.18f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.ArrowUpward,
                                    contentDescription = null,
                                    tint = SuccessGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.total_earned_title),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "+${wallet.totalPointsEarned}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = SuccessGreen
                                )
                            }
                        }
                    }

                    // Spent Pill
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.18f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.ArrowDownward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.total_spent_title),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "-${wallet.totalPointsSpent}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * ------------------------------------------------------------------------
 * 1. SUBSCRIPTIONS PAGE (Tab 3)
 * ------------------------------------------------------------------------
 */
@Composable
private fun SubscriptionsPage(
    currentTier: CanonicalSubscriptionTier,
    isAdFree: Boolean,
    isExpired: Boolean,
    expiryFormatted: String?,
    daysRemaining: Int,
    sourceLabel: String,
    economyConfig: EconomyConfig,
    featuresConfig: FeaturesConfig,
    restrictions: UserRestrictions,
    wallet: PointWallet,
    isGuest: Boolean,
    onRequireAuth: () -> Unit,
    onManageClick: () -> Unit,
    onPlanRequest: (String, Int) -> Unit,
    onPlanRedeem: (String, String, Long) -> Unit,
    onNavigateToEarn: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Balance Header Card
        PointsBalanceHeaderCard(wallet = wallet)

        Spacer(modifier = Modifier.height(16.dp))

        // Mandatory Core Entitlement Rule Banner
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.subscription_benefit_ad_free_only),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.quality_all_tiers_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Current Active Plan Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.current_plan_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when (currentTier) {
                                    CanonicalSubscriptionTier.FREE -> stringResource(R.string.free_title)
                                    CanonicalSubscriptionTier.PRO_LITE -> stringResource(R.string.pro_lite_title)
                                    CanonicalSubscriptionTier.PRO -> stringResource(R.string.pro_title)
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isAdFree) SuccessGreen.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = if (isAdFree) "● ${stringResource(R.string.active_status)}" else stringResource(R.string.free_account),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isAdFree) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onManageClick,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.Stars, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.manage_subscription), style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (expiryFormatted != null && currentTier != CanonicalSubscriptionTier.FREE) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.expires_in, expiryFormatted, stringResource(R.string.days_remaining, daysRemaining)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isAdFree) SuccessGreen else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (currentTier != CanonicalSubscriptionTier.FREE) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.activated_by, sourceLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Choose Your Subscription Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.choose_your_subscription),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Subscription Plans Display: FREE, PRO LITE, PRO
        // Plan 1: FREE
        PlanCard(
            tierTitle = stringResource(R.string.free_title),
            subtitle = stringResource(R.string.always_free),
            isCurrent = currentTier == CanonicalSubscriptionTier.FREE && !isExpired,
            isPopular = false,
            pointsCost = null,
            durationLabel = stringResource(R.string.always_free),
            features = listOf(
                Pair(false, "مشاهدة مع إعلانات"),
                Pair(true, stringResource(R.string.all_qualities_available)),
                Pair(true, stringResource(R.string.limited_download))
            ),
            buttonLabel = stringResource(R.string.current_plan_label),
            onActionClick = null
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Plan 2: PRO LITE (Canonical SKUs: pro_lite_1d, pro_lite_7d, pro_lite_10d)
        val costLite1d = economyConfig.redemptionCosts["pro_lite_1d"] ?: 50L
        val costLite7d = economyConfig.redemptionCosts["pro_lite_7d"] ?: 250L
        val costLite10d = economyConfig.redemptionCosts["pro_lite_10d"] ?: 350L

        var selectedLiteSku by remember { mutableStateOf("pro_lite_1d") }
        val currentLiteCost = when (selectedLiteSku) {
            "pro_lite_7d" -> costLite7d
            "pro_lite_10d" -> costLite10d
            else -> costLite1d
        }
        val currentLiteDurationDays = when (selectedLiteSku) {
            "pro_lite_7d" -> 7
            "pro_lite_10d" -> 10
            else -> 1
        }
        val currentLiteLabel = when (selectedLiteSku) {
            "pro_lite_7d" -> stringResource(R.string.duration_7_days)
            "pro_lite_10d" -> stringResource(R.string.duration_10_days)
            else -> stringResource(R.string.duration_1_day)
        }

        PlanCard(
            tierTitle = stringResource(R.string.pro_lite_title),
            subtitle = stringResource(R.string.suitable_short_term),
            isCurrent = currentTier == CanonicalSubscriptionTier.PRO_LITE && isAdFree,
            isPopular = false,
            pointsCost = currentLiteCost,
            durationLabel = currentLiteLabel,
            features = listOf(
                Pair(true, stringResource(R.string.ad_free_experience_benefit)),
                Pair(true, stringResource(R.string.all_qualities_available)),
                Pair(true, stringResource(R.string.unlimited_download))
            ),
            durationSelector = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        Triple("pro_lite_1d", stringResource(R.string.duration_1_day), costLite1d),
                        Triple("pro_lite_7d", stringResource(R.string.duration_7_days), costLite7d),
                        Triple("pro_lite_10d", stringResource(R.string.duration_10_days), costLite10d)
                    ).forEach { (sku, label, cost) ->
                        val isSelected = selectedLiteSku == sku
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedLiteSku = sku },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Column(
                                modifier = Modifier.padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                                Text("$cost pts", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            buttonLabel = stringResource(R.string.request_subscription),
            onActionClick = {
                onPlanRedeem(selectedLiteSku, "PRO LITE ($currentLiteLabel)", currentLiteCost)
            },
            secondaryButtonLabel = stringResource(R.string.submit_request),
            onSecondaryClick = {
                onPlanRequest(selectedLiteSku, currentLiteDurationDays)
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Plan 3: PRO (Canonical SKU: pro_30d)
        val costPro30d = economyConfig.redemptionCosts["pro_30d"] ?: 1000L
        PlanCard(
            tierTitle = stringResource(R.string.pro_title),
            subtitle = stringResource(R.string.best_value),
            isCurrent = currentTier == CanonicalSubscriptionTier.PRO && isAdFree,
            isPopular = true,
            pointsCost = costPro30d,
            durationLabel = stringResource(R.string.duration_30_days),
            features = listOf(
                Pair(true, stringResource(R.string.ad_free_experience_benefit)),
                Pair(true, stringResource(R.string.all_qualities_available)),
                Pair(true, stringResource(R.string.unlimited_download)),
                Pair(true, stringResource(R.string.priority_support))
            ),
            buttonLabel = stringResource(R.string.request_subscription),
            onActionClick = {
                onPlanRedeem("pro_30d", "PRO (30 Days)", costPro30d)
            },
            secondaryButtonLabel = stringResource(R.string.submit_request),
            onSecondaryClick = {
                onPlanRequest("pro_30d", 30)
            }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Quick Links to Earn Points
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onNavigateToEarn() },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PlayCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(stringResource(R.string.rewarded_ads_title), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("+1 pt per ad", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onNavigateToEarn() },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.TaskAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(stringResource(R.string.available_tasks), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("Earn extra points", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun PlanCard(
    tierTitle: String,
    subtitle: String,
    isCurrent: Boolean,
    isPopular: Boolean,
    pointsCost: Long?,
    durationLabel: String,
    features: List<Pair<Boolean, String>>,
    buttonLabel: String,
    onActionClick: (() -> Unit)?,
    secondaryButtonLabel: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
    durationSelector: (@Composable () -> Unit)? = null
) {
    val borderColor = if (isCurrent) SuccessGreen else if (isPopular) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (isCurrent || isPopular) 1.5.dp else 1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = tierTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (isPopular) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = stringResource(R.string.most_popular),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isCurrent) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = SuccessGreen.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "✓ ${stringResource(R.string.current_plan_label)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = SuccessGreen,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                } else if (pointsCost != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(R.string.points_cost, pointsCost),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = durationLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (durationSelector != null) {
                durationSelector()
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
            Spacer(modifier = Modifier.height(12.dp))

            // Features list
            features.forEach { (included, feat) ->
                Row(
                    modifier = Modifier.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (included) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = if (included) (if (isCurrent) SuccessGreen else MaterialTheme.colorScheme.primary) else MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = feat,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!isCurrent && onActionClick != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onActionClick,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPopular) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isPopular) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text(buttonLabel, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }

                    if (onSecondaryClick != null && secondaryButtonLabel != null) {
                        OutlinedButton(
                            onClick = onSecondaryClick,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Text(secondaryButtonLabel, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

/**
 * ------------------------------------------------------------------------
 * 2. EARN POINTS PAGE (Tab 2)
 * ------------------------------------------------------------------------
 */
@Composable
private fun EarnPointsPage(
    viewModel: PointsEarningViewModel,
    featuresConfig: FeaturesConfig,
    isGuest: Boolean,
    onRequireAuth: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val wallet by viewModel.wallet.collectAsState()
    val dailyLogin by viewModel.dailyLoginState.collectAsState()
    val rewardedAdState by viewModel.rewardedAdState.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val taskClaims by viewModel.taskClaims.collectAsState()

    val isClaimingDaily by viewModel.isClaimingDailyLogin.collectAsState()
    val isWatchingAd by viewModel.isWatchingRewardedAd.collectAsState()
    val claimingTaskId by viewModel.claimingTaskId.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Balance Header Card
        PointsBalanceHeaderCard(wallet = wallet)

        Spacer(modifier = Modifier.height(16.dp))

        // Daily Reward Card matching reference screenshot
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.daily_login_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.daily_reward_task_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Current streak flame badge
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🔥", fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.day_streak, dailyLogin.currentStreak),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 7 Days Ladder
                val ladder = if (dailyLogin.rewardLadder.isNotEmpty()) dailyLogin.rewardLadder else listOf(10L, 15L, 20L, 25L, 30L, 40L, 50L)
                val currentDayIndex = (dailyLogin.currentStreak % 7).toInt()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ladder.take(7).forEachIndexed { index, reward ->
                        val isClaimedPast = index < currentDayIndex || (index == currentDayIndex && dailyLogin.todayClaimed)
                        val isTodayActive = index == currentDayIndex && !dailyLogin.todayClaimed

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                        ) {
                            Text(
                                text = "يوم ${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = when {
                                    isClaimedPast -> SuccessGreen.copy(alpha = 0.18f)
                                    isTodayActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                },
                                border = BorderStroke(
                                    1.5.dp,
                                    when {
                                        isClaimedPast -> SuccessGreen
                                        isTodayActive -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                    }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.85f)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(4.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (isClaimedPast) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                                    } else {
                                        Icon(Icons.Default.CardGiftcard, contentDescription = null, tint = if (isTodayActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "+$reward",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isClaimedPast) SuccessGreen else if (isTodayActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Big Claim Button
                Button(
                    onClick = {
                        if (isGuest) {
                            onRequireAuth()
                        } else {
                            viewModel.claimDailyLogin()
                        }
                    },
                    enabled = !dailyLogin.todayClaimed && !isClaimingDaily && featuresConfig.dailyLogin != FeatureState.DISABLED,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("claim_daily_login_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (dailyLogin.todayClaimed) SuccessGreen else MaterialTheme.colorScheme.primary,
                        disabledContainerColor = if (dailyLogin.todayClaimed) SuccessGreen.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    if (isClaimingDaily) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else if (dailyLogin.todayClaimed) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.daily_login_claimed), fontWeight = FontWeight.Bold, color = Color.White)
                    } else {
                        Icon(Icons.Default.CardGiftcard, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("${stringResource(R.string.claim_today_points)} (+${dailyLogin.nextReward})", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Available Tasks Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = stringResource(R.string.available_tasks),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.available_tasks_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.FilterList, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.filter_all), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Task 1: Rewarded Ads Task
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.rewarded_ads_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.ad_points_subtitle, rewardedAdState.rewardPerAd),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.padding(horizontal = 6.dp)
                ) {
                    Text(
                        text = "+${rewardedAdState.rewardPerAd}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }

                Button(
                    onClick = {
                        if (isGuest) {
                            onRequireAuth()
                        } else {
                            viewModel.watchRewardedAd(context)
                        }
                    },
                    enabled = rewardedAdState.isEligible && !isWatchingAd && featuresConfig.rewardedAds != FeatureState.DISABLED,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("watch_rewarded_ad_button").height(38.dp)
                ) {
                    if (isWatchingAd) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else if (rewardedAdState.cooldownSecondsRemaining > 0) {
                        Text("${rewardedAdState.cooldownSecondsRemaining}s", style = MaterialTheme.typography.labelSmall)
                    } else {
                        Text(stringResource(R.string.watch_action), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Task 2: Daily Login Task Row
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(SuccessGreen.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.CalendarToday, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.daily_reward_task_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.daily_reward_task_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (dailyLogin.todayClaimed) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = SuccessGreen.copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.completed_status), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SuccessGreen)
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            if (isGuest) onRequireAuth() else viewModel.claimDailyLogin()
                        },
                        enabled = !isClaimingDaily,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Text(stringResource(R.string.claim_task_action), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Dynamic Tasks from Firestore /reward_tasks
        if (tasks.isNotEmpty()) {
            tasks.forEach { task ->
                val isClaimed = taskClaims[task.taskId] == true
                val isClaimingThis = claimingTaskId == task.taskId

                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = task.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (task.description.isNotBlank()) {
                                    Text(
                                        text = task.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Text(
                                text = "+${task.rewardPoints}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }

                        if (isClaimed) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = SuccessGreen.copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    stringResource(R.string.completed_status),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = SuccessGreen,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (isGuest) onRequireAuth() else viewModel.claimTaskReward(task)
                                },
                                enabled = !isClaimingThis && featuresConfig.tasks != FeatureState.DISABLED,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.height(38.dp)
                            ) {
                                if (isClaimingThis) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                } else {
                                    Text(stringResource(R.string.claim_task_action), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Important Notes Footer matching reference screenshot
        ImportantNotesFooter()

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * ------------------------------------------------------------------------
 * 3. LEADERBOARD PAGE (Tab 1)
 * ------------------------------------------------------------------------
 */
@Composable
private fun LeaderboardPage(
    viewModel: PointsEarningViewModel,
    featuresConfig: FeaturesConfig,
    isGuest: Boolean,
    onRequireAuth: () -> Unit
) {
    val leaderboard by viewModel.leaderboard.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") } // ALL, ACTIVE, INACTIVE, PRO

    val filteredList = remember(leaderboard, searchQuery, selectedFilter) {
        leaderboard.filter { entry ->
            val matchesSearch = if (searchQuery.isBlank()) true else {
                val q = searchQuery.trim().lowercase()
                entry.displayName.lowercase().contains(q) ||
                entry.username.lowercase().contains(q) ||
                entry.email.lowercase().contains(q) ||
                entry.userId.lowercase().contains(q)
            }
            val matchesFilter = when (selectedFilter) {
                "ACTIVE" -> entry.isActive
                "INACTIVE" -> !entry.isActive
                "PRO" -> entry.tier.equals("PRO", ignoreCase = true) || entry.isPremium
                else -> true
            }
            matchesSearch && matchesFilter
        }
    }

    val totalLeadersCount = leaderboard.size
    val activeNowCount = leaderboard.count { it.isActive }
    val proLeadersCount = leaderboard.count { it.tier.equals("PRO", ignoreCase = true) || it.isPremium }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Banner Card with 3 metric pills
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color(0xFFFFD700).copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = Color(0xFFFFD700), modifier = Modifier.size(24.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.leaderboard_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.leaderboard_subtitle),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 3 Metric Pills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricPill(
                            icon = Icons.Default.Groups,
                            iconTint = MaterialTheme.colorScheme.primary,
                            count = totalLeadersCount,
                            label = stringResource(R.string.total_leaders),
                            modifier = Modifier.weight(1f)
                        )
                        MetricPill(
                            icon = Icons.Default.Circle,
                            iconTint = SuccessGreen,
                            count = activeNowCount,
                            label = stringResource(R.string.active_now),
                            modifier = Modifier.weight(1f)
                        )
                        MetricPill(
                            icon = Icons.Default.Stars,
                            iconTint = Color(0xFFFFD700),
                            count = proLeadersCount,
                            label = stringResource(R.string.pro_leaders),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Search Bar
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.search_leaderboard_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }

        // Filter Pills Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    Pair("ALL", "${stringResource(R.string.filter_all)} ($totalLeadersCount)"),
                    Pair("ACTIVE", "${stringResource(R.string.filter_active)} ($activeNowCount)"),
                    Pair("INACTIVE", "${stringResource(R.string.filter_inactive)} (${(totalLeadersCount - activeNowCount).coerceAtLeast(0)})"),
                    Pair("PRO", "${stringResource(R.string.filter_pro)} ($proLeadersCount)")
                ).forEach { (code, label) ->
                    val isSelected = selectedFilter == code
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedFilter = code },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Empty state
        if (filteredList.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.EmojiEvents,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.no_leaderboard),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(filteredList, key = { it.userId }) { entry ->
                LeaderboardCard(entry = entry)
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun MetricPill(
    icon: ImageVector,
    iconTint: Color,
    count: Int,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LeaderboardCard(entry: LeaderboardEntry) {
    val rankColor = when (entry.rank) {
        1 -> Color(0xFFFFD700)
        2 -> Color(0xFFC0C0C0)
        3 -> Color(0xFFCD7F32)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Rank Ribbon Box
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = rankColor.copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, rankColor.copy(alpha = 0.5f)),
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "${entry.rank}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = rankColor
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Avatar with online status dot
                Box(modifier = Modifier.size(40.dp)) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = (entry.displayName.ifBlank { entry.username }).take(1).uppercase(),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    if (entry.isActive) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .align(Alignment.BottomEnd)
                                .background(SuccessGreen, CircleShape)
                                .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Name and details
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayName.ifBlank { entry.username.ifBlank { "User" } },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (entry.email.isNotBlank()) {
                        Text(
                            text = entry.email,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (entry.userId.isNotBlank()) {
                        Text(
                            text = "UID: ${entry.userId.take(8)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 10.sp
                        )
                    }
                }

                // Points & Active Status badge
                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (entry.isActive) SuccessGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = if (entry.isActive) stringResource(R.string.active_now) else stringResource(R.string.filter_inactive),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (entry.isActive) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "⭐ ${entry.weeklyEarnedPoints}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFD700)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(8.dp))

            // Footer row: Tier badge, join date
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (entry.tier.equals("PRO", ignoreCase = true) || entry.isPremium) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = if (entry.tier.isNotBlank()) entry.tier.uppercase() else "FREE",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (entry.tier.equals("PRO", ignoreCase = true) || entry.isPremium) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }

                Text(
                    text = stringResource(R.string.points_earned),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * ------------------------------------------------------------------------
 * 4. POINTS HISTORY PAGE (Tab 0)
 * ------------------------------------------------------------------------
 */
@Composable
private fun PointsHistoryPage(
    viewModel: PointsEarningViewModel,
    isGuest: Boolean,
    onRequireAuth: () -> Unit
) {
    val wallet by viewModel.wallet.collectAsState()
    val transactions by viewModel.transactions.collectAsState()

    var selectedFilter by remember { mutableStateOf("ALL") } // ALL, EARNED, SPENT, REWARDS, SUBSCRIPTIONS
    var sortDescending by remember { mutableStateOf(true) }
    var currentPage by remember { mutableIntStateOf(1) }
    val itemsPerPage = 8

    val filteredTransactions = remember(transactions, selectedFilter, sortDescending) {
        val filtered = transactions.filter { tx ->
            when (selectedFilter) {
                "EARNED" -> tx.amount > 0
                "SPENT" -> tx.amount < 0
                "REWARDS" -> tx.type in listOf("DAILY_LOGIN", "REWARDED_AD", "TASK_REWARD", "GAME_REWARD", "LEADERBOARD_REWARD")
                "SUBSCRIPTIONS" -> tx.type in listOf("SUBSCRIPTION_REDEMPTION", "SUBSCRIPTION") || tx.amount < 0
                else -> true
            }
        }
        if (sortDescending) {
            filtered.sortedByDescending { it.createdAt }
        } else {
            filtered.sortedBy { it.createdAt }
        }
    }

    val totalPages = (filteredTransactions.size + itemsPerPage - 1).coerceAtLeast(1) / itemsPerPage
    val safePage = currentPage.coerceIn(1, totalPages.coerceAtLeast(1))
    val pagedItems = remember(filteredTransactions, safePage) {
        val start = (safePage - 1) * itemsPerPage
        filteredTransactions.drop(start).take(itemsPerPage)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Balance Header Card
        item {
            PointsBalanceHeaderCard(wallet = wallet)
        }

        // Filter Chips Row matching reference screenshot
        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    HistoryFilterChip(
                        label = stringResource(R.string.filter_all),
                        icon = Icons.Default.List,
                        isSelected = selectedFilter == "ALL",
                        onClick = { selectedFilter = "ALL"; currentPage = 1 }
                    )
                }
                item {
                    HistoryFilterChip(
                        label = stringResource(R.string.filter_earned),
                        icon = Icons.Default.ArrowUpward,
                        iconTint = SuccessGreen,
                        isSelected = selectedFilter == "EARNED",
                        onClick = { selectedFilter = "EARNED"; currentPage = 1 }
                    )
                }
                item {
                    HistoryFilterChip(
                        label = stringResource(R.string.filter_spent),
                        icon = Icons.Default.ArrowDownward,
                        iconTint = MaterialTheme.colorScheme.error,
                        isSelected = selectedFilter == "SPENT",
                        onClick = { selectedFilter = "SPENT"; currentPage = 1 }
                    )
                }
                item {
                    HistoryFilterChip(
                        label = stringResource(R.string.filter_rewards),
                        icon = Icons.Default.CardGiftcard,
                        iconTint = MaterialTheme.colorScheme.primary,
                        isSelected = selectedFilter == "REWARDS",
                        onClick = { selectedFilter = "REWARDS"; currentPage = 1 }
                    )
                }
                item {
                    HistoryFilterChip(
                        label = stringResource(R.string.filter_subscriptions),
                        icon = Icons.Default.Stars,
                        iconTint = Color(0xFFFFD700),
                        isSelected = selectedFilter == "SUBSCRIPTIONS",
                        onClick = { selectedFilter = "SUBSCRIPTIONS"; currentPage = 1 }
                    )
                }
            }
        }

        // Section Title & Sort Button
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.transaction_history_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { sortDescending = !sortDescending }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Sort, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (sortDescending) stringResource(R.string.sort_newest) else stringResource(R.string.sort_oldest),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Empty state
        if (pagedItems.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.ReceiptLong,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.no_transactions),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(pagedItems, key = { it.id }) { tx ->
                TransactionCard(tx = tx)
            }

            // Pagination Controls matching reference screenshot
            if (totalPages > 1) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { if (safePage > 1) currentPage = safePage - 1 },
                            enabled = safePage > 1
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.previous), tint = if (safePage > 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                        }

                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.page_indicator, safePage, totalPages),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(12.dp))

                        IconButton(
                            onClick = { if (safePage < totalPages) currentPage = safePage + 1 },
                            enabled = safePage < totalPages
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.next), tint = if (safePage < totalPages) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                        }
                    }
                }
            }
        }

        // Important Notes Footer matching reference screenshot
        item {
            Spacer(modifier = Modifier.height(8.dp))
            ImportantNotesFooter()
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HistoryFilterChip(
    label: String,
    icon: ImageVector,
    iconTint: Color? = null,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else (iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun TransactionCard(tx: PointTransaction) {
    val isPositive = tx.amount >= 0
    val dateFormatted = remember(tx.createdAt) {
        if (tx.createdAt > 0) {
            SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(tx.createdAt))
        } else ""
    }

    val (icon, iconBg) = when (tx.type) {
        "REWARDED_AD" -> Pair(Icons.Default.PlayArrow, MaterialTheme.colorScheme.primary)
        "DAILY_LOGIN" -> Pair(Icons.Default.CalendarMonth, SuccessGreen)
        "TASK_REWARD" -> Pair(Icons.Default.TaskAlt, MaterialTheme.colorScheme.primary)
        "SUBSCRIPTION_REDEMPTION" -> Pair(Icons.Default.Stars, Color(0xFFFFD700))
        "ADMIN_GRANT", "ADMIN_ADJUSTMENT" -> Pair(Icons.Default.Settings, MaterialTheme.colorScheme.secondary)
        else -> Pair(Icons.Default.ReceiptLong, MaterialTheme.colorScheme.onSurfaceVariant)
    }

    val title = when (tx.type) {
        "DAILY_LOGIN" -> stringResource(R.string.daily_reward_task_title)
        "REWARDED_AD" -> stringResource(R.string.rewarded_ads_title)
        "TASK_REWARD" -> stringResource(R.string.tasks_title)
        "LEADERBOARD_REWARD" -> stringResource(R.string.leaderboard_title)
        "SUBSCRIPTION_REDEMPTION" -> stringResource(R.string.tab_subscriptions)
        "ADMIN_GRANT" -> "منحة إدارية"
        "ADMIN_ADJUSTMENT" -> "تعديل إداري"
        else -> tx.type
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(iconBg.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = iconBg, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (tx.description.isNotBlank()) {
                        Text(
                            text = tx.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (dateFormatted.isNotBlank()) {
                        Text(
                            text = dateFormatted,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (isPositive) "+${tx.amount}" else "${tx.amount}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isPositive) SuccessGreen else MaterialTheme.colorScheme.error
                )
                if (tx.balanceAfter > 0) {
                    Text(
                        text = stringResource(R.string.balance_after_label, tx.balanceAfter),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

/**
 * ------------------------------------------------------------------------
 * IMPORTANT NOTES FOOTER (matching reference screenshots)
 * ------------------------------------------------------------------------
 */
@Composable
fun ImportantNotesFooter() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.notes_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            listOf(
                stringResource(R.string.points_note_1),
                stringResource(R.string.points_note_2),
                stringResource(R.string.points_note_3),
                stringResource(R.string.points_note_4)
            ).forEach { note ->
                Row(
                    modifier = Modifier.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 6.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

private fun handlePointsRedeemClicked(
    context: Context,
    featuresConfig: FeaturesConfig,
    onProceed: () -> Unit
) {
    if (featuresConfig.points == FeatureState.COMING_SOON || featuresConfig.subscriptions == FeatureState.COMING_SOON) {
        val msg = if (featuresConfig.points == FeatureState.COMING_SOON) {
            featuresConfig.getComingSoonMessage("points")
        } else {
            featuresConfig.getComingSoonMessage("subscriptions")
        }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    } else if (featuresConfig.points == FeatureState.DISABLED || featuresConfig.subscriptions == FeatureState.DISABLED) {
        val msg = if (featuresConfig.points == FeatureState.DISABLED) {
            featuresConfig.getDisabledMessage("points")
        } else {
            featuresConfig.getDisabledMessage("subscriptions")
        }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    } else {
        onProceed()
    }
}
