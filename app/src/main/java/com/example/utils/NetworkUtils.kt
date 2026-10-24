package com.example.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    @androidx.annotation.VisibleForTesting
    var networkAvailableOverride: Boolean? = null

    fun isInternetAvailable(context: Context): Boolean {
        networkAvailableOverride?.let { return it }
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun getEstimatedBandwidthKbps(context: Context): Int {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return 5000
        val activeNetwork = connectivityManager.activeNetwork ?: return 5000
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return 5000
        val downstream = capabilities.linkDownstreamBandwidthKbps
        return if (downstream > 0) downstream else 5000
    }

    fun selectBestQuality(qualities: List<M3U8Parser.QualityInfo>, bandwidthKbps: Int): M3U8Parser.QualityInfo {
        if (qualities.isEmpty()) {
            return M3U8Parser.QualityInfo("Auto", "")
        }
        return when {
            bandwidthKbps >= 5000 -> qualities.find { it.name.contains("1080") } ?: qualities.first()
            bandwidthKbps >= 2500 -> qualities.find { it.name.contains("720") } ?: qualities.first()
            bandwidthKbps >= 1000 -> qualities.find { it.name.contains("480") } ?: qualities.first()
            else -> qualities.find { it.name.contains("360") } ?: qualities.last()
        }
    }

    fun getLocalIpAddress(context: Context? = null): String {
        // 1. Try WifiManager if connected to Wi-Fi
        if (context != null) {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
                if (ipInt != 0) {
                    return String.format(
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        ipInt shr 8 and 0xff,
                        ipInt shr 16 and 0xff,
                        ipInt shr 24 and 0xff
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Prioritize WLAN, AP, P2P network interfaces
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // First pass: wlan, ap, p2p, swlan, eth
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("p2p") || name.startsWith("swlan") || name.startsWith("eth")) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress ?: continue
                            if (!host.startsWith("127.")) {
                                return host
                            }
                        }
                    }
                }
            }

            // Second pass: any non-loopback interface except cellular/vpn/dummy
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") || name.startsWith("dummy")) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (!host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return "192.168.43.1"
    }

    fun getGatewayIp(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcpInfo = wifiManager?.dhcpInfo
            val gateway = dhcpInfo?.gateway ?: 0
            if (gateway != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    gateway and 0xff,
                    gateway shr 8 and 0xff,
                    gateway shr 16 and 0xff,
                    gateway shr 24 and 0xff
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun getAllDeviceIps(context: Context? = null): List<String> {
        val ips = mutableListOf<String>()
        val primary = getLocalIpAddress(context)
        if (primary.isNotBlank() && primary != "127.0.0.1") {
            ips.add(primary)
        }
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            var hasApInterface = false
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                if (name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("rndis") || name == "wlan1") {
                    hasApInterface = true
                }
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (!host.startsWith("127.") && !host.startsWith("169.254.") && !ips.contains(host)) {
                            ips.add(host)
                        }
                    }
                }
            }
            if (hasApInterface && !ips.contains("192.168.43.1")) {
                ips.add("192.168.43.1")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ips
    }
}
