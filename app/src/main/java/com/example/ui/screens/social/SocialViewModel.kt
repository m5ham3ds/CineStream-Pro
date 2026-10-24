package com.example.ui.screens.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SocialViewModel : ViewModel() {
    private val repo = SocialRepository()
    
    private val _currentUser = MutableStateFlow<UserProfile?>(repo.getCurrentUser())
    val currentUser: StateFlow<UserProfile?> = _currentUser.asStateFlow()
    
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()
    
    private val _stories = MutableStateFlow<List<Story>>(emptyList())
    val stories: StateFlow<List<Story>> = _stories.asStateFlow()
    
    private val _searchResults = MutableStateFlow<List<UserProfile>>(emptyList())
    val searchResults: StateFlow<List<UserProfile>> = _searchResults.asStateFlow()
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private var searchJob: Job? = null
    private var conversationJob: Job? = null
    private var storiesJob: Job? = null

    init {
        viewModelScope.launch {
            AuthRepository.currentUserFlow.collect { authUser ->
                if (authUser != null) {
                    _currentUser.value = UserProfile(
                        uid = authUser.uid,
                        username = authUser.username,
                        firstName = authUser.firstName,
                        lastName = authUser.lastName,
                        photoUrl = authUser.photoUrl,
                        isOnline = true,
                        isProfilePublic = authUser.isProfilePublic
                    )
                    startListening()
                } else {
                    val firebaseUser = AuthRepository.auth.currentUser
                    if (firebaseUser != null) {
                        // User is authenticated in Firebase Auth; provide immediate session profile
                        val fallbackName = firebaseUser.displayName?.ifBlank { null }
                            ?: firebaseUser.email?.substringBefore("@")
                            ?: "User"
                        _currentUser.value = UserProfile(
                            uid = firebaseUser.uid,
                            username = firebaseUser.email?.substringBefore("@") ?: fallbackName,
                            firstName = fallbackName.substringBefore(" "),
                            lastName = fallbackName.substringAfter(" ", ""),
                            photoUrl = firebaseUser.photoUrl?.toString() ?: "",
                            isOnline = true,
                            isProfilePublic = true
                        )
                        startListening()
                        viewModelScope.launch {
                            try { AuthRepository.getCurrentUser() } catch (_: Exception) {}
                        }
                    } else {
                        _currentUser.value = null
                        _isLoading.value = false
                        stopListening()
                    }
                }
            }
        }
    }

    fun refreshUser() {
        val user = repo.getCurrentUser()
        if (user != null) {
            _currentUser.value = user
            viewModelScope.launch { repo.saveUserProfile() }
            startListening()
        } else {
            val fbUser = AuthRepository.auth.currentUser
            if (fbUser != null) {
                val fallbackName = fbUser.displayName?.ifBlank { null }
                    ?: fbUser.email?.substringBefore("@")
                    ?: "User"
                _currentUser.value = UserProfile(
                    uid = fbUser.uid,
                    username = fbUser.email?.substringBefore("@") ?: fallbackName,
                    firstName = fallbackName.substringBefore(" "),
                    lastName = fallbackName.substringAfter(" ", ""),
                    photoUrl = fbUser.photoUrl?.toString() ?: "",
                    isOnline = true,
                    isProfilePublic = true
                )
                startListening()
            }
        }
    }

    private fun startListening() {
        stopListening()
        conversationJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1200)
            _isLoading.value = false
        }
        viewModelScope.launch {
            repo.getConversations().collect { convs ->
                _conversations.value = convs
                _isLoading.value = false
            }
        }
        storiesJob = viewModelScope.launch {
            repo.getStories().collect { sts ->
                _stories.value = sts
            }
        }
    }
    
    private fun stopListening() {
        conversationJob?.cancel()
        storiesJob?.cancel()
        searchJob?.cancel()
        _conversations.value = emptyList()
        _stories.value = emptyList()
        _searchResults.value = emptyList()
    }
    
    fun searchUsers(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            repo.searchUsers(query).collect { users ->
                _searchResults.value = users
            }
        }
    }
    
    fun startConversation(otherUserId: String, otherUserName: String, onConversationStarted: (String) -> Unit) {
        if (!UserSecurityManager.canChat()) return
        viewModelScope.launch {
            val convId = repo.startConversation(otherUserId, otherUserName)
            if (convId.isNotEmpty()) {
                onConversationStarted(convId)
            }
        }
    }

    fun addStory(imageUrl: String) {
        if (!UserSecurityManager.canPostStory() || !UserSecurityManager.canUpload()) return
        if (imageUrl.isNotBlank()) {
            viewModelScope.launch { repo.addStory(imageUrl) }
        }
    }

    fun deleteStory(storyId: String) {
        if (storyId.isNotBlank()) {
            viewModelScope.launch { repo.deleteStory(storyId) }
        }
    }
}