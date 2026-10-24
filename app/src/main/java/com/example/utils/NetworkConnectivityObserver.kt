package com.example.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Observes the device's default network and emits true only when Android has
 * validated real internet access. onAvailable alone is not sufficient: mobile
 * data/Wi-Fi may be connected while DNS or upstream access is still unavailable.
 */
class NetworkConnectivityObserver(private val context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun observe(): Flow<Boolean> = callbackFlow {
        val cm = connectivityManager
        if (cm == null) {
            trySend(NetworkUtils.isInternetAvailable(context))
            channel.close()
            return@callbackFlow
        }

        fun hasValidatedInternet(network: Network?): Boolean {
            if (network == null) return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
            // VALIDATED means Android has confirmed that this network can reach the internet.
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Do not report online until Android validates this network.
                trySend(hasValidatedInternet(network))
            }

            override fun onLost(network: Network) {
                // A replacement default network may already be active when this callback fires.
                trySend(hasValidatedInternet(cm.activeNetwork))
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val validated =
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                trySend(validated)
            }

            override fun onUnavailable() {
                trySend(false)
            }
        }

        // Emit initial state before registering the callback.
        trySend(NetworkUtils.isInternetAvailable(context))

        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            trySend(NetworkUtils.isInternetAvailable(context))
        }

        awaitClose {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
                // Callback may not have been registered if the platform rejected it.
            }
        }
    }.distinctUntilChanged()
}
