package com.babysleepmonitor.data

/**
 * Data class representing the sleep statistics response from /sleep_stats endpoint.
 * Contains all sleep monitoring metrics including state, breathing detection, and quality.
 */
data class SleepStatsResponse(
    val current_state: String = "unknown",
    val breathing_detected: Boolean = false,
    val state_duration_seconds: Int = 0,
    val session_duration_minutes: Int = 0,
    val session_duration_seconds: Int = 0,
    val total_sleep_minutes: Int = 0,
    val total_sleep_seconds: Int = 0,
    val wake_ups: Int = 0,
    val spasms: Int = 0,
    val breathing_quality_percent: Int = 0,
    val last_motion_score: Double = 0.0,
    val motion_mean: Double = 0.0,
    val motion_std: Double = 0.0,
    val is_rhythmic: Boolean = false,
    val events_count: Int = 0,
    val pending_transition: String? = null,
    val thresholds: SleepThresholds? = null
) {
    /**
     * Get a human-readable state description.
     */
    fun getStateDisplayName(): String {
        return when (current_state) {
            "sleeping" -> "Sleeping"
            "deep_sleep" -> "Deep Sleep"
            "spasm" -> "Spasm"
            "awake" -> "Awake"
            "no_breathing" -> "No Breathing!"
            else -> "Unknown"
        }
    }
    
    /**
     * Get a state emoji for visual representation.
     */
    fun getStateEmoji(): String {
        return when (current_state) {
            "sleeping" -> "😴"
            "deep_sleep" -> "💤"
            "spasm" -> "💢"
            "awake" -> "👀"
            "no_breathing" -> "⚠️"
            else -> "❓"
        }
    }
    
    /**
     * Get the color indicator for current state.
     */
    fun getStateColor(): String {
        return when (current_state) {
            "sleeping", "deep_sleep" -> "green"
            "spasm" -> "orange"
            "awake" -> "blue"
            "no_breathing" -> "red"
            else -> "gray"
        }
    }
    
    /**
     * Format total sleep time as "Xh Ym".
     */
    fun getFormattedSleepTime(): String {
        val hours = total_sleep_minutes / 60
        val minutes = total_sleep_minutes % 60
        return if (hours > 0) {
            "${hours}h ${minutes}m"
        } else {
            "${minutes}m"
        }
    }
    
    /**
     * Format session duration as "Xh Ym".
     */
    fun getFormattedSessionTime(): String {
        val hours = session_duration_minutes / 60
        val minutes = session_duration_minutes % 60
        return if (hours > 0) {
            "${hours}h ${minutes}m"
        } else {
            "${minutes}m"
        }
    }
    
    /**
     * Get seconds since breathing was last detected.
     * Based on state - if in sleep state, breathing is detected now.
     */
    fun getLastBreathingSecondsAgo(): Int {
        return if (breathing_detected) 0 else state_duration_seconds
    }
}

/**
 * Sleep detection thresholds returned by the server.
 */
data class SleepThresholds(
    val no_motion: Int = 50000,
    val breathing_low: Int = 100000,
    val breathing_high: Int = 1500000,
    val movement: Int = 2000000,
    val awake: Int = 3000000
)
