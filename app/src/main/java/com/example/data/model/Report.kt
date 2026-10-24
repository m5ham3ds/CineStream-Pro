package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
data class Report(
    val id: String = "",
    val reportId: String = id,
    val userId: String = "",
    val userEmail: String = "",
    val type: String = "ISSUE",
    val targetType: String = "",
    val targetId: String = "",
    val title: String = "",
    val description: String = "",
    val status: String = "PENDING",
    val resolutionNotes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null,
    val resolvedBy: String? = null,
    // Compatibility fields
    val reason: String = title,
    val details: String = description,
    val contentId: String = targetId,
    val contentType: String = targetType
) {
    fun toFirestoreMap(): Map<String, Any?> {
        val finalId = id.ifBlank { reportId }
        val finalTargetId = targetId.ifBlank { contentId }
        val finalTargetType = mapToCanonicalTargetType(targetType.ifBlank { contentType.ifBlank { "MEDIA" } })
        val legacyContentType = contentType.ifBlank { targetType.ifBlank { "MEDIA" } }
        val finalType = mapToCanonicalType(type, reason, finalTargetType)
        val finalTitle = title.ifBlank { reason }
        val finalDesc = description.ifBlank { details }
        val finalStatus = when (status.trim().uppercase()) {
            "RESOLVED" -> "RESOLVED"
            "DISMISSED", "REJECTED" -> "DISMISSED"
            "PENDING" -> "PENDING"
            else -> "PENDING"
        }
        val finalCreatedAt = if (createdAt > 0L) createdAt else System.currentTimeMillis()

        val map = mutableMapOf<String, Any?>(
            "id" to finalId,
            "reportId" to finalId,
            "userId" to userId,
            "userEmail" to userEmail,
            "type" to finalType,
            "targetType" to finalTargetType,
            "targetId" to finalTargetId,
            "title" to finalTitle,
            "description" to finalDesc,
            "status" to finalStatus,
            "createdAt" to finalCreatedAt,
            // Legacy aliases for backwards compatibility
            "reason" to finalTitle,
            "details" to finalDesc,
            "contentId" to finalTargetId,
            "contentType" to legacyContentType
        )

        // Resolution metadata is only serialized for a processed report.
        // Creation uses toCreationFirestoreMap(), which always strips these keys.
        if (finalStatus == "RESOLVED" || finalStatus == "DISMISSED") {
            if (resolvedAt != null) {
                map["resolvedAt"] = resolvedAt
            }
            if (!resolvedBy.isNullOrBlank()) {
                map["resolvedBy"] = resolvedBy
            }
            if (resolutionNotes.isNotBlank()) {
                map["resolutionNotes"] = resolutionNotes
            }
        }

        return map
    }

    /**
     * Returns the client-create payload for /reports/{reportId}.
     * Administrative resolution metadata is excluded regardless of model values.
     */
    fun toCreationFirestoreMap(): Map<String, Any?> {
        val map = toFirestoreMap().toMutableMap()
        map.remove("resolvedAt")
        map.remove("resolvedBy")
        map.remove("resolutionNotes")
        return map
    }

    companion object {
        fun mapToCanonicalTargetType(raw: String): String {
            return when (raw.trim().lowercase()) {
                "user" -> "USER"
                "stream" -> "STREAM"
                "extension" -> "EXTENSION"
                "movie", "tv", "series", "anime", "media", "content" -> "MEDIA"
                else -> raw.trim().uppercase().ifBlank { "MEDIA" }
            }
        }

        fun mapToCanonicalType(rawType: String, reason: String = "", targetType: String = ""): String {
            val t = rawType.trim().uppercase()
            val canonicalTarget = mapToCanonicalTargetType(targetType)
            val r = reason.trim().lowercase()

            // A report against a user is always classified as CONTENT in the Admin contract.
            if (canonicalTarget == "USER" || t == "USER") return "CONTENT"

            // Preserve explicit specific categories. CONTENT is a general category, so
            // a clearly technical reason can refine it to STREAM or BUG.
            val inferredFromReason = when {
                r.contains("stream") || r.contains("buffer") || r.contains("playback") -> "STREAM"
                r.contains("bug") || r.contains("crash") || r.contains("error") -> "BUG"
                else -> null
            }

            return when (t) {
                "STREAM" -> "STREAM"
                "BUG" -> "BUG"
                "ISSUE" -> "ISSUE"
                "CONTENT" -> inferredFromReason ?: "CONTENT"
                else -> inferredFromReason ?: "ISSUE"
            }
        }

        fun fromDocument(doc: DocumentSnapshot): Report {
            val id = doc.getString("id") ?: doc.getString("reportId") ?: doc.id
            val created = when (val c = doc.get("createdAt")) {
                is Timestamp -> c.toDate().time
                is Number -> c.toLong()
                else -> 0L
            }
            val resolved = when (val r = doc.get("resolvedAt")) {
                is Timestamp -> r.toDate().time
                is Number -> r.toLong()
                else -> null
            }
            val rawTargetType = doc.getString("targetType") ?: doc.getString("contentType") ?: "MEDIA"
            val targetId = doc.getString("targetId") ?: doc.getString("contentId") ?: ""
            val title = doc.getString("title") ?: doc.getString("reason") ?: ""
            val desc = doc.getString("description") ?: doc.getString("details") ?: ""
            val rawStatus = doc.getString("status")?.trim()?.uppercase() ?: "PENDING"
            val status = when (rawStatus) {
                "PENDING" -> "PENDING"
                "RESOLVED" -> "RESOLVED"
                "DISMISSED", "REJECTED" -> "DISMISSED"
                else -> rawStatus
            }

            return Report(
                id = id,
                reportId = id,
                userId = doc.getString("userId") ?: "",
                userEmail = doc.getString("userEmail") ?: "",
                type = doc.getString("type") ?: "ISSUE",
                targetType = rawTargetType,
                targetId = targetId,
                title = title,
                description = desc,
                status = status,
                resolutionNotes = doc.getString("resolutionNotes") ?: "",
                createdAt = created,
                resolvedAt = resolved,
                resolvedBy = doc.getString("resolvedBy"),
                reason = title,
                details = desc,
                contentId = targetId,
                contentType = doc.getString("contentType") ?: rawTargetType
            )
        }
    }
}
