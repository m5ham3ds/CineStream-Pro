package com.example.ui.screens.social

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.example.ui.theme.SuccessGreen
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

import androidx.compose.ui.res.stringResource
import com.example.R

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PlayArrow
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.example.data.repository.PrivateMessage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    conversationId: String,
    viewModel: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onBack: () -> Unit,
    onUserClick: (String) -> Unit = {}
) {
    val currentUser by viewModel.currentUser.collectAsState()
    val restrictions by com.example.data.repository.UserSecurityManager.restrictionsFlow.collectAsState()
    val context = LocalContext.current
    var selectedMessage by remember { mutableStateOf<PrivateMessage?>(null) }
    var editingMessage by remember { mutableStateOf<PrivateMessage?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val audioRecorder = remember { com.example.utils.AudioRecorder(context) }
    var isRecording by remember { mutableStateOf(false) }
    var recordedFile by remember { mutableStateOf<java.io.File?>(null) }
    val isUploading by viewModel.isUploading.collectAsState()
    val playingAudioId by com.example.utils.AudioPlayer.currentlyPlayingId.collectAsState()
    
    DisposableEffect(Unit) {
        onDispose {
            com.example.utils.AudioPlayer.stop()
        }
    }


    val micPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, context.getString(R.string.mic_permission_granted), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, context.getString(R.string.mic_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.sendMultipleMedia(uris)
        }
    }


    val otherUser by viewModel.otherUser.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val chatErrorMessage by viewModel.errorMessage.collectAsState()
    var messageText by remember { mutableStateOf("") }
    
    val bgColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val primaryRed = MaterialTheme.colorScheme.primary
    val darkGray = MaterialTheme.colorScheme.surfaceVariant

    LaunchedEffect(chatErrorMessage) {
        chatErrorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.clearErrorMessage()
        }
    }

    LaunchedEffect(conversationId) {
        viewModel.loadConversation(conversationId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { otherUser?.uid?.let { onUserClick(it) } }
                            .padding(vertical = 4.dp, horizontal = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(darkGray),
                            contentAlignment = Alignment.Center
                        ) {
                            if (otherUser?.photoUrl?.isNotEmpty() == true) {
                                AsyncImage(
                                    model = otherUser?.photoUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text((otherUser?.displayName?.take(1) ?: "U").uppercase(), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                            }
                            
                            // Online indicator
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .align(Alignment.BottomEnd)
                                    .background(bgColor, CircleShape)
                                    .padding(2.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxSize().background(SuccessGreen, CircleShape))
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(otherUser?.displayName ?: stringResource(R.string.loading), color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(6.dp).background(primaryRed, CircleShape))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.online), color = Color.LightGray, fontSize = 12.sp)
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    IconButton(onClick = { Toast.makeText(context, context.getString(R.string.voice_call_coming_soon), Toast.LENGTH_SHORT).show() }) { Icon(Icons.Default.Phone, contentDescription = stringResource(R.string.cd_call), tint = primaryRed) }
                    
                    IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more), tint = MaterialTheme.colorScheme.onBackground) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bgColor)
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bgColor)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (!restrictions.isChatAllowed) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.chat_restricted_by_admin), color = MaterialTheme.colorScheme.error, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(surfaceColor, RoundedCornerShape(32.dp)).padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(darkGray)
                            .clickable {
                            if (!com.example.data.repository.UserSecurityManager.canChat()) {
                                Toast.makeText(context, context.getString(R.string.media_sharing_restricted), Toast.LENGTH_LONG).show()
                            } else if (!com.example.data.repository.UserSecurityManager.canUpload()) {
                                Toast.makeText(context, com.example.data.repository.UserSecurityManager.getUploadRestrictionMessage(), Toast.LENGTH_LONG).show()
                            } else {
                                launcher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            }
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.cd_attach), tint = primaryRed, modifier = Modifier.size(24.dp))
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Box(modifier = Modifier.weight(1f)) {
                        if (messageText.isEmpty()) {
                            Text(if (editingMessage != null) stringResource(R.string.edit_message_hint) else stringResource(R.string.type_a_message), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                        }
                        BasicTextField(
                            value = messageText,
                            onValueChange = { messageText = it },
                            textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp),
                            cursorBrush = SolidColor(primaryRed),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    Icon(Icons.Default.Face, contentDescription = stringResource(R.string.cd_emoji), tint = primaryRed, modifier = Modifier.size(24.dp).clickable {
                        Toast.makeText(context, context.getString(R.string.emoji_keyboard_opened), Toast.LENGTH_SHORT).show()
                    })
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (isRecording) Color.Red else primaryRed)
                            .pointerInput(messageText, editingMessage) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        
                                        if (!com.example.data.repository.UserSecurityManager.canChat()) {
                                            Toast.makeText(context, context.getString(R.string.chat_restricted_by_admin), Toast.LENGTH_LONG).show()
                                            break
                                        }

                                        if (messageText.isNotBlank() || editingMessage != null) {
                                            // Tap to send text
                                            waitForUpOrCancellation()
                                            if (editingMessage != null) {
                                                viewModel.editMessage(editingMessage!!.id, messageText)
                                                editingMessage = null
                                            } else {
                                                viewModel.sendMessage(messageText)
                                            }
                                            messageText = ""
                                        } else {
                                            // Hold to record
                                            if (!com.example.data.repository.UserSecurityManager.canUpload()) {
                                                Toast.makeText(context, com.example.data.repository.UserSecurityManager.getUploadRestrictionMessage(), Toast.LENGTH_LONG).show()
                                                break
                                            }
                                            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                                isRecording = true
                                                recordedFile = audioRecorder.startRecording()
                                            } else {
                                                micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                            }
                                            
                                            waitForUpOrCancellation()
                                            
                                            if (isRecording) {
                                                isRecording = false
                                                audioRecorder.stopRecording()
                                                recordedFile?.let {
                                                    viewModel.sendVoiceMessage(it.absolutePath)
                                                }
                                                recordedFile = null
                                            }
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isUploading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onBackground, strokeWidth = 2.dp)
                        } else if (messageText.isNotBlank() || editingMessage != null) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.cd_send), tint = MaterialTheme.colorScheme.onBackground)
                        } else {
                            Icon(Icons.Outlined.MicNone, contentDescription = stringResource(R.string.cd_voice), tint = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }
                }
            }
        },
        containerColor = bgColor
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            reverseLayout = true
        ) {
            items(messages.reversed(), key = { it.id }) { msg ->
                val isMe = msg.senderId == currentUser?.uid
                val isDeletedForMe = msg.deletedFor.contains(currentUser?.uid)
                if (isDeletedForMe) return@items
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                ) {
                    Column(
                        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                    ) {
                        val isEffectivelyDeleted = msg.isDeleted
                        
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (isEffectivelyDeleted) Color.Transparent else if (isMe) primaryRed else darkGray,
                                    shape = RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp,
                                        bottomStart = if (isMe) 16.dp else 4.dp,
                                        bottomEnd = if (isMe) 4.dp else 16.dp
                                    )
                                )
                                .border(if (isEffectivelyDeleted) 1.dp else 0.dp, if (isEffectivelyDeleted) MaterialTheme.colorScheme.onSurfaceVariant else Color.Transparent, RoundedCornerShape(16.dp))
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        if (!isEffectivelyDeleted) {
                                            selectedMessage = msg
                                        }
                                    }
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            if (isEffectivelyDeleted) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Block, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = stringResource(R.string.msg_deleted), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                }
                            } else if (msg.isVoice) {
                                val textColor = if (isMe) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                val isPlaying = playingAudioId == msg.id
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                                    if (msg.mediaUrl != null && !msg.isDeleted) {
                                        if (isPlaying) {
                                            com.example.utils.AudioPlayer.stop()
                                        } else {
                                            com.example.utils.AudioPlayer.play(msg.id, msg.mediaUrl)
                                        }
                                    }
                                }) {
                                    Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = if (isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play), tint = textColor, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(modifier = Modifier.width(100.dp).height(2.dp).background(textColor.copy(alpha = 0.5f)))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(if (isPlaying) stringResource(R.string.playing) else stringResource(R.string.voice_label), color = textColor, fontSize = 12.sp)
                                }
                            } else {
                                val textColor = if (isMe) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                val timeColor = if (isMe) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                Column(horizontalAlignment = Alignment.End) {
                                    if (msg.mediaUrl != null) {
                                        val isVideo = msg.mediaUrl.contains(".mp4") || msg.mediaUrl.contains(".mov") || msg.mediaUrl.contains("/video/")
                                        Box(modifier = Modifier.padding(bottom = 4.dp)) {
                                            coil.compose.AsyncImage(
                                                model = if (isVideo) msg.mediaUrl.replace(".mp4", ".jpg") else msg.mediaUrl, // Simple trick for cloudinary thumbnails
                                                contentDescription = stringResource(R.string.cd_media),
                                                modifier = Modifier
                                                    .fillMaxWidth(0.7f)
                                                    .heightIn(max = 200.dp)
                                                    .clip(RoundedCornerShape(8.dp)),
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                            )
                                            if (isVideo) {
                                                Box(
                                                    modifier = Modifier.align(Alignment.Center).size(40.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.cd_video), tint = Color.White)
                                                }
                                            }
                                        }
                                    }
                                    if (msg.text.isNotBlank()) {
                                        Text(text = msg.text, color = textColor, fontSize = 15.sp, modifier = Modifier.align(Alignment.Start))
                                    }
                                    
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                        if (msg.isEdited) {
                                            Text(text = stringResource(R.string.edited), color = timeColor, fontSize = 10.sp)
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
                                        Text(sdf.format(Date(msg.timestamp)), fontSize = 10.sp, color = timeColor)
                                        if (isMe) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(Icons.Default.DoneAll, contentDescription = stringResource(R.string.cd_read), tint = Color.White, modifier = Modifier.size(12.dp))
                                        }
                                    }
                                }
                            }
                        }
                        
                        // Show Reactions if any
                        if (!isEffectivelyDeleted && msg.reactions.isNotEmpty()) {
                            Row(modifier = Modifier.padding(top = 2.dp)) {
                                msg.reactions.values.distinct().forEach { emoji ->
                                    Text(text = emoji, fontSize = 12.sp, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).padding(horizontal = 4.dp, vertical = 2.dp))
                                }
                            }
                        }
                    }
                }
            }
            
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .background(surfaceColor, RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, contentDescription = stringResource(R.string.cd_encrypted), tint = primaryRed, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.e2e_encryption), color = Color.LightGray, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Box(
                        modifier = Modifier
                            .background(surfaceColor, RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(stringResource(R.string.today), color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (selectedMessage != null) {
        ModalBottomSheet(onDismissRequest = { selectedMessage = null }, containerColor = MaterialTheme.colorScheme.surface) {
            val msg = selectedMessage!!
            val isMe = msg.senderId == currentUser?.uid
            val isLastMessage = messages.firstOrNull { it.senderId == currentUser?.uid }?.id == msg.id
            
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
                // Reactions
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val emojis = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
                    emojis.forEach { emoji ->
                        Text(
                            text = emoji,
                            fontSize = 28.sp,
                            modifier = Modifier.clickable {
                                viewModel.reactToMessage(msg.id, emoji)
                                selectedMessage = null
                            }.padding(8.dp)
                        )
                    }
                }
                
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                
                if (!msg.isVoice) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.copy), color = MaterialTheme.colorScheme.onBackground) },
                        leadingContent = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.message), msg.text))
                            Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                            selectedMessage = null
                        }
                    )
                }
                
                if (isMe && isLastMessage && !msg.isVoice) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.edit), color = MaterialTheme.colorScheme.onBackground) },
                        leadingContent = { Icon(Icons.Outlined.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            editingMessage = msg
                            messageText = msg.text
                            selectedMessage = null
                        }
                    )
                }
                
                ListItem(
                    headlineContent = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.primary) },
                    leadingContent = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        if (isMe) {
                            showDeleteConfirm = true
                        } else {
                            viewModel.deleteMessage(msg.id, false)
                            selectedMessage = null
                        }
                    }
                )
            }
        }
    }
    
    if (showDeleteConfirm && selectedMessage != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_message), color = MaterialTheme.colorScheme.onBackground) },
            text = { Text(stringResource(R.string.delete_msg_prompt), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMessage(selectedMessage!!.id, true)
                    showDeleteConfirm = false
                    selectedMessage = null
                }) { Text(stringResource(R.string.delete_everyone), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.deleteMessage(selectedMessage!!.id, false)
                    showDeleteConfirm = false
                    selectedMessage = null
                }) { Text(stringResource(R.string.delete_for_me), color = MaterialTheme.colorScheme.onBackground) }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

}