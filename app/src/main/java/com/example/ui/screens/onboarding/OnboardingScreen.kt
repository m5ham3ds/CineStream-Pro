package com.example.ui.screens.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Hd
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.repository.UserPreferencesRepository
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val userPrefs = UserPreferencesRepository(context)
    val pagerState = rememberPagerState(pageCount = { 3 })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Pager for Backgrounds and specific content
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            Box(modifier = Modifier.fillMaxSize()) {
                // Background Image
                val bgRes = when (page) {
                    0 -> R.drawable.onboarding_bg_1
                    1 -> R.drawable.onboarding_bg_2
                    else -> R.drawable.onboarding_bg_3
                }
                Image(
                    painter = painterResource(id = bgRes),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(radius = 24.dp)
                )
                // Additional tint layer
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)))
                
                // Gradients for readability
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color.Black.copy(alpha = 0.9f),
                                0.25f to Color.Black.copy(alpha = 0.3f),
                                0.6f to Color.Transparent,
                                0.8f to Color.Black.copy(alpha = 0.7f),
                                1.0f to Color.Black
                            )
                        )
                )

                // Page Specific Content
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 280.dp, bottom = 160.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    when (page) {
                        0 -> PageOneContent()
                        1 -> PageTwoContent()
                        2 -> PageThreeContent()
                    }
                }
            }
        }

        // Top Fixed Content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.welcome_to),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal
            )
            Text(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Cine") }
                    withStyle(style = SpanStyle(color = Color.White)) { append("Stream") }
                },
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Box(modifier = Modifier.width(32.dp).height(2.dp).background(MaterialTheme.colorScheme.primary))
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.slogan_new),
                color = Color.LightGray,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }

        // Bottom Fixed Content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Pager Dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { index ->
                    val isSelected = pagerState.currentPage == index
                    val color = if (isSelected) MaterialTheme.colorScheme.primary else Color.DarkGray
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Button
            Button(
                onClick = {
                    if (pagerState.currentPage < 2) {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    } else {
                        scope.launch {
                            userPrefs.saveOnboardingCompleted(true)
                            onComplete()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(28.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                // Button gradient effect
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (pagerState.currentPage < 2) stringResource(R.string.onboarding_next) else stringResource(R.string.onboarding_get_started),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(color = Color.Gray)) {
                        append(stringResource(R.string.already_have_account) + " ")
                    }
                    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                        append(stringResource(R.string.sign_in))
                    }
                },
                fontSize = 14.sp,
                modifier = Modifier.clickable {
                    scope.launch {
                        userPrefs.saveOnboardingCompleted(true)
                        onComplete()
                    }
                }
            )
        }
    }
}

@Composable
fun PageOneContent() {
    Box(modifier = Modifier.fillMaxSize()) {
        // Floating side texts
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .align(Alignment.Center), // middle of the specific content area
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.good_stories_never_end).replace(" ", "\n"), color = Color.Gray, fontSize = 9.sp, letterSpacing = 2.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.width(20.dp).height(1.dp).background(MaterialTheme.colorScheme.primary))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text((stringResource(R.string.movies) + "\n" + stringResource(R.string.category_series) + "\n" + stringResource(R.string.category_anime)).uppercase(), color = Color.Gray, fontSize = 9.sp, letterSpacing = 2.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.width(20.dp).height(1.dp).background(MaterialTheme.colorScheme.primary))
            }
        }
        
        // 3 Cards Box at bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .align(Alignment.BottomCenter)
                .border(1.dp, Color.DarkGray.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f), RoundedCornerShape(20.dp))
                .padding(vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FeatureItem(icon = Icons.Outlined.Hd, text = stringResource(R.string.hd_quality), iconTint = MaterialTheme.colorScheme.primary, isBoxed = true)
                Box(modifier = Modifier.width(1.dp).height(40.dp).background(Color.DarkGray.copy(alpha=0.5f)))
                FeatureItem(icon = Icons.Outlined.Movie, text = stringResource(R.string.movies_and_series), iconTint = Color.LightGray)
                Box(modifier = Modifier.width(1.dp).height(40.dp).background(Color.DarkGray.copy(alpha=0.5f)))
                FeatureItem(icon = Icons.Outlined.Monitor, text = stringResource(R.string.anytime_anywhere), iconTint = Color.LightGray) // Changed icon for anytime anywhere to monitor
            }
        }
    }
}

@Composable
fun PageTwoContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp), // Align near bottom
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        // Download Icon
        Box(
            modifier = Modifier
                .size(56.dp)
                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), Color.Transparent)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = Icons.Outlined.FileDownload, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = buildAnnotatedString {
                append(stringResource(R.string.download_and_watch))
                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                    append(stringResource(R.string.offline_highlight))
                }
            },
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = stringResource(R.string.download_any_movie_offline),
            color = Color.LightGray,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
    }
}

@Composable
fun PageThreeContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SmallCard(icon = Icons.Outlined.StarOutline, text = stringResource(R.string.personalized_recommendations))
            SmallCard(icon = Icons.Outlined.FavoriteBorder, text = stringResource(R.string.your_favorite_genres))
            SmallCard(icon = Icons.Outlined.BookmarkBorder, text = stringResource(R.string.build_your_watchlist))
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = buildAnnotatedString {
                append(stringResource(R.string.personalized_prefix))
                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                    append(stringResource(R.string.for_you_highlight))
                }
            },
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = stringResource(R.string.get_recommendations_tailored),
            color = Color.LightGray,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun FeatureItem(icon: ImageVector, text: String, iconTint: Color, isBoxed: Boolean = false) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(100.dp)
    ) {
        if (isBoxed) {
             Box(
                modifier = Modifier.size(24.dp).border(1.5.dp, iconTint, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
             ) {
                 Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
             }
        } else {
             Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = text, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 14.sp)
    }
}

@Composable
fun RowScope.SmallCard(icon: ImageVector, text: String) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(90.dp)
            .padding(horizontal = 4.dp)
            .border(1.dp, Color.DarkGray.copy(alpha=0.4f), RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha=0.8f), RoundedCornerShape(16.dp))
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = text, color = Color.LightGray, fontSize = 9.sp, textAlign = TextAlign.Center, lineHeight = 14.sp)
        }
    }
}
