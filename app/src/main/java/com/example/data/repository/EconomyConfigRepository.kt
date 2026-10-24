package com.example.data.repository

import android.util.Log
import com.example.data.model.EconomyConfig
import com.example.data.model.FeatureState
import com.example.data.model.FeaturesConfig
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * PHASE SUBSCRIPTION-POINTS-03B: ECONOMY & FEATURE CONFIG CONSUMPTION
 *
 * Invariants:
 * 1. Read-only consumption of /config/features and /config/economy.
 * 2. Zero client-side configuration writes.
 * 3. Graceful fallback on missing document, permission denied, offline, or malformed data.
 */
object EconomyConfigRepository {
    private const val TAG = "EconomyConfigRepository"

    private val _featuresConfig = MutableStateFlow(FeaturesConfig())
    val featuresConfig: StateFlow<FeaturesConfig> = _featuresConfig.asStateFlow()

    private val _economyConfig = MutableStateFlow(EconomyConfig())
    val economyConfig: StateFlow<EconomyConfig> = _economyConfig.asStateFlow()

    private var featuresListener: ListenerRegistration? = null
    private var economyListener: ListenerRegistration? = null

    @androidx.annotation.VisibleForTesting
    fun setTestFeaturesConfig(config: FeaturesConfig) {
        _featuresConfig.value = config
    }

    @androidx.annotation.VisibleForTesting
    fun setTestEconomyConfig(config: EconomyConfig) {
        _economyConfig.value = config
    }

    fun startListening() {
        stopListening()
        val db = FirebaseFirestore.getInstance()

        // 1. Listen to /config/features safely
        try {
            featuresListener = db.collection("config").document("features")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Features config listen error (using safe default): ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        _featuresConfig.value = parseFeaturesConfig(snapshot)
                    } else {
                        Log.d(TAG, "Features config document does not exist, using safe defaults")
                        _featuresConfig.value = FeaturesConfig()
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Exception attaching features listener: ${e.message}")
        }

        // 2. Listen to /config/economy safely
        try {
            economyListener = db.collection("config").document("economy")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Economy config listen error (using safe default): ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        _economyConfig.value = parseEconomyConfig(snapshot)
                    } else {
                        Log.d(TAG, "Economy config document does not exist, using safe defaults")
                        _economyConfig.value = EconomyConfig()
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Exception attaching economy listener: ${e.message}")
        }
    }

    fun stopListening() {
        featuresListener?.remove()
        featuresListener = null
        economyListener?.remove()
        economyListener = null
    }

    suspend fun refreshConfigOnce() {
        val db = FirebaseFirestore.getInstance()
        try {
            val featDoc = db.collection("config").document("features").get().await()
            if (featDoc != null && featDoc.exists()) {
                _featuresConfig.value = parseFeaturesConfig(featDoc)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh features config: ${e.message}")
        }

        try {
            val econDoc = db.collection("config").document("economy").get().await()
            if (econDoc != null && econDoc.exists()) {
                _economyConfig.value = parseEconomyConfig(econDoc)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh economy config: ${e.message}")
        }
    }

    fun parseFeaturesConfig(doc: DocumentSnapshot): FeaturesConfig {
        return try {
            val data = doc.data
            if (data != null && data.isNotEmpty()) {
                parseFeaturesConfig(data)
            } else {
                val map = mutableMapOf<String, Any?>()
                listOf("subscriptions", "points", "dailyLogin", "rewardedAds", "tasks", "leaderboard").forEach { key ->
                    try {
                        map[key] = doc.get(key)
                    } catch (_: Exception) {}
                }
                try {
                    map["disabledMessage"] = doc.getString("disabledMessage")
                } catch (_: Exception) {}
                parseFeaturesConfig(map)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Malformed features config, using defaults", e)
            FeaturesConfig()
        }
    }

    fun parseFeaturesConfig(data: Map<String, Any?>): FeaturesConfig {
        return try {
            val globalDisabledMessage = (data["disabledMessage"] as? String)?.trim()?.ifBlank { null }
                ?: FeaturesConfig.DEFAULT_DISABLED_MESSAGE

            val subsItem = FeaturesConfig.parseFeatureItem(data["subscriptions"])
            val pointsItem = FeaturesConfig.parseFeatureItem(data["points"])
            val dailyLoginItem = FeaturesConfig.parseFeatureItem(data["dailyLogin"])
            val rewardedAdsItem = FeaturesConfig.parseFeatureItem(data["rewardedAds"])
            val tasksItem = FeaturesConfig.parseFeatureItem(data["tasks"])
            val leaderboardItem = FeaturesConfig.parseFeatureItem(data["leaderboard"])

            val detailsMap = mutableMapOf<String, com.example.data.model.FeatureItemConfig>()
            data.forEach { (key, value) ->
                if (key != "disabledMessage") {
                    detailsMap[key] = FeaturesConfig.parseFeatureItem(value)
                }
            }

            FeaturesConfig(
                subscriptions = subsItem.status,
                points = pointsItem.status,
                dailyLogin = dailyLoginItem.status,
                rewardedAds = rewardedAdsItem.status,
                tasks = tasksItem.status,
                leaderboard = leaderboardItem.status,
                disabledMessage = globalDisabledMessage,
                featureDetails = detailsMap
            )
        } catch (e: Exception) {
            Log.e(TAG, "Malformed features config data, using defaults", e)
            FeaturesConfig()
        }
    }

    fun parseEconomyConfig(doc: DocumentSnapshot): EconomyConfig {
        return try {
            val data = doc.data
            if (data != null && data.isNotEmpty()) {
                parseEconomyConfig(data)
            } else {
                val map = mutableMapOf<String, Any?>()
                listOf("redemptionCosts", "dailyLoginRewards", "rewardedAdPoints", "rewardedAdDailyCap", "rewardedAdCooldownSeconds").forEach { key ->
                    try {
                        map[key] = doc.get(key)
                    } catch (_: Exception) {}
                }
                parseEconomyConfig(map)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Malformed economy config doc, using defaults", e)
            EconomyConfig()
        }
    }

    fun parseEconomyConfig(data: Map<String, Any?>): EconomyConfig {
        return try {
            val rawCosts = data["redemptionCosts"] as? Map<*, *>
            val costsMap = mutableMapOf<String, Long>()
            rawCosts?.forEach { (k, v) ->
                if (k is String) {
                    val costLong = when (v) {
                        is Number -> v.toLong()
                        is String -> v.trim().toLongOrNull()
                        else -> null
                    }
                    if (costLong != null && costLong > 0L) {
                        costsMap[k] = costLong
                    }
                }
            }

            val rawRewards = (data["dailyLoginRewards"] ?: data["dailyRewards"]) as? List<*>
            val rewardsList = rawRewards?.mapNotNull { item ->
                when (item) {
                    is Number -> item.toLong()
                    is String -> item.trim().toLongOrNull()
                    else -> null
                }
            }

            val rawRewardedPoints = data["rewardedAdPoints"] ?: data["rewardedPoints"] ?: data["adReward"] ?: data["pointsPerAd"]
            val rewardedPoints = when (rawRewardedPoints) {
                is Number -> rawRewardedPoints.toLong()
                is String -> rawRewardedPoints.trim().toLongOrNull() ?: 15L
                else -> 15L
            }

            val rawDailyCap = data["rewardedAdDailyCap"] ?: data["dailyCap"] ?: data["maxDailyAds"] ?: data["adDailyCap"]
            val dailyCap = when (rawDailyCap) {
                is Number -> rawDailyCap.toInt()
                is String -> rawDailyCap.trim().toIntOrNull() ?: 5
                else -> 5
            }

            val rawCooldown = data["rewardedAdCooldownSeconds"] ?: data["cooldownSeconds"] ?: data["adCooldownSeconds"] ?: data["cooldown"]
            val cooldown = when (rawCooldown) {
                is Number -> rawCooldown.toLong()
                is String -> rawCooldown.trim().toLongOrNull() ?: 300L
                else -> 300L
            }

            EconomyConfig(
                redemptionCosts = if (costsMap.isNotEmpty()) costsMap else EconomyConfig().redemptionCosts,
                dailyLoginRewards = if (!rewardsList.isNullOrEmpty()) rewardsList else EconomyConfig().dailyLoginRewards,
                rewardedAdPoints = rewardedPoints,
                rewardedAdDailyCap = dailyCap,
                rewardedAdCooldownSeconds = cooldown
            )
        } catch (e: Exception) {
            Log.e(TAG, "Malformed economy config data, using defaults", e)
            EconomyConfig()
        }
    }
}
