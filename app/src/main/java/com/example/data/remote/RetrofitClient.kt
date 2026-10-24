package com.example.data.remote

import android.content.Context
import android.net.ConnectivityManager
import com.example.MyApplication
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

object RetrofitClient {

    // Cloudflare Worker TMDB Gateway
    private const val BASE_URL = "https://cinestream-api-gateway.blackmyemail1.workers.dev/api/v1/tmdb/"

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        // Redact Authorization header to prevent token leakage in debug logs
        redactHeader("Authorization")
        level = if (com.example.BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
    }

    private val cacheSize = (50 * 1024 * 1024).toLong() // 50 MB

    private fun isNetworkAvailable(): Boolean {
        return try {
            com.example.utils.NetworkUtils.isInternetAvailable(com.example.di.AppContainer.application)
        } catch (_: Exception) {
            true
        }
    }

    private val authInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val builder = originalRequest.newBuilder()

        // 1. Ensure standard API headers
        builder.header("Accept", "application/json")
        if (originalRequest.header("User-Agent") == null) {
            builder.header("User-Agent", "CineStream-Android/1.0 (Linux; Android)")
        }

        // 2. Attach Firebase ID token if user is signed in (supports both registered users and anonymous guests)
        try {
            val auth = FirebaseAuth.getInstance()
            val currentUser = auth.currentUser
            if (currentUser != null) {
                val tokenResult = Tasks.await(currentUser.getIdToken(false), 5, TimeUnit.SECONDS)
                val token = tokenResult?.token
                if (!token.isNullOrBlank()) {
                    builder.header("Authorization", "Bearer $token")
                }
            }
        } catch (_: Exception) {
            // Silently allow proceed so network can handle or cache can be used; never log or leak token
        }

        chain.proceed(builder.build())
    }

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .cache(Cache(File(com.example.di.AppContainer.application.cacheDir, "http_cache_v2"), cacheSize))
            .addInterceptor(authInterceptor)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val originalUrl = originalRequest.url
                
                val urlWithLanguage = if (originalUrl.queryParameter("language") == null) {
                    val currentLanguage = java.util.Locale.getDefault().language
                    val tmdbLanguage = if (currentLanguage == "ar") "ar-SA" else "en-US"
                    originalUrl.newBuilder()
                        .addQueryParameter("language", tmdbLanguage)
                        .build()
                } else {
                    originalUrl
                }
                
                var request = originalRequest.newBuilder().url(urlWithLanguage).build()
                // Always try network first with short cache, unless explicitly offline
                if (!isNetworkAvailable()) {
                    request = request.newBuilder()
                        .header("Cache-Control", "public, only-if-cached, max-stale=" + 60 * 60 * 24 * 7)
                        .build()
                } else {
                    request = request.newBuilder()
                        .header("Cache-Control", "public, max-age=" + 60)
                        .build()
                }

                try {
                    chain.proceed(request)
                } catch (e: Exception) {
                    // Fallback to cache if network throws (e.g. no internet despite active connection)
                    val offlineRequest = request.newBuilder()
                        .header("Cache-Control", "public, only-if-cached, max-stale=" + 60 * 60 * 24 * 7)
                        .build()
                    chain.proceed(offlineRequest)
                }
            }
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val cacheControl = response.header("Cache-Control")
                // Respect Worker cache directives. In particular, never turn
                // no-store/no-cache into a one-hour cache: doing so can keep old
                // Coming Soon dates and invalid ratings visible after the API changes.
                if (cacheControl.isNullOrBlank()) {
                    response.newBuilder()
                        .removeHeader("Pragma")
                        .header("Cache-Control", "public, max-age=60")
                        .build()
                } else {
                    response
                }
            }
            .addInterceptor(loggingInterceptor)
            .build()
    }

    val tmdbApi: TmdbApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TmdbApiService::class.java)
    }
}