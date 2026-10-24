package com.example.ui.screens.auth

import androidx.compose.ui.res.stringResource
import com.example.R

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.example.ui.ViewModelFactory
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.example.BuildConfig
import com.example.data.repository.UserPreferencesRepository

private fun Context.findActivity(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) {
            return currentContext
        }
        currentContext = currentContext.baseContext
    }
    return null
}

private fun generateSecureNonce(): String {
    val randomBytes = ByteArray(32)
    SecureRandom().nextBytes(randomBytes)
    val digest = MessageDigest.getInstance("SHA-256").digest(randomBytes)
    return digest.joinToString("") { "%02x".format(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    onSkip: () -> Unit,
    onAuthSuccess: () -> Unit,
    userPrefs: UserPreferencesRepository = UserPreferencesRepository(LocalContext.current)
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authViewModel: AuthViewModel = viewModel(factory = ViewModelFactory())
    val currentUser by authViewModel.currentUser.collectAsState()
    val authError by authViewModel.authError.collectAsState()
    val isLoading by authViewModel.isLoading.collectAsState()

    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberMe by remember { mutableStateOf(false) }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }
    var isGuestLoading by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val primaryRed = MaterialTheme.colorScheme.primary
    val bgColor = MaterialTheme.colorScheme.background
    val cardColor = MaterialTheme.colorScheme.surface

    LaunchedEffect(currentUser) {
        val fbUser = com.example.data.repository.AuthRepository.auth.currentUser
        val isAnon = fbUser?.isAnonymous ?: false
        if (currentUser != null && !isAnon) {
            Toast.makeText(context, context.getString(R.string.signed_in_successfully), Toast.LENGTH_SHORT).show()
            userPrefs.saveIsGuest(false)
            userPrefs.saveIsLoggedIn(true)
            onAuthSuccess()
        }
    }

    LaunchedEffect(authError) {
        authError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            authViewModel.resetError()
        }
    }

    fun signInWithGoogle() {
        val webClientId = BuildConfig.WEB_CLIENT_ID
        if (!GoogleCredentialParser.validateWebClientId(webClientId)) {
            Toast.makeText(context, context.getString(R.string.add_web_client_id_secret), Toast.LENGTH_LONG).show()
            return
        }

        val activity = context.findActivity()
        if (activity == null) {
            Log.e("AuthScreen", "Google Sign-In requires an Activity context")
            Toast.makeText(context, context.getString(R.string.google_sign_in_failed, "Activity context unavailable"), Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch {
            try {
                val credentialManager = CredentialManager.create(activity)
                val secureNonce = generateSecureNonce()

                val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(serverClientId = webClientId)
                    .setNonce(secureNonce)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(signInWithGoogleOption)
                    .build()

                val result = credentialManager.getCredential(activity, request)

                when (val parsed = GoogleCredentialParser.parseCredential(result.credential)) {
                    is GoogleCredentialResult.Success -> {
                        val tokenCredential = parsed.credential
                        authViewModel.handleGoogleSignIn(
                            idToken = tokenCredential.idToken,
                            email = tokenCredential.id,
                            displayName = tokenCredential.displayName,
                            photoUrl = tokenCredential.profilePictureUri?.toString()
                        )
                    }
                    is GoogleCredentialResult.UnexpectedType -> {
                        Log.w("AuthScreen", "Google Sign-In returned unexpected credential type: ${parsed.actualType}")
                        Toast.makeText(
                            context,
                            context.getString(R.string.google_sign_in_failed, "Unexpected credential type: ${parsed.actualType}"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is GoogleCredentialResult.NotCustomCredential -> {
                        Log.w("AuthScreen", "Google Sign-In returned non-custom credential format")
                        Toast.makeText(
                            context,
                            context.getString(R.string.google_sign_in_failed, "Unsupported credential format received"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is GoogleCredentialResult.ParsingError -> {
                        Log.w("AuthScreen", "Google Sign-In parsing failed: ${parsed.message}")
                        Toast.makeText(
                            context,
                            context.getString(R.string.google_sign_in_failed, parsed.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: NoCredentialException) {
                Log.w("AuthScreen", "Google Sign-In NoCredentialException: ${e.message}")
                Toast.makeText(
                    context,
                    context.getString(R.string.no_google_accounts_found),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: GetCredentialCancellationException) {
                // User intentionally cancelled or dismissed account picker; do not show error toast
            } catch (e: GetCredentialException) {
                Log.w("AuthScreen", "Google Sign-In GetCredentialException: ${e.message}")
                Toast.makeText(context, context.getString(R.string.google_sign_in_failed, e.message ?: "Credential provider error"), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.w("AuthScreen", "Google Sign-In Exception: ${e.message}")
                Toast.makeText(context, context.getString(R.string.google_sign_in_failed, e.message ?: "Authentication failed"), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Isolated bottom-sheet / One-Tap sign-in flow utilizing GetGoogleIdOption.
     * Kept fully separate from the explicit button flow.
     */
    fun launchGoogleOneTapBottomSheet() {
        val webClientId = BuildConfig.WEB_CLIENT_ID
        if (!GoogleCredentialParser.validateWebClientId(webClientId)) return
        val activity = context.findActivity() ?: return
        scope.launch {
            try {
                val credentialManager = CredentialManager.create(activity)
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(true)
                    .setServerClientId(webClientId)
                    .setAutoSelectEnabled(false)
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
                val result = credentialManager.getCredential(activity, request)
                val parsed = GoogleCredentialParser.parseCredential(result.credential)
                if (parsed is GoogleCredentialResult.Success) {
                    val tokenCredential = parsed.credential
                    authViewModel.handleGoogleSignIn(
                        idToken = tokenCredential.idToken,
                        email = tokenCredential.id,
                        displayName = tokenCredential.displayName,
                        photoUrl = tokenCredential.profilePictureUri?.toString()
                    )
                }
            } catch (e: Exception) {
                Log.d("AuthScreen", "One-Tap bottom sheet unavailable or dismissed: ${e.javaClass.simpleName}")
            }
        }
    }

    if (showForgotPasswordDialog) {
        var resetEmail by remember { mutableStateOf(email) }
        AlertDialog(
            onDismissRequest = { showForgotPasswordDialog = false },
            title = { Text(stringResource(R.string.reset_password)) },
            text = {
                OutlinedTextField(
                    value = resetEmail,
                    onValueChange = { resetEmail = it },
                    placeholder = { Text(stringResource(R.string.enter_email)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (resetEmail.isNotBlank()) {
                        authViewModel.resetPassword(resetEmail)
                        showForgotPasswordDialog = false
                    }
                }) {
                    Text(stringResource(R.string.send), color = primaryRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showForgotPasswordDialog = false }) {
                    Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = stringResource(R.string.app_name),
                color = primaryRed,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 42.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = if (isSignUp) "Create Account" else "Welcome Back!",
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isSignUp) "Sign up to start your cinematic journey" else "Sign in to continue your cinematic journey",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(32.dp))

            // Input Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor, RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    placeholder = { Text(stringResource(R.string.email_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    leadingIcon = { Icon(Icons.Outlined.Email, contentDescription = null, tint = primaryRed) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                        cursorColor = primaryRed
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = { Text(stringResource(R.string.password), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null, tint = primaryRed) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { 
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                            Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                            return@KeyboardActions
                        }
                        if (email.isNotBlank() && password.isNotBlank()) {
                            if (isSignUp) {
                                authViewModel.signUpWithEmail(email, password)
                            } else {
                                authViewModel.signInWithEmail(email, password)
                            }
                        }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                        cursorColor = primaryRed
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = rememberMe,
                            onCheckedChange = { rememberMe = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = primaryRed,
                                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                checkmarkColor = MaterialTheme.colorScheme.onBackground
                            )
                        )
                        Text(stringResource(R.string.remember_me), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    }
                    if (!isSignUp) {
                        Text(text = stringResource(R.string.forgot_password),
                            color = primaryRed,
                            fontSize = 14.sp,
                            modifier = Modifier.clickable { showForgotPasswordDialog = true }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                            Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (email.isNotBlank() && password.isNotBlank()) {
                            if (isSignUp) {
                                authViewModel.signUpWithEmail(email, password)
                            } else {
                                authViewModel.signInWithEmail(email, password)
                            }
                        } else {
                            Toast.makeText(context, context.getString(R.string.please_enter_email_password), Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryRed),
                    shape = RoundedCornerShape(8.dp),
                    enabled = !isLoading && !isGuestLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(24.dp))
                    } else {
                        Text(if (isSignUp) stringResource(R.string.sign_up) else stringResource(R.string.sign_in), color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceVariant)
                Text(stringResource(R.string.continue_with), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceVariant)
            }
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = {
                    if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                        Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                    } else {
                        signInWithGoogle()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onBackground),
                shape = RoundedCornerShape(8.dp),
                enabled = !isLoading && !isGuestLoading
            ) {
                Text("G ", color = MaterialTheme.colorScheme.background, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text(stringResource(R.string.sign_in_google), color = MaterialTheme.colorScheme.background, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(32.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isSignUp) "Already have an account? " else "Don't have an account? ",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
                Text(
                    text = if (isSignUp) "Sign In" else "Sign Up",
                    color = primaryRed,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { if (!isLoading && !isGuestLoading) isSignUp = !isSignUp }
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
            OutlinedButton(
                onClick = {
                    if (isGuestLoading || isLoading) return@OutlinedButton
                    isGuestLoading = true
                    authViewModel.signInAnonymously { success, errorMsg ->
                        if (success) {
                            scope.launch {
                                userPrefs.saveIsGuest(true)
                                userPrefs.saveIsLoggedIn(false)
                                isGuestLoading = false
                                onSkip()
                            }
                        } else {
                            isGuestLoading = false
                            val message = errorMsg ?: context.getString(R.string.login_failed)
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("continue_as_guest_button"),
                enabled = !isLoading && !isGuestLoading,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                if (isGuestLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    Text(
                        text = stringResource(R.string.continue_as_guest),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}