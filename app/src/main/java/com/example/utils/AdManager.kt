package com.example.utils

import android.content.Context
import com.example.data.repository.UserSecurityManager
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.adlisteners.AdEventListener
import com.startapp.sdk.adsbase.adlisteners.AdDisplayListener
import com.startapp.sdk.adsbase.Ad
import com.startapp.sdk.adsbase.adlisteners.VideoListener

/**
 * PHASE SUBSCRIPTION-POINTS-03B: AD MANAGER INTEGRATION
 *
 * Entitlement Rule:
 * Ad suppression is strictly driven by `UserSecurityManager.isAdFree()`.
 * Active PRO_LITE -> Ads suppressed.
 * Active PRO -> Ads suppressed.
 * FREE -> Ads shown according to forced ads config.
 * Expired -> Ads resume.
 *
 * Ad decisions NEVER inspect video quality, downloadLimit, or allowedQuality.
 */
object AdManager {
    private var startAppAd: StartAppAd? = null

    @Volatile
    var isShowingAd: Boolean = false
        private set

    @Volatile
    var isInsideDetailsScreen: Boolean = false
        private set

    fun enterDetailsScreen() {
        isInsideDetailsScreen = true
    }

    fun exitDetailsScreen() {
        isInsideDetailsScreen = false
    }

    fun preload(context: Context) {
        if (UserSecurityManager.isAdFree() || UserSecurityManager.getForcedAdsRequired() == 0) {
            return
        }
        if (startAppAd == null) {
            startAppAd = StartAppAd(context.applicationContext)
        }
        if (startAppAd?.isReady != true) {
            startAppAd?.loadAd()
        }
    }

    /**
     * Shows a rewarded video advertisement via StartApp.
     * Invokes [onRewardEarned] only if the video completes playback successfully.
     * Invokes [onAdFailed] if the ad cannot be loaded or displayed.
     */
    fun showRewardedAd(
        context: Context,
        onRewardEarned: () -> Unit,
        onAdFailed: (String) -> Unit
    ) {
        val appContext = context.applicationContext
        val rewardedAd = StartAppAd(appContext)

        val rewardAwarded = java.util.concurrent.atomic.AtomicBoolean(false)
        rewardedAd.setVideoListener(object : VideoListener {
            override fun onVideoCompleted() {
                if (rewardAwarded.compareAndSet(false, true)) {
                    onRewardEarned()
                }
            }
        })

        val eventListener = object : AdEventListener {
            override fun onReceiveAd(ad: Ad) {
                val displayListener = object : AdDisplayListener {
                    override fun adHidden(ad: Ad?) {
                        isShowingAd = false
                        if (!rewardAwarded.get()) {
                            onAdFailed("تم إغلاق الإعلان قبل اكتمال المشاهدة")
                        }
                    }

                    override fun adDisplayed(ad: Ad?) {
                        isShowingAd = true
                    }

                    override fun adClicked(ad: Ad?) {}

                    override fun adNotDisplayed(ad: Ad?) {
                        isShowingAd = false
                        onAdFailed("تعذر عرض الإعلان")
                    }
                }

                val shown = rewardedAd.showAd(displayListener)
                if (!shown) {
                    isShowingAd = false
                    onAdFailed("فشل عرض الإعلان")
                }
            }

            override fun onFailedToReceiveAd(ad: Ad?) {
                isShowingAd = false
                onAdFailed("فشل تحميل الإعلان المكافئ، يرجى المحاولة لاحقاً")
            }
        }

        rewardedAd.loadAd(StartAppAd.AdMode.REWARDED_VIDEO, eventListener)
    }

    fun showInterstitial(context: Context, onDismissed: (() -> Unit)? = null) {
        if (UserSecurityManager.isAdFree() || UserSecurityManager.getForcedAdsRequired() == 0) {
            onDismissed?.invoke()
            return
        }

        if (startAppAd == null) {
            startAppAd = StartAppAd(context.applicationContext)
        }

        val displayListener = object : AdDisplayListener {
            override fun adHidden(ad: Ad?) {
                isShowingAd = false
                startAppAd?.loadAd()
                onDismissed?.invoke()
            }

            override fun adDisplayed(ad: Ad?) {
                isShowingAd = true
            }

            override fun adClicked(ad: Ad?) {
            }

            override fun adNotDisplayed(ad: Ad?) {
                isShowingAd = false
                startAppAd?.loadAd()
                onDismissed?.invoke()
            }
        }

        if (startAppAd?.isReady == true) {
            isShowingAd = true
            val shown = startAppAd?.showAd(displayListener) ?: false
            if (!shown) {
                isShowingAd = false
                startAppAd?.loadAd()
                onDismissed?.invoke()
            }
        } else {
            // Ad is not ready yet, preload for future actions so we don't unexpectedly hijack the user later
            startAppAd?.loadAd()
            onDismissed?.invoke()
        }
    }
}
