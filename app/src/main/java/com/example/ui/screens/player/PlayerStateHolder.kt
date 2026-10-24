package com.example.ui.screens.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object PlayerStateHolder {
    var isPlayerActive: Boolean = false
    var isInPipMode by mutableStateOf(false)
    var onEnterPipRequested: (() -> Unit)? = null
}
