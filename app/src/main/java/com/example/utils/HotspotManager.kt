package com.example.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build

class HotspotManager(private val context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    var activeSsid: String? = null
    var activePassword: String? = null

    fun is5GHzSupported(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (wifiManager?.is5GHzBandSupported == true) return true
                val info = wifiManager?.connectionInfo
                val freq = info?.frequency ?: 0
                if (freq in 4900..5900) return true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (wifiManager?.isWifiStandardSupported(android.net.wifi.ScanResult.WIFI_STANDARD_11AC) == true ||
                        wifiManager?.isWifiStandardSupported(android.net.wifi.ScanResult.WIFI_STANDARD_11AX) == true) {
                        return true
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        }
    }

    fun startLocalHotspot(onStarted: (String, String) -> Unit, onError: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && wifiManager != null) {
            try {
                wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        super.onStarted(reservation)
                        hotspotReservation = reservation
                        val (ssid, password) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val softAp = reservation.softApConfiguration
                            val s = softAp?.ssid
                            val p = softAp?.passphrase
                            Pair(s, p)
                        } else {
                            @Suppress("DEPRECATION")
                            val config = reservation.wifiConfiguration
                            Pair(config?.SSID, config?.preSharedKey)
                        }
                        val finalSsid = ssid ?: "DIRECT-CS-${(1000..9999).random()}"
                        val finalPassword = password ?: generateSecurePassphrase()
                        activeSsid = finalSsid
                        activePassword = finalPassword
                        onStarted(finalSsid, finalPassword)
                    }

                    override fun onStopped() {
                        super.onStopped()
                        hotspotReservation = null
                    }

                    override fun onFailed(reason: Int) {
                        super.onFailed(reason)
                        hotspotReservation = null
                        onError()
                    }
                }, null)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        onError()
    }

    companion object {
        private val SECURE_RANDOM = java.security.SecureRandom()
        private const val PASSPHRASE_CHARS = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"

        /**
         * Generates a cryptographically secure random alphanumeric passphrase
         * conforming to WPA2 SoftAP requirements (>= 8 characters).
         */
        fun generateSecurePassphrase(length: Int = 16): String {
            val chars = CharArray(length)
            for (i in 0 until length) {
                chars[i] = PASSPHRASE_CHARS[SECURE_RANDOM.nextInt(PASSPHRASE_CHARS.length)]
            }
            return String(chars)
        }
    }

    fun stopLocalHotspot() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                hotspotReservation?.close()
                hotspotReservation = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        activeSsid = null
        activePassword = null
    }

    fun connectToHotspot(ssid: String, password: String, onConnected: () -> Unit, onFailed: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && connectivityManager != null) {
            try {
                val specifier = WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(password)
                    .build()

                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build()

                var callbackTriggered = false
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        try {
                            connectivityManager.bindProcessToNetwork(network)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        if (!callbackTriggered) {
                            callbackTriggered = true
                            onConnected()
                        }
                    }

                    override fun onUnavailable() {
                        super.onUnavailable()
                        if (!callbackTriggered) {
                            callbackTriggered = true
                            onFailed()
                        }
                    }
                }
                connectivityManager.requestNetwork(request, callback, 15000)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        onConnected()
    }
}
