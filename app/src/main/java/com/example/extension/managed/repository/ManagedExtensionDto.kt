package com.example.extension.managed.repository

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.IgnoreExtraProperties

/**
 * Data Transfer Object representing an untrusted remote snapshot from Firestore (/managed_extensions/{id}).
 * Contains only configuration parameters and zero executable logic.
 */
@IgnoreExtraProperties
data class ManagedExtensionDto(
    val id: String? = null,
    val name: String? = null,
    val description: String? = null,
    val baseUrl: String? = null,
    val iconUrl: String? = null,
    val scraperKey: String? = null,
    val definitionVersion: Long? = null,
    val minAppVersionCode: Long? = null,
    val runtimeApiVersion: Long? = null,
    val priority: Long? = null,
    val language: String? = null,
    val contentTypes: List<String>? = null,
    val capabilities: List<String>? = null,
    val audioCapabilities: List<String>? = null,
    val searchUrl: String? = null,
    val enabled: Boolean? = null,
    val status: String? = null,
    val updatedAt: Long? = null
) {
    companion object {
        fun fromDocument(doc: DocumentSnapshot): ManagedExtensionDto {
            val docId = doc.id
            val id = doc.getString("id")?.takeIf { it.isNotBlank() } ?: docId
            val name = doc.getString("name")
            val description = doc.getString("description")
            val baseUrl = doc.getString("baseUrl")
            val iconUrl = doc.getString("iconUrl")
            val scraperKey = doc.getString("scraperKey") ?: id.lowercase().trim()
            val definitionVersion = doc.getLong("definitionVersion") ?: doc.getLong("versionCode")
            val minAppVersionCode = doc.getLong("minAppVersionCode")
            val runtimeApiVersion = doc.getLong("runtimeApiVersion")
            val priority = doc.getLong("priority")
            val language = doc.getString("language")
            val searchUrl = doc.getString("searchUrl")

            @Suppress("UNCHECKED_CAST")
            val contentTypes = (doc.get("contentTypes") as? List<*>)?.mapNotNull { it?.toString() }

            @Suppress("UNCHECKED_CAST")
            val capabilities = (doc.get("capabilities") as? List<*>)?.mapNotNull { it?.toString() }

            @Suppress("UNCHECKED_CAST")
            val audioCapabilities = (doc.get("audioCapabilities") as? List<*>)?.mapNotNull { it?.toString() }

            val rawStatus = doc.getString("status")
            val isEnabled = doc.getBoolean("enabled")
            val status = when {
                isEnabled == false -> "DISABLED"
                rawStatus != null -> rawStatus
                isEnabled == true -> "ACTIVE"
                else -> null
            }

            val updatedAt = when (val u = doc.get("updatedAt")) {
                is Timestamp -> u.toDate().time
                is Number -> u.toLong()
                else -> null
            }

            return ManagedExtensionDto(
                id = id,
                name = name,
                description = description,
                baseUrl = baseUrl,
                iconUrl = iconUrl,
                scraperKey = scraperKey,
                definitionVersion = definitionVersion,
                minAppVersionCode = minAppVersionCode,
                runtimeApiVersion = runtimeApiVersion,
                priority = priority,
                language = language,
                contentTypes = contentTypes,
                capabilities = capabilities,
                audioCapabilities = audioCapabilities,
                searchUrl = searchUrl,
                enabled = isEnabled,
                status = status,
                updatedAt = updatedAt
            )
        }

        fun fromMap(docId: String, map: Map<String, Any?>): ManagedExtensionDto {
            val id = (map["id"] as? String)?.takeIf { it.isNotBlank() } ?: docId
            val name = map["name"] as? String
            val description = map["description"] as? String
            val baseUrl = map["baseUrl"] as? String
            val iconUrl = map["iconUrl"] as? String
            val scraperKey = (map["scraperKey"] as? String) ?: id.lowercase().trim()
            val definitionVersion = (map["definitionVersion"] as? Number)?.toLong() ?: (map["versionCode"] as? Number)?.toLong()
            val minAppVersionCode = (map["minAppVersionCode"] as? Number)?.toLong()
            val runtimeApiVersion = (map["runtimeApiVersion"] as? Number)?.toLong()
            val priority = (map["priority"] as? Number)?.toLong()
            val language = map["language"] as? String
            val searchUrl = map["searchUrl"] as? String

            @Suppress("UNCHECKED_CAST")
            val contentTypes = (map["contentTypes"] as? List<*>)?.mapNotNull { it?.toString() }

            @Suppress("UNCHECKED_CAST")
            val capabilities = (map["capabilities"] as? List<*>)?.mapNotNull { it?.toString() }

            @Suppress("UNCHECKED_CAST")
            val audioCapabilities = (map["audioCapabilities"] as? List<*>)?.mapNotNull { it?.toString() }

            val rawStatus = map["status"] as? String
            val isEnabled = map["enabled"] as? Boolean
            val status = when {
                isEnabled == false -> "DISABLED"
                rawStatus != null -> rawStatus
                isEnabled == true -> "ACTIVE"
                else -> null
            }

            val updatedAt = when (val u = map["updatedAt"]) {
                is Timestamp -> u.toDate().time
                is Number -> u.toLong()
                else -> null
            }

            return ManagedExtensionDto(
                id = id,
                name = name,
                description = description,
                baseUrl = baseUrl,
                iconUrl = iconUrl,
                scraperKey = scraperKey,
                definitionVersion = definitionVersion,
                minAppVersionCode = minAppVersionCode,
                runtimeApiVersion = runtimeApiVersion,
                priority = priority,
                language = language,
                contentTypes = contentTypes,
                capabilities = capabilities,
                audioCapabilities = audioCapabilities,
                searchUrl = searchUrl,
                enabled = isEnabled,
                status = status,
                updatedAt = updatedAt
            )
        }
    }
}
