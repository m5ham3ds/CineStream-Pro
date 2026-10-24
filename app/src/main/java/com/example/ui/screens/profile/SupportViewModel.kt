package com.example.ui.screens.profile

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.db.AppDatabase
import com.example.data.model.SupportMessage
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

class SupportViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).supportDao()
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private var listenerRegistration: ListenerRegistration? = null

    val messages: StateFlow<List<SupportMessage>> = dao.getAllMessages()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            if (dao.getMessageCount() == 0) {
                // Initial greeting message
                dao.insertMessage(
                    SupportMessage(
                        text = application.getString(R.string.support_welcome_message),
                        isFromUser = false
                    )
                )
            }
            startRemoteSupportSync()
        }
    }

    private fun startRemoteSupportSync() {
        val user = auth.currentUser ?: return
        listenerRegistration?.remove()

        listenerRegistration = db.collection("support_conversations")
            .document(user.uid)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("SupportViewModel", "Remote support listen error: ${error.message}")
                    listenerRegistration = null
                    return@addSnapshotListener
                }

                val docs = snapshot?.documents ?: return@addSnapshotListener
                viewModelScope.launch {
                    for (doc in docs) {
                        val text = doc.getString("text") ?: continue
                        val senderRole = doc.getString("senderRole") ?: "user"
                        val senderId = doc.getString("senderId") ?: ""
                        val isFromUser = senderRole == "user" && senderId == user.uid
                        val timestamp = when (val t = doc.get("timestamp")) {
                            is Number -> t.toLong()
                            is Timestamp -> t.toDate().time
                            else -> System.currentTimeMillis()
                        }
                        val msg = SupportMessage(
                            id = doc.id,
                            text = text,
                            isFromUser = isFromUser,
                            timestamp = timestamp
                        )
                        dao.insertMessage(msg)
                    }
                }
            }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val user = auth.currentUser
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        viewModelScope.launch {
            // 1. Immediately cache in local Room database
            dao.insertMessage(SupportMessage(id = msgId, text = text.trim(), isFromUser = true, timestamp = now))

            // 2. If authenticated, dispatch to Firestore canonical support path
            if (user != null) {
                try {
                    val convRef = db.collection("support_conversations").document(user.uid)
                    val convSnapshot = convRef.get().await()
                    if (convSnapshot.exists()) {
                        // Do not reset status to "open" when an existing ticket is
                        // resolved/closed. The user message updates activity metadata only.
                        convRef.update(
                            mapOf(
                                "lastMessage" to text.trim(),
                                "lastMessageTime" to now,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                    } else {
                        val convData = mapOf(
                            "conversationId" to user.uid,
                            "userId" to user.uid,
                            "userEmail" to (user.email ?: ""),
                            "userName" to (user.displayName ?: "User"),
                            "status" to "open",
                            "lastMessage" to text.trim(),
                            "lastMessageTime" to now,
                            "updatedAt" to FieldValue.serverTimestamp(),
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                        convRef.set(convData).await()
                    }

                    val msgRef = convRef.collection("messages").document(msgId)
                    val msgData = mapOf(
                        "id" to msgId,
                        "messageId" to msgId,
                        "conversationId" to user.uid,
                        "senderId" to user.uid,
                        "senderRole" to "user",
                        "text" to text.trim(),
                        "timestamp" to now,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                    msgRef.set(msgData).await()

                    if (listenerRegistration == null) {
                        startRemoteSupportSync()
                    }
                } catch (e: Exception) {
                    Log.e("SupportViewModel", "Failed to sync message to Firestore", e)
                }
            } else {
                // 3. Guest fallback: simulated local bot response
                delay(1000)
                val response = generateResponse(text.trim())
                dao.insertMessage(SupportMessage(text = response, isFromUser = false))
            }
        }
    }

    private fun generateResponse(input: String): String {
        val app = getApplication<Application>()
        val lower = input.lowercase()
        return when {
            lower.contains("premium") || lower.contains("plan") -> app.getString(R.string.support_response_premium)
            lower.contains("device") || lower.contains("multiple") -> app.getString(R.string.support_response_device)
            lower.contains("hello") || lower.contains("hi") -> app.getString(R.string.support_response_greeting)
            lower.contains("issue") || lower.contains("problem") || lower.contains("playback") -> app.getString(R.string.support_response_issue)
            else -> app.getString(R.string.support_response_default)
        }
    }

    fun clearChat() {
        viewModelScope.launch {
            dao.clearMessages()
            dao.insertMessage(
                SupportMessage(
                    text = getApplication<Application>().getString(R.string.support_welcome_message),
                    isFromUser = false
                )
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        listenerRegistration?.remove()
        listenerRegistration = null
    }
}
