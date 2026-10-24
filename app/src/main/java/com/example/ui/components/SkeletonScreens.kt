package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun MediaScreenSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        // Hero Banner Skeleton (16:9)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .shimmerEffect()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Tab Row Skeleton (Not equal weight, like real tabs)
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = false
        ) {
            items(4) {
                Box(
                    modifier = Modifier
                        .width(90.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .shimmerEffect()
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Sections
        repeat(2) {
            // Section Title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .shimmerEffect()
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            // Section Items (Media Cards 140x210)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                userScrollEnabled = false
            ) {
                items(5) {
                    Box(
                        modifier = Modifier
                            .width(140.dp)
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(12.dp))
                            .shimmerEffect()
                    )
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun GridScreenSkeleton() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp, top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false
    ) {
        items(15) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(12.dp))
                    .shimmerEffect()
            )
        }
    }
}

@Composable
fun SocialScreenSkeleton() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(120.dp).height(32.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).shimmerEffect())
        }
        Spacer(modifier = Modifier.height(24.dp))
        
        // Stories Row
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            userScrollEnabled = false
        ) {
            items(6) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.size(70.dp).clip(CircleShape).shimmerEffect())
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.width(50.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        
        // Conversations List
        repeat(5) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(56.dp).clip(CircleShape).shimmerEffect())
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.width(150.dp).height(20.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth(0.6f).height(16.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                }
            }
        }
    }
}

@Composable
fun SearchScreenSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(16.dp))
        // Fake "Results for..." text
        Box(
            modifier = Modifier
                .width(180.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .shimmerEffect()
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        repeat(8) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Image
                Box(
                    modifier = Modifier
                        .size(60.dp, 80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .shimmerEffect()
                )
                Spacer(modifier = Modifier.width(16.dp))
                // Texts
                Column(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.width(150.dp).height(20.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.width(80.dp).height(16.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                }
            }
        }
    }
}

@Composable
fun ProfileScreenSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Hero Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(24.dp))
                .shimmerEffect()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Stats Row
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .shimmerEffect()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // About Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(16.dp))
                .shimmerEffect()
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Recent Activity Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(4) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(130.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .shimmerEffect()
                )
            }
        }
    }
}

@Composable
fun LibraryScreenSkeleton() {
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(4.dp))
        
        // Tab Row
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = false
        ) {
            items(3) {
                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(50))
                        .shimmerEffect()
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Grid
        GridScreenSkeleton()
    }
}

@Composable
fun ExtensionsScreenSkeleton() {
    Column(modifier = Modifier.fillMaxSize()) {
        // Search Bar Skeleton
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .shimmerEffect()
        )

        // Filter Chips Skeleton
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(4) {
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .shimmerEffect()
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // List items
        repeat(6) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).shimmerEffect())
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.width(120.dp).height(20.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(modifier = Modifier.width(80.dp).height(16.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                }
                Spacer(modifier = Modifier.width(16.dp))
                Box(modifier = Modifier.width(48.dp).height(24.dp).clip(RoundedCornerShape(12.dp)).shimmerEffect())
            }
        }
    }
}


@Composable
fun DownloadsScreenSkeleton() {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(16.dp))
        
        // Storage Header
        Box(modifier = Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(16.dp)).shimmerEffect())
        Spacer(modifier = Modifier.height(24.dp))
        
        // Tabs
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) {
                Box(modifier = Modifier.width(80.dp).height(32.dp).clip(RoundedCornerShape(16.dp)).shimmerEffect())
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        
        // Items
        repeat(5) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(80.dp, 120.dp).clip(RoundedCornerShape(8.dp)).shimmerEffect())
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.width(140.dp).height(20.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(modifier = Modifier.fillMaxWidth(0.8f).height(6.dp).clip(RoundedCornerShape(3.dp)).shimmerEffect())
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.width(60.dp).height(14.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                }
            }
        }
    }
}

@Composable
fun ShareScreenSkeleton() {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(16.dp))
        
        // Connection Status Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(16.dp))
                .shimmerEffect()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(12.dp)).shimmerEffect())
            Box(modifier = Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(12.dp)).shimmerEffect())
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Items list title
        Box(modifier = Modifier.width(150.dp).height(24.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
        Spacer(modifier = Modifier.height(16.dp))
        
        // Items
        repeat(4) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.width(100.dp).height(60.dp).clip(RoundedCornerShape(8.dp)).shimmerEffect())
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.width(140.dp).height(16.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.width(80.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                }
            }
        }
    }
}

@Composable
fun AboutScreenSkeleton() {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(64.dp))
        // Logo
        Box(modifier = Modifier.size(100.dp).clip(CircleShape).shimmerEffect())
        Spacer(modifier = Modifier.height(24.dp))
        // Title
        Box(modifier = Modifier.width(150.dp).height(28.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
        Spacer(modifier = Modifier.height(16.dp))
        // Version
        Box(modifier = Modifier.width(80.dp).height(16.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
        
        Spacer(modifier = Modifier.height(48.dp))
        
        // Text blocks
        repeat(3) {
            Box(modifier = Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
            Spacer(modifier = Modifier.height(8.dp))
        }
        Box(modifier = Modifier.fillMaxWidth(0.7f).height(16.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
        
        Spacer(modifier = Modifier.height(48.dp))
        
        // Links
        repeat(3) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(24.dp).clip(CircleShape).shimmerEffect())
                Spacer(modifier = Modifier.width(16.dp))
                Box(modifier = Modifier.width(120.dp).height(20.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
            }
        }
    }
}


@Composable
fun NotificationsScreenSkeleton() {
    Column(modifier = Modifier.fillMaxSize()) {
        // TopBar fake
        Row(modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(24.dp).clip(CircleShape).shimmerEffect())
            Spacer(modifier = Modifier.width(16.dp))
            Box(modifier = Modifier.width(120.dp).height(24.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Notifications list
        repeat(6) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp).clip(CircleShape).shimmerEffect())
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.width(150.dp).height(18.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth(0.9f).height(14.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.width(80.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                }
            }
        }
    }
}

@Composable
fun HelpSupportScreenSkeleton() {
    Column(modifier = Modifier.fillMaxSize()) {
        // TopBar fake
        Row(modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(24.dp).clip(CircleShape).shimmerEffect())
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Box(modifier = Modifier.width(120.dp).height(20.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
                Spacer(modifier = Modifier.height(4.dp))
                Box(modifier = Modifier.width(80.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).shimmerEffect())
            }
        }
        
        // Agent Card fake
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp).height(80.dp).clip(RoundedCornerShape(16.dp)).shimmerEffect())
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Chat bubbles fake
        repeat(3) { index ->
            val isUser = index % 2 != 0
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
            ) {
                if (!isUser) {
                    Box(modifier = Modifier.size(36.dp).clip(CircleShape).shimmerEffect())
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Box(
                    modifier = Modifier
                        .width(200.dp)
                        .height(80.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .shimmerEffect()
                )
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Input bar fake
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(24.dp).clip(CircleShape).shimmerEffect())
            Spacer(modifier = Modifier.width(16.dp))
            Box(modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).shimmerEffect())
            Spacer(modifier = Modifier.width(16.dp))
            Box(modifier = Modifier.size(48.dp).clip(CircleShape).shimmerEffect())
        }
    }
}
