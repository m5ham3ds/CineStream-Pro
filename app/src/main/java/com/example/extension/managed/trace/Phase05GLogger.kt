package com.example.extension.managed.trace

import android.util.Log

/**
 * Phase 05G: Dedicated trace logger for critical playback and extension synchronization events.
 * Emits required forensic tags [05G][PLAY][id][...] to both Logcat and standard output.
 */
object Phase05GLogger {

    fun log(tagSuffix: String, id: String, message: String) {
        val fullTag = "[05G][PLAY][$id][$tagSuffix]"
        try {
            Log.i("Phase05GTrace", "$fullTag $message")
        } catch (_: Throwable) {}
        println("$fullTag $message")
    }
}
