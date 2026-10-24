package com.example.ui.screens.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.DailyLoginClaimRequest
import com.example.data.model.DailyLoginState
import com.example.data.model.EconomyConfig
import com.example.data.model.FeatureState
import com.example.data.model.FeaturesConfig
import com.example.data.model.LeaderboardEntry
import com.example.data.model.PointTransaction
import com.example.data.model.PointWallet
import com.example.data.model.PointsOperationResult
import com.example.data.model.RewardTask
import com.example.data.model.RewardedAdClaimRequest
import com.example.data.model.RewardedAdState
import com.example.data.model.TaskRewardClaimRequest
import com.example.data.model.SubscriptionRedemptionRequest
import com.example.data.repository.AuthRepository
import com.example.data.repository.EconomyConfigRepository
import com.example.data.repository.PointsEarningRepository
import com.example.data.repository.PointsRepository
import com.example.data.repository.UserSecurityManager
import com.example.utils.AdManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * PHASE 03D: POINTS EARNING VIEW MODEL
 *
 * Coordinates UI state for:
 * 1. Point Wallet (balance, earned, spent)
 * 2. Point Ledger (transaction history)
 * 3. Daily Login (streaks, ladder, claim)
 * 4. Rewarded Ads (cap, cooldown, watch)
 * 5. Reward Tasks (listing, claims)
 * 6. Leaderboard (weekly rankings)
 *
 * Strict Invariant: Zero client economic authority. UI state is driven by authoritative reads.
 */
class PointsEarningViewModel : ViewModel() {

    private val auth get() = FirebaseAuth.getInstance()
    private val currentUid: String
        get() = auth.currentUser?.uid ?: AuthRepository.currentUserFlow.value?.id ?: ""

    val featuresConfig: StateFlow<FeaturesConfig> = EconomyConfigRepository.featuresConfig
    val economyConfig: StateFlow<EconomyConfig> = EconomyConfigRepository.economyConfig

    val wallet: StateFlow<PointWallet> = PointsEarningRepository.observeWallet(currentUid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PointWallet())

    val transactions: StateFlow<List<PointTransaction>> = PointsRepository.observePointTransactions(currentUid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailyLoginState: StateFlow<DailyLoginState> = PointsEarningRepository.observeDailyLoginState(currentUid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DailyLoginState())

    val rewardedAdState: StateFlow<RewardedAdState> = PointsEarningRepository.observeRewardedAdState(currentUid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RewardedAdState())

    val tasks: StateFlow<List<RewardTask>> = PointsRepository.observeRewardTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val taskClaims: StateFlow<Map<String, Boolean>> = PointsEarningRepository.observeUserTaskClaims(currentUid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val leaderboard: StateFlow<List<LeaderboardEntry>> = PointsRepository.observeWeeklyLeaderboard()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isClaimingDailyLogin = MutableStateFlow(false)
    val isClaimingDailyLogin: StateFlow<Boolean> = _isClaimingDailyLogin.asStateFlow()

    private val _isWatchingRewardedAd = MutableStateFlow(false)
    val isWatchingRewardedAd: StateFlow<Boolean> = _isWatchingRewardedAd.asStateFlow()

    private val _claimingTaskId = MutableStateFlow<String?>(null)
    val claimingTaskId: StateFlow<String?> = _claimingTaskId.asStateFlow()

    private val _isRedeemingSubscription = MutableStateFlow(false)
    val isRedeemingSubscription: StateFlow<Boolean> = _isRedeemingSubscription.asStateFlow()

    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage: StateFlow<String?> = _operationMessage.asStateFlow()

    init {
        EconomyConfigRepository.startListening()
    }

    fun clearMessage() {
        _operationMessage.value = null
    }

    /**
     * Dispatches daily login claim request with idempotency.
     */
    fun claimDailyLogin() {
        if (_isClaimingDailyLogin.value) return // Prevent duplicate / double tap
        val uid = currentUid
        if (uid.isBlank()) {
            _operationMessage.value = "يجب تسجيل الدخول لاستلام المكافأة اليومية"
            return
        }

        // Feature flag enforcement
        val featState = featuresConfig.value.dailyLogin
        if (featState == FeatureState.COMING_SOON) {
            _operationMessage.value = featuresConfig.value.getComingSoonMessage("dailyLogin")
            return
        } else if (featState == FeatureState.DISABLED) {
            _operationMessage.value = featuresConfig.value.getDisabledMessage("dailyLogin")
            return
        }

        _isClaimingDailyLogin.value = true
        viewModelScope.launch {
            try {
                val req = DailyLoginClaimRequest(userId = uid)
                val result = PointsEarningRepository.claimDailyLogin(req)
                when (result) {
                    is PointsOperationResult.Success -> {
                        _operationMessage.value = "تم استلام المكافأة اليومية بنجاح"
                    }
                    is PointsOperationResult.PendingUnknown -> {
                        _operationMessage.value = result.message
                    }
                    is PointsOperationResult.Error -> {
                        _operationMessage.value = result.message
                    }
                }
            } catch (e: Exception) {
                _operationMessage.value = "حدث خطأ: ${e.message}"
            } finally {
                _isClaimingDailyLogin.value = false
                PointsEarningRepository.refreshAuthoritativeState(uid)
            }
        }
    }

    /**
     * Dispatches rewarded ad claim request with idempotency.
     */
    fun watchRewardedAd(context: Context) {
        if (_isWatchingRewardedAd.value) return // Prevent duplicate
        val uid = currentUid
        if (uid.isBlank()) {
            _operationMessage.value = "يجب تسجيل الدخول لمشاهدة الإعلانات وكسب النقاط"
            return
        }

        val featState = featuresConfig.value.rewardedAds
        if (featState == FeatureState.COMING_SOON) {
            _operationMessage.value = featuresConfig.value.getComingSoonMessage("rewardedAds")
            return
        } else if (featState == FeatureState.DISABLED) {
            _operationMessage.value = featuresConfig.value.getDisabledMessage("rewardedAds")
            return
        }

        val adState = rewardedAdState.value
        if (!adState.isEligible) {
            if (adState.dailyWatchedCount >= adState.dailyCap) {
                _operationMessage.value = "تم الوصول إلى الحد الأقصى لمشاهدة الإعلانات اليوم (${adState.dailyCap})"
            } else if (adState.cooldownSecondsRemaining > 0) {
                _operationMessage.value = "يرجى الانتظار ${adState.cooldownSecondsRemaining} ثانية قبل مشاهدة الإعلان التالي"
            }
            return
        }

        val claimSessionId = java.util.UUID.randomUUID().toString()
        _isWatchingRewardedAd.value = true
        AdManager.showRewardedAd(
            context = context,
            onRewardEarned = {
                viewModelScope.launch {
                    try {
                        val req = RewardedAdClaimRequest(
                            requestId = claimSessionId,
                            userId = uid,
                            verificationToken = "startapp_rewarded_verified_$claimSessionId"
                        )
                        val result = PointsEarningRepository.claimRewardedAd(req)
                        when (result) {
                            is PointsOperationResult.Success -> {
                                _operationMessage.value = "تمت إضافة نقاط الإعلان بنجاح"
                            }
                            is PointsOperationResult.PendingUnknown -> {
                                _operationMessage.value = result.message
                            }
                            is PointsOperationResult.Error -> {
                                _operationMessage.value = result.message
                            }
                        }
                    } catch (e: Exception) {
                        _operationMessage.value = "حدث خطأ: ${e.message}"
                    } finally {
                        _isWatchingRewardedAd.value = false
                        PointsEarningRepository.refreshAuthoritativeState(uid)
                    }
                }
            },
            onAdFailed = { failureReason ->
                _isWatchingRewardedAd.value = false
                _operationMessage.value = failureReason
            }
        )
    }

    /**
     * Dispatches task reward claim request.
     */
    fun claimTaskReward(task: RewardTask) {
        if (_claimingTaskId.value != null) return // Prevent duplicate
        val uid = currentUid
        if (uid.isBlank()) {
            _operationMessage.value = "يجب تسجيل الدخول لاستلام مكافأة المهمة"
            return
        }

        val featState = featuresConfig.value.tasks
        if (featState == FeatureState.COMING_SOON) {
            _operationMessage.value = featuresConfig.value.getComingSoonMessage("tasks")
            return
        } else if (featState == FeatureState.DISABLED) {
            _operationMessage.value = featuresConfig.value.getDisabledMessage("tasks")
            return
        }

        if (taskClaims.value[task.taskId] == true) {
            _operationMessage.value = "تم استلام مكافأة هذه المهمة مسبقاً"
            return
        }

        _claimingTaskId.value = task.taskId
        viewModelScope.launch {
            try {
                val req = TaskRewardClaimRequest(userId = uid, taskId = task.taskId)
                val result = PointsEarningRepository.claimTaskReward(req)
                when (result) {
                    is PointsOperationResult.Success -> {
                        _operationMessage.value = "تم استلام مكافأة المهمة بنجاح"
                    }
                    is PointsOperationResult.PendingUnknown -> {
                        _operationMessage.value = result.message
                    }
                    is PointsOperationResult.Error -> {
                        _operationMessage.value = result.message
                    }
                }
            } catch (e: Exception) {
                _operationMessage.value = "حدث خطأ: ${e.message}"
            } finally {
                _claimingTaskId.value = null
                PointsEarningRepository.refreshAuthoritativeState(uid)
            }
        }
    }

    /**
     * Dispatches trusted points subscription redemption (Phase 03F).
     * Authoritative backend activates subscription and deducts points atomically.
     */
    fun redeemSubscription(sku: String, onComplete: ((Boolean, String?) -> Unit)? = null) {
        if (_isRedeemingSubscription.value) return // Prevent double-submit
        val uid = currentUid
        if (uid.isBlank()) {
            _operationMessage.value = "يجب تسجيل الدخول لاستبدال النقاط بالاشتراك"
            onComplete?.invoke(false, "يجب تسجيل الدخول")
            return
        }

        // Feature flags check
        val featPoints = featuresConfig.value.points
        val featSubs = featuresConfig.value.subscriptions
        if (featPoints == FeatureState.COMING_SOON || featSubs == FeatureState.COMING_SOON) {
            val msg = if (featPoints == FeatureState.COMING_SOON) {
                featuresConfig.value.getComingSoonMessage("points")
            } else {
                featuresConfig.value.getComingSoonMessage("subscriptions")
            }
            _operationMessage.value = msg
            onComplete?.invoke(false, msg)
            return
        } else if (featPoints == FeatureState.DISABLED || featSubs == FeatureState.DISABLED) {
            val msg = if (featPoints == FeatureState.DISABLED) {
                featuresConfig.value.getDisabledMessage("points")
            } else {
                featuresConfig.value.getDisabledMessage("subscriptions")
            }
            _operationMessage.value = msg
            onComplete?.invoke(false, msg)
            return
        }

        _isRedeemingSubscription.value = true
        viewModelScope.launch {
            try {
                val req = SubscriptionRedemptionRequest(sku = sku)
                val result = PointsEarningRepository.redeemSubscription(req)
                when (result) {
                    is PointsOperationResult.Success -> {
                        _operationMessage.value = "تم استبدال النقاط وتفعيل الاشتراك بنجاح!"
                        onComplete?.invoke(true, null)
                    }
                    is PointsOperationResult.PendingUnknown -> {
                        _operationMessage.value = result.message
                        onComplete?.invoke(false, result.message)
                    }
                    is PointsOperationResult.Error -> {
                        _operationMessage.value = result.message
                        onComplete?.invoke(false, result.message)
                    }
                }
            } catch (e: Exception) {
                _operationMessage.value = "حدث خطأ: ${e.message}"
                onComplete?.invoke(false, e.message)
            } finally {
                _isRedeemingSubscription.value = false
                PointsEarningRepository.refreshAuthoritativeState(uid)
                UserSecurityManager.refreshUserSecurity(uid)
            }
        }
    }

    fun refreshAll() {
        val uid = currentUid
        viewModelScope.launch {
            EconomyConfigRepository.refreshConfigOnce()
            if (uid.isNotBlank()) {
                PointsEarningRepository.refreshAuthoritativeState(uid)
                UserSecurityManager.refreshUserSecurity(uid)
            }
        }
    }
}
