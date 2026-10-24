package com.example.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.net.Uri

data class UserProfile(
    val uid: String = "",
    val username: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val photoUrl: String = "",
    val isOnline: Boolean = false,
    val isProfilePublic: Boolean = true,
    val bio: String = "",
    val isPremium: Boolean = false
) {
    @get:com.google.firebase.firestore.Exclude
    val displayName: String
        get() = "${firstName} ${lastName}".trim().takeIf { it.isNotBlank() } ?: username
}

data class PrivateMessage(
    val id: String = "",
    val senderId: String = "",
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    @get:com.google.firebase.firestore.PropertyName("isDeleted")
    val isDeleted: Boolean = false,
    @get:com.google.firebase.firestore.PropertyName("isEdited")
    val isEdited: Boolean = false,
    @get:com.google.firebase.firestore.PropertyName("isVoice")
    val isVoice: Boolean = false,
    val mediaUrl: String? = null,
    val deletedFor: List<String> = emptyList(),
    val reactions: Map<String, String> = emptyMap()
)

data class Conversation(
    val id: String = "",
    val participants: List<String> = emptyList(),
    val participantNames: Map<String, String> = emptyMap(),
    val lastMessage: String = "",
    val lastMessageTime: Long = 0L,
    val unreadCounts: Map<String, Int> = emptyMap(),
    val isGroup: Boolean = false,
    val isRequest: Boolean = false
)

data class Story(
    val id: String = "",
    val userId: String = "",
    val imageUrl: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

class SocialRepository {

    companion object {
        private val userProfileCache = mutableMapOf<String, UserProfile>()

        fun clearCache() {
            synchronized(userProfileCache) {
                userProfileCache.clear()
            }
        }
    }

    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()

    fun getCurrentUser(): UserProfile? {
        val user = auth.currentUser ?: return null
        return UserProfile(uid = user.uid)
    }

    suspend fun getUserProfile(uid: String, forceRefresh: Boolean = false): UserProfile? {
        if (!forceRefresh && userProfileCache.containsKey(uid)) return userProfileCache[uid]
        return try {
            val collection = if (uid == auth.currentUser?.uid) "users" else "public_profiles"
            val doc = db.collection(collection).document(uid).get().await()
            val p = doc.toObject(UserProfile::class.java)
            if (p != null) userProfileCache[uid] = p
            p
        } catch (e: Exception) {
            null
        }
    }

    fun getUserProfileFlow(userId: String): Flow<UserProfile?> = callbackFlow {
        if (userProfileCache.containsKey(userId)) {
            trySend(userProfileCache[userId])
        }
        val collection = if (userId == auth.currentUser?.uid) "users" else "public_profiles"
        val listener = db.collection(collection).document(userId).addSnapshotListener { snapshot, _ ->
            if (snapshot != null) {
                val profile = snapshot.toObject(UserProfile::class.java)
                if (profile != null) {
                    userProfileCache[userId] = profile
                    trySend(profile)
                }
            }
        }
        awaitClose { listener?.remove() }
    }

    fun getStories(): Flow<List<Story>> = callbackFlow {
        val listener = db.collection("stories")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val stories = snapshot.toObjects(Story::class.java)
                    trySend(stories)
                }
            }
        awaitClose { listener.remove() }
    }

    fun getConversations(): Flow<List<Conversation>> = callbackFlow {
        val user = auth.currentUser
        if (user == null || user.uid.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val conversationsById = linkedMapOf<String, Conversation>()
        var orderedConversationIds: List<String> = emptyList()
        val readTimes = mutableMapOf<String, Long>()
        val unreadById = mutableMapOf<String, Int>()
        val receiptListeners = mutableMapOf<String, com.google.firebase.firestore.ListenerRegistration>()
        val messageListeners = mutableMapOf<String, com.google.firebase.firestore.ListenerRegistration>()

        fun emitConversations() {
            val merged = orderedConversationIds.mapNotNull { id -> conversationsById[id] }.map { conversation ->
                val unread = unreadById[conversation.id] ?: 0
                conversation.copy(unreadCounts = conversation.unreadCounts + (user.uid to unread))
            }
            trySend(merged)
        }

        fun attachUnreadMessageListener(conversationId: String, afterMillis: Long) {
            messageListeners.remove(conversationId)?.remove()
            val query = db.collection("conversations")
                .document(conversationId)
                .collection("messages")
                .whereGreaterThan("timestamp", afterMillis)
            messageListeners[conversationId] = query.addSnapshotListener { messages, error ->
                if (error != null) {
                    android.util.Log.w("SocialRepository", "Unread message listener failed for $conversationId: ${error.message}")
                    unreadById[conversationId] = 0
                    emitConversations()
                    return@addSnapshotListener
                }
                if (messages != null) {
                    unreadById[conversationId] = messages.documents.count { message ->
                        val senderId = message.getString("senderId") ?: ""
                        val isDeleted = message.getBoolean("isDeleted") == true
                        val deletedFor = message.get("deletedFor") as? List<*> ?: emptyList<Any>()
                        senderId.isNotBlank() && senderId != user.uid && !isDeleted && user.uid !in deletedFor
                    }
                    emitConversations()
                }
            }
        }

        fun attachReceiptListener(conversationId: String) {
            if (receiptListeners.containsKey(conversationId)) return
            receiptListeners[conversationId] = db.collection("conversations")
                .document(conversationId)
                .collection("readReceipts")
                .document(user.uid)
                .addSnapshotListener { receipt, error ->
                    if (error != null) {
                        android.util.Log.w("SocialRepository", "Read-receipt listener failed for $conversationId: ${error.message}")
                        return@addSnapshotListener
                    }
                    val lastReadValue = receipt?.get("lastReadAt")
                    val lastReadMillis = when (lastReadValue) {
                        is Number -> lastReadValue.toLong()
                        is com.google.firebase.Timestamp -> lastReadValue.toDate().time
                        else -> 0L
                    }
                    if (readTimes[conversationId] != lastReadMillis || !messageListeners.containsKey(conversationId)) {
                        readTimes[conversationId] = lastReadMillis
                        attachUnreadMessageListener(conversationId, lastReadMillis)
                    }
                }
        }

        val conversationListener = db.collection("conversations")
            .whereArrayContains("participants", user.uid)
            .orderBy("lastMessageTime", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.w("SocialRepository", "Error listening to conversations for ${user.uid}: ${error.message}")
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val currentIds = snapshot.documents.map { it.id }.toSet()
                    orderedConversationIds = snapshot.documents.map { it.id }
                    (receiptListeners.keys - currentIds).forEach { id ->
                        receiptListeners.remove(id)?.remove()
                        messageListeners.remove(id)?.remove()
                        readTimes.remove(id)
                        unreadById.remove(id)
                        conversationsById.remove(id)
                    }
                    snapshot.documents.forEach { document ->
                        val conversation = document.toObject(Conversation::class.java)
                            ?: return@forEach
                        val normalized = conversation.copy(id = document.id)
                        conversationsById[document.id] = normalized
                        attachReceiptListener(document.id)
                    }
                    emitConversations()
                }
            }

        awaitClose {
            conversationListener.remove()
            receiptListeners.values.forEach { it.remove() }
            messageListeners.values.forEach { it.remove() }
            receiptListeners.clear()
            messageListeners.clear()
            readTimes.clear()
            unreadById.clear()
            orderedConversationIds = emptyList()
            conversationsById.clear()
        }
    }

    suspend fun getConversation(conversationId: String): Conversation? {
        if (conversationId.isBlank()) return null
        return try {
            val doc = db.collection("conversations").document(conversationId).get().await()
            doc.toObject(Conversation::class.java)
        } catch (e: Exception) {
            android.util.Log.w("SocialRepository", "Error getting conversation $conversationId: ${e.message}")
            null
        }
    }

    suspend fun uploadMedia(uri: Uri): String? {
        if (!com.example.data.repository.UserSecurityManager.canUpload()) {
            try { android.util.Log.w("SocialRepository", "Upload blocked: ${com.example.data.repository.UserSecurityManager.getUploadRestrictionMessage()}") } catch (_: Throwable) {}
            return null
        }
        return suspendCancellableCoroutine { continuation ->
            try {
                com.cloudinary.android.MediaManager.get().upload(uri)
                    .unsigned(com.example.BuildConfig.CLOUDINARY_UPLOAD_PRESET)
                    .callback(object : com.cloudinary.android.callback.UploadCallback {
                        override fun onStart(requestId: String?) {}
                        override fun onProgress(requestId: String?, bytes: Long, totalBytes: Long) {}
                        override fun onSuccess(requestId: String?, resultData: MutableMap<Any?, Any?>?) {
                            val secureUrl = resultData?.get("secure_url") as? String
                            continuation.resume(secureUrl)
                        }
                        override fun onError(requestId: String?, error: com.cloudinary.android.callback.ErrorInfo?) {
                            android.util.Log.e("SocialRepository", "Upload Error: ${error?.description}")
                            continuation.resume(null)
                        }
                        override fun onReschedule(requestId: String?, error: com.cloudinary.android.callback.ErrorInfo?) {
                            continuation.resume(null)
                        }
                    }).dispatch()
            } catch (e: Exception) {
                android.util.Log.e("SocialRepository", "Exception during upload", e)
                continuation.resume(null)
            }
        }
    }

    suspend fun uploadMedia(filePath: String): String? {
        if (!com.example.data.repository.UserSecurityManager.canUpload()) {
            try { android.util.Log.w("SocialRepository", "Upload blocked: ${com.example.data.repository.UserSecurityManager.getUploadRestrictionMessage()}") } catch (_: Throwable) {}
            return null
        }
        return suspendCancellableCoroutine { continuation ->
            try {
                com.cloudinary.android.MediaManager.get().upload(filePath)
                    .unsigned(com.example.BuildConfig.CLOUDINARY_UPLOAD_PRESET)
                    .callback(object : com.cloudinary.android.callback.UploadCallback {
                        override fun onStart(requestId: String?) {}
                        override fun onProgress(requestId: String?, bytes: Long, totalBytes: Long) {}
                        override fun onSuccess(requestId: String?, resultData: MutableMap<Any?, Any?>?) {
                            val secureUrl = resultData?.get("secure_url") as? String
                            var format = resultData?.get("format") as? String
                            var finalUrl = secureUrl
                            // Cloudinary sometimes returns .m4a as video, it's fine, the URL works.
                            continuation.resume(finalUrl)
                        }
                        override fun onError(requestId: String?, error: com.cloudinary.android.callback.ErrorInfo?) {
                            android.util.Log.e("SocialRepository", "Upload Error: ${error?.description}")
                            continuation.resume(null)
                        }
                        override fun onReschedule(requestId: String?, error: com.cloudinary.android.callback.ErrorInfo?) {
                            continuation.resume(null)
                        }
                    }).dispatch()
            } catch (e: Exception) {
                android.util.Log.e("SocialRepository", "Exception during upload", e)
                continuation.resume(null)
            }
        }
    }

    fun getMessages(conversationId: String): Flow<List<PrivateMessage>> = callbackFlow {
        if (conversationId.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val listener = db.collection("conversations").document(conversationId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.w("SocialRepository", "Error listening to messages for $conversationId: ${error.message}")
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val msgs = snapshot.toObjects(PrivateMessage::class.java)
                    trySend(msgs)
                }
            }
        awaitClose { listener.remove() }
    }

    fun markConversationAsRead(conversationId: String) {
        val user = auth.currentUser ?: return
        if (conversationId.isBlank()) return
        // Store the read receipt in a per-user document. Never rewrite the
        // shared unreadCounts map, which would let one participant alter another's state.
        db.collection("conversations").document(conversationId)
            .collection("readReceipts").document(user.uid)
            .set(
                mapOf(
                    "uid" to user.uid,
                    "lastReadAt" to System.currentTimeMillis(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
            .addOnFailureListener { error ->
                android.util.Log.w("SocialRepository", "Failed to update read receipt", error)
            }
    }

    suspend fun createOrGetConversation(otherUserId: String, otherUserName: String): String {
        val user = auth.currentUser ?: return ""
        val currentUid = user.uid
        val targetUid = otherUserId.trim()
        if (currentUid.isBlank() || targetUid.isBlank() || targetUid == currentUid) {
            android.util.Log.w("SocialRepository", "Cannot create conversation with invalid or self participants: ($currentUid, $targetUid)")
            return ""
        }
        val dbUserDoc = try { db.collection("users").document(currentUid).get().await() } catch (_: Exception) { null }
        val realUser = dbUserDoc?.toObject(UserProfile::class.java) ?: UserProfile(uid = currentUid)
        val participants = listOf(currentUid, targetUid).sorted()
        val convId = participants.joinToString("_")
        
        val docRef = db.collection("conversations").document(convId)
        val doc = docRef.get().await()
        
        if (!doc.exists()) {
            val conv = Conversation(
                id = convId,
                participants = participants,
                participantNames = mapOf(currentUid to realUser.displayName, targetUid to otherUserName.trim())
            )
            docRef.set(conv).await()
        }
        return convId
    }

    fun sendMessage(conversationId: String, text: String) {
        val user = auth.currentUser ?: return
        if (text.isBlank() || conversationId.isBlank()) return
        val docRef = db.collection("conversations").document(conversationId)
        
        val msgRef = docRef.collection("messages").document()
        val now = System.currentTimeMillis()
        val msg = PrivateMessage(msgRef.id, user.uid, text.trim(), now)
        
        // Write message document first; update conversation preview only upon write confirmation
        msgRef.set(msg).addOnSuccessListener {
            db.runTransaction { transaction ->
                val convSnapshot = transaction.get(docRef)
                if (convSnapshot.exists()) {
                    // Unread counters are intentionally not client-writable. Per-user
                    // read receipts are used until a trusted counter updater is added.
                    transaction.update(
                        docRef,
                        "lastMessage", text.trim(),
                        "lastMessageTime", now
                    )
                }
            }.addOnFailureListener { error ->
                android.util.Log.w("SocialRepository", "Message written but failed to update conversation preview", error)
            }
        }.addOnFailureListener { error ->
            android.util.Log.e("SocialRepository", "Failed to send message: ${error.message}", error)
        }
    }

    fun searchUsers(query: String): Flow<List<UserProfile>> = callbackFlow {
        if (query.isBlank()) {
            trySend(emptyList())
            return@callbackFlow
        }
        // Search the limited public projection, never the private /users collection.
        // Firestore Rules are not filters, so the query must constrain visibility.
        val normalizedQuery = query.trim()
        val listener = db.collection("public_profiles")
            .whereEqualTo("isProfilePublic", true)
            .orderBy("username")
            .startAt(normalizedQuery)
            .endAt(normalizedQuery + "\uf8ff")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val result = snapshot.toObjects(UserProfile::class.java)
                    trySend(result.filter { it.uid != auth.currentUser?.uid })
                }
            }
        awaitClose { listener?.remove() }
    }

    suspend fun saveUserProfile() {
        val user = auth.currentUser ?: return
        val username = user.displayName?.takeIf { it.isNotBlank() } ?: "User"
        val photoUrl = user.photoUrl?.toString() ?: ""
        val privateRef = db.collection("users").document(user.uid)
        val existing = privateRef.get().await()
        val privateMap = mapOf<String, Any>(
            "uid" to user.uid,
            "id" to user.uid,
            "username" to (existing.getString("username") ?: username),
            "photoUrl" to (existing.getString("photoUrl") ?: photoUrl),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        privateRef.set(privateMap, com.google.firebase.firestore.SetOptions.merge()).await()

        // Mirror only explicitly public fields. Never copy the full /users document.
        val publicMap = mapOf<String, Any>(
            "uid" to user.uid,
            "username" to (existing.getString("username") ?: username),
            "firstName" to (existing.getString("firstName") ?: ""),
            "lastName" to (existing.getString("lastName") ?: ""),
            "photoUrl" to (existing.getString("photoUrl") ?: photoUrl),
            "isOnline" to (existing.getBoolean("isOnline") ?: false),
            "isProfilePublic" to (existing.getBoolean("isProfilePublic") ?: true),
            "bio" to (existing.getString("bio") ?: ""),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        db.collection("public_profiles").document(user.uid)
            .set(publicMap).await()
    }

    suspend fun startConversation(otherUserId: String, otherUserName: String): String {
        return createOrGetConversation(otherUserId, otherUserName)
    }

    suspend fun addStory(imageUrl: String) {
        if (!com.example.data.repository.UserSecurityManager.canUpload() || !com.example.data.repository.UserSecurityManager.canPostStory()) {
            android.util.Log.w("SocialRepository", "addStory blocked by permissions: ${com.example.data.repository.UserSecurityManager.getUploadRestrictionMessage()}")
            return
        }
        val user = auth.currentUser ?: return
        val story = Story(id = "", userId = user.uid, imageUrl = imageUrl, timestamp = System.currentTimeMillis())
        val ref = db.collection("stories").document()
        ref.set(story.copy(id = ref.id)).await()
    }

    suspend fun deleteStory(storyId: String) {
        val user = auth.currentUser ?: return
        if (storyId.isBlank()) return
        val docRef = db.collection("stories").document(storyId)
        val doc = try { docRef.get().await() } catch (_: Exception) { null } ?: return
        if (doc.exists() && doc.getString("userId") == user.uid) {
            docRef.delete().await()
        }
    }


    fun editMessage(conversationId: String, msgId: String, newText: String) {
        val user = auth.currentUser ?: return
        if (conversationId.isBlank() || msgId.isBlank() || newText.isBlank()) return
        val msgRef = db.collection("conversations").document(conversationId).collection("messages").document(msgId)
        msgRef.update("text", newText.trim(), "isEdited", true)
            .addOnFailureListener { error ->
                android.util.Log.e("SocialRepository", "Failed to edit message: ${error.message}", error)
            }
    }

    fun deleteMessage(conversationId: String, msgId: String, forEveryone: Boolean) {
        val user = auth.currentUser ?: return
        if (conversationId.isBlank() || msgId.isBlank()) return
        val msgRef = db.collection("conversations").document(conversationId).collection("messages").document(msgId)
        if (forEveryone) {
            msgRef.update("isDeleted", true)
                .addOnFailureListener { error ->
                    android.util.Log.e("SocialRepository", "Failed to mark message deleted: ${error.message}", error)
                }
        } else {
            db.runTransaction { transaction ->
                val snapshot = transaction.get(msgRef)
                if (snapshot.exists()) {
                    val msg = snapshot.toObject(PrivateMessage::class.java)
                    if (msg != null) {
                        val newDeletedFor = msg.deletedFor.toMutableList()
                        if (!newDeletedFor.contains(user.uid)) {
                            newDeletedFor.add(user.uid)
                            transaction.update(msgRef, "deletedFor", newDeletedFor)
                        }
                    }
                }
            }.addOnFailureListener { error ->
                android.util.Log.e("SocialRepository", "Failed to delete message for user: ${error.message}", error)
            }
        }
    }

    fun reactToMessage(conversationId: String, msgId: String, emoji: String) {
        val user = auth.currentUser ?: return
        if (conversationId.isBlank() || msgId.isBlank() || emoji.isBlank()) return
        val msgRef = db.collection("conversations").document(conversationId).collection("messages").document(msgId)
        db.runTransaction { transaction ->
            val snapshot = transaction.get(msgRef)
            if (snapshot.exists()) {
                val msg = snapshot.toObject(PrivateMessage::class.java)
                if (msg != null) {
                    val newReactions = msg.reactions.toMutableMap()
                    if (newReactions[user.uid] == emoji) {
                        newReactions.remove(user.uid)
                    } else {
                        newReactions[user.uid] = emoji
                    }
                    transaction.update(msgRef, "reactions", newReactions)
                }
            }
        }.addOnFailureListener { error ->
            android.util.Log.e("SocialRepository", "Failed to react to message: ${error.message}", error)
        }
    }

    fun sendMediaMessage(conversationId: String, text: String, mediaUrl: String) {
        val user = auth.currentUser ?: return
        if (conversationId.isBlank() || mediaUrl.isBlank()) return
        val docRef = db.collection("conversations").document(conversationId)
        val msgRef = docRef.collection("messages").document()
        val now = System.currentTimeMillis()
        val msg = PrivateMessage(msgRef.id, user.uid, text.trim(), now, mediaUrl = mediaUrl)
        
        msgRef.set(msg).addOnSuccessListener {
            db.runTransaction { transaction ->
                val convSnapshot = transaction.get(docRef)
                if (convSnapshot.exists()) {
                    val displayMsg = if (text.isNotBlank()) text.trim() else "📷 Media"
                    transaction.update(docRef, "lastMessage", displayMsg)
                    transaction.update(docRef, "lastMessageTime", now)
                }
            }.addOnFailureListener { error ->
                android.util.Log.w("SocialRepository", "Media message saved but failed to update conversation preview", error)
            }
        }.addOnFailureListener { error ->
            android.util.Log.e("SocialRepository", "Failed to send media message: ${error.message}", error)
        }
    }

    fun sendRealVoiceMessage(conversationId: String, voiceUrl: String) {
        val user = auth.currentUser ?: return
        if (conversationId.isBlank() || voiceUrl.isBlank()) return
        val docRef = db.collection("conversations").document(conversationId)
        val msgRef = docRef.collection("messages").document()
        val now = System.currentTimeMillis()
        val msg = PrivateMessage(msgRef.id, user.uid, "Voice Message", now, isVoice = true, mediaUrl = voiceUrl)
        
        msgRef.set(msg).addOnSuccessListener {
            db.runTransaction { transaction ->
                val convSnapshot = transaction.get(docRef)
                if (convSnapshot.exists()) {
                    transaction.update(docRef, "lastMessage", "🎤 Voice Message")
                    transaction.update(docRef, "lastMessageTime", now)
                }
            }.addOnFailureListener { error ->
                android.util.Log.w("SocialRepository", "Voice message saved but failed to update conversation preview", error)
            }
        }.addOnFailureListener { error ->
            android.util.Log.e("SocialRepository", "Failed to send real voice message: ${error.message}", error)
        }
    }

    fun sendVoiceMessage(conversationId: String, voicePath: String) {
        val user = auth.currentUser ?: return
        if (conversationId.isBlank() || voicePath.isBlank()) return
        val docRef = db.collection("conversations").document(conversationId)
        val msgRef = docRef.collection("messages").document()
        val now = System.currentTimeMillis()
        val msg = PrivateMessage(msgRef.id, user.uid, "Voice Message", now, isVoice = true, mediaUrl = voicePath)
        
        msgRef.set(msg).addOnSuccessListener {
            db.runTransaction { transaction ->
                val convSnapshot = transaction.get(docRef)
                if (convSnapshot.exists()) {
                    transaction.update(
                        docRef,
                        "lastMessage", "Voice Message",
                        "lastMessageTime", now
                    )
                }
            }.addOnFailureListener { error ->
                android.util.Log.w("SocialRepository", "Voice-message saved but failed to update preview", error)
            }
        }.addOnFailureListener { error ->
            android.util.Log.e("SocialRepository", "Failed to send voice message: ${error.message}", error)
        }
    }
}