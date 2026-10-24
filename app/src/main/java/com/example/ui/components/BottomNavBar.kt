package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.R
import com.example.navigation.Screen

/**
 * Modern floating pill bottom navigation bar matching the design specification.
 * Completely transparent surroundings so content visibly flows behind and around it
 * on the left, right, and bottom.
 */
@Composable
fun BottomNavBar(
    navController: NavController,
    modifier: Modifier = Modifier,
    onReselectItem: ((Screen) -> Unit)? = null,
    onNavigateToItem: ((Screen) -> Unit)? = null
) {
    val items = listOf(
        Screen.Home,
        Screen.Movies,
        Screen.Search,
        Screen.Series,
        Screen.Anime
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Ensure LTR layout order for bottom navigation tabs (Home -> Movies -> Search -> Series -> Anime)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        // Outer wrapper is strictly transparent with no background color
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // Floating Pill Navigation Dock
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
                    .height(66.dp)
                    .shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(32.dp),
                        spotColor = Color.Black.copy(alpha = 0.5f),
                        ambientColor = Color.Black.copy(alpha = 0.35f)
                    )
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xFF16181C))
                    .border(
                        border = BorderStroke(
                            width = 1.dp,
                            color = Color(0xFF262931)
                        ),
                        shape = RoundedCornerShape(32.dp)
                    )
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { screen ->
                    val selected = currentRoute == screen.route

                    val label = when (screen) {
                        Screen.Home -> stringResource(R.string.home)
                        Screen.Movies -> stringResource(R.string.movies)
                        Screen.Search -> stringResource(R.string.search)
                        Screen.Series -> stringResource(R.string.series)
                        Screen.Anime -> stringResource(R.string.anime)
                        else -> screen.title
                    }

                    val unselectedColor = Color(0xFF8A92A0)
                    val activeColor = MaterialTheme.colorScheme.primary

                    val animatedTint by animateColorAsState(
                        targetValue = if (selected) activeColor else unselectedColor,
                        animationSpec = tween(durationMillis = 200),
                        label = "nav_item_tint"
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = androidx.compose.material3.ripple(bounded = false, radius = 28.dp, color = activeColor)
                            ) {
                                if (!selected) {
                                    onNavigateToItem?.invoke(screen)
                                    navController.navigate(screen.route) {
                                        popUpTo(Screen.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                } else {
                                    onReselectItem?.invoke(screen)
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = screen.icon,
                            contentDescription = label,
                            tint = animatedTint,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = label,
                            color = animatedTint,
                            fontSize = 11.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
