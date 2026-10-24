package com.example.utils

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.net.NetworkInterface
import java.util.Collections

object DevicePreparationHelper {

    fun isWifiEnabled(context: Context): Boolean {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.isWifiEnabled == true
        } catch (_: Exception) {
            false
        }
    }

    fun isLocationEnabled(context: Context): Boolean {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                val mode = Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF)
                mode != Settings.Secure.LOCATION_MODE_OFF
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isHotspotEnabled(context: Context): Boolean {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                val method = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
                method.isAccessible = true
                val isEnabled = method.invoke(wm) as? Boolean
                if (isEnabled != null) return isEnabled
            }
        } catch (_: Exception) {}

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            return interfaces.any { intf ->
                intf.isUp && (
                    intf.name.startsWith("ap") ||
                    intf.name.startsWith("swlan") ||
                    intf.name.startsWith("rndis") ||
                    intf.name == "wlan1"
                )
            }
        } catch (_: Exception) {
            return false
        }
    }

    fun isReadyForSend(context: Context): Boolean {
        val wifiOk = isWifiEnabled(context)
        val hotspotOk = !isHotspotEnabled(context)
        val locationOk = isLocationEnabled(context)
        return wifiOk && hotspotOk && locationOk
    }

    fun isReadyForReceive(context: Context): Boolean {
        return isLocationEnabled(context)
    }

    fun openWifiSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                @Suppress("DEPRECATION")
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wm?.isWifiEnabled = true
            } catch (_: Exception) {}
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(panelIntent)
                return
            } catch (_: Exception) {}
        }
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun openLocationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun openHotspotSettings(context: Context) {
        val candidateIntents = listOf(
            Intent().apply {
                setClassName("com.android.settings", "com.android.settings.TetherSettings")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            Intent("android.settings.TETHER_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )

        for (intent in candidateIntents) {
            try {
                context.startActivity(intent)
                break
            } catch (_: Exception) {}
        }
    }
}
