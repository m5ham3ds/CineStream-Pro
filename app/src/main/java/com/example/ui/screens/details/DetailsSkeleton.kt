package com.example.ui.screens.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ui.components.shimmerEffect

@Composable
fun DetailsSkeleton(onBack: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Hero Image Shimmer Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .shimmerEffect()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Title Shimmer
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .width(220.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .shimmerEffect()
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Subtitle / Rating Shimmer
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .width(160.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .shimmerEffect()
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Action Buttons Row (Play, Download, Favorite)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .clip(RoundedCornerShape(25.dp))
                        .shimmerEffect()
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .shimmerEffect()
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .shimmerEffect()
                )
            }
            
            Spacer(modifier = Modifier.height(28.dp))
            
            // Overview Title
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .width(120.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .shimmerEffect()
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            // Overview Paragraph Text
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )
            }
            
            Spacer(modifier = Modifier.height(28.dp))

            // Section Header
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .width(150.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .shimmerEffect()
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            // Horizontal list (Trailers / Episodes)
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(160.dp)
                        .height(90.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .shimmerEffect()
                )
                Box(
                    modifier = Modifier
                        .width(160.dp)
                        .height(90.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .shimmerEffect()
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
        }

        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        }
    }
}
