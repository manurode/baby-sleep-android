package com.babysleepmonitor.data

/**
 * Data class representing the sleep statistics response from /sleep_stats endpoint.
 * Contains comprehensive sleep monitoring metrics including breathing analysis and quality scoring.
 */
data class SleepStatsResponse(
    // Current State
    val current_state: String = "unknown",
    val breathing_detected: Boolean = false,
    val state_duration_seconds: Int = 0,
    
    // Session Summary
    val session_duration_minutes: Int = 0,
    val session_duration_seconds: Int = 0,
    
    // Sleep Duration Breakdown
    val total_sleep_minutes: Int = 0,
    val total_sleep_seconds: Int = 0,
    val deep_sleep_minutes: Int = 0,
    val deep_sleep_seconds: Int = 0,
    val light_sleep_minutes: Int = 0,
    val light_sleep_seconds: Int = 0,
    
    // Sleep Quality
    val sleep_quality_score: Int = 0,
    val deep_sleep_percent: Int = 0,
    val light_sleep_percent: Int = 0,
    
    // Events
    val wake_ups: Int = 0,
    val spasms: Int = 0,
    val sleep_cycles_completed: Int = 0,
    
    // Breathing Analysis
    val breathing_rate_bpm: Double = 0.0,
    val breathing_variability: Double = 0.0,
    val breathing_phase: String = "unknown",
    val breaths_detected: Int = 0,
    
    // Motion Analysis
    val last_motion_score: Double = 0.0,
    val motion_mean: Double = 0.0,
    val motion_std: Double = 0.0,
    
    // Misc
    val events_count: Int = 0,
    val pending_transition: String? = null
) {
    /**
     * Get a human-readable state description.
     */
    fun getStateDisplayName(): String {
        return when (current_state) {
            "light_sleep" -> "Light Sleep"
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
            "light_sleep" -> "😴"
            "deep_sleep" -> "💤"
            "spasm" -> "💢"
            "awake" -> "👀"
            "no_breathing" -> "⚠️"
            else -> "❓"
        }
    }
    
    /**
     * Get quality rating text.
     */
    fun getQualityRating(): String {
        return when {
            sleep_quality_score >= 85 -> "Excellent"
            sleep_quality_score >= 70 -> "Good"
            sleep_quality_score >= 50 -> "Fair"
            sleep_quality_score >= 30 -> "Poor"
            else -> "Very Poor"
        }
    }
    
    /**
     * Get breathing status description.
     */
    fun getBreathingStatus(): String {
        return when {
            breathing_rate_bpm <= 0 -> "Detecting..."
            breathing_rate_bpm < 25 -> "Slow"
            breathing_rate_bpm > 60 -> "Fast"
            else -> "Normal"
        }
    }
    
    /**
     * Get sleep phase description.
     */
    fun getSleepPhaseDescription(): String {
        return when (breathing_phase) {
            "deep" -> "Regular breathing (restorative)"
            "light" -> "Variable breathing (REM/brain development)"
            "transitional" -> "Changing phases"
            else -> "Analyzing..."
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
     * Get formatted deep sleep time.
     */
    fun getFormattedDeepSleep(): String {
        return "${deep_sleep_minutes}m (${deep_sleep_percent}%)"
    }
    
    /**
     * Get formatted light sleep time.
     */
    fun getFormattedLightSleep(): String {
        return "${light_sleep_minutes}m (${light_sleep_percent}%)"
    }
}

/**
 * Sleep detection thresholds returned by the server.
 */
data class SleepThresholds(
    val no_motion: Int = 10000,
    val breathing_low: Int = 10000,
    val breathing_high: Int = 1500000,
    val movement: Int = 5000000,
    val awake: Int = 10000000
)
