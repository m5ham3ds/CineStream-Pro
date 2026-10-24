package com.example.extension.managed.repository

import com.example.extension.managed.model.*
import com.example.extension.managed.registry.ManagedExtensionRuntimeRegistry
import com.example.extension.managed.searchorder.SearchOrder
import com.example.extension.managed.trace.Phase05GLogger
import com.example.extension.managed.trace.Phase05HLogger
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 05M: Realtime Firestore Extension Sync Manager.
 *
 * Enforces Phase 05M Architecture:
 * 1. Single Listener Architecture (Rule 31 & 32): Exactly ONE listener at application/repository scope.
 * 2. Realtime sync for /managed_extensions and /config/search_order without app restarts or manual refresh.
 * 3. Observability tokens: EXT_SYNC_LISTENER_STARTED, EXT_SYNC_REMOTE_RECEIVED, EXT_SYNC_VALIDATION_STARTED,
 *    EXT_SYNC_VALIDATION_PASSED, EXT_SYNC_VALIDATION_FAILED, EXT_SYNC_SNAPSHOT_COMMITTED, EXT_SYNC_SNAPSHOT_REJECTED,
 *    EXT_SYNC_LKG_LOADED, EXT_SYNC_RECONNECT, EXT_SYNC_PERMISSION_DENIED, EXT_SYNC_NETWORK_FAILURE, EXT_SYNC_STATE_CHANGED.
 * 4. Empty data protection (Rule 11): Transient network/permission errors NEVER replace valid LKG with empty data.
 * 5. Admin authority (Rule 06 & 12): Disabled extensions remain strictly DISABLED.
 * 6. Search order canonicality (Rule 04 & 14): Search order is the sole source for playback order.
 */
class ManagedExtensionRealtimeSyncManager(
    private val registry: ManagedExtensionRuntimeRegistry = ManagedExtensionRuntimeRegistry.INSTANCE,
    private val cache: ManagedExtensionCache = SafeLocalMetadataCache(),
    private val firestoreProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
    private val syncScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        const val CANONICAL_COLLECTION = "managed_extensions"
        const val CONFIG_COLLECTION = "config"
        const val SEARCH_ORDER_DOC = "search_order"

        @Volatile
        private var instance: ManagedExtensionRealtimeSyncManager? = null

        fun getInstance(): ManagedExtensionRealtimeSyncManager =
            instance ?: synchronized(this) {
                instance ?: ManagedExtensionRealtimeSyncManager().also { instance = it }
            }

        fun setTestInstance(manager: ManagedExtensionRealtimeSyncManager) {
            instance = manager
        }
    }

    private val isListening = AtomicBoolean(false)
    private var extensionsListenerRegistration: ListenerRegistration? = null
    private var searchOrderListenerRegistration: ListenerRegistration? = null

    fun isListeningActive(): Boolean = isListening.get()

    /**
     * Starts realtime Firestore listeners. Idempotent: safe to call repeatedly;
     * guaranteed to run only ONE active listener per domain.
     *
     * F-009 Compliance: Public collections (/managed_extensions and /config/search_order)
     * are read without silently signing in as anonymous Firebase users.
     */
    fun startListening() {
        if (!isListening.compareAndSet(false, true)) {
            // Already listening, skip duplicate registration (Rule 31 & 32)
            return
        }

        Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_LISTENER_STARTED")

        syncScope.launch {
            attachListeners()
        }
    }

    private fun attachListeners() {
        val firestore = try {
            firestoreProvider()
        } catch (e: Exception) {
            Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_NETWORK_FAILURE: ${e.message}")
            isListening.set(false)
            return
        }

        // 1. Listen to /managed_extensions
        try {
            extensionsListenerRegistration?.remove()
            extensionsListenerRegistration = firestore.collection(CANONICAL_COLLECTION)
                .addSnapshotListener { snapshot, error ->
                    handleExtensionsSnapshot(snapshot, error)
                }
        } catch (e: Exception) {
            Phase05HLogger.log("SYNC", "realtime", "Failed to attach extensions listener: ${e.message}")
        }

        // 2. Listen to /config/search_order
        try {
            searchOrderListenerRegistration?.remove()
            searchOrderListenerRegistration = firestore.collection(CONFIG_COLLECTION)
                .document(SEARCH_ORDER_DOC)
                .addSnapshotListener { snapshot, error ->
                    handleSearchOrderSnapshot(snapshot, error)
                }
        } catch (e: Exception) {
            Phase05HLogger.log("SYNC", "realtime", "Failed to attach search_order listener: ${e.message}")
        }
    }

    private fun handleExtensionsSnapshot(
        snapshot: com.google.firebase.firestore.QuerySnapshot?,
        error: FirebaseFirestoreException?
    ) {
        if (error != null) {
            val code = error.code
            if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_PERMISSION_DENIED: ${error.message}")
            } else {
                Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_NETWORK_FAILURE: ${error.message}")
            }

            // Rule 11: Empty Data Protection — NEVER wipe out local registry on sync errors!
            val current = registry.getCurrentSnapshot()
            if (current.extensions.isEmpty()) {
                val lkg = cache.loadSnapshot()
                if (lkg != null && lkg.extensions.isNotEmpty()) {
                    Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_LKG_LOADED: retaining LKG during sync error")
                    registry.commitSnapshot(lkg.copy(state = GlobalExtensionConfigState.READY))
                } else {
                    registry.commitSnapshot(current.copy(state = GlobalExtensionConfigState.GLOBAL_CONFIG_UNAVAILABLE))
                }
            }
            return
        }

        if (snapshot == null) return

        Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_REMOTE_RECEIVED: docs=${snapshot.documents.size}")
        Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_VALIDATION_STARTED")

        val rawDtos = mutableListOf<ManagedExtensionDto>()
        for (doc in snapshot.documents) {
            try {
                val dto = ManagedExtensionDto.fromDocument(doc)
                if (dto.id != null) {
                    rawDtos.add(dto)
                }
            } catch (e: Exception) {
                Phase05HLogger.log("SYNC", "realtime", "Malformed extension document ${doc.id}: ${e.message}")
            }
        }

        // Validate remote entries strictly
        val validExtensions = mutableListOf<ManagedExtension>()
        var validationFailedCount = 0

        for (dto in rawDtos) {
            val domainExt = ManagedExtensionMapper.toDomain(dto, localUserEnabled = true)
            when (val result = ManagedExtensionValidator.validateRemoteEntry(domainExt)) {
                is ManagedExtensionValidator.ValidationResult.Valid -> {
                    validExtensions.add(domainExt)
                }
                is ManagedExtensionValidator.ValidationResult.Invalid -> {
                    validationFailedCount++
                    Phase05HLogger.log("SYNC", "realtime", "Rejected extension ${domainExt.id}: ${result.error}")
                }
            }
        }

        if (validationFailedCount > 0) {
            Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_VALIDATION_FAILED: rejected=$validationFailedCount")
        }

        if (validExtensions.isNotEmpty()) {
            Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_VALIDATION_PASSED: valid=${validExtensions.size}")
            
            val remoteIds = rawDtos.mapNotNull { it.id?.trim()?.lowercase() }.toSet()
            val currentExtensions = registry.getAllExtensions()
            val currentIds = currentExtensions.map { it.id.lowercase() }.toSet()
            val remotelyDisabledIds = rawDtos
                .filter { it.status.equals("DISABLED", ignoreCase = true) || it.status.equals("DEPRECATED", ignoreCase = true) }
                .mapNotNull { it.id?.trim()?.lowercase() }
                .toSet()
            val removedIds = currentIds.minus(remoteIds)
            cache.recordTombstones(remotelyDisabledIds, removedIds)

            // Deduplicate and retain priority sorting
            val deduplicated = validExtensions
                .groupBy { it.id }
                .map { (_, group) ->
                    group.maxWithOrNull(compareBy({ it.updatedAt }, { it.priority })) ?: group.first()
                }

            val maxRevision = snapshot.documents.mapNotNull { doc ->
                doc.getLong("updatedAt")
            }.maxOrNull() ?: System.currentTimeMillis()

            registry.commitRemoteUpdate(
                newExtensions = deduplicated,
                revision = maxRevision,
                timestamp = System.currentTimeMillis()
            )
        } else if (snapshot.documents.isEmpty()) {
            // Rule 11: Only accept genuine empty catalog if authoritative from server
            if (!snapshot.metadata.isFromCache) {
                Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_VALIDATION_PASSED: genuine 0 extensions from remote")
                registry.commitRemoteUpdate(
                    newExtensions = emptyList(),
                    revision = System.currentTimeMillis(),
                    timestamp = System.currentTimeMillis()
                )
            }
        }
    }

    private fun handleSearchOrderSnapshot(
        snapshot: com.google.firebase.firestore.DocumentSnapshot?,
        error: FirebaseFirestoreException?
    ) {
        if (error != null) {
            val code = error.code
            if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_PERMISSION_DENIED (search_order): ${error.message}")
            } else {
                Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_NETWORK_FAILURE (search_order): ${error.message}")
            }
            return
        }

        if (snapshot == null || !snapshot.exists()) return

        val data = snapshot.data ?: return
        Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_REMOTE_RECEIVED (search_order)")

        fun parseStringList(key: String): List<String>? {
            if (!data.containsKey(key) || data[key] == null) return null
            val raw = data[key] as? List<*> ?: return emptyList()
            return raw.mapNotNull { item ->
                val str = item?.toString()?.trim()
                if (!str.isNullOrBlank()) str else null
            }
        }

        val arabicDubbedList = parseStringList("arabicDubbed")
            ?: parseStringList("arabicDub")
            ?: parseStringList("arabic_dub")
            ?: emptyList()

        val englishDubbedList = parseStringList("englishDubbed")
            ?: parseStringList("englishDub")
            ?: parseStringList("english_dub")
            ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        val dubbingConfig = data["dubbingConfig"] as? Map<String, Any?>

        val rawArabicStatus = (dubbingConfig?.get("arabicDubbedStatus") as? String)
            ?: (data["arabicDubbedStatus"] as? String)
        val arabicDubbedStatus = when (rawArabicStatus?.trim()?.uppercase()) {
            "MAINTENANCE" -> com.example.domain.models.GroupStatus.MAINTENANCE
            "DISABLED" -> com.example.domain.models.GroupStatus.DISABLED
            else -> com.example.domain.models.GroupStatus.ACTIVE
        }

        val rawEnglishStatus = (dubbingConfig?.get("englishDubbedStatus") as? String)
            ?: (data["englishDubbedStatus"] as? String)
        val englishDubbedStatus = when (rawEnglishStatus?.trim()?.uppercase()) {
            "ACTIVE" -> com.example.domain.models.GroupStatus.ACTIVE
            "DISABLED" -> com.example.domain.models.GroupStatus.DISABLED
            else -> com.example.domain.models.GroupStatus.MAINTENANCE
        }

        val searchOrder = SearchOrder(
            movie = parseStringList("movie") ?: emptyList(),
            tv = parseStringList("tv"),
            series = parseStringList("series") ?: emptyList(),
            anime = parseStringList("anime") ?: emptyList(),
            arabicDubbed = arabicDubbedList,
            englishDubbed = englishDubbedList,
            arabicDubbedStatus = arabicDubbedStatus,
            englishDubbedStatus = englishDubbedStatus
        )

        val revision = snapshot.getLong("updatedAt") ?: System.currentTimeMillis()
        Phase05HLogger.log(
            "SYNC",
            "realtime",
            "EXT_SYNC_VALIDATION_PASSED (search_order): anime=${searchOrder.anime}, movie=${searchOrder.movie}"
        )

        registry.commitRemoteUpdate(
            newSearchOrder = searchOrder,
            revision = revision,
            timestamp = System.currentTimeMillis()
        )
    }

    fun reconnect() {
        stopListening()
        Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_RECONNECT")
        startListening()
    }

    fun stopListening() {
        extensionsListenerRegistration?.remove()
        extensionsListenerRegistration = null
        searchOrderListenerRegistration?.remove()
        searchOrderListenerRegistration = null
        isListening.set(false)
        Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_LISTENER_STOPPED")
    }
}
