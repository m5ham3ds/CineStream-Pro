package com.example.ui.screens.profile

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.DownloadItem
import com.example.data.model.HistoryItem
import com.example.data.model.LibraryItem
import com.example.data.repository.DownloadRepository
import com.example.data.repository.HistoryRepository
import com.example.data.repository.LibraryRepository
import com.example.data.repository.UserPreferencesRepository
import com.example.data.repository.UserSecurityManager
import com.example.data.repository.WatchedEpisodeRepository
import com.example.ui.components.ProfileScreenSkeleton
import com.example.ui.screens.auth.AuthViewModel
import com.example.utils.NetworkUtils
import kotlinx.coroutines.launch

private enum class StatType {
    WATCHLIST, MOVIES, SERIES, ANIME, DOWNLOADS
}

// Preset avatars for quick and stylish selection
private val PRESET_AVATARS = listOf(
    "https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=400&auto=format&fit=crop&q=80",
    "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=400&auto=format&fit=crop&q=80",
    "https://images.unsplash.com/photo-1517841905240-472988babdf9?w=400&auto=format&fit=crop&q=80",
    "https://images.unsplash.com/photo-1539571696357-5a69c17a67c6?w=400&auto=format&fit=crop&q=80",
    "https://images.unsplash.com/photo-1524504388940-b1c1722653e1?w=400&auto=format&fit=crop&q=80",
    "https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=400&auto=format&fit=crop&q=80"
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileScreen(
    onNavigateToEditProfile: () -> Unit,
    onNavigateToAccountSettings: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSecurity: () -> Unit = {},
    onNavigateToSubscription: () -> Unit = {},
    onNavigateToHelpSupport: () -> Unit = {},
    onNavigateToAuth: () -> Unit = {},
    onMediaClick: (String, Boolean) -> Unit = { _, _ -> },
    onMediaClickWithMode: ((String, Boolean, String) -> Unit)? = null,
    authViewModel: AuthViewModel = viewModel()
) {
    var isLoading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200)
        isLoading = false
    }

    if (isLoading) {
        ProfileScreenSkeleton()
        return
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    val currentUser by authViewModel.currentUser.collectAsStateWithLifecycle()
    val restrictions by UserSecurityManager.restrictionsFlow.collectAsState()
    val isAdFree = restrictions.isAdFree

    val userPrefs = remember { UserPreferencesRepository(context) }
    val userBio by userPrefs.userBio.collectAsState(initial = "Movie & Anime Lover\nEnjoying great stories ✨")
    val customAvatarUri by userPrefs.customAvatarUri.collectAsState(initial = "")

    val historyRepo = remember { HistoryRepository(context) }
    val libraryRepo = remember { LibraryRepository(context) }
    val downloadRepo = remember { DownloadRepository(context) }
    val watchedEpisodeRepo = remember { WatchedEpisodeRepository(context) }

    val historyItems by historyRepo.getHistoryItems().collectAsState(initial = emptyList())
    val libraryItems by libraryRepo.getLibraryItems().collectAsState(initial = emptyList())
    val downloadItems by downloadRepo.getDownloadItems().collectAsState(initial = emptyList())
    val watchedEpisodes by watchedEpisodeRepo.getAllWatched().collectAsState(initial = emptyList())

    // Helper to identify anime items
    fun isAnimeMedia(title: String, isMovie: Boolean): Boolean {
        val lower = title.lowercase()
        return lower.contains("anime") || lower.contains("titan") || lower.contains("piece") ||
                lower.contains("naruto") || lower.contains("bleach") || lower.contains("demon slayer") ||
                lower.contains("jujutsu") || lower.contains("hero academia") || lower.contains("hunter") ||
                lower.contains("dragon ball") || lower.contains("death note") || lower.contains("ghoul") ||
                lower.contains("solo leveling") || lower.contains("haikyuu") || lower.contains("chainsaw")
    }

    val moviesList = remember(historyItems) { historyItems.filter { it.isMovie && !isAnimeMedia(it.title, true) } }
    val seriesList = remember(historyItems) { historyItems.filter { !it.isMovie && !isAnimeMedia(it.title, false) } }
    val animeList = remember(historyItems, libraryItems) {
        historyItems.filter { isAnimeMedia(it.title, it.isMovie) }
    }
    val watchlistList = remember(libraryItems) { libraryItems }
    val downloadsList = remember(downloadItems) { downloadItems.filter { it.isCompleted } }

    val watchlistCount = watchlistList.size
    val moviesCount = moviesList.size
    val seriesCount = seriesList.size
    val animeCount = if (animeList.isNotEmpty()) animeList.size else watchedEpisodes.size
    val downloadsCount = downloadsList.size

    // Dialog & sheet states
    var showDropdownMenu by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showFullscreenAvatar by remember { mutableStateOf(false) }
    var showChangeAvatarDialog by remember { mutableStateOf(false) }
    var showEditBioDialog by remember { mutableStateOf(false) }
    var activeStatType by remember { mutableStateOf<StatType?>(null) }
    var showViewAllActivityDialog by remember { mutableStateOf(false) }

    // Photo picker launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                userPrefs.saveCustomAvatarFromUri(uri)
                authViewModel.updateProfile(
                    firstName = currentUser?.firstName ?: "",
                    lastName = currentUser?.lastName ?: "",
                    username = currentUser?.username ?: "user",
                    photoUri = uri,
                    isProfilePublic = currentUser?.isProfilePublic ?: true,
                    onComplete = { success, _ ->
                        if (success) {
                            Toast.makeText(context, context.getString(R.string.avatar_updated), Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }

    val primaryRed = MaterialTheme.colorScheme.primary
    val cardBackground = Color(0xFF14151C)
    val textMuted = Color(0xFF8E8E93)

    // Current avatar image url
    val effectiveAvatarUrl = when {
        customAvatarUri.isNotBlank() -> customAvatarUri
        !currentUser?.photoUrl.isNullOrBlank() -> currentUser?.photoUrl
        else -> null
    }

    // Raw display name & stylized name
    val rawDisplayName = remember(currentUser) {
        if (currentUser != null) {
            val full = "${currentUser?.firstName ?: ""} ${currentUser?.lastName ?: ""}".trim()
            if (full.isNotBlank()) full else (currentUser?.username ?: "MOHAMED")
        } else {
            "MOHAMED"
        }
    }

    // Stylized spaced name like "M O H A M E D"
    val spacedDisplayName = remember(rawDisplayName) {
        rawDisplayName.uppercase().replace(" ", "").toList().joinToString(" ")
    }

    val usernameHandle = currentUser?.username?.takeIf { it.isNotBlank() } ?: "mohamed2"

    // Sign out confirmation dialog
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(stringResource(R.string.sign_out), color = Color.White) },
            text = { Text(stringResource(R.string.confirm_sign_out), color = textMuted) },
            confirmButton = {
                TextButton(onClick = {
                    if (!NetworkUtils.isInternetAvailable(context)) {
                        Toast.makeText(context, context.getString(R.string.cannot_logout_offline), Toast.LENGTH_SHORT).show()
                        showLogoutConfirm = false
                    } else {
                        showLogoutConfirm = false
                        authViewModel.signOut()
                        onNavigateToAuth()
                    }
                }) {
                    Text(stringResource(R.string.sign_out), color = primaryRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text(stringResource(R.string.cancel), color = Color.White)
                }
            },
            containerColor = Color(0xFF1C1D24)
        )
    }

    // Fullscreen Avatar View Dialog
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
                // Top close button & actions
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
                        onClick = { showFullscreenAvatar = false },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = 0.1f))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }

                    TextButton(
                        onClick = {
                            showFullscreenAvatar = false
                            showChangeAvatarDialog = true
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = primaryRed)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.change_profile_photo), fontWeight = FontWeight.Bold)
                    }
                }

                // Avatar Content
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .clip(CircleShape)
                            .border(4.dp, primaryRed, CircleShape)
                            .background(Color(0xFF1E1E1E)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (effectiveAvatarUrl != null) {
                            AsyncImage(
                                model = effectiveAvatarUrl,
                                contentDescription = stringResource(R.string.profile_picture),
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            val initial = rawDisplayName.firstOrNull()?.toString()?.uppercase() ?: "M"
                            Text(
                                initial,
                                color = Color.White,
                                fontSize = 96.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Text(spacedDisplayName, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("@$usernameHandle", color = textMuted, fontSize = 14.sp)
                }
            }
        }
    }

    // Change Avatar Dialog
    if (showChangeAvatarDialog) {
        AlertDialog(
            onDismissRequest = { showChangeAvatarDialog = false },
            title = {
                Text(
                    stringResource(R.string.change_profile_photo),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.preset_avatars),
                        color = textMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Presets Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        PRESET_AVATARS.take(4).forEach { url ->
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .border(1.5.dp, primaryRed.copy(alpha = 0.5f), CircleShape)
                                    .clickable {
                                        coroutineScope.launch {
                                            userPrefs.saveCustomAvatarUri(url)
                                            authViewModel.updateProfilePictureDirect(photoUri = null, avatarUrl = url)
                                            showChangeAvatarDialog = false
                                            Toast.makeText(context, context.getString(R.string.avatar_updated), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                            ) {
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Pick from Gallery Button
                    OutlinedButton(
                        onClick = {
                            showChangeAvatarDialog = false
                            photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, primaryRed)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = primaryRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.gallery), color = Color.White)
                    }

                    if (effectiveAvatarUrl != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    userPrefs.saveCustomAvatarUri("")
                                    showChangeAvatarDialog = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.remove_avatar), color = Color.Red, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showChangeAvatarDialog = false }) {
                    Text(stringResource(R.string.cancel), color = textMuted)
                }
            },
            containerColor = Color(0xFF1C1D24)
        )
    }

    // Edit Bio Dialog (Max 100 characters strictly)
    if (showEditBioDialog) {
        var bioInput by remember { mutableStateOf(userBio) }
        AlertDialog(
            onDismissRequest = { showEditBioDialog = false },
            title = {
                Text(
                    stringResource(R.string.edit_about),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = bioInput,
                        onValueChange = {
                            if (it.length <= 100) {
                                bioInput = it
                            }
                        },
                        placeholder = { Text(stringResource(R.string.about_hint), color = textMuted, fontSize = 13.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryRed,
                            unfocusedBorderColor = Color(0xFF333542),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        supportingText = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Text(
                                    text = "${bioInput.length} / 100",
                                    color = if (bioInput.length >= 95) primaryRed else textMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            userPrefs.saveUserBio(bioInput.trim())
                            showEditBioDialog = false
                            Toast.makeText(context, context.getString(R.string.about_updated), Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryRed),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(stringResource(R.string.save), color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditBioDialog = false }) {
                    Text(stringResource(R.string.cancel), color = Color.White)
                }
            },
            containerColor = Color(0xFF1C1D24)
        )
    }

    // Category Detail Popup (Watchlist, Movies, Series, Anime, Downloads)
    if (activeStatType != null) {
        val currentType = activeStatType!!
        CategoryDetailSheet(
            category = currentType,
            watchlistItems = watchlistList,
            moviesItems = moviesList,
            seriesItems = seriesList,
            animeItems = animeList,
            downloadsItems = downloadsList,
            onDismiss = { activeStatType = null },
            onMediaClick = { id, isMovie ->
                activeStatType = null
                onMediaClick(id, isMovie)
            },
            onMediaClickWithMode = { id, isMovie, mode ->
                activeStatType = null
                if (onMediaClickWithMode != null) {
                    onMediaClickWithMode(id, isMovie, mode)
                } else {
                    onMediaClick(id, isMovie)
                }
            }
        )
    }

    // View All Activity Modal Sheet (All, Movies, Series, Anime)
    if (showViewAllActivityDialog) {
        ViewAllActivitySheet(
            historyItems = historyItems,
            onDismiss = { showViewAllActivityDialog = false },
            onMediaClick = { id, isMovie ->
                showViewAllActivityDialog = false
                onMediaClick(id, isMovie)
            }
        )
    }

    // Main Profile Screen Layout
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
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
                        onClick = { showDropdownMenu = true },
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
                        expanded = showDropdownMenu,
                        onDismissRequest = { showDropdownMenu = false },
                        modifier = Modifier
                            .background(Color(0xFF1E1F28))
                            .border(1.dp, Color(0xFF333545), RoundedCornerShape(12.dp))
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit_account_info), color = Color.White, fontSize = 14.sp) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = primaryRed, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                showDropdownMenu = false
                                onNavigateToEditProfile()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.account_settings), color = Color.White, fontSize = 14.sp) },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = primaryRed, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                showDropdownMenu = false
                                onNavigateToAccountSettings()
                            }
                        )
                        HorizontalDivider(color = Color(0xFF2E303F))
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sign_out), color = Color(0xFFFF5252), fontSize = 14.sp) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(20.dp)) },
                            onClick = {
                                showDropdownMenu = false
                                showLogoutConfirm = true
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

                // Center Content: Avatar + Name + Username + Plan Badge
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Circular Avatar with Glowing Red Border
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .clip(CircleShape)
                            .border(3.dp, primaryRed, CircleShape)
                            .background(Color(0xFF1E1E1E))
                            .combinedClickable(
                                onClick = { showFullscreenAvatar = true },
                                onLongClick = { showChangeAvatarDialog = true }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (effectiveAvatarUrl != null) {
                            AsyncImage(
                                model = effectiveAvatarUrl,
                                contentDescription = stringResource(R.string.profile_picture),
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            val initial = rawDisplayName.firstOrNull()?.toString()?.uppercase() ?: "M"
                            Text(
                                initial,
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
                                Toast.makeText(context, context.getString(R.string.username_copied), Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "@$usernameHandle",
                            color = textMuted,
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Clickable Plan / Premium Badge
                    Surface(
                        onClick = onNavigateToSubscription,
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF221115),
                        border = BorderStroke(1.dp, Color(0xFF6B1D22)),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("👑", fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when {
                                    restrictions.isAdFree && restrictions.canonicalTier == com.example.data.model.CanonicalSubscriptionTier.PRO -> stringResource(R.string.pro_member)
                                    restrictions.isAdFree && restrictions.canonicalTier == com.example.data.model.CanonicalSubscriptionTier.PRO_LITE -> stringResource(R.string.pro_lite_member)
                                    restrictions.isSubscriptionExpired -> stringResource(R.string.subscription_expired)
                                    else -> stringResource(R.string.free_account)
                                },
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            if (restrictions.pointsBalance > 0) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("•", color = Color.Gray, fontSize = 12.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "⭐ ${restrictions.pointsBalance}",
                                    color = Color(0xFFFFD700),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5 Stats Row (Watchlist, Movies, Series, Anime, Downloads)
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
                StatColumn(
                    icon = Icons.Filled.Favorite,
                    count = watchlistCount.toString(),
                    label = "Watchlist",
                    tintColor = primaryRed,
                    onClick = { activeStatType = StatType.WATCHLIST },
                    modifier = Modifier.weight(1f)
                )
                StatColumn(
                    icon = Icons.Filled.Movie,
                    count = moviesCount.toString(),
                    label = "Movies",
                    tintColor = primaryRed,
                    onClick = { activeStatType = StatType.MOVIES },
                    modifier = Modifier.weight(1f)
                )
                StatColumn(
                    icon = Icons.Filled.Tv,
                    count = seriesCount.toString(),
                    label = "Series",
                    tintColor = primaryRed,
                    onClick = { activeStatType = StatType.SERIES },
                    modifier = Modifier.weight(1f)
                )
                StatColumn(
                    icon = Icons.Filled.Face,
                    count = animeCount.toString(),
                    label = "Anime",
                    tintColor = primaryRed,
                    onClick = { activeStatType = StatType.ANIME },
                    modifier = Modifier.weight(1f)
                )
                StatColumn(
                    icon = Icons.Filled.Download,
                    count = downloadsCount.toString(),
                    label = "Downloads",
                    tintColor = primaryRed,
                    onClick = { activeStatType = StatType.DOWNLOADS },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // About Section Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardBackground)
                    .border(0.5.dp, Color(0xFF262835), RoundedCornerShape(16.dp))
                    .clickable { showEditBioDialog = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "About",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = userBio,
                        color = Color(0xFFC7C7CC),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = textMuted,
                    modifier = Modifier.size(20.dp)
                )
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

            // 4-Column Poster Grid of Real Watched Activity
            val displayRecent = historyItems.take(8)

            if (displayRecent.isEmpty()) {
                // Sleek empty state for activity
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
                // Chunk into rows of 4
                displayRecent.chunked(4).forEach { rowItems ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { item ->
                            Box(modifier = Modifier.weight(1f)) {
                                ActivityPosterCard(
                                    item = item,
                                    onClick = { onMediaClick(item.id, item.isMovie) }
                                )
                            }
                        }

                        // Fill remainder if less than 4 items in row
                        if (rowItems.size < 4) {
                            repeat(4 - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(64.dp))
        }
    }
}

// Single Stat Item Column
@Composable
private fun StatColumn(
    icon: ImageVector,
    count: String,
    label: String,
    tintColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = tintColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = count,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = Color(0xFF8E8E93),
            fontSize = 10.sp
        )
    }
}

// 4-Column Poster Card
@Composable
private fun ActivityPosterCard(
    item: HistoryItem,
    onClick: () -> Unit
) {
    val relativeTime = remember(item.timestamp) {
        val now = System.currentTimeMillis()
        val diff = (now - item.timestamp).coerceAtLeast(0L)
        val minutes = diff / (1000 * 60)
        val hours = diff / (1000 * 60 * 60)
        val days = diff / (1000 * 60 * 60 * 24)
        val weeks = days / 7
        when {
            minutes < 60 -> "Just now"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            weeks < 4 -> "${weeks}w ago"
            else -> "${days / 30}mo ago"
        }
    }

    val statusText = remember(item.positionMillis, item.durationMillis, relativeTime) {
        if (item.positionMillis > 0 && item.durationMillis > 0) {
            val pct = ((item.positionMillis.toFloat() / item.durationMillis.toFloat()) * 100).toInt()
            if (pct in 5..92) "Watching · $pct%" else "Watched · $relativeTime"
        } else {
            "Watched · $relativeTime"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        // Poster Image
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(115.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF22242E)),
            contentAlignment = Alignment.Center
        ) {
            if (item.posterUrl.isNotBlank()) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    if (item.isMovie) Icons.Default.Movie else Icons.Default.Tv,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Title
        Text(
            text = item.title,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Subtitle / Type
        Text(
            text = if (item.isMovie) "Movie" else "Series",
            color = Color(0xFFAAAAAA),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Status / Time
        Text(
            text = statusText,
            color = Color(0xFF888888),
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// Category Detail Bottom Sheet / Modal
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDetailSheet(
    category: StatType,
    watchlistItems: List<LibraryItem>,
    moviesItems: List<HistoryItem>,
    seriesItems: List<HistoryItem>,
    animeItems: List<HistoryItem>,
    downloadsItems: List<DownloadItem>,
    onDismiss: () -> Unit,
    onMediaClick: (String, Boolean) -> Unit,
    onMediaClickWithMode: ((String, Boolean, String) -> Unit)? = null
) {
    val categoryTitle = when (category) {
        StatType.WATCHLIST -> "Watchlist (${watchlistItems.size})"
        StatType.MOVIES -> "Watched Movies (${moviesItems.size})"
        StatType.SERIES -> "Watched Series (${seriesItems.size})"
        StatType.ANIME -> "Watched Anime (${animeItems.size})"
        StatType.DOWNLOADS -> "Downloads (${downloadsItems.size})"
    }

    val categoryIcon = when (category) {
        StatType.WATCHLIST -> Icons.Filled.Favorite
        StatType.MOVIES -> Icons.Filled.Movie
        StatType.SERIES -> Icons.Filled.Tv
        StatType.ANIME -> Icons.Filled.Face
        StatType.DOWNLOADS -> Icons.Filled.Download
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
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(categoryIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(categoryTitle, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF8E8E93))
                }
            }

            when (category) {
                StatType.WATCHLIST -> {
                    if (watchlistItems.isEmpty()) {
                        EmptyCategoryState("No items in your Watchlist yet.")
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.heightIn(max = 420.dp)
                        ) {
                            items(watchlistItems, key = { it.libraryId }) { item ->
                                val expMode = if (item.variantMode.equals("dubbed", ignoreCase = true)) "ARABIC_DUBBED" else "STANDARD"
                                GenericPosterItem(
                                    title = item.title,
                                    posterUrl = item.posterUrl,
                                    subtitle = if (item.isMovie) "Movie" else "Series",
                                    onClick = {
                                        if (onMediaClickWithMode != null) {
                                            onMediaClickWithMode(item.id, item.isMovie, expMode)
                                        } else {
                                            onMediaClick(item.id, item.isMovie)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                StatType.MOVIES -> {
                    if (moviesItems.isEmpty()) {
                        EmptyCategoryState("No watched movies recorded yet.")
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.heightIn(max = 420.dp)
                        ) {
                            items(moviesItems, key = { it.id }) { item ->
                                GenericPosterItem(
                                    title = item.title,
                                    posterUrl = item.posterUrl,
                                    subtitle = "Movie",
                                    onClick = { onMediaClick(item.id, true) }
                                )
                            }
                        }
                    }
                }
                StatType.SERIES -> {
                    if (seriesItems.isEmpty()) {
                        EmptyCategoryState("No watched series recorded yet.")
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.heightIn(max = 420.dp)
                        ) {
                            items(seriesItems, key = { it.id }) { item ->
                                GenericPosterItem(
                                    title = item.title,
                                    posterUrl = item.posterUrl,
                                    subtitle = "Series",
                                    onClick = { onMediaClick(item.id, false) }
                                )
                            }
                        }
                    }
                }
                StatType.ANIME -> {
                    if (animeItems.isEmpty()) {
                        EmptyCategoryState("No watched anime recorded yet.")
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.heightIn(max = 420.dp)
                        ) {
                            items(animeItems, key = { it.id }) { item ->
                                GenericPosterItem(
                                    title = item.title,
                                    posterUrl = item.posterUrl,
                                    subtitle = "Anime",
                                    onClick = { onMediaClick(item.id, item.isMovie) }
                                )
                            }
                        }
                    }
                }
                StatType.DOWNLOADS -> {
                    if (downloadsItems.isEmpty()) {
                        EmptyCategoryState("No completed downloads found.")
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.heightIn(max = 420.dp)
                        ) {
                            items(downloadsItems, key = { it.id }) { item ->
                                GenericPosterItem(
                                    title = item.title,
                                    posterUrl = item.posterUrl,
                                    subtitle = item.cleanQuality,
                                    onClick = { onMediaClick(item.mediaId.ifEmpty { item.id }, item.isMovie) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// View All Activity Bottom Sheet with 4 Tabs
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewAllActivitySheet(
    historyItems: List<HistoryItem>,
    onDismiss: () -> Unit,
    onMediaClick: (String, Boolean) -> Unit
) {
    var selectedFilterIndex by remember { mutableStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.all_filter),
        stringResource(R.string.movies_filter),
        stringResource(R.string.series_filter),
        stringResource(R.string.anime_filter)
    )

    fun isAnime(title: String): Boolean {
        val lower = title.lowercase()
        return lower.contains("anime") || lower.contains("titan") || lower.contains("piece") ||
                lower.contains("naruto") || lower.contains("bleach") || lower.contains("demon slayer") ||
                lower.contains("jujutsu") || lower.contains("hero academia") || lower.contains("hunter") ||
                lower.contains("solo leveling")
    }

    val filteredItems = remember(historyItems, selectedFilterIndex) {
        when (selectedFilterIndex) {
            0 -> historyItems
            1 -> historyItems.filter { it.isMovie && !isAnime(it.title) }
            2 -> historyItems.filter { !it.isMovie && !isAnime(it.title) }
            3 -> historyItems.filter { isAnime(it.title) }
            else -> historyItems
        }
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
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF8E8E93))
                }
            }

            // 4 Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    val isSelected = selectedFilterIndex == index
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedFilterIndex = index },
                        label = { Text(title, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Color.White,
                            containerColor = Color(0xFF1E202B),
                            labelColor = Color(0xFF8E8E93)
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF2A2D3C)
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }

            // Content Grid
            if (filteredItems.isEmpty()) {
                EmptyCategoryState(stringResource(R.string.empty_category))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.heightIn(max = 440.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        GenericPosterItem(
                            title = item.title,
                            posterUrl = item.posterUrl,
                            subtitle = if (item.isMovie) "Movie" else "Series",
                            onClick = { onMediaClick(item.id, item.isMovie) }
                        )
                    }
                }
            }
        }
    }
}

// Reusable Poster Item for Bottom Sheets
@Composable
private fun GenericPosterItem(
    title: String,
    posterUrl: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF22242E)),
            contentAlignment = Alignment.Center
        ) {
            if (posterUrl.isNotBlank()) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Default.Movie, contentDescription = null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(32.dp))
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            title,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            subtitle,
            color = Color(0xFF888888),
            fontSize = 9.sp,
            maxLines = 1
        )
    }
}

// Empty Category State
@Composable
private fun EmptyCategoryState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = Color(0xFF6B6E7D),
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                message,
                color = Color(0xFF8E8E93),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
