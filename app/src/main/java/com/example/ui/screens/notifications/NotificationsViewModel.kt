package com.example.ui.screens.notifications

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.NotificationItem
import com.example.data.repository.NotificationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NotificationsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NotificationRepository(application)

    val notifications: StateFlow<List<NotificationItem>> = repository.getAllNotifications()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
        
    val unreadCount: StateFlow<Int> = repository.getUnreadCount()
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        sync()
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    fun sync() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        _errorMessage.value = null
        viewModelScope.launch {
            try {
                repository.syncCloudNotifications(getApplication())
            } catch (e: Exception) {
                _errorMessage.value = e.localizedMessage ?: "Failed to sync notifications"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            try {
                repository.markAllAsRead()
            } catch (e: Exception) {
                _errorMessage.value = e.localizedMessage
            }
        }
    }
    
    fun markAsRead(id: String) {
        viewModelScope.launch {
            try {
                repository.markAsRead(id)
            } catch (e: Exception) {
                _errorMessage.value = e.localizedMessage
            }
        }
    }

    fun deleteNotification(id: String) {
        viewModelScope.launch {
            try {
                repository.deleteNotification(id)
            } catch (e: Exception) {
                _errorMessage.value = e.localizedMessage
            }
        }
    }
}
