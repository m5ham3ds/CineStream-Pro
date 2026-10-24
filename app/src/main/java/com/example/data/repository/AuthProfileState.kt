package com.example.data.repository

import com.google.firebase.auth.FirebaseUser

/**
 * PHASE 22.19-A: Profile State Machine
 *
 * Differentiates cleanly between:
 * - Loading: Initial check or in-flight profile fetch
 * - Unauthenticated: No active Firebase Authentication session
 * - AuthenticatedWithProfile: Valid Firebase Auth session and resolved Firestore user document
 * - AuthenticatedProfileMissing: Valid Firebase Auth session, but /users/{uid} document does not exist
 * - ProfileLoadFailed: Valid Firebase Auth session, but Firestore document fetch failed (e.g. network timeout)
 */
sealed class AuthProfileState {
    object Loading : AuthProfileState()
    object Unauthenticated : AuthProfileState()
    data class AuthenticatedWithProfile(val user: User) : AuthProfileState()
    data class AuthenticatedProfileMissing(val firebaseUser: FirebaseUser? = null) : AuthProfileState()
    data class ProfileLoadFailed(val firebaseUser: FirebaseUser? = null, val error: Throwable) : AuthProfileState()
}
