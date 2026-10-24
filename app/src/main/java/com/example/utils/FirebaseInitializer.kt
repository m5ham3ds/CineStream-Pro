package com.example.utils

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Initializes the User App against the canonical CineStream Firebase Android client.
 *
 * The checked-in reference JSON lives under public/assets/aistudio and is not read by the
 * Google Services Gradle plugin using the authoritative app/google-services.json that
 * matches the registered CineStream User App applicationId. Explicit options remain a
 * defensive fallback if generated resources are unavailable in a test/runtime environment.
 * These values were copied from that retained reference configuration; do not change the
 * Firebase project or registered app identity here to match a generated package name.
 */
object FirebaseInitializer {
    private const val TAG = "UserFirebaseInitializer"
    private const val APP_ID = "1:979447256418:android:037a9b3a1393e550a9883b"
    private const val API_KEY = "AIzaSyC9CvaM9Mw3NiD-KsOiYvHZHj6XZJQJnPs"
    private const val PROJECT_ID = "cinestream-sulo"
    private const val STORAGE_BUCKET = "cinestream-sulo.firebasestorage.app"
    private const val SENDER_ID = "979447256418"

    @Synchronized
    fun initialize(context: Context): Boolean {
        return try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                Log.i(TAG, "Firebase is already initialized")
                return true
            }

            // Prefer the standard Google Services resources generated from app/google-services.json.
            val standard = try {
                FirebaseApp.initializeApp(context)
            } catch (e: Exception) {
                Log.w(TAG, "Default Firebase initialization unavailable: ${e.message}")
                null
            }
            if (standard != null) {
                Log.i(TAG, "Initialized through Google Services resources")
                return true
            }

            val options = FirebaseOptions.Builder()
                .setApplicationId(resolveString(context, "google_app_id") ?: APP_ID)
                .setApiKey(resolveString(context, "google_api_key") ?: API_KEY)
                .setProjectId(resolveString(context, "project_id") ?: PROJECT_ID)
                .setStorageBucket(resolveString(context, "google_storage_bucket") ?: STORAGE_BUCKET)
                .setGcmSenderId(resolveString(context, "gcm_defaultSenderId") ?: SENDER_ID)
                .build()

            val initialized = FirebaseApp.initializeApp(context, options)
            val success = initialized != null && FirebaseApp.getApps(context).isNotEmpty()
            if (success) {
                Log.i(TAG, "Initialized canonical Firebase app for project $PROJECT_ID")
            } else {
                Log.e(TAG, "Explicit Firebase initialization returned no app")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Fatal Firebase initialization error", e)
            false
        }
    }

    private fun resolveString(context: Context, name: String): String? {
        return try {
            val byApplicationId = context.resources.getIdentifier(name, "string", context.packageName)
            val resourceId = if (byApplicationId != 0) byApplicationId else
                context.resources.getIdentifier(name, "string", "com.example")
            if (resourceId != 0) context.getString(resourceId).takeIf { it.isNotBlank() } else null
        } catch (_: Exception) {
            null
        }
    }
}
