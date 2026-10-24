package com.example.ui.screens.auth

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.repository.AccountDeletionResult
import com.example.data.repository.AuthRepository
import com.example.data.repository.User
import com.example.data.session.SessionManager
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AuthRepository
    
    val currentUser: StateFlow<User?> = repository.currentUserFlow
    
    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        try {
            repository.auth.addAuthStateListener { auth ->
                viewModelScope.launch {
                    val currentAuth = auth.currentUser
                    if (currentAuth != null && !currentAuth.isAnonymous) {
                        if (SessionManager.activeUid != currentAuth.uid) {
                            SessionManager.onUserLoggedIn(getApplication(), currentAuth.uid)
                        }
                        if (repository.currentUserFlow.value == null || repository.currentUserFlow.value?.uid != currentAuth.uid) {
                            repository.getCurrentUser()
                            if (repository.auth.currentUser != null) {
                                com.example.data.sync.CloudSyncManager(getApplication()).syncFromCloud(repository.auth.currentUser!!.uid)
                            }
                        }
                    } else if (currentAuth != null && currentAuth.isAnonymous) {
                        if (repository.currentUserFlow.value == null || repository.currentUserFlow.value?.uid != currentAuth.uid) {
                            repository.getCurrentUser()
                        }
                    } else if (currentAuth == null) {
                        repository.getCurrentUser()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AuthViewModel", "AuthStateListener initialization skipped: ${e.message}")
        }
    }

    fun checkCurrentUser() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.getCurrentUser()
            if (repository.auth.currentUser != null) {
                com.example.data.sync.CloudSyncManager(getApplication()).syncFromCloud(repository.auth.currentUser!!.uid)
            }
            _isLoading.value = false
        }
    }

    fun resetError() {
        _authError.value = null
    }

    /**
     * Maps Firebase and network authentication errors to user-friendly localized messages.
     * Prevents raw internal strings or account enumeration leaks.
     */
    fun mapAuthErrorMessage(e: Throwable): String {
        val app = getApplication<Application>()
        return when (e) {
            is FirebaseAuthInvalidUserException,
            is FirebaseAuthInvalidCredentialsException -> {
                app.getString(R.string.invalid_email_or_password)
            }
            is FirebaseAuthUserCollisionException -> {
                app.getString(R.string.google_account_already_linked)
            }
            is FirebaseNetworkException -> {
                app.getString(R.string.network_error_auth)
            }
            is FirebaseAuthException -> {
                when (e.errorCode) {
                    "ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND", "invalid-credential" ->
                        app.getString(R.string.invalid_email_or_password)
                    "ERROR_USER_DISABLED" ->
                        app.getString(R.string.account_disabled)
                    "ERROR_TOO_MANY_REQUESTS" ->
                        app.getString(R.string.too_many_requests)
                    "ERROR_NETWORK_REQUEST_FAILED" ->
                        app.getString(R.string.network_error_auth)
                    "ERROR_CREDENTIAL_ALREADY_IN_USE" ->
                        app.getString(R.string.google_account_already_linked)
                    else -> app.getString(R.string.invalid_email_or_password)
                }
            }
            else -> {
                if (!com.example.utils.NetworkUtils.isInternetAvailable(app)) {
                    app.getString(R.string.network_error_auth)
                } else {
                    app.getString(R.string.login_failed)
                }
            }
        }
    }

    fun handleGoogleSignIn(idToken: String, email: String?, displayName: String?, photoUrl: String?) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null)
                val authResult = kotlinx.coroutines.withTimeout(15000) { repository.auth.signInWithCredential(credential).await() }
                val firebaseUser = authResult.user
                
                if (firebaseUser != null) {
                    val user = repository.getCurrentUser() ?: repository.createDefaultUser(firebaseUser)
                    repository.currentUserFlow.value = user
                    com.example.data.repository.UserSecurityManager.listenToUserSecurity(firebaseUser.uid)
                    com.example.data.sync.CloudSyncManager(getApplication()).syncFromCloud(firebaseUser.uid)
                    SessionManager.onUserLoggedIn(getApplication(), firebaseUser.uid)
                }
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Google sign-in error", e)
                _authError.value = mapAuthErrorMessage(e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Explicit guest authentication flow using Firebase Anonymous Authentication.
     * Reuses active session if already present, or initiates anonymous sign-in with Firebase.
     */
    fun signInAnonymously(onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            _authError.value = null
            try {
                // If there is no existing Firebase user session and device has no internet, fail fast with localized error
                if (repository.auth.currentUser == null && !com.example.utils.NetworkUtils.isInternetAvailable(getApplication())) {
                    val errorMsg = getApplication<Application>().getString(R.string.network_error_auth)
                    _authError.value = errorMsg
                    onComplete(false, errorMsg)
                    return@launch
                }

                val result = repository.signInAnonymously()
                result.fold(
                    onSuccess = { fbUser ->
                        onComplete(true, null)
                    },
                    onFailure = { err ->
                        Log.e("AuthViewModel", "Anonymous sign-in error", err)
                        val mappedError = mapAuthErrorMessage(err)
                        _authError.value = mappedError
                        onComplete(false, mappedError)
                    }
                )
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Anonymous sign-in unexpected exception", e)
                val mappedError = mapAuthErrorMessage(e)
                _authError.value = mappedError
                onComplete(false, mappedError)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun linkGoogleAccount(idToken: String, onComplete: (Boolean, String?) -> Unit) {
        val app = getApplication<Application>()
        if (!com.example.utils.NetworkUtils.isInternetAvailable(app)) {
            onComplete(false, app.getString(R.string.network_error_auth))
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null)
                val result = repository.linkGoogleAccount(credential)
                result.fold(
                    onSuccess = {
                        onComplete(true, app.getString(R.string.google_account_linked_success))
                    },
                    onFailure = { err ->
                        val msg = if (err is FirebaseAuthUserCollisionException) {
                            app.getString(R.string.google_account_already_linked)
                        } else {
                            mapAuthErrorMessage(err)
                        }
                        onComplete(false, msg)
                    }
                )
            } catch (e: Exception) {
                onComplete(false, mapAuthErrorMessage(e))
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun signInWithEmail(email: String, pass: String) {
        if (!com.example.utils.NetworkUtils.isInternetAvailable(getApplication())) {
            _authError.value = getApplication<android.app.Application>().getString(com.example.R.string.network_error_auth)
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val authResult = kotlinx.coroutines.withTimeout(15000) { repository.auth.signInWithEmailAndPassword(email, pass).await() }
                val firebaseUser = authResult.user
                if (firebaseUser != null) {
                    val user = repository.getCurrentUser() ?: repository.createDefaultUser(firebaseUser)
                    repository.currentUserFlow.value = user
                    com.example.data.repository.UserSecurityManager.listenToUserSecurity(firebaseUser.uid)
                    com.example.data.sync.CloudSyncManager(getApplication()).syncFromCloud(firebaseUser.uid)
                    SessionManager.onUserLoggedIn(getApplication(), firebaseUser.uid)
                }
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Email sign-in error", e)
                _authError.value = mapAuthErrorMessage(e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun signUpWithEmail(email: String, pass: String) {
        if (!com.example.utils.NetworkUtils.isInternetAvailable(getApplication())) {
            _authError.value = getApplication<android.app.Application>().getString(com.example.R.string.network_error_auth)
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val authResult = kotlinx.coroutines.withTimeout(15000) { repository.auth.createUserWithEmailAndPassword(email, pass).await() }
                val firebaseUser = authResult.user
                if (firebaseUser != null) {
                    val generatedUsername = try {
                        repository.generateUniqueUsername(email.substringBefore("@"))
                    } catch (e: Exception) {
                        Log.e("AuthViewModel", "Cannot safely verify a username during registration", e)
                        try { firebaseUser.delete().await() } catch (cleanup: Exception) {
                            Log.w("AuthViewModel", "Best-effort Auth rollback after username-check failure did not complete", cleanup)
                        }
                        _authError.value = getApplication<Application>().getString(R.string.username_check_failed)
                        return@launch
                    }
                    val newUser = User(
                        uid = firebaseUser.uid,
                        email = email,
                        firstName = "",
                        lastName = "",
                        username = generatedUsername,
                        photoUrl = ""
                    )
                    try {
                        kotlinx.coroutines.withTimeout(15000) { repository.saveUser(newUser) }
                    } catch (e: Exception) {
                        Log.e("AuthViewModel", "Initial user profile save failed; registration will not be reported as successful", e)
                        try { firebaseUser.delete().await() } catch (cleanup: Exception) {
                            Log.w("AuthViewModel", "Best-effort Auth rollback after profile-save failure did not complete", cleanup)
                        }
                        _authError.value = getApplication<Application>().getString(R.string.failed_save_server)
                        return@launch
                    }
                    repository.currentUserFlow.value = newUser
                    com.example.data.sync.CloudSyncManager(getApplication()).syncFromCloud(firebaseUser.uid)
                    SessionManager.onUserLoggedIn(getApplication(), firebaseUser.uid)
                }
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Email sign-up error", e)
                _authError.value = mapAuthErrorMessage(e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun resetPassword(email: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.auth.sendPasswordResetEmail(email).await()
                _authError.value = getApplication<Application>().getString(R.string.password_reset_sent)
            } catch (e: Exception) {
                _authError.value = e.message ?: getApplication<Application>().getString(R.string.failed_send_reset_email)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateProfile(firstName: String, lastName: String, username: String, isProfilePublic: Boolean = true, photoUri: Uri? = null, onComplete: (Boolean, String?) -> Unit) {
        val safeUsername = username.trim().lowercase().replace(Regex("\\s+"), "")
        viewModelScope.launch {
            _isLoading.value = true
            val currentUserData = repository.currentUserFlow.value
            if (currentUserData == null) {
                onComplete(false, getApplication<Application>().getString(R.string.user_not_found))
                _isLoading.value = false
                return@launch
            }
            
            try {
                val currentUsernameNormalized = currentUserData.username.trim().lowercase().replace(Regex("\\s+"), "")
                if (safeUsername != currentUsernameNormalized) {
                    when (repository.checkUsernameAvailability(safeUsername, currentUserData.uid)) {
                        com.example.data.repository.UsernameAvailability.AVAILABLE -> Unit
                        com.example.data.repository.UsernameAvailability.TAKEN -> {
                            onComplete(false, getApplication<Application>().getString(R.string.username_already_taken))
                            _isLoading.value = false
                            return@launch
                        }
                        com.example.data.repository.UsernameAvailability.UNKNOWN -> {
                            onComplete(false, getApplication<Application>().getString(R.string.username_check_failed))
                            _isLoading.value = false
                            return@launch
                        }
                    }
                }
                
                var finalPhotoUrl = currentUserData.photoUrl
                if (photoUri != null) {
                    if (!com.example.data.repository.UserSecurityManager.canUpload()) {
                        onComplete(false, com.example.data.repository.UserSecurityManager.getUploadRestrictionMessage())
                        _isLoading.value = false
                        return@launch
                    }
                    val uploadedUrl = repository.uploadProfilePicture(currentUserData.uid, photoUri)
                    if (uploadedUrl != null) {
                        finalPhotoUrl = uploadedUrl
                    } else {
                        onComplete(false, getApplication<Application>().getString(R.string.failed_upload_image))
                        _isLoading.value = false
                        return@launch
                    }
                }
                
                val updatedUser = currentUserData.copy(
                    firstName = firstName,
                    lastName = lastName,
                    username = safeUsername,
                    photoUrl = finalPhotoUrl,
                    isProfilePublic = isProfilePublic
                )
                kotlinx.coroutines.withTimeout(15000) { repository.saveUser(updatedUser) }
                
                onComplete(true, null)
            } catch (e: Exception) {
                onComplete(false, getApplication<Application>().getString(R.string.failed_save_server))
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateBio(newBio: String) {
        val safeBio = newBio.take(100)
        viewModelScope.launch {
            val currentUserData = repository.currentUserFlow.value
            if (currentUserData != null) {
                val updatedUser = currentUserData.copy(bio = safeBio)
                repository.currentUserFlow.value = updatedUser
                try {
                    kotlinx.coroutines.withTimeout(10000) { repository.saveUser(updatedUser) }
                } catch (e: Exception) {}
            }
        }
    }

    fun updateProfilePictureDirect(photoUri: Uri?, avatarUrl: String? = null, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _isLoading.value = true
            val currentUserData = repository.currentUserFlow.value
            if (currentUserData == null) {
                _isLoading.value = false
                onComplete(false)
                return@launch
            }
            try {
                var finalUrl = avatarUrl ?: currentUserData.photoUrl
                if (photoUri != null) {
                    if (!com.example.data.repository.UserSecurityManager.canUpload()) {
                        _isLoading.value = false
                        onComplete(false)
                        return@launch
                    }
                    val uploadedUrl = repository.uploadProfilePicture(currentUserData.uid, photoUri)
                    if (uploadedUrl != null) {
                        finalUrl = uploadedUrl
                    }
                }
                val updatedUser = currentUserData.copy(photoUrl = finalUrl)
                repository.currentUserFlow.value = updatedUser
                kotlinx.coroutines.withTimeout(10000) { repository.saveUser(updatedUser) }
                onComplete(true)
            } catch (e: Exception) {
                onComplete(false)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun changePassword(currentPassword: String, newPassword: String, onComplete: (Boolean, String?) -> Unit) {
        val user = repository.auth.currentUser
        if (user == null) {
            onComplete(false, getApplication<Application>().getString(R.string.no_logged_in_user))
            return
        }
        if (user.email.isNullOrBlank()) {
            onComplete(false, getApplication<Application>().getString(R.string.account_no_email))
            return
        }
        if (newPassword.length < 6) {
            onComplete(false, getApplication<Application>().getString(R.string.password_min_length))
            return
        }

        _isLoading.value = true
        viewModelScope.launch {
            try {
                val credential = com.google.firebase.auth.EmailAuthProvider.getCredential(user.email!!, currentPassword)
                user.reauthenticate(credential).await()
                user.updatePassword(newPassword).await()
                onComplete(true, null)
            } catch (e: Exception) {
                onComplete(false, e.localizedMessage ?: getApplication<Application>().getString(R.string.failed_update_password))
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteAccount(onResult: (AccountDeletionResult) -> Unit) {
        if (!com.example.utils.NetworkUtils.isInternetAvailable(getApplication())) {
            val errorMsg = getApplication<Application>().getString(R.string.cannot_logout_offline)
            onResult(AccountDeletionResult.Failed(errorMsg))
            return
        }
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val result = repository.deleteAccount(getApplication())
                _isLoading.value = false
                onResult(result)
            } catch (e: Exception) {
                _isLoading.value = false
                onResult(AccountDeletionResult.Failed(e.localizedMessage ?: "Failed to delete account"))
            }
        }
    }

    fun signOut(onFailure: ((String) -> Unit)? = null): Boolean {
        if (!com.example.utils.NetworkUtils.isInternetAvailable(getApplication())) {
            val errorMsg = getApplication<Application>().getString(R.string.cannot_logout_offline)
            _authError.value = errorMsg
            onFailure?.invoke(errorMsg)
            return false
        }
        viewModelScope.launch { 
            try {
                SessionManager.onUserLoggedOut(getApplication())
            } catch (_: Exception) {}
        }
        return true
    }
}