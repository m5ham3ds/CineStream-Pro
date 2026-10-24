package com.example.extension.managed.searchorder

import com.example.domain.models.GroupStatus
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Data source abstraction for reading Search Order configuration.
 */
interface SearchOrderDataSource {
    suspend fun fetchSearchOrder(): SearchOrder?
}

/**
 * Canonical Firestore implementation reading from `/config/search_order`.
 *
 * Rules:
 * - Read-only. Users App MUST NEVER write to `/config/search_order`.
 * - Admin order is authoritative.
 * - Extracts fields strictly preserving order.
 * - Supports canonical and backwards-compatible fields:
 *   arabicDubbed (fallback arabicDub, arabic_dub)
 *   englishDubbed (fallback englishDub, english_dub)
 *   dubbingConfig.arabicDubbedStatus (default ACTIVE)
 *   dubbingConfig.englishDubbedStatus (default MAINTENANCE)
 */
class FirebaseSearchOrderDataSource(
    private val firestoreProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() }
) : SearchOrderDataSource {

    companion object {
        const val CONFIG_COLLECTION = "config"
        const val SEARCH_ORDER_DOC = "search_order"
    }

    override suspend fun fetchSearchOrder(): SearchOrder? {
        return try {
            val firestore = firestoreProvider()
            val snapshot = firestore.collection(CONFIG_COLLECTION)
                .document(SEARCH_ORDER_DOC)
                .get()
                .await()

            if (!snapshot.exists()) {
                return null
            }

            val data = snapshot.data ?: return null

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
                "MAINTENANCE" -> GroupStatus.MAINTENANCE
                "DISABLED" -> GroupStatus.DISABLED
                else -> GroupStatus.ACTIVE // Default: ACTIVE
            }

            val rawEnglishStatus = (dubbingConfig?.get("englishDubbedStatus") as? String)
                ?: (data["englishDubbedStatus"] as? String)
            val englishDubbedStatus = when (rawEnglishStatus?.trim()?.uppercase()) {
                "ACTIVE" -> GroupStatus.ACTIVE
                "DISABLED" -> GroupStatus.DISABLED
                else -> GroupStatus.MAINTENANCE // Default: MAINTENANCE
            }

            val updatedAt = when (val u = data["updatedAt"]) {
                is com.google.firebase.Timestamp -> u.toDate().time
                is Number -> u.toLong()
                else -> null
            }

            val updatedBy = data["updatedBy"] as? String

            SearchOrder(
                movie = parseStringList("movie") ?: emptyList(),
                tv = parseStringList("tv"),
                series = parseStringList("series") ?: emptyList(),
                anime = parseStringList("anime") ?: emptyList(),
                arabicDubbed = arabicDubbedList,
                englishDubbed = englishDubbedList,
                arabicDubbedStatus = arabicDubbedStatus,
                englishDubbedStatus = englishDubbedStatus,
                updatedAt = updatedAt,
                updatedBy = updatedBy
            )
        } catch (_: Exception) {
            null
        }
    }
}
