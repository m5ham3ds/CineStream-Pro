package com.example.extension.managed.trace

import android.util.Log

/**
 * Phase 05H: Dedicated trace logger for global administrative extension control.
 * Emits required forensic tags [05H][GLOBAL][id][...] to Logcat and standard output.
 */
object Phase05HLogger {

    fun log(tagSuffix: String, id: String, message: String) {
        val fullTag = "[05H][GLOBAL][$id][$tagSuffix]"
        try {
            Log.i("Phase05HTrace", "$fullTag $message")
        } catch (_: Throwable) {}
        println("$fullTag $message")
    }
}
