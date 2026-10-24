package com.example.ui.screens.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.models.PersonDetails
import com.example.domain.repository.MediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PersonDetailsUiState(
    val isLoading: Boolean = false,
    val person: PersonDetails? = null,
    val error: String? = null
)

class PersonDetailsViewModel(
    private val repository: MediaRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(PersonDetailsUiState())
    val uiState: StateFlow<PersonDetailsUiState> = _uiState.asStateFlow()

    fun loadPerson(personId: String, force: Boolean = false) {
        if (!force && _uiState.value.person?.id == personId && !_uiState.value.isLoading) {
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val person = repository.getPersonDetails(personId)
                if (person != null) {
                    _uiState.update { it.copy(person = person, isLoading = false) }
                } else {
                    val app = try { com.example.di.AppContainer.application } catch (_: Exception) { null }
                    val errorMsg = app?.getString(com.example.R.string.person_not_found) ?: "Person not found"
                    _uiState.update { it.copy(error = errorMsg, isLoading = false) }
                }
            } catch (e: Exception) {
                val app = try { com.example.di.AppContainer.application } catch (_: Exception) { null }
                val errorMsg = when (e) {
                    is java.io.IOException -> app?.getString(com.example.R.string.no_internet_check_connection) ?: "No internet connection"
                    is retrofit2.HttpException -> {
                        when (e.code()) {
                            404 -> app?.getString(com.example.R.string.person_not_found) ?: "Person not found"
                            401, 403 -> "Authentication required"
                            else -> e.localizedMessage ?: "Network error (${e.code()})"
                        }
                    }
                    else -> e.localizedMessage ?: e.message ?: "Failed to load person details"
                }
                _uiState.update { it.copy(error = errorMsg, isLoading = false) }
            }
        }
    }
}