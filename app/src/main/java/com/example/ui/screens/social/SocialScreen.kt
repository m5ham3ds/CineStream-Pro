package com.example.ui.screens.social
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

import androidx.compose.ui.res.stringResource
import com.example.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.repository.Conversation
import com.example.data.repository.UserProfile
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialScreen(
    viewModel: SocialViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onChatSelected: (String) -> Unit = {},
    onUserProfileClick: (String) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val restrictions by com.example.data.repository.UserSecurityManager.restrictionsFlow.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val categories = listOf(stringResource(R.string.all_messages), stringResource(R.string.unread), stringResource(R.string.groups), stringResource(R.string.requests))
    var selectedCategory by remember { mutableStateOf(categories[0]) }

    
    val primaryRed = MaterialTheme.colorScheme.primary
    val bgColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    
    var searchQuery by remember { mutableStateOf("") }
    val isLoading by viewModel.isLoading.collectAsState()
    
    var transitionFinished by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1200)
        transitionFinished = true
    }

    if (isLoading || !transitionFinished) {
        com.example.ui.components.SocialScreenSkeleton()
        return
    }

    val isUserAuthenticated = currentUser != null || com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null

    if (!isUserAuthenticated) {
        Box(modifier = Modifier.fillMaxSize().background(bgColor), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.community), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.sign_in_social), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
        ) {
            // Header / Search Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { 
                        searchQuery = it 
                        viewModel.searchUsers(it)
                    },
                    placeholder = { Text(stringResource(R.string.search_username), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, primaryRed.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp)),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = surfaceColor,
                        unfocusedContainerColor = surfaceColor,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground
                    ),
                    singleLine = true
                )
            }
            
            if (searchQuery.isNotEmpty()) {
                // Show Search Results
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(searchResults, key = { it.uid }) { user ->
                        if (user.uid != currentUser?.uid) {
                            UserSearchResultItem(
                                user = user,
                                onClick = {
                                    viewModel.startConversation(user.uid, user.displayName) { convId ->
                                        onChatSelected(convId)
                                    }
                                },
                                onAvatarClick = { onUserProfileClick(user.uid) }
                            )
                        }
                    }
                }
            } else {
                // Default View (Stories + Conversations)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.stories), color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.view_all_small), color = primaryRed, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .border(1.dp, if (!restrictions.isStoryAllowed) Color.Gray else primaryRed, CircleShape)
                                    .clickable {
                                        if (!restrictions.isStoryAllowed) {
                                            Toast.makeText(context, context.getString(R.string.story_posting_restricted), Toast.LENGTH_LONG).show()
                                        } else if (!com.example.data.repository.UserSecurityManager.canUpload()) {
                                            Toast.makeText(context, com.example.data.repository.UserSecurityManager.getUploadRestrictionMessage(), Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, context.getString(R.string.story_feature_coming_soon), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (!restrictions.isStoryAllowed) Icons.Default.Lock else Icons.Default.Add,
                                    contentDescription = stringResource(R.string.add_story),
                                    tint = if (!restrictions.isStoryAllowed) Color.Gray else primaryRed,
                                    modifier = Modifier.size(if (!restrictions.isStoryAllowed) 24.dp else 32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.add_story), color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp)
                        }
                    }
                    // For now, no actual stories are rendered until fetched, we just show add story
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Filter Chips container
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(surfaceColor)
                ) {
                    Column {
                        LazyRow(
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            
                            items(categories, key = { it }) { category ->
                                CustomFilterChip(
                                    text = category,
                                    selected = selectedCategory == category,
                                    onClick = { selectedCategory = category }
                                )
                            }
                        }
                        

                        val strAll = stringResource(R.string.all_messages)
                        val strUnread = stringResource(R.string.unread)
                        val strGroups = stringResource(R.string.groups)
                        val strReqs = stringResource(R.string.requests)
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val filteredConversations = when (selectedCategory) {
                                strAll -> conversations.filter { !it.isGroup && !it.isRequest }
                                strUnread -> conversations.filter { (it.unreadCounts[currentUser?.uid ?: ""] ?: 0) > 0 }
                                strGroups -> conversations.filter { it.isGroup }
                                strReqs -> conversations.filter { it.isRequest }
                                else -> conversations
                            }
                            items(filteredConversations, key = { it.id }) { conv ->
                                val otherUserId = conv.participants.firstOrNull { it != currentUser?.uid } ?: ""
                                val otherUserName = conv.participantNames[otherUserId] ?: "Unknown"
                                val unreadCount = conv.unreadCounts[currentUser?.uid ?: ""] ?: 0
                                
                                ChatListItem(
                                    name = otherUserName,
                                    message = conv.lastMessage,
                                    time = formatTime(conv.lastMessageTime),
                                    unreadCount = unreadCount,
                                    onClick = { onChatSelected(conv.id) },
                                    onAvatarClick = {
                                        if (otherUserId.isNotBlank()) onUserProfileClick(otherUserId)
                                    }
                                )
                            }
                            item { Spacer(modifier = Modifier.height(80.dp)) }
                        }
                    }
                }
            }
    }
}}

private fun formatTime(timeMillis: Long): String {
    if (timeMillis == 0L) return ""
    val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
    return sdf.format(Date(timeMillis))
}

@Composable
fun CustomFilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun UserSearchResultItem(user: UserProfile, onClick: () -> Unit, onAvatarClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onAvatarClick?.invoke() ?: onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (user.photoUrl.isNotBlank()) {
                AsyncImage(
                    model = user.photoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(user.displayName.take(1).uppercase(), color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(user.displayName, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ChatListItem(
    name: String,
    message: String,
    time: String,
    unreadCount: Int,
    onClick: () -> Unit,
    onAvatarClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onAvatarClick?.invoke() ?: onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(name.take(1).uppercase(), color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // Texts
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(message.ifEmpty { stringResource(R.string.start_conversation) }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 1)
        }
        
        // Time & Badge
        Column(horizontalAlignment = Alignment.End) {
            Text(time, color = if (unreadCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(6.dp))
            if (unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(unreadCount.toString(), color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }

    



}
