package com.example.ui.screens.settings

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.NotificationPreferences
import com.example.data.repository.NotificationPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NotificationPreferencesUiState(
    val preferences: NotificationPreferences = NotificationPreferences.DEFAULT,
    val isSystemNotificationBlocked: Boolean = false,
    val isLoading: Boolean = false
)

class NotificationPreferencesViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: NotificationPreferencesRepository = NotificationPreferencesRepository(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(NotificationPreferencesUiState(isLoading = true))
    val uiState: StateFlow<NotificationPreferencesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.preferencesFlow.collect { prefs ->
                _uiState.value = _uiState.value.copy(
                    preferences = prefs,
                    isLoading = false
                )
            }
        }
        checkSystemPermission()
    }

    fun checkSystemPermission() {
        val areEnabled = NotificationManagerCompat.from(getApplication()).areNotificationsEnabled()
        _uiState.value = _uiState.value.copy(
            isSystemNotificationBlocked = !areEnabled
        )
    }

    fun toggleMaster(enabled: Boolean) {
        viewModelScope.launch { repository.updateNotificationsEnabled(enabled) }
    }

    fun toggleAnnouncements(enabled: Boolean) {
        viewModelScope.launch { repository.updateAnnouncementsEnabled(enabled) }
    }

    fun toggleAppUpdates(enabled: Boolean) {
        viewModelScope.launch { repository.updateAppUpdatesEnabled(enabled) }
    }

    fun toggleMaintenance(enabled: Boolean) {
        viewModelScope.launch { repository.updateMaintenanceEnabled(enabled) }
    }

    fun toggleNewMovies(enabled: Boolean) {
        viewModelScope.launch { repository.updateNewMoviesEnabled(enabled) }
    }

    fun toggleNewTvSeries(enabled: Boolean) {
        viewModelScope.launch { repository.updateNewTvSeriesEnabled(enabled) }
    }

    fun toggleNewAnime(enabled: Boolean) {
        viewModelScope.launch { repository.updateNewAnimeEnabled(enabled) }
    }

    fun toggleNewTvEpisodes(enabled: Boolean) {
        viewModelScope.launch { repository.updateNewTvEpisodesEnabled(enabled) }
    }

    fun toggleNewAnimeEpisodes(enabled: Boolean) {
        viewModelScope.launch { repository.updateNewAnimeEpisodesEnabled(enabled) }
    }

    fun toggleNewTvSeasons(enabled: Boolean) {
        viewModelScope.launch { repository.updateNewTvSeasonsEnabled(enabled) }
    }

    fun toggleNewAnimeSeasons(enabled: Boolean) {
        viewModelScope.launch { repository.updateNewAnimeSeasonsEnabled(enabled) }
    }
}
