package com.example.utils

import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AudioPlayer {
    private var mediaPlayer: MediaPlayer? = null
    
    private val _currentlyPlayingId = MutableStateFlow<String?>(null)
    val currentlyPlayingId: StateFlow<String?> = _currentlyPlayingId.asStateFlow()

    fun play(id: String, url: String) {
        if (_currentlyPlayingId.value == id) {
            stop()
            return
        }
        
        stop()
        
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(url)
                prepareAsync()
                setOnPreparedListener { 
                    it.start()
                    _currentlyPlayingId.value = id
                }
                setOnCompletionListener { 
                    _currentlyPlayingId.value = null
                    mediaPlayer?.release()
                    mediaPlayer = null
                }
                setOnErrorListener { _, _, _ ->
                    _currentlyPlayingId.value = null
                    true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {}
        mediaPlayer = null
        _currentlyPlayingId.value = null
    }
}
