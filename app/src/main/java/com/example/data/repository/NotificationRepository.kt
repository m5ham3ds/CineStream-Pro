package com.example.data.repository

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.model.NotificationItem
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class NotificationRepository(context: Context) {
    private val appContext = context.applicationContext
    private val notificationDao = AppDatabase.getDatabase(appContext).notificationDao()

    fun getAllNotifications(): Flow<List<NotificationItem>> = notificationDao.getAllNotifications()

    fun getUnreadCount(): Flow<Int> = notificationDao.getUnreadCount()

    suspend fun syncCloudNotifications(context: Context, currentUid: String = "") {
        val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
        val authUid = auth.currentUser?.uid
        if (authUid.isNullOrBlank()) {
            // Firestore rules require isAuthenticated() to read /notifications.
            // Unauthenticated reads fail with PERMISSION_DENIED.
            android.util.Log.d("NotificationRepo", "Skipping cloud notification sync: user is not authenticated")
            return
        }

        val resolvedUid = if (currentUid.isNotBlank() && currentUid == authUid) {
            currentUid
        } else {
            authUid
        }

        try {
            val firestore = FirebaseFirestore.getInstance()
            val notifications = firestore.collection("notifications")
            val queries = mutableListOf<com.google.firebase.firestore.Query>(
                // Rules are not filters: every query must prove its own target scope before reading.
                notifications.whereEqualTo("target", "all").limit(50),
                notifications.whereEqualTo("target", "user")
                    .whereEqualTo("targetUid", resolvedUid).limit(50)
            )

            var isProUser = false
            try {
                val userDoc = firestore.collection("users").document(resolvedUid).get().await()
                isProUser = userDoc.getBoolean("isPro") == true ||
                    userDoc.getBoolean("isPremium") == true ||
                    userDoc.getString("subscriptionTier") in listOf("pro", "PRO")
                if (isProUser) {
                    queries += notifications.whereEqualTo("target", "pro").limit(50)
                }
            } catch (e: Exception) {
                android.util.Log.w("NotificationRepo", "Failed to check user pro status for notifications: ${e.message}")
            }

            val documents = queries.flatMap { query ->
                try {
                    query.get().await().documents
                } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
                    android.util.Log.e("NotificationRepo", "Firestore notification query error: ${e.code} - ${e.message}")
                    throw e
                }
            }.distinctBy { it.id }

            val now = System.currentTimeMillis()

            // Load latest preferences once before processing notifications
            val prefs = NotificationPreferencesRepository(context).getPreferences()
            val deduplicator = com.example.data.notification.NotificationDeduplicator.getInstance(context)

            for (doc in documents) {
                if (!isNotificationValidAndTargeted(doc, resolvedUid, now, isProUser)) {
                    continue
                }

                val id = doc.id
                val title = doc.getString("title") ?: continue
                val body = doc.getString("body") ?: doc.getString("message") ?: ""
                val timestamp = when (val c = doc.get("createdAt")) {
                    is Timestamp -> c.toDate().time
                    is Number -> c.toLong()
                    else -> doc.getLong("timestamp") ?: now
                }
                val imageUrl = doc.getString("imageUrl") ?: doc.getString("image_url")
                val type = doc.getString("type") ?: "info"
                val event = doc.getString("event") ?: doc.getString("eventType") ?: doc.getString("event_type")
                val isAnime = when (val v = doc.get("isAnime") ?: doc.get("is_anime")) {
                    is Boolean -> v
                    is String -> v.trim().lowercase() in listOf("true", "1")
                    is Number -> v.toInt() == 1
                    else -> type.equals("anime", ignoreCase = true)
                }

                // Preference Gate: evaluate before Room insertion, before deduplication mark, and before tray alert
                val category = com.example.data.notification.NotificationCategoryResolver.resolveCategory(
                    type = type,
                    event = event,
                    isAnime = isAnime
                )

                if (!com.example.data.notification.NotificationCategoryResolver.isNotificationAllowed(category, prefs)) {
                    android.util.Log.d("NotificationRepo", "Cloud notification '$id' ($category) completely suppressed by user preferences. Not saved to Room.")
                    deduplicator.checkAndMarkProcessedSuspend(id)
                    continue
                }

                if (deduplicator.checkAndMarkProcessedSuspend(id)) {
                    continue
                }

                val existing = notificationDao.getNotificationById(id)
                if (existing == null) {
                    val item = NotificationItem(
                        id = id,
                        title = title,
                        message = body,
                        timestamp = timestamp,
                        isRead = false,
                        imageUrl = imageUrl,
                        type = type
                    )
                    notificationDao.insertNotification(item)
                    com.example.utils.NotificationHelper.showGeneralNotification(context, id, title, body)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NotificationRepo", "Error syncing cloud notifications: ${e.message}", e)
            throw e
        }
    }

    companion object {
        private val listenerRegistrations = mutableListOf<com.google.firebase.firestore.ListenerRegistration>()
        @Volatile private var listenerGeneration: Long = 0L
        private var lastAlertTimestamp: Long = System.currentTimeMillis()
        private val listenerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

        private fun isNotificationValidAndTargeted(
            doc: DocumentSnapshot,
            currentUid: String,
            currentTime: Long,
            isUserPro: Boolean = false
        ): Boolean {
            // 1. Check isActive (default: true)
            val isActive = doc.getBoolean("isActive") ?: true
            if (!isActive) return false

            // 2. Check expiresAt
            val expiresAt = when (val exp = doc.get("expiresAt")) {
                is Timestamp -> exp.toDate().time
                is Number -> exp.toLong()
                else -> null
            }
            if (expiresAt != null && expiresAt < currentTime) {
                return false
            }

            // 3. Strict target validation: canonical values "all", "user", "pro"
            // Note: targetType is NOT an authorized security surrogate for target.
            // Legacy documents missing 'target' must be migrated on backend and are ignored by client.
            val target = doc.getString("target")?.trim()?.lowercase() ?: return false
            val targetUid = doc.getString("targetUid")?.trim() ?: ""

            return when (target) {
                "all" -> true
                "user" -> currentUid.isNotBlank() && targetUid == currentUid
                "pro" -> isUserPro
                else -> false
            }
        }

        fun listenForAnnouncements(
            context: Context? = null,
            currentUid: String,
            onNewAlert: (title: String, message: String) -> Unit
        ): com.google.firebase.firestore.ListenerRegistration? {
            stopListeningAnnouncements()

            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            val authUid = auth.currentUser?.uid
            if (authUid.isNullOrBlank()) {
                // Must be authenticated to read /notifications under firestore.rules
                return null
            }
            val resolvedUid = if (currentUid.isNotBlank() && currentUid == authUid) currentUid else authUid

            val currentGen = synchronized(listenerRegistrations) {
                listenerGeneration + 1L
            }.also { listenerGeneration = it }

            val firestore = FirebaseFirestore.getInstance()
            val collection = firestore.collection("notifications")
            val queries = mutableListOf<com.google.firebase.firestore.Query>(
                collection.whereEqualTo("target", "all").limit(10),
                collection.whereEqualTo("target", "user").whereEqualTo("targetUid", resolvedUid).limit(10)
            )

            val appContext = context?.applicationContext
            var cachedIsPro = false

            val processSnapshot: (com.google.firebase.firestore.QuerySnapshot?, Exception?) -> Unit = process@{ snapshot, error ->
                if (currentGen != listenerGeneration) {
                    // Stale listener callback after account switch or stop
                    return@process
                }
                if (error != null) {
                    android.util.Log.e("NotificationRepo", "Announcement listener error: ${error.message}")
                    return@process
                }
                if (snapshot != null) {
                    val now = System.currentTimeMillis()
                    val docs = snapshot.documents
                    listenerScope.launch {
                        if (currentGen != listenerGeneration) return@launch
                        val prefs = if (appContext != null) {
                            try {
                                NotificationPreferencesRepository(appContext).getPreferences()
                            } catch (_: Exception) { null }
                        } else null

                        for (doc in docs) {
                            if (currentGen != listenerGeneration) return@launch
                            if (!isNotificationValidAndTargeted(doc, resolvedUid, now, cachedIsPro)) continue
                            if (prefs != null) {
                                val category = com.example.data.notification.NotificationCategoryResolver.resolveCategory(
                                    type = doc.getString("type") ?: "announcement"
                                )
                                if (!com.example.data.notification.NotificationCategoryResolver.isNotificationAllowed(category, prefs)) continue
                            }

                            val createdAt = when (val c = doc.get("createdAt")) {
                                is Timestamp -> c.toDate().time
                                is Number -> c.toLong()
                                else -> doc.getLong("timestamp") ?: 0L
                            }
                            val notifId = doc.id
                            val title = doc.getString("title") ?: ""
                            val body = doc.getString("body") ?: doc.getString("message") ?: ""
                            val imageUrl = doc.getString("imageUrl") ?: doc.getString("image_url")
                            val type = doc.getString("type") ?: "info"

                            if (appContext != null && (title.isNotBlank() || body.isNotBlank())) {
                                try {
                                    val dao = AppDatabase.getDatabase(appContext).notificationDao()
                                    if (dao.getNotificationById(notifId) == null) {
                                        dao.insertNotification(NotificationItem(
                                            id = notifId, title = title, message = body,
                                            timestamp = createdAt, isRead = false,
                                            imageUrl = imageUrl, type = type
                                        ))
                                    }
                                } catch (_: Exception) {}
                            }

                            if (createdAt > lastAlertTimestamp) {
                                lastAlertTimestamp = createdAt
                                if (title.isNotBlank() || body.isNotBlank()) {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        if (currentGen == listenerGeneration) {
                                            onNewAlert(title, body)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            synchronized(listenerRegistrations) {
                if (currentGen == listenerGeneration) {
                    queries.forEach { query ->
                        val reg = query.addSnapshotListener { snapshot, error ->
                            processSnapshot(snapshot, error)
                        }
                        listenerRegistrations += reg
                    }
                }
            }

            firestore.collection("users").document(resolvedUid).get()
                .addOnSuccessListener { userDoc ->
                    if (currentGen != listenerGeneration) return@addOnSuccessListener
                    val isPro = userDoc.getBoolean("isPro") == true ||
                        userDoc.getBoolean("isPremium") == true ||
                        userDoc.getString("subscriptionTier") in listOf("pro", "PRO")
                    cachedIsPro = isPro
                    if (isPro) {
                        val proListener = collection.whereEqualTo("target", "pro").limit(10)
                            .addSnapshotListener { snapshot, error -> processSnapshot(snapshot, error) }
                        synchronized(listenerRegistrations) {
                            if (currentGen == listenerGeneration) {
                                listenerRegistrations += proListener
                            } else {
                                proListener.remove()
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
                    android.util.Log.w("NotificationRepo", "Could not fetch user pro status: ${e.message}")
                }

            // The public API returns one removable handle for all constrained listeners.
            return object : com.google.firebase.firestore.ListenerRegistration {
                override fun remove() = stopListeningAnnouncements()
            }
        }

        fun stopListeningAnnouncements() {
            synchronized(listenerRegistrations) {
                listenerGeneration += 1L
                listenerRegistrations.forEach { it.remove() }
                listenerRegistrations.clear()
            }
        }
    }

    suspend fun addNotification(
        title: String,
        message: String,
        imageUrl: String? = null,
        type: String = "info",
        event: String? = null,
        isAnime: Boolean = false
    ) {
        val prefs = NotificationPreferencesRepository(appContext).getPreferences()
        val category = com.example.data.notification.NotificationCategoryResolver.resolveCategory(
            type = type,
            event = event,
            isAnime = isAnime
        )
        if (!com.example.data.notification.NotificationCategoryResolver.isNotificationAllowed(category, prefs)) {
            return
        }
        val notification = NotificationItem(
            title = title,
            message = message,
            imageUrl = imageUrl,
            type = type
        )
        notificationDao.insertNotification(notification)
    }

    suspend fun markAllAsRead() {
        notificationDao.markAllAsRead()
    }
    
    suspend fun markAsRead(id: String) {
        notificationDao.markAsRead(id)
    }

    suspend fun deleteNotification(id: String) {
        notificationDao.deleteNotification(id)
    }
}
