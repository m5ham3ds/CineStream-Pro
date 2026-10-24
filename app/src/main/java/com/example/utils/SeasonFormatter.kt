package com.example.utils

import android.content.Context
import com.example.R

object SeasonFormatter {
    fun getSeasonString(context: Context, seasonCount: Int): String {
        return when (seasonCount) {
            1 -> context.getString(R.string.season_one)
            2 -> context.getString(R.string.season_two)
            3 -> context.getString(R.string.season_three)
            4 -> context.getString(R.string.season_four)
            5 -> context.getString(R.string.season_five)
            6 -> context.getString(R.string.season_six)
            7 -> context.getString(R.string.season_seven)
            8 -> context.getString(R.string.season_eight)
            9 -> context.getString(R.string.season_nine)
            10 -> context.getString(R.string.season_ten)
            else -> context.getString(R.string.season_x, seasonCount)
        }
    }

    /**
     * Formats season count when authoritative metadata is available (> 0).
     * If season metadata is unavailable or not loaded (<= 0), safely falls back
     * to the provided fallback string (e.g. release year) to prevent displaying "Season 0".
     */
    fun formatSeasonOrFallback(context: Context, seasonCount: Int, fallback: String): String {
        return if (seasonCount > 0) {
            getSeasonString(context, seasonCount)
        } else {
            fallback
        }
    }
}
