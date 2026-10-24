package com.example.extension.managed.repository

import com.example.extension.managed.trace.Phase05GLogger
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Production implementation of ManagedExtensionRemoteDataSource querying Firestore at canonical path:
 * /managed_extensions/{extensionId}.
 *
 * Phase 07.0 / Wave 3 Requirements:
 * - F-009: No silent anonymous authentication at startup/sync. Reading public collections
 *   (/managed_extensions) does not mutate or establish auth session.
 * - F-017: /managed_extensions is the sole canonical runtime source. The legacy /extensions
 *   collection is NEVER used as an active runtime fallback, preventing resurrection of
 *   disabled or removed extensions.
 */
class FirebaseFirestoreManagedExtensionDataSource(
    private val firestoreProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() }
) : ManagedExtensionRemoteDataSource {

    companion object {
        const val COLLECTION_PATH = "managed_extensions"
    }

    override suspend fun fetchManagedExtensionDtos(): Result<List<ManagedExtensionDto>> {
        val firestore = try {
            firestoreProvider()
        } catch (e: Exception) {
            Phase05GLogger.log("FIREBASE", "init", "Firestore provider initialization failed: ${e.message}")
            return Result.failure(e)
        }

        val dtosMap = LinkedHashMap<String, ManagedExtensionDto>()

        // 1. Fetch exclusively from canonical /managed_extensions collection
        try {
            val managedSnap = firestore.collection(COLLECTION_PATH).get().await()
            val managedDocCount = managedSnap.documents.size
            for (doc in managedSnap.documents) {
                try {
                    val dto = ManagedExtensionDto.fromDocument(doc)
                    if (dto.id != null) {
                        dtosMap[dto.id] = dto
                    }
                } catch (_: Exception) {}
            }
            Phase05GLogger.log("FIREBASE", "managed", "Read /managed_extensions: success, docs=$managedDocCount, parsed=${dtosMap.size}")
            return Result.success(dtosMap.values.toList())
        } catch (e: Exception) {
            Phase05GLogger.log("FIREBASE", "managed", "Read /managed_extensions: failed with ${e.javaClass.simpleName}: ${e.message}")
            return Result.failure(e)
        }
    }
}
