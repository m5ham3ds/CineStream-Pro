package com.example.ui.screens.details
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

import androidx.compose.ui.res.stringResource
import com.example.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.ui.components.shimmerEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.domain.models.Movie
import com.example.domain.models.Series
import com.example.ui.ViewModelFactory
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailsScreen(
    personId: String,
    onBack: () -> Unit,
    onMovieClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    viewModel: PersonDetailsViewModel = viewModel(factory = ViewModelFactory())
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(personId) {
        viewModel.loadPerson(personId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.person?.name ?: "", color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { /* Share */ },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .size(36.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share), tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(18.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                windowInsets = WindowInsets(0.dp)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header profile skeleton
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .shimmerEffect()
                )
                Spacer(modifier = Modifier.height(16.dp))
                // Name skeleton
                Box(modifier = Modifier.width(180.dp).height(28.dp).clip(RoundedCornerShape(8.dp)).shimmerEffect())
                Spacer(modifier = Modifier.height(8.dp))
                // Known for skeleton
                Box(modifier = Modifier.width(120.dp).height(16.dp).clip(RoundedCornerShape(8.dp)).shimmerEffect())
                Spacer(modifier = Modifier.height(24.dp))
                
                // Info row skeleton
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Box(modifier = Modifier.width(80.dp).height(60.dp).clip(RoundedCornerShape(12.dp)).shimmerEffect())
                    Box(modifier = Modifier.width(80.dp).height(60.dp).clip(RoundedCornerShape(12.dp)).shimmerEffect())
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                // Biography skeleton
                Box(modifier = Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth(0.7f).height(16.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                
                Spacer(modifier = Modifier.height(32.dp))
                // Known for list skeleton
                Box(modifier = Modifier.width(120.dp).height(24.dp).clip(RoundedCornerShape(8.dp)).shimmerEffect().align(Alignment.Start))
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.width(110.dp).height(160.dp).clip(RoundedCornerShape(12.dp)).shimmerEffect())
                    Box(modifier = Modifier.width(110.dp).height(160.dp).clip(RoundedCornerShape(12.dp)).shimmerEffect())
                    Box(modifier = Modifier.width(110.dp).height(160.dp).clip(RoundedCornerShape(12.dp)).shimmerEffect())
                }
            }
        } else if (uiState.person != null) {
            val person = uiState.person!!
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header Profile
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // Portrait Image
                    Box(
                        modifier = Modifier
                            .weight(0.42f)
                            .aspectRatio(0.7f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
                        AsyncImage(
                            model = person.profileUrl,
                            placeholder = androidx.compose.ui.graphics.painter.ColorPainter(placeholderColor),
                            error = androidx.compose.ui.graphics.painter.ColorPainter(placeholderColor),
                            contentDescription = person.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        // Gallery Icon
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = stringResource(R.string.gallery), tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(16.dp))
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))
                    
                    // Info Section
                    Column(
                        modifier = Modifier.weight(0.58f)
                    ) {
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                            Column(horizontalAlignment = Alignment.Start) {
                                val names = person.name.split(" ", limit = 2)
                                val firstName = names.getOrNull(0) ?: ""
                                val lastName = names.getOrNull(1) ?: ""
                                
                                Text(firstName, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, lineHeight = 32.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(lastName, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, lineHeight = 32.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.verified), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(person.knownFor ?: stringResource(R.string.actor), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Info Grid - Changed to vertical list for better text alignment
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Born
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(Icons.Default.CalendarToday, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(stringResource(R.string.born), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                                        Text(person.birthday ?: "-", fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            // Birthplace
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(stringResource(R.string.birthplace), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                                        Text(person.placeOfBirth ?: "-", fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Stats Row
                val firstCreditYear = (person.movies.map { it.year } + person.series.map { it.year }).filter { it > 0 }.minOrNull()
                val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                val yearsActive = if (firstCreditYear != null && firstCreditYear > 0) {
                    currentYear - firstCreditYear
                } else {
                    0
                }
                val yearsActiveStr = if (yearsActive > 0) "${yearsActive}+" else "-"
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatBox(icon = Icons.Default.Movie, value = if (person.movies.isNotEmpty()) "${person.movies.size}+" else "-", label = stringResource(R.string.movies), modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    StatBox(icon = Icons.Default.Tv, value = if (person.series.isNotEmpty()) "${person.series.size}+" else "-", label = stringResource(R.string.series), modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    StatBox(icon = Icons.Outlined.Star, value = yearsActiveStr, label = stringResource(R.string.years_active), modifier = Modifier.weight(1f))
                }
                
                // Biography
                if (person.biography.isNotBlank()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.width(3.dp).height(16.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.biography), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            var isBioExpanded by remember { mutableStateOf(false) }
                            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                                Text(
                                    text = person.biography, 
                                    color = Color.LightGray, 
                                    fontSize = 14.sp, 
                                    lineHeight = 20.sp,
                                    maxLines = if (isBioExpanded) Int.MAX_VALUE else 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (isBioExpanded) stringResource(R.string.read_less) else stringResource(R.string.read_more),
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { isBioExpanded = !isBioExpanded }.padding(vertical = 4.dp)
                            )
                        }
                    }
                }

                // Movies
                if (person.movies.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    SectionHeader(stringResource(R.string.top_movies))
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(person.movies, key = { it.id }) { movie ->
                            MediaCreditCard(title = movie.title, posterUrl = movie.posterUrl, year = movie.year, onClick = { onMovieClick(movie.id) })
                        }
                    }
                }

                // Series
                if (person.series.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    SectionHeader(stringResource(R.string.top_shows))
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(person.series, key = { it.id }) { series ->
                            MediaCreditCard(title = series.title, posterUrl = series.posterUrl, year = series.year, onClick = { onSeriesClick(series.id) })
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        } else if (uiState.error != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = uiState.error ?: stringResource(R.string.person_not_found),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { viewModel.loadPerson(personId, force = true) }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
        }
    }
}

@Composable
fun StatBox(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        }
        Text(stringResource(R.string.view_all_small), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { })
    }
}

@Composable
fun MediaCreditCard(title: String, posterUrl: String, year: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(110.dp).clickable { onClick() }
    ) {
        AsyncImage(
            model = posterUrl,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f/3f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (year > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = year.toString(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}