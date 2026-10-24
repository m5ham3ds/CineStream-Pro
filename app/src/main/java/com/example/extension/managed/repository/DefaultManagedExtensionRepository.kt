package com.example.extension.managed.repository

import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.ManagedExtensionValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Standard implementation of ManagedExtensionRepository.
 * Enforces:
 * 1. Untrusted remote snapshot validation via ManagedExtensionValidator
 * 2. Fault tolerance: malformed documents are skipped, not crashing the list
 * 3. Deterministic deduplication
 * 4. Priority tiering: Fresh remote -> Validated cache -> Domain error
 * 5. Mapping of Firestore exceptions to domain ExtensionErrors
 */
class DefaultManagedExtensionRepository(
    private val remoteDataSource: ManagedExtensionRemoteDataSource,
    private val cache: ManagedExtensionCache = SafeLocalMetadataCache(),
    private val userPreferences: ExtensionUserPreferences = InMemoryExtensionUserPreferences(),
    private val bundledDefaults: List<ManagedExtension> = emptyList()
) : ManagedExtensionRepository {

    override suspend fun getExtensions(forceRefresh: Boolean): Result<List<ManagedExtension>> =
        withContext(Dispatchers.IO) {
            // 1. Check cache if not forcing refresh
            if (!forceRefresh && !cache.isExpired()) {
                val cached = cache.getCached()
                if (cached != null && cached.isNotEmpty()) {
                    val updatedWithPreferences = cached.map { ext ->
                        ext.copy(userEnabled = userPreferences.isExtensionEnabled(ext.id))
                    }
                    com.example.extension.managed.trace.Phase05GLogger.log("CACHE", "repo", "Cache hit with ${cached.size} items")
                    return@withContext Result.success(updatedWithPreferences)
                }
            }

            // 2. Fetch fresh remote data from Firestore
            val remoteResult = remoteDataSource.fetchManagedExtensionDtos()

            if (remoteResult.isSuccess) {
                val dtos = remoteResult.getOrNull() ?: emptyList()
                com.example.extension.managed.trace.Phase05GLogger.log("REMOTE_COUNT", "repo", "Received ${dtos.size} DTOs from Firestore")

                if (dtos.isEmpty()) {
                    // Genuine empty catalog from remote
                    cache.saveCache(emptyList())
                    return@withContext Result.success(emptyList())
                }

                val validExtensions = mutableListOf<ManagedExtension>()
                val rejectedIds = mutableSetOf<String>()
                val remotelyDisabledIds = dtos
                    .filter { it.status.equals("DISABLED", ignoreCase = true) || it.status.equals("DEPRECATED", ignoreCase = true) }
                    .mapNotNull { it.id?.trim()?.lowercase() }
                    .toSet()

                val currentCached = cache.getLastKnownGood() ?: emptyList()
                val currentIds = currentCached.map { it.id.lowercase() }.toSet()
                val remoteIds = dtos.mapNotNull { it.id?.trim()?.lowercase() }.toSet()
                val removedIds = currentIds.minus(remoteIds)
                cache.recordTombstones(remotelyDisabledIds, removedIds)

                for (dto in dtos) {
                    val localEnabled = userPreferences.isExtensionEnabled(dto.id.orEmpty())
                    val domainExt = ManagedExtensionMapper.toDomain(dto, localUserEnabled = localEnabled)

                    // Gate: strict validation per Phase 05G FIX 3
                    when (val validation = ManagedExtensionValidator.validateRemoteEntry(domainExt)) {
                        is ManagedExtensionValidator.ValidationResult.Valid -> {
                            validExtensions.add(domainExt)
                        }
                        is ManagedExtensionValidator.ValidationResult.Invalid -> {
                            val id = domainExt.id
                            if (id.isNotBlank()) {
                                rejectedIds.add(id)
                            }
                            com.example.extension.managed.trace.Phase05GLogger.log(
                                "VALIDATED_COUNT",
                                id.ifBlank { "unknown" },
                                "Rejected remote document: ${validation.error}"
                            )
                        }
                    }
                }

                // If remote sent documents, but ALL were invalid/incompatible -> structurally unusable remote configuration
                if (validExtensions.isEmpty() && dtos.isNotEmpty()) {
                    com.example.extension.managed.trace.Phase05GLogger.log(
                        "VALIDATED_COUNT",
                        "repo",
                        "All ${dtos.size} remote documents rejected; falling back to healthy cache / bundled candidates"
                    )
                    val cachedFallback = cache.getLastKnownGood()
                    if (cachedFallback != null && cachedFallback.isNotEmpty()) {
                        return@withContext Result.success(cachedFallback.map { ext ->
                            ext.copy(userEnabled = userPreferences.isExtensionEnabled(ext.id))
                        })
                    }
                    if (bundledDefaults.isNotEmpty()) {
                        val resolved = bundledDefaults.map { ext ->
                            val isRemotelyDisabled = remotelyDisabledIds.contains(ext.id.lowercase())
                            ext.copy(
                                status = if (isRemotelyDisabled) com.example.extension.managed.model.ExtensionLifecycleStatus.DISABLED else ext.status,
                                userEnabled = userPreferences.isExtensionEnabled(ext.id)
                            )
                        }
                        cache.saveCache(resolved)
                        com.example.extension.managed.trace.Phase05HLogger.log(
                            "CACHE",
                            "repo",
                            "Saved ${resolved.size} resolved candidates to cache (remotelyDisabled=${remotelyDisabledIds.size})"
                        )
                        return@withContext Result.success(resolved)
                    }
                    com.example.extension.managed.trace.Phase05HLogger.log(
                        "STATE",
                        "repo",
                        "All remote documents invalid and no cache exists: GLOBAL_CONFIG_UNAVAILABLE"
                    )
                    return@withContext Result.failure(
                        ExtensionError.GlobalConfigUnavailable("All remote documents rejected by validation gate and no cache exists")
                    )
                }

                // Deterministic deduplication
                val deduplicatedValid = validExtensions
                    .groupBy { it.id }
                    .map { (_, group) ->
                        group.maxWithOrNull(compareBy({ it.updatedAt }, { it.priority })) ?: group.first()
                    }

                // For partially invalid remote documents: retain healthy candidate if remote was rejected
                val finalCatalog = mutableListOf<ManagedExtension>()
                finalCatalog.addAll(deduplicatedValid)

                for (rejectedId in rejectedIds) {
                    // Do not allow an invalid remote document to destroy a healthy bundled runtime candidate,
                    // BUT NEVER resurrect a candidate that was remotely disabled or removed (F-017 & Hard Rules 22 & 24)
                    val healthyCandidate = bundledDefaults.firstOrNull { it.id.equals(rejectedId, ignoreCase = true) }
                    if (healthyCandidate != null && finalCatalog.none { it.id.equals(rejectedId, ignoreCase = true) }) {
                        val isRemotelyDisabled = remotelyDisabledIds.contains(rejectedId.lowercase()) || cache.getDisabledTombstones().contains(rejectedId.lowercase())
                        val isRemotelyRemoved = removedIds.contains(rejectedId.lowercase()) || cache.getRemovedTombstones().contains(rejectedId.lowercase())
                        if (!isRemotelyRemoved) {
                            val resolvedCandidate = if (isRemotelyDisabled) {
                                healthyCandidate.copy(
                                    status = com.example.extension.managed.model.ExtensionLifecycleStatus.DISABLED,
                                    userEnabled = userPreferences.isExtensionEnabled(rejectedId)
                                )
                            } else {
                                healthyCandidate.copy(userEnabled = userPreferences.isExtensionEnabled(rejectedId))
                            }
                            finalCatalog.add(resolvedCandidate)
                        }
                    }
                }

                finalCatalog.sortByDescending { it.priority }

                com.example.extension.managed.trace.Phase05GLogger.log(
                    "VALIDATED_COUNT",
                    "repo",
                    "Final catalog contains ${finalCatalog.size} extensions (validRemote=${deduplicatedValid.size}, retainedHealthyCandidates=${finalCatalog.size - deduplicatedValid.size})"
                )

                // Save validated items to cache
                cache.saveCache(finalCatalog)
                com.example.extension.managed.trace.Phase05HLogger.log(
                    "CACHE",
                    "repo",
                    "Saved ${finalCatalog.size} validated extensions to cache"
                )

                return@withContext Result.success(finalCatalog)
            }

            // 3. Fallback to cache on remote failure (e.g. offline/network failure/permission error)
            com.example.extension.managed.trace.Phase05GLogger.log(
                "FIREBASE",
                "repo",
                "Remote fetch failed: ${remoteResult.exceptionOrNull()?.message}; attempting fallback"
            )
            val cachedFallback = cache.getLastKnownGood()
            if (cachedFallback != null && cachedFallback.isNotEmpty()) {
                val disabledTombstones = cache.getDisabledTombstones()
                val removedTombstones = cache.getRemovedTombstones()
                val filteredFallback = cachedFallback
                    .filterNot { removedTombstones.contains(it.id.lowercase()) }
                    .map { ext ->
                        val isTombstonedDisabled = disabledTombstones.contains(ext.id.lowercase())
                        ext.copy(
                            status = if (isTombstonedDisabled) com.example.extension.managed.model.ExtensionLifecycleStatus.DISABLED else ext.status,
                            userEnabled = userPreferences.isExtensionEnabled(ext.id)
                        )
                    }
                if (filteredFallback.isNotEmpty()) {
                    com.example.extension.managed.trace.Phase05HLogger.log(
                        "CACHE",
                        "repo",
                        "Restored ${filteredFallback.size} extensions from last-known-good cache"
                    )
                    return@withContext Result.success(filteredFallback)
                }
            }

            // 4. Remote failure + NO last-known-good cache:
            // DO NOT blindly activate bundled defaults (Hard Rules 6, 8, 10, 15)
            com.example.extension.managed.trace.Phase05HLogger.log(
                "STATE",
                "repo",
                "Remote fetch failed and no last-known-good cache exists: GLOBAL_CONFIG_UNAVAILABLE"
            )
            val error = remoteResult.exceptionOrNull()
            val domainError = mapToDomainError(error)
            Result.failure(domainError)
        }

    override suspend fun getExtensionById(id: String, forceRefresh: Boolean): Result<ManagedExtension> =
        withContext(Dispatchers.IO) {
            val listResult = getExtensions(forceRefresh)
            if (listResult.isFailure) {
                return@withContext Result.failure(listResult.exceptionOrNull()!!)
            }

            val extensions = listResult.getOrNull() ?: emptyList()
            val match = extensions.firstOrNull { it.id == id }

            if (match != null) {
                Result.success(match)
            } else {
                Result.failure(
                    ExtensionError.ExtractionFailed("Managed extension with ID '$id' was not found or is invalid")
                )
            }
        }

    private fun mapToDomainError(throwable: Throwable?): Throwable {
        if (throwable == null) {
            return ExtensionError.ExtractionFailed("Unknown repository failure occurred")
        }

        val message = throwable.message.orEmpty().lowercase()
        return if (throwable is IOException ||
            message.contains("unavailable") ||
            message.contains("offline") ||
            message.contains("network") ||
            message.contains("no internet")
        ) {
            ExtensionError.NoInternet(
                detail = "No internet connection available and no local cache present",
                cause = throwable
            )
        } else {
            ExtensionError.ExtractionFailed(
                detail = "Failed to retrieve managed extensions: ${throwable.message}",
                cause = throwable
            )
        }
    }
}
