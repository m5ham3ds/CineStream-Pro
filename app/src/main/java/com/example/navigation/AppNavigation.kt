package com.example.navigation
import com.example.ui.components.swipeToNavigate

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Share

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.ui.platform.LocalContext

import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.screens.auth.AuthViewModel
import com.example.ui.screens.home.HomeViewModel
import com.example.ui.screens.movies.MoviesViewModel
import com.example.ui.screens.series.SeriesViewModel
import com.example.ui.screens.anime.AnimeViewModel
import com.example.ui.screens.search.SearchViewModel
import com.example.ui.ViewModelFactory
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import java.net.URLDecoder
import java.net.URLEncoder
import com.example.ui.components.BottomNavBar
import com.example.ui.components.ExpandableSearchBar
import com.example.ui.screens.details.MovieDetailsScreen
import com.example.ui.screens.details.SeriesDetailsScreen
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.anime.AnimeScreen
import com.example.ui.screens.home.PopularScreen
import com.example.ui.screens.home.NewReleasesScreen
import com.example.ui.screens.home.UpcomingScreen
import com.example.ui.screens.home.TrendingScreen
import com.example.ui.screens.home.WatchingScreen
import com.example.ui.screens.library.LibraryScreen
import com.example.ui.screens.movies.MoviesScreen
import com.example.ui.screens.search.SearchScreen
import com.example.ui.screens.series.SeriesScreen
import com.example.ui.screens.player.PlayerScreen
import com.example.ui.screens.splash.SplashScreen
import com.example.ui.screens.onboarding.OnboardingScreen
import com.example.ui.screens.profile.ProfileScreen
import com.example.ui.screens.profile.SecurityScreen
import com.example.ui.screens.profile.SubscriptionScreen
import com.example.ui.screens.profile.EditProfileScreen
import com.example.ui.screens.profile.PublicProfileScreen
import com.example.ui.screens.profile.BlockedUsersScreen
import com.example.ui.screens.downloads.DownloadsScreen
import com.example.ui.screens.settings.SettingsScreen

import com.example.ui.screens.social.SocialScreen
import com.example.ui.screens.social.ChatScreen
import com.example.ui.screens.share.ShareScreen

import com.example.ui.screens.about.AboutScreen
import com.example.ui.screens.auth.AuthScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Splash.route

    val context = LocalContext.current
    val activity = context as? androidx.activity.ComponentActivity
    val sharedViewModelStoreOwner = activity ?: checkNotNull(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current)
    val authViewModel: AuthViewModel = viewModel(viewModelStoreOwner = sharedViewModelStoreOwner, factory = ViewModelFactory())
    val homeViewModel: HomeViewModel = viewModel(viewModelStoreOwner = sharedViewModelStoreOwner, factory = ViewModelFactory())
    val moviesViewModel: MoviesViewModel = viewModel(viewModelStoreOwner = sharedViewModelStoreOwner, factory = ViewModelFactory())
    val seriesViewModel: SeriesViewModel = viewModel(viewModelStoreOwner = sharedViewModelStoreOwner, factory = ViewModelFactory())
    val animeViewModel: AnimeViewModel = viewModel(viewModelStoreOwner = sharedViewModelStoreOwner, factory = ViewModelFactory())
    val searchViewModel: SearchViewModel = viewModel(viewModelStoreOwner = sharedViewModelStoreOwner, factory = ViewModelFactory())

    val pendingNavRoute by NavigationIntentHandler.targetDestination.collectAsState()
    LaunchedEffect(Unit) {
        authViewModel.checkCurrentUser()
    }
    LaunchedEffect(pendingNavRoute, currentRoute) {
        val route = pendingNavRoute
        val isInitialRoute = currentRoute == Screen.Splash.route ||
                currentRoute == Screen.Onboarding.route ||
                currentRoute == Screen.Auth.route
        if (route != null && !isInitialRoute) {
            navController.navigate(route) {
                launchSingleTop = true
            }
            NavigationIntentHandler.clear()
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val currentUser by authViewModel.currentUser.collectAsState()
    val userPrefs = remember { com.example.data.repository.UserPreferencesRepository(context) }
    val isGuest by userPrefs.isGuest.collectAsState(initial = false)
    val customAvatarUri by userPrefs.customAvatarUri.collectAsState(initial = "")
    val effectiveAvatarUrl = remember(currentUser?.photoUrl, customAvatarUri) {
        when {
            customAvatarUri.isNotBlank() -> customAvatarUri
            !currentUser?.photoUrl.isNullOrBlank() -> currentUser?.photoUrl
            else -> null
        }
    }
    val restrictions by com.example.data.repository.UserSecurityManager.restrictionsFlow.collectAsState()
    val isPremiumUser = !isGuest && (
        restrictions.isAdFree ||
        restrictions.canonicalTier != com.example.data.model.CanonicalSubscriptionTier.FREE ||
        currentUser?.isPremium == true ||
        currentUser?.subscriptionTier?.uppercase() in listOf("PRO", "PRO_LITE", "PREMIUM", "VIP")
    )
    val planDisplayName = when {
        isGuest -> stringResource(R.string.free_account)
        restrictions.isSubscriptionExpired -> stringResource(R.string.subscription_expired)
        restrictions.canonicalTier == com.example.data.model.CanonicalSubscriptionTier.PRO ||
        currentUser?.subscriptionTier?.uppercase() == "PRO" -> stringResource(R.string.pro_title)
        restrictions.canonicalTier == com.example.data.model.CanonicalSubscriptionTier.PRO_LITE ||
        currentUser?.subscriptionTier?.uppercase() == "PRO_LITE" -> stringResource(R.string.pro_lite_title)
        currentUser?.subscriptionTier?.uppercase() == "VIP" -> "VIP"
        restrictions.isAdFree || currentUser?.isPremium == true -> stringResource(R.string.pro_title)
        else -> stringResource(R.string.free_title)
    }
    
    var isSearchExpanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var searchQuery by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var showLogoutDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showExitDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val backStack by navController.currentBackStack.collectAsState()
    var lastBackStackSize by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var backPressCount by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    
    androidx.compose.runtime.LaunchedEffect(backStack.size) {
        if (backStack.size > lastBackStackSize) {
            backPressCount = 0
        }
        lastBackStackSize = backStack.size
    }

    androidx.compose.runtime.LaunchedEffect(currentRoute) {
        isSearchExpanded = false
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        if (isSearchExpanded) {
            isSearchExpanded = false
            return@BackHandler
        }
        if (currentRoute == Screen.Home.route) {
            showExitDialog = true
        } else {
            if (backPressCount < 2) {
                backPressCount++
                navController.popBackStack()
                if (navController.previousBackStackEntry == null) {
                    showExitDialog = true
                }
            } else {
                backPressCount = 0
                navController.navigate(Screen.Home.route) {
                    popUpTo(navController.graph.startDestinationId) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
    }


    var isUpdatingData by remember { mutableStateOf(com.example.utils.NetworkUtils.isInternetAvailable(context)) }
    var updateFinishedShowGreen by remember { mutableStateOf(false) }
    
    androidx.compose.runtime.LaunchedEffect(updateFinishedShowGreen) {
        if (updateFinishedShowGreen) {
            kotlinx.coroutines.delay(2000)
            isUpdatingData = false
            updateFinishedShowGreen = false
        }
    }
    val bottomBarRoutes = listOf(Screen.Home.route, Screen.Movies.route, Screen.Search.route, Screen.Series.route, Screen.Anime.route)
    val hasTopBar = bottomBarRoutes.contains(currentRoute) || currentRoute in listOf(
        Screen.Library.route, Screen.Profile.route, Screen.Downloads.route, Screen.Settings.route, Screen.Extensions.route, Screen.Share.route, Screen.About.route, Screen.Social.route
    )

    val currentAppLayoutDirection = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(300.dp)
            ) {
                // Top Header Section
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface)
                            )
                        )
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        val displayName = if (isGuest || currentUser == null) stringResource(R.string.guest_user) else {
                            "${currentUser?.firstName ?: ""} ${currentUser?.lastName ?: ""}".trim().takeIf { it.isNotBlank() } ?: currentUser?.username ?: "User"
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = displayName,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (!isGuest && currentUser?.username?.isNotBlank() == true) {
                                    Text(
                                        text = "@${currentUser?.username}",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                androidx.compose.material3.Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isPremiumUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = BorderStroke(1.dp, if (isPremiumUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                    modifier = Modifier
                                        .clickable {
                                            scope.launch { drawerState.close() }
                                            navController.navigate(Screen.Subscription.route)
                                        }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isPremiumUser) Icons.Default.Star else Icons.Outlined.Info,
                                            contentDescription = null,
                                            tint = if (isPremiumUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = planDisplayName,
                                            fontSize = 12.5.sp,
                                            color = if (isPremiumUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            
                            Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(CircleShape)
                                    .background(if (effectiveAvatarUrl != null) Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                    .clickable { 
                                        scope.launch { drawerState.close() }
                                        navController.navigate(Screen.Profile.route)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (effectiveAvatarUrl != null) {
                                    AsyncImage(
                                        model = effectiveAvatarUrl,
                                        contentDescription = stringResource(R.string.avatar),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                                    )
                                } else if (!isGuest && displayName.isNotBlank() && displayName != stringResource(R.string.guest_user)) {
                                    Text(
                                        text = displayName.first().toString().uppercase(),
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    Icon(Icons.Default.Person, contentDescription = stringResource(R.string.avatar), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                                }
                            }
                        }
                    }
                }
                
                Column(modifier = Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text(stringResource(R.string.home), fontSize = 16.sp) },
                        selected = currentRoute == Screen.Home.route,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurface,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(16.dp),
                        onClick = {
                            scope.launch { drawerState.close() }
                            if (currentRoute == Screen.Home.route) {
                                homeViewModel.loadData(forceRefresh = true)
                            } else {
                                navController.navigate(Screen.Home.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Star, contentDescription = null) },
                        label = { Text(stringResource(R.string.subscription), fontSize = 16.sp) },
                        selected = currentRoute == Screen.Subscription.route,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurface,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(Screen.Subscription.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Favorite, contentDescription = null) },
                        label = { Text(stringResource(R.string.library), fontSize = 16.sp) },
                        selected = currentRoute == Screen.Library.route,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurface,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        onClick = {
                            scope.launch { drawerState.close() }
                            if (currentRoute == Screen.Library.route) {
                                navController.popBackStack(Screen.Library.route, inclusive = true)
                                navController.navigate(Screen.Library.route)
                            } else {
                                navController.navigate(Screen.Library.route)
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    val isAdminAuthority by com.example.data.repository.UserSecurityManager.isAdminAuthorityFlow.collectAsState()
                    if (isAdminAuthority) {
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                            label = { Text(stringResource(R.string.extensions), fontSize = 16.sp) },
                            selected = currentRoute == Screen.Extensions.route,
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                unselectedContainerColor = Color.Transparent,
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurface,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurface
                            ),
                            onClick = {
                                scope.launch { drawerState.close() }
                                navController.navigate(Screen.Extensions.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Person, contentDescription = null) },
                        label = { Text(stringResource(R.string.community), fontSize = 16.sp) },
                        selected = currentRoute == Screen.Social.route,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurface,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(Screen.Social.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                        label = { Text(stringResource(R.string.offline_share), fontSize = 16.sp) },
                        selected = currentRoute == Screen.Share.route,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurface,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(Screen.Share.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.settings), fontSize = 16.sp) },
                        selected = currentRoute == Screen.Settings.route,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurface,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(Screen.Settings.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                        label = { Text(stringResource(R.string.downloads), fontSize = 16.sp) },
                        selected = currentRoute == Screen.Downloads.route,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurface,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(Screen.Downloads.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                        label = { Text(stringResource(R.string.about_app), fontSize = 16.sp) },
                        selected = currentRoute == Screen.About.route,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurface,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(Screen.About.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    
                    NavigationDrawerItem(
                        icon = { Icon(Icons.AutoMirrored.Filled.Help, contentDescription = null) },
                        label = { Text(stringResource(R.string.help_support), fontSize = 16.sp) },
                        selected = false,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurface,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        onClick = { navController.navigate(Screen.HelpSupport.route); scope.launch { drawerState.close() } },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                
                // Bottom Area (Logout)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { 
                                scope.launch { drawerState.close() }
                                if (!isGuest && !com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                    android.widget.Toast.makeText(context, context.getString(R.string.cannot_logout_offline), android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    showLogoutDialog = true
                                }
                            }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (isGuest) stringResource(R.string.login) else stringResource(R.string.logout), fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Icon(if (isGuest) Icons.Default.Person else Icons.AutoMirrored.Filled.ExitToApp, contentDescription = if (isGuest) stringResource(R.string.login) else stringResource(R.string.logout), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    ) {
        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = { Text(stringResource(R.string.exit_app_title)) },
                text = { Text(stringResource(R.string.exit_app_desc)) },
                confirmButton = {
                    TextButton(onClick = {
                        val activity = context as? android.app.Activity
                        activity?.finish()
                    }) { Text(stringResource(R.string.yes), color = Color.Red) }
                },
                dismissButton = {
                    TextButton(onClick = { showExitDialog = false }) { Text(stringResource(R.string.no), color = MaterialTheme.colorScheme.onSurface) }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showLogoutDialog) {
            AlertDialog(
                onDismissRequest = { showLogoutDialog = false },
                title = { Text(stringResource(R.string.logout)) },
                text = { Text(stringResource(R.string.logout_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        if (!isGuest && !com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                            android.widget.Toast.makeText(context, context.getString(R.string.cannot_logout_offline), android.widget.Toast.LENGTH_SHORT).show()
                            showLogoutDialog = false
                            return@TextButton
                        }
                        showLogoutDialog = false
                        scope.launch { 
                            userPrefs.saveIsGuest(false)
                            userPrefs.saveIsLoggedIn(false)
                        }
                        authViewModel.signOut()
                        navController.navigate(Screen.Auth.route) { popUpTo(0) }
                    }) { Text(stringResource(R.string.yes), color = Color.Red) }
                },
                dismissButton = {
                    TextButton(onClick = { showLogoutDialog = false }) { Text(stringResource(R.string.no), color = MaterialTheme.colorScheme.onSurface) }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        CompositionLocalProvider(LocalLayoutDirection provides currentAppLayoutDirection) {
        Scaffold(

            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column {
                    if (hasTopBar) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!isSearchExpanded || currentRoute == Screen.Search.route) {
                                // Avatar
                                val headerDisplayName = if (isGuest || currentUser == null) stringResource(R.string.guest_user) else {
                                    "${currentUser?.firstName ?: ""} ${currentUser?.lastName ?: ""}".trim().takeIf { it.isNotBlank() } ?: currentUser?.username ?: "User"
                                }
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(if (effectiveAvatarUrl != null) Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                        .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                        .clickable {
                                            navController.navigate(Screen.Profile.route)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (effectiveAvatarUrl != null) {
                                        AsyncImage(
                                            model = effectiveAvatarUrl,
                                            contentDescription = stringResource(R.string.avatar),
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                                        )
                                    } else if (!isGuest && headerDisplayName.isNotBlank() && headerDisplayName != stringResource(R.string.guest_user)) {
                                        Text(
                                            text = headerDisplayName.first().toString().uppercase(),
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    } else {
                                        Icon(Icons.Default.Person, contentDescription = stringResource(R.string.avatar), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    }
                                }
                                
                                Spacer(modifier = Modifier.weight(1f))
                                
                                // App Title
                                val titleRes = when(currentRoute) {
                                    Screen.Home.route -> R.string.app_name
                                    Screen.Movies.route -> R.string.movies
                                    Screen.Series.route -> R.string.series
                                    Screen.Anime.route -> R.string.anime
                                    Screen.Search.route -> R.string.search
                                    Screen.Library.route -> R.string.library
                                    Screen.Profile.route -> R.string.profile
                                    Screen.Settings.route -> R.string.settings
                                    Screen.Downloads.route -> R.string.downloads
                                    Screen.About.route -> R.string.about
                                    Screen.Extensions.route -> R.string.extensions
                                    Screen.Share.route -> R.string.offline_share
                                    Screen.Social.route -> R.string.community
                                    else -> R.string.app_name
                                }
                                Text(
                                    text = stringResource(id = titleRes),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (titleRes == R.string.app_name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                                )
                                
                                Spacer(modifier = Modifier.weight(1f))
                                
                                // Search Icon (only visible when not already on Search screen)
                                if (currentRoute != Screen.Search.route) {
                                    androidx.compose.material3.IconButton(
                                        onClick = { isSearchExpanded = true }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = stringResource(R.string.search),
                                            tint = MaterialTheme.colorScheme.onBackground
                                        )
                                    }
                                }
                                
                                // Notification Icon with Badge
                                Box(
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .size(36.dp)
                                        .clickable { navController.navigate(Screen.Notifications.route) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Outlined.Notifications,
                                        contentDescription = stringResource(R.string.notifications),
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                    androidx.compose.material3.Badge(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = Color.White,
                                        modifier = Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 2.dp)
                                    ) {
                                        Text("1", fontSize = 10.sp)
                                    }
                                }
                                
                                // Menu Icon
                                androidx.compose.material3.IconButton(
                                    onClick = { scope.launch { drawerState.open() } }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Menu,
                                        contentDescription = stringResource(R.string.menu),
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            } else if (currentRoute != Screen.Search.route) {
                                ExpandableSearchBar(
                                    isExpanded = isSearchExpanded,
                                    onExpandedChange = { isSearchExpanded = it },
                                    onMovieClick = { id -> 
                                        isSearchExpanded = false
                                        navController.navigate(Screen.MovieDetails.createRoute(id))
                                    },
                                    onMovieClickWithMode = { id, expMode -> 
                                        isSearchExpanded = false
                                        navController.navigate(Screen.MovieDetails.createRoute(id, experienceMode = expMode))
                                    },
                                    onSeriesClick = { id -> 
                                        isSearchExpanded = false
                                        navController.navigate(Screen.SeriesDetails.createRoute(id))
                                    },
                                    onSeriesClickWithMode = { id, expMode -> 
                                        isSearchExpanded = false
                                        navController.navigate(Screen.SeriesDetails.createRoute(id, experienceMode = expMode))
                                    },
                                    onSearchSubmit = {
                                        isSearchExpanded = false
                                        if (currentRoute != Screen.Search.route) {
                                            navController.navigate(Screen.Search.route) {
                                                popUpTo(Screen.Home.route) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                    viewModel = searchViewModel
                                )
                            }
                        }
                    }
                }
                }
                }
            },
            bottomBar = {}
        ) { innerPadding ->
            val pagerRoutes = listOf(Screen.Home.route, Screen.Movies.route, Screen.Search.route, Screen.Series.route, Screen.Anime.route)
            val isBottomBarRoute = bottomBarRoutes.contains(currentRoute)
            val topPadding = innerPadding.calculateTopPadding()
            val bottomPadding = if (isBottomBarRoute) 0.dp else innerPadding.calculateBottomPadding()

            val baseNavHostModifier = if (hasTopBar) {
                Modifier.padding(top = topPadding, bottom = bottomPadding)
            } else {
                Modifier.padding(top = topPadding, bottom = bottomPadding)
                    .then(Modifier.windowInsetsPadding(WindowInsets.statusBars).consumeWindowInsets(WindowInsets.statusBars))
            }
            
            val navHostModifier = if (pagerRoutes.contains(currentRoute)) {
                baseNavHostModifier.swipeToNavigate(
                    onSwipeLeft = {
                        val idx = pagerRoutes.indexOf(currentRoute)
                        if (idx < pagerRoutes.size - 1) {
                            navController.navigate(pagerRoutes[idx + 1]) {
                                popUpTo(Screen.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    onSwipeRight = {
                        val idx = pagerRoutes.indexOf(currentRoute)
                        if (idx > 0) {
                            navController.navigate(pagerRoutes[idx - 1]) {
                                popUpTo(Screen.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                )
            } else {
                baseNavHostModifier
            }
            // Define Top-Level Routes that should use simple crossfade instead of slide
            val topLevelRoutes = listOf(
                Screen.Home.route, Screen.Movies.route, Screen.Search.route, 
                Screen.Series.route, Screen.Anime.route, Screen.Library.route, 
                Screen.Profile.route, Screen.Downloads.route, Screen.Settings.route, 
                Screen.Extensions.route, Screen.Share.route, Screen.About.route, 
                Screen.Social.route, Screen.Splash.route, Screen.Onboarding.route, Screen.Auth.route, Screen.Notifications.route
            )

            Box(modifier = Modifier.fillMaxSize()) {
                NavHost(
                navController = navController,
                startDestination = Screen.Splash.route,
                modifier = navHostModifier,
                enterTransition = { 
                    val route = targetState.destination.route ?: ""
                    val initialRoute = initialState.destination.route ?: ""
                    if (initialRoute == Screen.Splash.route && topLevelRoutes.any { r: String -> route.startsWith(r) }) {
                        androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(400))
                    } else if (topLevelRoutes.any { r: String -> route.startsWith(r) }) {
                        slideIntoContainer(
                            towards = androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Start,
                            animationSpec = androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f))
                        ) + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(200))
                    } else {
                        slideIntoContainer(
                            towards = androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Start, 
                            animationSpec = androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f))
                        ) + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(250))
                    }
                },
                exitTransition = { 
                    val route = targetState.destination.route ?: ""
                    val initialRoute = initialState.destination.route ?: ""
                    if (initialRoute == Screen.Splash.route) {
                        androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(400))
                    } else if (topLevelRoutes.any { r: String -> route.startsWith(r) }) {
                        slideOutOfContainer(
                            towards = androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Start,
                            animationSpec = androidx.compose.animation.core.tween(190, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f))
                        ) + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(170))
                    } else {
                        slideOutOfContainer(
                            towards = androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Start, 
                            animationSpec = androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f))
                        ) + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(200))
                    }
                },
                popEnterTransition = { 
                    val route = targetState.destination.route ?: ""
                    if (topLevelRoutes.any { r: String -> route.startsWith(r) }) {
                        slideIntoContainer(
                            towards = androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.End,
                            animationSpec = androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f))
                        ) + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(200))
                    } else {
                        slideIntoContainer(
                            towards = androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.End, 
                            animationSpec = androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f))
                        ) + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(250))
                    }
                },
                popExitTransition = { 
                    val route = targetState.destination.route ?: ""
                    if (topLevelRoutes.any { r: String -> route.startsWith(r) }) {
                        slideOutOfContainer(
                            towards = androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.End,
                            animationSpec = androidx.compose.animation.core.tween(190, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f))
                        ) + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(170))
                    } else {
                        slideOutOfContainer(
                            towards = androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.End, 
                            animationSpec = androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f))
                        ) + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(200))
                    }
                }
            ) {
                composable(Screen.Splash.route) {
                    SplashScreen(
                        onNavigateToOnboarding = {
                            navController.navigate(Screen.Onboarding.route) {
                                popUpTo(Screen.Splash.route) { inclusive = true }
                            }
                        },
                        onNavigateToAuth = {
                            navController.navigate(Screen.Auth.route) {
                                popUpTo(Screen.Splash.route) { inclusive = true }
                            }
                        },
                        onNavigateToMain = { startRoute ->
                            navController.navigate(startRoute) {
                                popUpTo(Screen.Splash.route) { inclusive = true }
                            }
                        }
                    )
                }
                composable(Screen.Onboarding.route) {
                    OnboardingScreen(
                        onComplete = {
                            navController.navigate(Screen.Auth.route) {
                                popUpTo(Screen.Onboarding.route) { inclusive = true }
                            }
                        }
                    )
                }
                composable(Screen.Auth.route) {
                    AuthScreen(
                        onSkip = {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Auth.route) { inclusive = true }
                            }
                        },
                        onAuthSuccess = {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Auth.route) { inclusive = true }
                            }
                        }
                    )
                }
                composable(Screen.Home.route) {
                    HomeScreen(
                        onMovieClick = { id -> 
                            navController.navigate(Screen.MovieDetails.createRoute(id)) 
                        },
                        onSeriesClick = { id -> 
                            navController.navigate(Screen.SeriesDetails.createRoute(id)) 
                        },
                        onPlayMovie = { id ->
                            navController.navigate(Screen.MovieDetails.createRoute(id, autoPlay = true))
                        },
                        onPlaySeries = { id ->
                            navController.navigate(Screen.SeriesDetails.createRoute(id, autoPlay = true))
                        },
                        onNavigateToTrending = { navController.navigate(Screen.Trending.route) },
                        onNavigateToWatching = { navController.navigate(Screen.Watching.route) },
                        onNavigateToPopular = { navController.navigate(Screen.Popular.route) },
                        onNavigateToNewReleases = { navController.navigate(Screen.NewReleases.route) },
                        onNavigateToUpcoming = { navController.navigate(Screen.Upcoming.route) },
                        onNavigateToAnime = { navController.navigate(Screen.Anime.route) },
                        viewModel = homeViewModel
                    )
                }
                composable(Screen.Movies.route) {
                    MoviesScreen(
                        onMovieClick = { id -> 
                            navController.navigate(Screen.MovieDetails.createRoute(id)) 
                        },
                        onPlayMovie = { id ->
                            navController.navigate(Screen.MovieDetails.createRoute(id, autoPlay = true))
                        },
                        onNavigateToTrending = { navController.navigate(Screen.Trending.route) },
                        onNavigateToWatching = { navController.navigate(Screen.Watching.route) },
                        onNavigateToPopular = { navController.navigate(Screen.Popular.route) },
                        onNavigateToNewReleases = { navController.navigate(Screen.NewReleases.route) },
                        onNavigateToUpcoming = { navController.navigate(Screen.Upcoming.route) },
                        viewModel = moviesViewModel
                    )
                }
                composable(Screen.Anime.route) {
                    AnimeScreen(
                        onAnimeClick = { seriesId ->
                            com.example.data.repository.AnimePlaybackStore.markAsAnime(context, seriesId)
                            navController.navigate(Screen.SeriesDetails.createRoute(seriesId))
                        },
                        onPlayAnime = { seriesId ->
                            com.example.data.repository.AnimePlaybackStore.markAsAnime(context, seriesId)
                            navController.navigate(Screen.SeriesDetails.createRoute(seriesId, autoPlay = true))
                        },
                        onNavigateToPopular = { navController.navigate(Screen.Popular.route) },
                        onNavigateToNewReleases = { navController.navigate(Screen.NewReleases.route) },
                        onNavigateToTrending = { navController.navigate(Screen.Trending.route) },
                        onNavigateToWatching = { navController.navigate(Screen.Watching.route) },
                        onNavigateToUpcoming = { navController.navigate(Screen.Upcoming.route) },
                        viewModel = animeViewModel
                    )
                }
                composable(Screen.Series.route) {
                    SeriesScreen(
                        onSeriesClick = { id -> 
                            navController.navigate(Screen.SeriesDetails.createRoute(id)) 
                        },
                        onPlaySeries = { id ->
                            navController.navigate(Screen.SeriesDetails.createRoute(id, autoPlay = true))
                        },
                        onNavigateToTrending = { navController.navigate(Screen.Trending.route) },
                        onNavigateToWatching = { navController.navigate(Screen.Watching.route) },
                        onNavigateToPopular = { navController.navigate(Screen.Popular.route) },
                        onNavigateToNewReleases = { navController.navigate(Screen.NewReleases.route) },
                        onNavigateToUpcoming = { navController.navigate(Screen.Upcoming.route) },
                        viewModel = seriesViewModel
                    )
                }
                composable(Screen.Search.route) {
                    SearchScreen(
                        onMediaClick = { id, isMovie ->
                            if (isMovie) {
                                navController.navigate(Screen.MovieDetails.createRoute(id))
                            } else {
                                navController.navigate(Screen.SeriesDetails.createRoute(id))
                            }
                        },
                        onMediaClickWithMode = { id, isMovie, expMode ->
                            if (isMovie) {
                                navController.navigate(Screen.MovieDetails.createRoute(id, experienceMode = expMode))
                            } else {
                                navController.navigate(Screen.SeriesDetails.createRoute(id, experienceMode = expMode))
                            }
                        },
                        onNavigateToTrending = { navController.navigate(Screen.Trending.route) },
                        viewModel = searchViewModel
                    )
                }
                composable(Screen.Library.route) {
                    LibraryScreen(
                        onItemClick = { id, isMovie ->
                            if (isMovie) {
                                navController.navigate(Screen.MovieDetails.createRoute(id))
                            } else {
                                navController.navigate(Screen.SeriesDetails.createRoute(id))
                            }
                        },
                        onItemClickWithMode = { id, isMovie, mode ->
                            if (isMovie) {
                                navController.navigate(Screen.MovieDetails.createRoute(id, experienceMode = mode))
                            } else {
                                navController.navigate(Screen.SeriesDetails.createRoute(id, experienceMode = mode))
                            }
                        }
                    )
                }
                            composable(Screen.Profile.route) {
                ProfileScreen(
                    authViewModel = authViewModel,
                    onNavigateToAuth = {
                        scope.launch {
                            userPrefs.saveIsGuest(false)
                            userPrefs.saveIsLoggedIn(false)
                        }
                        navController.navigate(Screen.Auth.route) { popUpTo(0) }
                    },
                    onNavigateToEditProfile = {
                        navController.navigate(Screen.EditProfile.route)
                    },
                    onNavigateToAccountSettings = {
                        navController.navigate(Screen.AccountSettings.route)
                    },
                    onNavigateToSecurity = {
                        navController.navigate(Screen.Security.route)
                    },
                    onNavigateToSubscription = {
                        navController.navigate(Screen.Subscription.route)
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onNavigateToHelpSupport = {
                        navController.navigate(Screen.HelpSupport.route)
                    },
                    onMediaClick = { id, isMovie ->
                        if (isMovie) {
                            navController.navigate(Screen.MovieDetails.createRoute(id))
                        } else {
                            navController.navigate(Screen.SeriesDetails.createRoute(id))
                        }
                    },
                    onMediaClickWithMode = { id, isMovie, mode ->
                        if (isMovie) {
                            navController.navigate(Screen.MovieDetails.createRoute(id, experienceMode = mode))
                        } else {
                            navController.navigate(Screen.SeriesDetails.createRoute(id, experienceMode = mode))
                        }
                    }
                )
            }
            composable(Screen.AccountSettings.route) {
                com.example.ui.screens.profile.AccountSettingsScreen(
                    onNavigateToEditProfile = { navController.navigate(Screen.EditProfile.route) },
                    onNavigateToSecurity = { navController.navigate(Screen.Security.route) },
                    onNavigateToSubscription = { navController.navigate(Screen.Subscription.route) },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToHelpSupport = { navController.navigate(Screen.HelpSupport.route) },
                    onBack = { navController.popBackStack() },
                    onSignOut = {
                        scope.launch { 
                            userPrefs.saveIsGuest(false)
                            userPrefs.saveIsLoggedIn(false)
                        }
                        authViewModel.signOut()
                        navController.navigate(Screen.Auth.route) { popUpTo(0) }
                    }
                )
            }
            composable(Screen.EditProfile.route) {
                com.example.ui.screens.profile.EditProfileScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            
            composable(Screen.PublicProfile.route) { backStackEntry ->
                val userId = backStackEntry.arguments?.getString("userId") ?: return@composable
                PublicProfileScreen(
                    userId = userId,
                    onBack = { navController.popBackStack() },
                    onChatSelected = { convId -> navController.navigate("chat/$convId") },
                    onMediaClick = { id, isMovie ->
                        if (isMovie) {
                            navController.navigate(Screen.MovieDetails.createRoute(id))
                        } else {
                            navController.navigate(Screen.SeriesDetails.createRoute(id))
                        }
                    },
                    onNavigateToBlockedUsers = {
                        navController.navigate(Screen.BlockedUsers.route)
                    }
                )
            }
            composable(Screen.BlockedUsers.route) {
                BlockedUsersScreen(
                    onBack = { navController.popBackStack() },
                    onUserClick = { targetUserId ->
                        navController.navigate(Screen.PublicProfile.createRoute(targetUserId))
                    }
                )
            }
                composable(Screen.Downloads.route) { 
                    DownloadsScreen(
                        onNavigateToHome = {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Home.route) { inclusive = true }
                            }
                        },
                        onItemClick = { id, isMovie, mode ->
                            val expMode = if (mode.equals("dubbed", ignoreCase = true) || mode.equals("arabic_dubbed", ignoreCase = true)) "DUBBED" else "STANDARD"
                            if (isMovie) {
                                navController.navigate(Screen.MovieDetails.createRoute(id, autoPlay = true, experienceMode = expMode))
                            } else {
                                navController.navigate(Screen.SeriesDetails.createRoute(id, autoPlay = true, experienceMode = expMode))
                            }
                        }
                    ) 
                }
                
            composable(Screen.Social.route) {
                SocialScreen(
                    onChatSelected = { convId -> navController.navigate("chat/$convId") },
                    onUserProfileClick = { userId -> navController.navigate(Screen.PublicProfile.createRoute(userId)) },
                    onBack = { navController.popBackStack() }
                )
            }
            composable("chat/{conversationId}") { backStackEntry ->
                val convId = backStackEntry.arguments?.getString("conversationId") ?: return@composable
                ChatScreen(
                    conversationId = convId,
                    onBack = { navController.popBackStack() },
                    onUserClick = { userId ->
                        navController.navigate(Screen.PublicProfile.createRoute(userId))
                    }
                )
            }
            composable(Screen.Share.route) {
                ShareScreen(
                    onBack = { navController.popBackStack() },
                    onItemClick = { id, isMovie ->
                        if (isMovie) {
                            navController.navigate(Screen.MovieDetails.createRoute(id))
                        } else {
                            navController.navigate(Screen.SeriesDetails.createRoute(id))
                        }
                    },
                    onNavigateToRecentTransfers = {
                        navController.navigate(Screen.RecentTransfers.route)
                    }
                )
            }
            composable(Screen.RecentTransfers.route) {
                com.example.ui.screens.share.RecentTransfersScreen(
                    onBack = { navController.popBackStack() },
                    onItemClick = { id, isMovie ->
                        if (isMovie) {
                            navController.navigate(Screen.MovieDetails.createRoute(id))
                        } else {
                            navController.navigate(Screen.SeriesDetails.createRoute(id))
                        }
                    }
                )
            }

            
                composable(Screen.Security.route) {
                    SecurityScreen(onBack = { navController.popBackStack() })
                }
                composable(Screen.Subscription.route) {
                    SubscriptionScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.Extensions.route) {
                    val isAdminAuthority by com.example.data.repository.UserSecurityManager.isAdminAuthorityFlow.collectAsState()
                    if (!isAdminAuthority) {
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Home.route) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    } else {
                        com.example.ui.screens.extensions.ExtensionsScreen(onBackClick = { navController.popBackStack() })
                    }
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        onNavigateToNotificationPreferences = { navController.navigate(Screen.NotificationPreferences.route) }
                    )
                }
                composable(Screen.NotificationPreferences.route) {
                    com.example.ui.screens.settings.NotificationPreferencesScreen(onBack = { navController.popBackStack() })
                }
                composable(Screen.HelpSupport.route) { com.example.ui.screens.profile.HelpSupportScreen(onBack = { navController.popBackStack() }) }
                composable(Screen.About.route) { AboutScreen() }
                composable(Screen.Notifications.route) { com.example.ui.screens.notifications.NotificationsScreen(onBack = { navController.popBackStack() }) }
                composable(Screen.Trending.route) {
                    TrendingScreen(
                        onItemClick = { id, isMovie ->
                            if (isMovie) {
                                navController.navigate(Screen.MovieDetails.createRoute(id))
                            } else {
                                navController.navigate(Screen.SeriesDetails.createRoute(id))
                            }
                        },
                        onBack = { navController.popBackStack() },
                        viewModel = homeViewModel
                    )
                }
                composable(Screen.Popular.route) {
                    PopularScreen(
                        onItemClick = { id, isMovie ->
                            if (isMovie) {
                                navController.navigate(Screen.MovieDetails.createRoute(id))
                            } else {
                                navController.navigate(Screen.SeriesDetails.createRoute(id))
                            }
                        },
                        onBack = { navController.popBackStack() },
                        viewModel = homeViewModel
                    )
                }
                composable(Screen.Upcoming.route) {
                    UpcomingScreen(
                        onItemClick = { id, isMovie ->
                            if (isMovie) {
                                navController.navigate(Screen.MovieDetails.createRoute(id))
                            } else {
                                navController.navigate(Screen.SeriesDetails.createRoute(id))
                            }
                        },
                        onBack = { navController.popBackStack() },
                        viewModel = homeViewModel
                    )
                }
                composable(Screen.NewReleases.route) {
                    NewReleasesScreen(
                        onItemClick = { id, isMovie ->
                            if (isMovie) {
                                navController.navigate(Screen.MovieDetails.createRoute(id))
                            } else {
                                navController.navigate(Screen.SeriesDetails.createRoute(id))
                            }
                        },
                        onBack = { navController.popBackStack() },
                        viewModel = homeViewModel
                    )
                }
                composable(Screen.Watching.route) {
                    WatchingScreen(
                        onItemClick = { id, isMovie ->
                            if (isMovie) {
                                navController.navigate(Screen.MovieDetails.createRoute(id))
                            } else {
                                navController.navigate(Screen.SeriesDetails.createRoute(id))
                            }
                        },
                        onPlayItem = { id, isMovie ->
                            if (isMovie) {
                                navController.navigate(Screen.MovieDetails.createRoute(id, autoPlay = true))
                            } else {
                                navController.navigate(Screen.SeriesDetails.createRoute(id, autoPlay = true))
                            }
                        },
                        onBack = { navController.popBackStack() }
                    )
                }
                
                composable("person/{personId}") { backStackEntry ->
                    val personId = backStackEntry.arguments?.getString("personId") ?: return@composable
                    com.example.ui.screens.details.PersonDetailsScreen(
                        personId = personId,
                        onBack = { navController.popBackStack() },
                        onMovieClick = { navController.navigate(Screen.MovieDetails.createRoute(it)) },
                        onSeriesClick = { navController.navigate(Screen.SeriesDetails.createRoute(it)) }
                    )
                }

                composable(Screen.MovieDetails.route) { backStackEntry ->
                    val rawMovieId = backStackEntry.arguments?.getString("movieId") ?: return@composable
                    val movieId = try { java.net.URLDecoder.decode(rawMovieId, "UTF-8") } catch (_: Exception) { rawMovieId }
                    val autoPlay = backStackEntry.arguments?.getString("autoPlay")?.toBoolean() ?: false
                    val experienceMode = backStackEntry.arguments?.getString("experienceMode") ?: "STANDARD"
                    MovieDetailsScreen(
                        movieId = movieId, 
                        autoPlay = autoPlay,
                        initialExperienceMode = experienceMode,
                        onBack = { navController.popBackStack() },
                        onPersonClick = { personId -> navController.navigate("person/$personId") },
                        onNavigateToExtensions = { navController.navigate(Screen.Extensions.route) },
                        onPlay = { title, url, server, website, posterUrl, mode, providerId -> 
                            if (url.startsWith("trailer:")) {
                                if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                    Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                                } else {
                                    val trailerId = url.removePrefix("trailer:")
                                    navController.navigate("trailer/$trailerId")
                                }
                            } else {
                                val encodedUrl = URLEncoder.encode(url, "UTF-8")
                                val encodedTitle = URLEncoder.encode(title, "UTF-8")
                                val encodedServer = URLEncoder.encode(server ?: "", "UTF-8")
                                val encodedWebsite = URLEncoder.encode(website ?: "", "UTF-8")
                                val encodedPoster = java.net.URLEncoder.encode(posterUrl, "UTF-8")
                                val encodedMediaId = java.net.URLEncoder.encode(movieId, "UTF-8")
                                val encodedMode = URLEncoder.encode(mode ?: "subtitled", "UTF-8")
                                val encodedProviderId = URLEncoder.encode(providerId ?: "", "UTF-8")
                                navController.navigate("player?mediaId=$encodedMediaId&isMovie=true&title=$encodedTitle&url=$encodedUrl&server=$encodedServer&website=$encodedWebsite&poster=$encodedPoster&contentType=movie&mode=$encodedMode&providerId=$encodedProviderId")
                            }
                        }
                    )
                }
                composable(Screen.SeriesDetails.route) { backStackEntry ->
                    val rawSeriesId = backStackEntry.arguments?.getString("seriesId") ?: return@composable
                    val seriesId = try { java.net.URLDecoder.decode(rawSeriesId, "UTF-8") } catch (_: Exception) { rawSeriesId }
                    val autoPlay = backStackEntry.arguments?.getString("autoPlay")?.toBoolean() ?: false
                    val experienceMode = backStackEntry.arguments?.getString("experienceMode") ?: "STANDARD"
                    SeriesDetailsScreen(
                        seriesId = seriesId, 
                        autoPlay = autoPlay,
                        initialExperienceMode = experienceMode,
                        onBack = { navController.popBackStack() },
                        onPersonClick = { personId -> navController.navigate("person/$personId") },
                        onNavigateToExtensions = { navController.navigate(Screen.Extensions.route) },
                        onPlay = { title, url, server, website, posterUrl, contentType, episodeId, mode, providerId -> 
                            if (url.startsWith("trailer:")) {
                                if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                    Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                                } else {
                                    val trailerId = url.removePrefix("trailer:")
                                    navController.navigate("trailer/$trailerId")
                                }
                            } else {
                                val encodedUrl = URLEncoder.encode(url, "UTF-8")
                                val encodedTitle = URLEncoder.encode(title, "UTF-8")
                                val encodedServer = URLEncoder.encode(server ?: "", "UTF-8")
                                val encodedWebsite = URLEncoder.encode(website ?: "", "UTF-8")
                                val encodedPoster = java.net.URLEncoder.encode(posterUrl, "UTF-8")
                                val encodedContentType = URLEncoder.encode(contentType ?: "", "UTF-8")
                                val encodedMediaId = java.net.URLEncoder.encode(seriesId, "UTF-8")
                                val encodedEpisodeId = URLEncoder.encode(episodeId ?: "", "UTF-8")
                                val encodedMode = URLEncoder.encode(mode ?: "subtitled", "UTF-8")
                                val encodedProviderId = URLEncoder.encode(providerId ?: "", "UTF-8")
                                navController.navigate("player?mediaId=$encodedMediaId&episodeId=$encodedEpisodeId&isMovie=false&title=$encodedTitle&url=$encodedUrl&server=$encodedServer&website=$encodedWebsite&poster=$encodedPoster&contentType=$encodedContentType&mode=$encodedMode&providerId=$encodedProviderId")
                            }
                        }
                    )
                }
                

                composable(
                    route = "player?mediaId={mediaId}&episodeId={episodeId}&isMovie={isMovie}&title={title}&url={url}&server={server}&website={website}&poster={poster}&contentType={contentType}&mode={mode}&providerId={providerId}",
                    arguments = listOf(
                        androidx.navigation.navArgument("mediaId") { defaultValue = ""; nullable = true },
                        androidx.navigation.navArgument("episodeId") { defaultValue = ""; nullable = true },
                        androidx.navigation.navArgument("isMovie") { defaultValue = "true"; nullable = true },
                        androidx.navigation.navArgument("title") { defaultValue = ""; nullable = true },
                        androidx.navigation.navArgument("url") { defaultValue = ""; nullable = true },
                        androidx.navigation.navArgument("server") { defaultValue = ""; nullable = true },
                        androidx.navigation.navArgument("website") { defaultValue = ""; nullable = true },
                        androidx.navigation.navArgument("poster") { defaultValue = ""; nullable = true },
                        androidx.navigation.navArgument("contentType") { defaultValue = ""; nullable = true },
                        androidx.navigation.navArgument("mode") { defaultValue = "subtitled"; nullable = true },
                        androidx.navigation.navArgument("providerId") { defaultValue = ""; nullable = true }
                    )
                ) { backStackEntry ->
                    val rawMediaId = backStackEntry.arguments?.getString("mediaId") ?: ""
                    val mediaId = try { URLDecoder.decode(rawMediaId, "UTF-8") } catch (_: Exception) { rawMediaId }
                    val episodeId = backStackEntry.arguments?.getString("episodeId") ?: ""
                    val isMovieStr = backStackEntry.arguments?.getString("isMovie") ?: "true"
                    val isMovie = isMovieStr.toBoolean()
                    val title = backStackEntry.arguments?.getString("title") ?: stringResource(R.string.unknown)
                    val url = backStackEntry.arguments?.getString("url") ?: ""
                    val server = backStackEntry.arguments?.getString("server") ?: ""
                    val website = backStackEntry.arguments?.getString("website") ?: ""
                    val poster = backStackEntry.arguments?.getString("poster") ?: ""
                    val contentType = backStackEntry.arguments?.getString("contentType") ?: ""
                    val rawMode = backStackEntry.arguments?.getString("mode") ?: "subtitled"
                    val mode = try { URLDecoder.decode(rawMode, "UTF-8") } catch (_: Exception) { rawMode }
                    val rawProviderId = backStackEntry.arguments?.getString("providerId") ?: ""
                    val providerId = if (rawProviderId.isNotBlank()) try { URLDecoder.decode(rawProviderId, "UTF-8").ifBlank { null } } catch (_: Exception) { rawProviderId } else null
                    
                    val decodedTitle = URLDecoder.decode(title, "UTF-8")
                    val decodedUrl = if (url.isNotEmpty()) URLDecoder.decode(url, "UTF-8") else ""
                    val decodedServer = if (server.isNotEmpty()) URLDecoder.decode(server, "UTF-8") else ""
                    val decodedWebsite = if (website.isNotEmpty()) URLDecoder.decode(website, "UTF-8") else ""
                    val decodedPoster = if (poster.isNotEmpty()) URLDecoder.decode(poster, "UTF-8") else ""
                    val decodedContentType = if (contentType.isNotEmpty()) URLDecoder.decode(contentType, "UTF-8").ifBlank { null } else null
                    
                    com.example.ui.screens.player.PlayerScreen(
                        mediaId = mediaId,
                        episodeId = episodeId,
                        isMovie = isMovie,
                        title = decodedTitle,
                        posterUrl = decodedPoster,
                        url = decodedUrl,
                        targetServer = decodedServer,
                        website = decodedWebsite,
                        contentType = decodedContentType,
                        mode = mode.ifBlank { "subtitled" },
                        providerId = providerId,
                        onBack = { navController.popBackStack() },
                        onNavigateToExtensions = { navController.navigate(Screen.Extensions.route) }
                    )
                }

                composable("trailer/{videoId}") { backStackEntry ->
                    val videoId = backStackEntry.arguments?.getString("videoId") ?: return@composable
                    if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                        Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                            com.example.ui.components.InlineYouTubePlayer(
                                videoId = videoId,
                                modifier = Modifier.fillMaxSize()
                            )
                            IconButton(
                                onClick = { navController.popBackStack() },
                                modifier = Modifier.padding(16.dp).align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                            }
                        }
                    }
                }
            }

            // Floating Bottom Navigation Bar with transparent margins
            if (isBottomBarRoute) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                ) {
                    com.example.ui.components.BottomNavBar(
                        navController = navController,
                        onNavigateToItem = { targetScreen ->
                            if (currentRoute == Screen.Search.route && targetScreen != Screen.Search) {
                                searchViewModel.resetTransientSearchState()
                            }
                        },
                        onReselectItem = { screen ->
                            when (screen) {
                                Screen.Home -> homeViewModel.loadData(forceRefresh = true)
                                Screen.Movies -> moviesViewModel.loadMovies(forceRefresh = true)
                                Screen.Search -> searchViewModel.refresh()
                                Screen.Series -> seriesViewModel.loadSeries(forceRefresh = true)
                                Screen.Anime -> animeViewModel.loadData(forceRefresh = true)
                                else -> {}
                            }
                        }
                    )
                }
            }
        }
    }
        }
    }
}
}