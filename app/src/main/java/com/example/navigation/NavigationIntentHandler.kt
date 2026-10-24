package com.example.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NavigationIntentHandler {
    private val _targetDestination = MutableStateFlow<String?>(null)
    val targetDestination: StateFlow<String?> = _targetDestination.asStateFlow()

    fun navigateTo(destination: String) {
        _targetDestination.value = destination
    }

    fun clear() {
        _targetDestination.value = null
    }
}
