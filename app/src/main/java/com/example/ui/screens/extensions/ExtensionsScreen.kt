package com.example.ui.screens.extensions

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.domain.models.GroupStatus
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.GlobalExtensionConfigState
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.registry.ManagedExtensionRuntimeRegistry
import com.example.extension.managed.searchorder.SearchOrder
import com.example.ui.components.ExtensionsScreenSkeleton
import kotlinx.coroutines.delay

enum class ExtensionFilter(@androidx.annotation.StringRes val titleRes: Int, val icon: ImageVector) {
    ALL(R.string.filter_all, Icons.Filled.GridView),
    ANIME(R.string.anime, Icons.Default.Face),
    MOVIES(R.string.movies, Icons.Outlined.Movie),
    SERIES(R.string.series, Icons.Outlined.Tv)
}

/**
 * Phase 05M: ExtensionsScreen
 *
 * Enforces Phase 05M Requirements:
 * 1. Directly consumes ManagedExtensionRuntimeRegistry (Local Runtime Snapshot).
 * 2. Displays live updates from Firestore Realtime Listener automatically without app restarts.
 * 3. Incorporates dedicated Search Order section categorized by Anime, Movies, Series, TV.
 * 4. Read-only presentation: Admin has exclusive authority over status and ordering.
 * 5. Visual live indicators (● ACTIVE / ○ DISABLED) reflecting real-time backend state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(onBackClick: () -> Unit) {
    var isLoading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(200)
        isLoading = false
    }

    if (isLoading) {
        ExtensionsScreenSkeleton()
        return
    }

    val registry = remember { ManagedExtensionRuntimeRegistry.INSTANCE }
    val runtimeSnapshot by registry.snapshotFlow.collectAsState()
    val managedExtensions = runtimeSnapshot.extensions
    val searchOrder = runtimeSnapshot.searchOrder

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var selectedFilter by remember { mutableStateOf(ExtensionFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var showSecurityBanner by remember { mutableStateOf(true) }

    val filteredManagedExtensions = managedExtensions.filter { ext ->
        val matchesSearch = ext.name.contains(searchQuery, ignoreCase = true) ||
            ext.baseUrl.contains(searchQuery, ignoreCase = true) ||
            ext.description.contains(searchQuery, ignoreCase = true)
        val matchesFilter = when (selectedFilter) {
            ExtensionFilter.ALL -> true
            ExtensionFilter.ANIME -> ext.contentTypes.contains(ContentType.ANIME)
            ExtensionFilter.MOVIES -> ext.contentTypes.contains(ContentType.MOVIE)
            ExtensionFilter.SERIES -> ext.contentTypes.contains(ContentType.SERIES)
        }
        matchesSearch && matchesFilter
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "الامتدادات المدارة",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (runtimeSnapshot.state) {
                                            GlobalExtensionConfigState.READY -> Color(0xFF4CAF50)
                                            GlobalExtensionConfigState.REMOTE_SYNC_PENDING -> Color(0xFFFF9800)
                                            GlobalExtensionConfigState.GLOBAL_CONFIG_UNAVAILABLE -> Color(0xFFF44336)
                                        }
                                    )
                            )
                            Text(
                                text = when (runtimeSnapshot.state) {
                                    GlobalExtensionConfigState.READY -> "مزامنة لحظية نشطة"
                                    GlobalExtensionConfigState.REMOTE_SYNC_PENDING -> "جاري الاتصال بالسحابة..."
                                    GlobalExtensionConfigState.GLOBAL_CONFIG_UNAVAILABLE -> "الإعدادات غير متوفرة"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Tabs Row: Extensions vs Search Order
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)) }
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Filled.GridView, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("الامتدادات (${managedExtensions.size})", fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.FormatListNumbered, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("ترتيب البحث (Search Order)", fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
            }

            if (selectedTabIndex == 0) {
                // Tab 0: Extensions List
                Column(modifier = Modifier.fillMaxSize()) {
                    // Search Bar
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.search_extensions)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.search),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.clear),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    // Filter Chips Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ExtensionFilter.values().forEach { filter ->
                            val isSelected = selectedFilter == filter
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedFilter = filter },
                                label = { Text(stringResource(filter.titleRes), fontSize = 13.sp) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = filter.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = Color.White,
                                    selectedLeadingIconColor = Color.White
                                )
                            )
                        }
                    }

                    // Security Notice Banner
                    AnimatedVisibility(
                        visible = showSecurityBanner,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "نظام الامتدادات المدارة سحابياً",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "تحديثات المشرف تطبق لحظياً دون الحاجة لإعادة تشغيل التطبيق أو الضغط على تحديث.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }

                            IconButton(
                                onClick = { showSecurityBanner = false },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.close),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Managed Extensions List
                if (filteredManagedExtensions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ExtensionOff,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "لا توجد امتدادات مدارة مطابقة للبحث",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(filteredManagedExtensions, key = { it.id }) { ext ->
                            ManagedExtensionItem(ext = ext)
                        }
                    }
                }
            }
        } else {
            // Tab 1: Search Order Section (Phase 05M Rule 16)
            SearchOrderTabContent(
                searchOrder = searchOrder,
                allExtensions = managedExtensions
            )
        }
    }
}
}

/**
 * Phase 05M Rule 16: Canonical Search Order presentation categorized by Anime, Movies, Series, TV.
 * Strictly read-only for Users App.
 */
@Composable
fun SearchOrderTabContent(
    searchOrder: SearchOrder,
    allExtensions: List<ManagedExtension>
) {
    val extensionsMap = remember(allExtensions) { allExtensions.associateBy { it.id.lowercase() } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "ترتيب التشغيل المعتمد سحابياً (Search Order)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "المصدر الحصري لترتيب مصادر التشغيل والتحميل في التطبيق. يُدار مركزياً عبر المشرف للقراءة فقط.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        // 1. Anime Category
        item {
            SearchOrderCategoryCard(
                title = "الأنمي (ANIME)",
                icon = Icons.Default.Face,
                orderedIds = searchOrder.anime,
                extensionsMap = extensionsMap
            )
        }

        // 2. Movies Category
        item {
            SearchOrderCategoryCard(
                title = "الأفلام (MOVIES)",
                icon = Icons.Outlined.Movie,
                orderedIds = searchOrder.movie,
                extensionsMap = extensionsMap
            )
        }

        // 3. Series Category
        item {
            SearchOrderCategoryCard(
                title = "المسلسلات (SERIES)",
                icon = Icons.Outlined.Tv,
                orderedIds = searchOrder.series,
                extensionsMap = extensionsMap
            )
        }

        // 4. TV Category
        item {
            SearchOrderCategoryCard(
                title = "البث التلفزيوني (TV)",
                icon = Icons.Default.LiveTv,
                orderedIds = searchOrder.tv ?: emptyList(),
                extensionsMap = extensionsMap
            )
        }

        // 5. Arabic Dubbed Category
        item {
            SearchOrderCategoryCard(
                title = "المدبلج للعربية (ARABIC DUBBED)",
                icon = Icons.Default.RecordVoiceOver,
                orderedIds = searchOrder.arabicDubbed,
                extensionsMap = extensionsMap,
                groupStatus = searchOrder.arabicDubbedStatus
            )
        }

        // 6. English Dubbed Category
        item {
            SearchOrderCategoryCard(
                title = "المدبلج للإنجليزية (ENGLISH DUBBED)",
                icon = Icons.Default.Language,
                orderedIds = searchOrder.englishDubbed,
                extensionsMap = extensionsMap,
                groupStatus = searchOrder.englishDubbedStatus
            )
        }
    }
}

@Composable
fun SearchOrderCategoryCard(
    title: String,
    icon: ImageVector,
    orderedIds: List<String>,
    extensionsMap: Map<String, ManagedExtension>,
    groupStatus: GroupStatus? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.weight(1f))
                if (groupStatus != null) {
                    val (statusText, statusBg, statusColor) = when (groupStatus) {
                        GroupStatus.ACTIVE -> Triple("نشط", Color(0xFF2E7D32).copy(alpha = 0.15f), Color(0xFF4CAF50))
                        GroupStatus.MAINTENANCE -> Triple("صيانة", Color(0xFFEF6C00).copy(alpha = 0.15f), Color(0xFFFF9800))
                        GroupStatus.DISABLED -> Triple("معطل", Color(0xFFC62828).copy(alpha = 0.15f), Color(0xFFF44336))
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(statusBg)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = statusText,
                            fontSize = 11.sp,
                            color = statusColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${orderedIds.size} مصادر",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(10.dp))

            if (orderedIds.isEmpty()) {
                Text(
                    text = "لم يتم تحديد ترتيب لهذه الفئة في إعدادات الإدارة",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val numbers = listOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩")
                    orderedIds.forEachIndexed { index, id ->
                        val ext = extensionsMap[id.lowercase()]
                        val isActive = ext?.status == ExtensionLifecycleStatus.ACTIVE
                        val prefix = if (index < numbers.size) numbers[index] else "${index + 1}."

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = prefix,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ext?.name ?: id,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = ext?.baseUrl ?: "معرف الإضافة: $id",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Live Status indicator badge
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isActive) Color(0xFF4CAF50).copy(alpha = 0.15f)
                                        else Color(0xFFF44336).copy(alpha = 0.15f)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(if (isActive) Color(0xFF4CAF50) else Color(0xFFF44336))
                                    )
                                    Text(
                                        text = if (isActive) "ACTIVE" else "DISABLED",
                                        color = if (isActive) Color(0xFF4CAF50) else Color(0xFFF44336),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Phase 05M Rule 17 & 27: Extension Card with Live Status indicator dot (● ACTIVE / ○ DISABLED)
 * and read-only architecture (no user toggles).
 */
@Composable
fun ManagedExtensionItem(
    ext: ManagedExtension
) {
    val isGloballyActive = ext.status == ExtensionLifecycleStatus.ACTIVE

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGloballyActive) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = BorderStroke(
            1.dp,
            if (isGloballyActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Official Shield Icon
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = "Official Managed Extension",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = ext.name,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "رسمي",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        text = ext.baseUrl,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Admin live availability badge (Rule 17: ● ACTIVE / ○ DISABLED)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isGloballyActive) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color(0xFFF44336).copy(alpha = 0.15f))
                        .border(1.dp, if (isGloballyActive) Color(0xFF4CAF50).copy(alpha = 0.35f) else Color(0xFFF44336).copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isGloballyActive) "● ACTIVE" else "○ DISABLED",
                        color = if (isGloballyActive) Color(0xFF4CAF50) else Color(0xFFF44336),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (ext.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = ext.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Badges row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (statusText, statusColor, statusBg) = when (ext.status) {
                    ExtensionLifecycleStatus.ACTIVE ->
                        Triple("نشط سحابياً", Color(0xFF4CAF50), Color(0xFF4CAF50).copy(alpha = 0.15f))
                    ExtensionLifecycleStatus.MAINTENANCE ->
                        Triple("تحت الصيانة", Color(0xFFFF9800), Color(0xFFFF9800).copy(alpha = 0.15f))
                    ExtensionLifecycleStatus.DISABLED ->
                        Triple("معطل من المشرف", Color(0xFFF44336), Color(0xFFF44336).copy(alpha = 0.15f))
                    ExtensionLifecycleStatus.DEPRECATED ->
                        Triple("موقوف نهائياً", Color(0xFF9E9E9E), Color(0xFF9E9E9E).copy(alpha = 0.15f))
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusBg)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(text = statusText, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(text = "أولوية: ${ext.priority} (إعلامي)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(text = "إصدار ${ext.definitionVersion} (API ${ext.runtimeApiVersion})", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }

                for (ct in ext.contentTypes) {
                    val label = when (ct) {
                        ContentType.MOVIE -> "أفلام"
                        ContentType.SERIES -> "مسلسلات"
                        ContentType.ANIME -> "أنمي"
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(text = label, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
