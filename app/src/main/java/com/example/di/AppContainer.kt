package com.example.di

import com.example.data.repository.TmdbMediaRepositoryImpl
import com.example.domain.repository.MediaRepository
import android.app.Application

object AppContainer {
    lateinit var application: Application
    
    val mediaRepository: MediaRepository by lazy {
        TmdbMediaRepositoryImpl()
    }

    val connectivityObserver: com.example.utils.NetworkConnectivityObserver by lazy {
        com.example.utils.NetworkConnectivityObserver(application)
    }
}