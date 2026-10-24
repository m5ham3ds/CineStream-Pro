package com.example.ui.screens.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.PrivateMessage
import com.example.data.repository.SocialRepository
import com.example.data.repository.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {
    private val repo = SocialRepository()
    private val _currentUser = MutableStateFlow<UserProfile?>(repo.getCurrentUser())
    val currentUser: StateFlow<UserProfile?> = _currentUser.asStateFlow()
    private val _otherUser = MutableStateFlow<UserProfile?>(null)
    val otherUser: StateFlow<UserProfile?> = _otherUser.asStateFlow()
    private val _messages = MutableStateFlow<List<PrivateMessage>>(emptyList())
    val messages: StateFlow<List<PrivateMessage>> = _messages.asStateFlow()
    private var currentConversationId: String = ""
    
    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    fun loadConversation(conversationId: String) {
        currentConversationId = conversationId
        repo.markConversationAsRead(conversationId)
        
        viewModelScope.launch {
            val conv = repo.getConversation(conversationId)
            if (conv != null) {
                val otherUserId = conv.participants.firstOrNull { it != currentUser.value?.uid }
                if (otherUserId != null) {
                    val initialName = conv.participantNames[otherUserId] ?: "User"
                    _otherUser.value = UserProfile(uid = otherUserId, username = initialName) // Immediate fallback
                    
                    repo.getUserProfileFlow(otherUserId).collect { profile ->
                        if (profile != null) _otherUser.value = profile
                    }
                }
            }
        }
        
        viewModelScope.launch {
            repo.getMessages(conversationId).collect { msgs ->
                _messages.value = msgs
                repo.markConversationAsRead(conversationId) // Mark as read as new messages come in
            }
        }
    }

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    val canChat: Boolean get() = com.example.data.repository.UserSecurityManager.canChat()
    val canUpload: Boolean get() = com.example.data.repository.UserSecurityManager.canUpload()

    fun sendMessage(text: String, mediaUri: android.net.Uri? = null) {
        if (!canChat) return
        if (currentConversationId.isEmpty()) return
        if (text.isBlank() && mediaUri == null) return
        if (mediaUri != null && !canUpload) {
            _errorMessage.value = com.example.data.repository.UserSecurityManager.getUploadRestrictionMessage()
            return
        }

        viewModelScope.launch {
            if (mediaUri != null) {
                _isUploading.value = true
                val url = repo.uploadMedia(mediaUri)
                _isUploading.value = false
                if (url != null) {
                    repo.sendMediaMessage(currentConversationId, text, url)
                }
            } else {
                repo.sendMessage(currentConversationId, text)
            }
        }
    }

    fun sendMultipleMedia(uris: List<android.net.Uri>) {
        if (!canChat) return
        if (currentConversationId.isEmpty()) return
        if (!canUpload) {
            _errorMessage.value = com.example.data.repository.UserSecurityManager.getUploadRestrictionMessage()
            return
        }
        viewModelScope.launch {
            _isUploading.value = true
            for (uri in uris) {
                val url = repo.uploadMedia(uri)
                if (url != null) {
                    repo.sendMediaMessage(currentConversationId, "", url)
                }
            }
            _isUploading.value = false
        }
    }

    fun sendVoiceMessage(voicePath: String) {
        if (!canChat) return
        if (currentConversationId.isEmpty()) return
        if (!canUpload) {
            _errorMessage.value = com.example.data.repository.UserSecurityManager.getUploadRestrictionMessage()
            return
        }
        viewModelScope.launch {
            _isUploading.value = true
            val url = repo.uploadMedia(voicePath)
            _isUploading.value = false
            if (url != null) {
                repo.sendRealVoiceMessage(currentConversationId, url)
            }
        }
    }

    fun editMessage(msgId: String, newText: String) {
        if (currentConversationId.isNotEmpty() && newText.isNotBlank()) {
            repo.editMessage(currentConversationId, msgId, newText)
        }
    }

    fun deleteMessage(msgId: String, forEveryone: Boolean) {
        if (currentConversationId.isNotEmpty()) {
            repo.deleteMessage(currentConversationId, msgId, forEveryone)
        }
    }

    fun reactToMessage(msgId: String, emoji: String) {
        if (currentConversationId.isNotEmpty()) {
            repo.reactToMessage(currentConversationId, msgId, emoji)
        }
    }
}
