package com.example.ui.screens.profile

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.R
import com.example.data.repository.ReportRepository
import com.example.data.repository.SocialRepository
import com.example.data.repository.UserProfile
import com.example.data.repository.UserPreferencesRepository
import com.example.di.AppContainer
import kotlinx.coroutines.launch

private enum class FriendStatType {
    MOVIES, SERIES, ANIME
}

data class FriendMediaItem(
    val id: String,
    val title: String,
    val posterUrl: String,
    val isMovie: Boolean,
    val isAnime: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicProfileScreen(
    userId: String,
    onBack: () -> Unit,
    onChatSelected: (String) -> Unit = {},
    onMediaClick: (String, Boolean) -> Unit = { _, _ -> },
    onNavigateToBlockedUsers: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val socialRepo = remember { SocialRepository() }
    val userPrefs = remember { UserPreferencesRepository(context) }

    var userProfile by remember { mutableStateOf<UserProfile?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Conversations to check if already friends / talking
    val conversations by socialRepo.getConversations().collectAsState(initial = emptyList())
    val friendRequests by userPrefs.friendRequests.collectAsState(initial = emptySet())
    val blockedUsers by userPrefs.blockedUsers.collectAsState(initial = emptySet())

    val isFriend = remember(conversations, userId) {
        conversations.any { it.participants.contains(userId) && !it.isRequest }
    }
    var requestSentLocally by remember { mutableStateOf(false) }
    val isRequestSent = requestSentLocally || friendRequests.contains(userId)

    // Dialog & Sheet States
    var showMenu by remember { mutableStateOf(false) }
    var showFullscreenAvatar by remember { mutableStateOf(false) }
    var showBlockConfirmDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var activeStatSheet by remember { mutableStateOf<FriendStatType?>(null) }
    var showViewAllActivityDialog by remember { mutableStateOf(false) }

    // Report state
    var selectedReportReason by remember { mutableStateOf("") }
    var reportDetails by remember { mutableStateOf("") }
    var isSubmittingReport by remember { mutableStateOf(false) }

    // Fetch user profile from Firestore / cache
    LaunchedEffect(userId) {
        isLoading = true
        userProfile = socialRepo.getUserProfile(userId)
        isLoading = false
    }

    // Media lists from repository to populate the friend's activity and categories
    val trendingMovies by AppContainer.mediaRepository.getTrendingMovies().collectAsState(initial = emptyList())
    val trendingSeries by AppContainer.mediaRepository.getTrendingSeries().collectAsState(initial = emptyList())
    val trendingAnime by AppContainer.mediaRepository.getTrendingAnime().collectAsState(initial = emptyList())

    // Deterministic selection based on userId
    val seed = remember(userId) { kotlin.math.abs(userId.hashCode()) }

    val moviesList = remember(trendingMovies, seed) {
        if (trendingMovies.isEmpty()) emptyList()
        else {
            val count = (8 + (seed % 9)).coerceAtMost(trendingMovies.size)
            trendingMovies.shuffled(java.util.Random(seed.toLong())).take(count).map {
                FriendMediaItem(
                    id = it.id,
                    title = it.title,
                    posterUrl = it.posterUrl,
                    isMovie = true,
                    isAnime = false
                )
            }
        }
    }

    val seriesList = remember(trendingSeries, seed) {
        if (trendingSeries.isEmpty()) emptyList()
        else {
            val count = (6 + (seed % 7)).coerceAtMost(trendingSeries.size)
            trendingSeries.shuffled(java.util.Random((seed + 11).toLong())).take(count).map {
                FriendMediaItem(
                    id = it.id,
                    title = it.title,
                    posterUrl = it.posterUrl,
                    isMovie = false,
                    isAnime = false
                )
            }
        }
    }

    val animeList = remember(trendingAnime, seed) {
        if (trendingAnime.isEmpty()) emptyList()
        else {
            val count = (4 + (seed % 6)).coerceAtMost(trendingAnime.size)
            trendingAnime.shuffled(java.util.Random((seed + 23).toLong())).take(count).map {
                FriendMediaItem(
                    id = it.id,
                    title = it.title,
                    posterUrl = it.posterUrl,
                    isMovie = false,
                    isAnime = true
                )
            }
        }
    }

    val allRecentActivity = remember(moviesList, seriesList, animeList) {
        (moviesList + seriesList + animeList).shuffled(java.util.Random(seed.toLong()))
    }

    val primaryRed = MaterialTheme.colorScheme.primary
    val cardBackground = Color(0xFF14151C)
    val textMuted = Color(0xFF8E90A6)

    // Full Screen Avatar Dialog
    if (showFullscreenAvatar) {
        Dialog(
            onDismissRequest = { showFullscreenAvatar = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
                    .clickable { showFullscreenAvatar = false },
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = { showFullscreenAvatar = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(24.dp)
                        .size(44.dp)
                        .background(Color(0xFF222430), CircleShape)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White
                    )
                }

                val photo = userProfile?.photoUrl
                if (!photo.isNullOrEmpty()) {
                    AsyncImage(
                        model = photo,
                        contentDescription = stringResource(R.string.profile_picture),
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .border(3.dp, primaryRed, CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .clip(CircleShape)
                            .border(3.dp, primaryRed, CircleShape)
                            .background(Color(0xFF222430)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (userProfile?.displayName?.take(1) ?: "U").uppercase(),
                            color = Color.White,
                            fontSize = 80.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    // Block Confirmation Dialog
    if (showBlockConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showBlockConfirmDialog = false },
            containerColor = Color(0xFF1C1D26),
            title = {
                Text(
                    stringResource(R.string.block_confirm_title),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    stringResource(R.string.block_confirm_desc),
                    color = Color(0xFFC7C7CC),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            userPrefs.blockUser(userId)
                            showBlockConfirmDialog = false
                            Toast.makeText(
                                context,
                                context.getString(R.string.user_blocked_success),
                                Toast.LENGTH_SHORT
                            ).show()
                            onBack()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30))
                ) {
                    Text(stringResource(R.string.block_user), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel), color = Color.White)
                }
            }
        )
    }

    // Report User Dialog
    if (showReportDialog) {
        val reportReasons = listOf(
            stringResource(R.string.report_reason_inappropriate),
            stringResource(R.string.report_reason_harassment),
            stringResource(R.string.report_reason_impersonation),
            stringResource(R.string.report_reason_spam),
            stringResource(R.string.report_reason_other)
        )

        AlertDialog(
            onDismissRequest = { if (!isSubmittingReport) showReportDialog = false },
            containerColor = Color(0xFF1C1D26),
            title = {
                Text(
                    stringResource(R.string.report_user),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.select_report_reason),
                        color = Color(0xFFC7C7CC),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    reportReasons.forEach { reason ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedReportReason = reason }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = selectedReportReason == reason,
                                onClick = { selectedReportReason = reason },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = primaryRed,
                                    unselectedColor = Color(0xFF6B6E7D)
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = reason,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = reportDetails,
                        onValueChange = { reportDetails = it },
                        placeholder = {
                            Text(
                                stringResource(R.string.report_details_hint),
                                color = Color(0xFF6B6E7D),
                                fontSize = 13.sp
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryRed,
                            unfocusedBorderColor = Color(0xFF333545),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF12131A),
                            unfocusedContainerColor = Color(0xFF12131A)
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedReportReason.isBlank()) {
                            Toast.makeText(context, context.getString(R.string.select_report_reason), Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (!com.example.data.repository.UserSecurityManager.canRequest()) {
                            Toast.makeText(context, com.example.data.repository.UserSecurityManager.getRequestRestrictionMessage(), Toast.LENGTH_LONG).show()
                            showReportDialog = false
                            return@Button
                        }
                        isSubmittingReport = true
                        coroutineScope.launch {
                            val targetName = userProfile?.displayName ?: "User $userId"
                            val result = ReportRepository.submitReport(
                                contentId = userId,
                                contentType = "user",
                                reason = selectedReportReason,
                                details = reportDetails,
                                title = "Report User: $targetName",
                                type = "user"
                            )
                            isSubmittingReport = false
                            showReportDialog = false
                            if (result.isSuccess) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.report_submitted_success),
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                val errMsg = result.exceptionOrNull()?.message ?: "Failed to submit report"
                                Toast.makeText(
                                    context,
                                    errMsg,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryRed),
                    enabled = !isSubmittingReport
                ) {
                    if (isSubmittingReport) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.submit_report), color = Color.White)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }, enabled = !isSubmittingReport) {
                    Text(stringResource(R.string.cancel), color = Color.White)
                }
            }
        )
    }

    // Category Popup Sheet (Movies, Series, Anime)
    activeStatSheet?.let { statType ->
        val itemsList = when (statType) {
            FriendStatType.MOVIES -> moviesList
            FriendStatType.SERIES -> seriesList
            FriendStatType.ANIME -> animeList
        }
        val sheetTitle = when (statType) {
            FriendStatType.MOVIES -> stringResource(R.string.movies_filter)
            FriendStatType.SERIES -> stringResource(R.string.series_filter)
            FriendStatType.ANIME -> stringResource(R.string.anime_filter)
        }

        FriendStatDetailSheet(
            title = sheetTitle,
            items = itemsList,
            onDismiss = { activeStatSheet = null },
            onMediaClick = { id, isMovie ->
                activeStatSheet = null
                onMediaClick(id, isMovie)
            }
        )
    }

    // View All Activity Sheet (4 Filter Tabs)
    if (showViewAllActivityDialog) {
        FriendViewAllActivitySheet(
            allItems = allRecentActivity,
            onDismiss = { showViewAllActivityDialog = false },
            onMediaClick = { id, isMovie ->
                showViewAllActivityDialog = false
                onMediaClick(id, isMovie)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = userProfile?.displayName ?: stringResource(R.string.profile),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = primaryRed)
                }
            } else if (userProfile == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.user_not_found),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            } else {
                val profile = userProfile!!
                val rawDisplayName = profile.displayName.ifBlank { "USER" }
                val spacedDisplayName = remember(rawDisplayName) {
                    rawDisplayName.uppercase().toList().joinToString(" ")
                }
                val usernameHandle = profile.username.ifBlank { userId.take(8) }
                val bioText = profile.bio.ifBlank {
                    "Movie & Series enthusiast • CineStream Community 🍿"
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // Hero Profile Banner Card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0xFF1E1316),
                                        Color(0xFF15161D),
                                        Color(0xFF101116)
                                    )
                                )
                            )
                            .border(
                                1.dp,
                                Brush.verticalGradient(
                                    colors = listOf(Color(0xFF3A1C22), Color(0xFF1F212B))
                                ),
                                RoundedCornerShape(24.dp)
                            )
                            .padding(16.dp)
                    ) {
                        // Top Left: Three Dots Menu
                        Box(modifier = Modifier.align(Alignment.TopStart)) {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "Options",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier
                                    .background(Color(0xFF1E1F28))
                                    .border(1.dp, Color(0xFF333545), RoundedCornerShape(12.dp))
                            ) {
                                // 1. Block User
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.block_user),
                                            color = Color(0xFFFF5252),
                                            fontSize = 14.sp
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Block,
                                            contentDescription = null,
                                            tint = Color(0xFFFF5252),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showBlockConfirmDialog = true
                                    }
                                )

                                // 2. Blocked Users
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.blocked_users),
                                            color = Color.White,
                                            fontSize = 14.sp
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.PersonOff,
                                            contentDescription = null,
                                            tint = primaryRed,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        onNavigateToBlockedUsers()
                                    }
                                )

                                HorizontalDivider(color = Color(0xFF2E303F))

                                // 3. Report User
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.report_user),
                                            color = Color.White,
                                            fontSize = 14.sp
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Flag,
                                            contentDescription = null,
                                            tint = primaryRed,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        if (!com.example.data.repository.UserSecurityManager.canRequest()) {
                                            Toast.makeText(context, com.example.data.repository.UserSecurityManager.getRequestRestrictionMessage(), Toast.LENGTH_LONG).show()
                                        } else {
                                            showReportDialog = true
                                        }
                                    }
                                )
                            }
                        }

                        // Top Right: Stylized CineStream Motto
                        Column(
                            modifier = Modifier.align(Alignment.TopEnd),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                "GOOD\nMOVIES",
                                color = Color(0xFF6B6E7D),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 12.sp,
                                textAlign = TextAlign.End,
                                letterSpacing = 1.sp
                            )
                            Text(
                                "BETTER",
                                color = primaryRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                lineHeight = 12.sp,
                                textAlign = TextAlign.End,
                                letterSpacing = 1.sp
                            )
                            Text(
                                "PEOPLE",
                                color = Color(0xFF6B6E7D),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 12.sp,
                                textAlign = TextAlign.End,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .width(28.dp)
                                    .height(2.dp)
                                    .background(primaryRed)
                            )
                        }

                        // Center Content: Avatar + Name + Username + Friend / Message Actions
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 18.dp, bottom = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Circular Avatar (Clickable to show full screen preview)
                            Box(
                                modifier = Modifier
                                    .size(104.dp)
                                    .clip(CircleShape)
                                    .border(3.dp, primaryRed, CircleShape)
                                    .background(Color(0xFF1E1E1E))
                                    .clickable { showFullscreenAvatar = true },
                                contentAlignment = Alignment.Center
                            ) {
                                if (profile.photoUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = profile.photoUrl,
                                        contentDescription = stringResource(R.string.profile_picture),
                                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Text(
                                        rawDisplayName.firstOrNull()?.toString()?.uppercase() ?: "U",
                                        color = Color.White,
                                        fontSize = 42.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Non-clickable Spaced Display Name
                            Text(
                                text = spacedDisplayName,
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 3.sp
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            // Clickable Username (Copies immediately to clipboard)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        clipboardManager.setText(AnnotatedString("@$usernameHandle"))
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.username_copied),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "@$usernameHandle",
                                    color = textMuted,
                                    fontSize = 13.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Action Row: Friend Status Button + Message Button
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (isFriend) {
                                    // Already Friends: Grey background
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = Color(0xFF262835),
                                        border = BorderStroke(1.dp, Color(0xFF3E4154)),
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color(0xFF4CAF50),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = stringResource(R.string.friends),
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                } else if (isRequestSent) {
                                    // Request Sent: Slate/Pending button
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = Color(0xFF232532),
                                        border = BorderStroke(1.dp, Color(0xFF4A4E63)),
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Schedule,
                                                contentDescription = null,
                                                tint = Color(0xFFFFB300),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = stringResource(R.string.request_sent),
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                } else {
                                    // Add Friend: Red button
                                    Surface(
                                        onClick = {
                                            requestSentLocally = true
                                            coroutineScope.launch {
                                                userPrefs.sendFriendRequest(userId)
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.friend_request_sent_toast),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                        shape = RoundedCornerShape(20.dp),
                                        color = primaryRed,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.PersonAdd,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = stringResource(R.string.add_friend),
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Message Icon Button
                                Surface(
                                    onClick = {
                                        coroutineScope.launch {
                                            val convId = socialRepo.startConversation(userId, profile.displayName)
                                            if (convId.isNotBlank()) {
                                                onChatSelected(convId)
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(20.dp),
                                    color = Color(0xFF221115),
                                    border = BorderStroke(1.dp, Color(0xFF6B1D22)),
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Send,
                                            contentDescription = stringResource(R.string.send_message),
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Stats Row (Movies, Series, Anime)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(cardBackground)
                            .border(0.5.dp, Color(0xFF262835), RoundedCornerShape(16.dp))
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FriendStatColumn(
                            icon = Icons.Filled.Movie,
                            count = moviesList.size.toString(),
                            label = "Movies",
                            tintColor = primaryRed,
                            onClick = { activeStatSheet = FriendStatType.MOVIES },
                            modifier = Modifier.weight(1f)
                        )
                        FriendStatColumn(
                            icon = Icons.Filled.Tv,
                            count = seriesList.size.toString(),
                            label = "Series",
                            tintColor = primaryRed,
                            onClick = { activeStatSheet = FriendStatType.SERIES },
                            modifier = Modifier.weight(1f)
                        )
                        FriendStatColumn(
                            icon = Icons.Filled.Face,
                            count = animeList.size.toString(),
                            label = "Anime",
                            tintColor = primaryRed,
                            onClick = { activeStatSheet = FriendStatType.ANIME },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // About Section Card (Read-only as requested)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(cardBackground)
                            .border(0.5.dp, Color(0xFF262835), RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.about_me),
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = bioText,
                                color = Color(0xFFC7C7CC),
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Recent Activity Section
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Schedule,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.recent_activity),
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = "View All >",
                            color = primaryRed,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { showViewAllActivityDialog = true }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 4-Column Poster Grid of Watched Activity
                    val displayRecent = allRecentActivity.take(8)

                    if (displayRecent.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(cardBackground)
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Outlined.PlayCircleOutline,
                                    contentDescription = null,
                                    tint = textMuted,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.no_recent_activity_desc),
                                    color = textMuted,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    } else {
                        displayRecent.chunked(4).forEach { rowItems ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowItems.forEach { item ->
                                    Box(modifier = Modifier.weight(1f)) {
                                        FriendPosterCard(
                                            item = item,
                                            onClick = { onMediaClick(item.id, item.isMovie) }
                                        )
                                    }
                                }

                                if (rowItems.size < 4) {
                                    repeat(4 - rowItems.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun FriendStatColumn(
    icon: ImageVector,
    count: String,
    label: String,
    tintColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tintColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = count,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = Color(0xFF8E90A6),
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

@Composable
private fun FriendPosterCard(
    item: FriendMediaItem,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1E202B))
                .border(0.5.dp, Color(0xFF2F3244), RoundedCornerShape(10.dp))
        ) {
            if (item.posterUrl.isNotEmpty()) {
                val fullPoster = if (item.posterUrl.startsWith("http")) item.posterUrl else "https://image.tmdb.org/t/p/w500${item.posterUrl}"
                AsyncImage(
                    model = fullPoster,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        if (item.isMovie) Icons.Filled.Movie else Icons.Filled.Tv,
                        contentDescription = null,
                        tint = Color(0xFF555970),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Tag badge
            val tagText = if (item.isAnime) "Anime" else if (item.isMovie) "Movie" else "Series"
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.75f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
            ) {
                Text(
                    text = tagText,
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = item.title,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// Category Detail Bottom Sheet
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FriendStatDetailSheet(
    title: String,
    items: List<FriendMediaItem>,
    onDismiss: () -> Unit,
    onMediaClick: (String, Boolean) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF14151C),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF333545)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF262835)
                    ) {
                        Text(
                            text = items.size.toString(),
                            color = Color(0xFF8E90A6),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = Color(0xFF8E90A6),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.empty_category),
                        color = Color(0xFF8E90A6),
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.heightIn(max = 420.dp)
                ) {
                    items(items, key = { it.id }) { item ->
                        FriendPosterCard(
                            item = item,
                            onClick = { onMediaClick(item.id, item.isMovie) }
                        )
                    }
                }
            }
        }
    }
}

// View All Activity Bottom Sheet with 4 Tabs
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FriendViewAllActivitySheet(
    allItems: List<FriendMediaItem>,
    onDismiss: () -> Unit,
    onMediaClick: (String, Boolean) -> Unit
) {
    var selectedFilterIndex by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    val tabs = listOf(
        stringResource(R.string.all_filter),
        stringResource(R.string.movies_filter),
        stringResource(R.string.series_filter),
        stringResource(R.string.anime_filter)
    )

    val filteredItems = remember(allItems, selectedFilterIndex, searchQuery) {
        val byTab = when (selectedFilterIndex) {
            0 -> allItems
            1 -> allItems.filter { it.isMovie && !it.isAnime }
            2 -> allItems.filter { !it.isMovie && !it.isAnime }
            3 -> allItems.filter { it.isAnime }
            else -> allItems
        }
        if (searchQuery.isBlank()) byTab
        else byTab.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF14151C),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF333545)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.recent_activity),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = Color(0xFF8E90A6),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Search Filter
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        stringResource(R.string.search),
                        color = Color(0xFF6B6E7D),
                        fontSize = 13.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = Color(0xFF6B6E7D),
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color(0xFF262835),
                    focusedContainerColor = Color(0xFF1B1D26),
                    unfocusedContainerColor = Color(0xFF1B1D26),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                singleLine = true
            )

            // 4 Filter Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    val isSelected = selectedFilterIndex == index
                    Surface(
                        onClick = { selectedFilterIndex = index },
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF1E202B),
                        border = if (isSelected) null else BorderStroke(0.5.dp, Color(0xFF2F3244)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = title,
                            color = if (isSelected) Color.White else Color(0xFF8E90A6),
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }

            // Content Grid
            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.empty_category),
                        color = Color(0xFF8E90A6),
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.heightIn(max = 420.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        FriendPosterCard(
                            item = item,
                            onClick = { onMediaClick(item.id, item.isMovie) }
                        )
                    }
                }
            }
        }
    }
}
