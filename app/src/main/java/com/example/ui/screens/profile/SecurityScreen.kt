package com.example.ui.screens.profile
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.ui.screens.auth.GoogleCredentialParser
import com.example.ui.screens.auth.GoogleCredentialResult
import kotlinx.coroutines.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.screens.auth.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    onBack: () -> Unit,
    authViewModel: AuthViewModel = viewModel()
) {
    val bgColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val primaryRed = MaterialTheme.colorScheme.primary

    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isUpdating by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.security), color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = surfaceColor)
            )
        },
        containerColor = bgColor
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text(stringResource(R.string.change_password), color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = currentPassword,
                onValueChange = { 
                    currentPassword = it 
                    statusMessage = null
                },
                label = { Text(stringResource(R.string.current_password), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = primaryRed,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = newPassword,
                onValueChange = { 
                    newPassword = it 
                    statusMessage = null
                },
                label = { Text(stringResource(R.string.new_password), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = primaryRed,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground
                )
            )

            statusMessage?.let { msg ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = msg,
                    color = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    if (currentPassword.isBlank() || newPassword.isBlank()) {
                        statusMessage = "Please enter both current and new passwords"
                        isSuccess = false
                        return@Button
                    }
                    if (newPassword.length < 6) {
                        statusMessage = "New password must be at least 6 characters"
                        isSuccess = false
                        return@Button
                    }
                    isUpdating = true
                    statusMessage = null
                    authViewModel.changePassword(currentPassword, newPassword) { success, err ->
                        isUpdating = false
                        if (success) {
                            isSuccess = true
                            statusMessage = "Password updated successfully!"
                            currentPassword = ""
                            newPassword = ""
                        } else {
                            isSuccess = false
                            statusMessage = err ?: "Failed to update password"
                        }
                    }
                },
                enabled = !isUpdating,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primaryRed),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isUpdating) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.update_password), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            
            Spacer(modifier = Modifier.height(28.dp))
            Text(stringResource(R.string.linked_accounts_title), color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))

            val firebaseAuth = remember { com.google.firebase.auth.FirebaseAuth.getInstance() }
            val currentFbUser = firebaseAuth.currentUser
            var isGoogleLinked by remember(currentFbUser) {
                mutableStateOf(currentFbUser?.providerData?.any { it.providerId == "google.com" } == true)
            }
            var isLinkingGoogle by remember { mutableStateOf(false) }
            val coroutineScope = rememberCoroutineScope()
            val context = LocalContext.current

            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "G",
                            color = primaryRed,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Google",
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            val googleEmail = currentFbUser?.providerData?.firstOrNull { it.providerId == "google.com" }?.email
                            Text(
                                text = if (isGoogleLinked) {
                                    googleEmail ?: stringResource(R.string.google_account_linked)
                                } else {
                                    stringResource(R.string.google_account_not_linked)
                                },
                                color = if (isGoogleLinked) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }

                        if (isGoogleLinked) {
                            Text(
                                text = "✓",
                                color = Color(0xFF4CAF50),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Button(
                                onClick = {
                                    if (isLinkingGoogle) return@Button
                                    if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                        Toast.makeText(context, context.getString(R.string.network_error_auth), Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    val webClientId = com.example.BuildConfig.WEB_CLIENT_ID
                                    if (!GoogleCredentialParser.validateWebClientId(webClientId)) {
                                        Toast.makeText(context, context.getString(R.string.add_web_client_id_secret), Toast.LENGTH_LONG).show()
                                        return@Button
                                    }
                                    val activity = generateSequence(context) { if (it is android.content.ContextWrapper) it.baseContext else null }.filterIsInstance<android.app.Activity>().firstOrNull()
                                    if (activity == null) {
                                        Toast.makeText(context, "Activity context not available", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    coroutineScope.launch {
                                        isLinkingGoogle = true
                                        try {
                                            val credentialManager = androidx.credentials.CredentialManager.create(activity)
                                            val randomBytes = ByteArray(32)
                                            java.security.SecureRandom().nextBytes(randomBytes)
                                            val nonce = java.security.MessageDigest.getInstance("SHA-256").digest(randomBytes).joinToString("") { "%02x".format(it) }
                                            val option = com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption.Builder(serverClientId = webClientId)
                                                .setNonce(nonce)
                                                .build()
                                            val request = androidx.credentials.GetCredentialRequest.Builder()
                                                .addCredentialOption(option)
                                                .build()
                                            val result = credentialManager.getCredential(activity, request)
                                            when (val parsed = GoogleCredentialParser.parseCredential(result.credential)) {
                                                is GoogleCredentialResult.Success -> {
                                                    authViewModel.linkGoogleAccount(parsed.credential.idToken) { success, msg ->
                                                        isLinkingGoogle = false
                                                        if (success) {
                                                            isGoogleLinked = true
                                                            Toast.makeText(context, msg ?: context.getString(R.string.google_account_linked_success), Toast.LENGTH_LONG).show()
                                                        } else {
                                                            Toast.makeText(context, msg ?: "Failed to link Google account", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                }
                                                else -> {
                                                    isLinkingGoogle = false
                                                    Toast.makeText(context, "Could not retrieve Google credential", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
                                            isLinkingGoogle = false
                                        } catch (e: Exception) {
                                            isLinkingGoogle = false
                                            Toast.makeText(context, authViewModel.mapAuthErrorMessage(e), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                enabled = !isLinkingGoogle,
                                colors = ButtonDefaults.buttonColors(containerColor = primaryRed),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                if (isLinkingGoogle) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(
                                        text = stringResource(R.string.link_google_account),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            Text(stringResource(R.string.active_devices), color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            
            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = primaryRed, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.iphone_13), color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.active_now_riyadh), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.macbook_air), color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.last_active_yesterday), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                        TextButton(onClick = {}) {
                            Text(stringResource(R.string.sign_out2), color = primaryRed)
                        }
                    }
                }
            }
        }
    }
}