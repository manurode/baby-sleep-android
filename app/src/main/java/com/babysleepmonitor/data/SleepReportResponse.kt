package com.babysleepmonitor.data

/**
 * Full sleep report data from a historical session.
 * Matches the structure of the /sleep_report/<session_id> endpoint.
 */
data class SleepReportResponse(
    val report_generated_at: Double = 0.0,
    val summary: SleepSummary = SleepSummary(),
    val sleep_breakdown: SleepBreakdown = SleepBreakdown(),
    val events_summary: EventsSummary = EventsSummary(),
    val breathing: BreathingData = BreathingData(),
    val raw_stats: SleepStatsResponse? = null
)

data class SleepSummary(
    val total_sleep: String = "0h 0m",
    val quality_score: Int = 0,
    val quality_rating: String = "Unknown"
) {
    fun getQualityColorHex(): String {
        return when {
            quality_score >= 85 -> "#14B8A6" // Teal (Excellent)
            quality_score >= 70 -> "#22C55E" // Green (Good)
            quality_score >= 50 -> "#F59E0B" // Yellow/Amber (Fair)
            quality_score >= 30 -> "#F97316" // Orange (Poor)
            else -> "#EF4444" // Red (Very Poor)
        }
    }
}

data class SleepBreakdown(
    val deep_sleep: String = "0m (0%)",
    val light_sleep: String = "0m (0%)",
    val description: String = ""
) {
    /**
     * Parse deep sleep percentage from string like "40m (35%)"
     */
    fun getDeepSleepPercent(): Int {
        return extractPercent(deep_sleep)
    }
    
    fun getLightSleepPercent(): Int {
        return extractPercent(light_sleep)
    }
    
    fun getAwakePercent(): Int {
        return 100 - getDeepSleepPercent() - getLightSleepPercent()
    }
    
    private fun extractPercent(str: String): Int {
        val regex = """\((\d+)%\)""".toRegex()
        val match = regex.find(str)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
    
    /**
     * Parse deep sleep minutes from string like "40m (35%)"
     */
    fun getDeepSleepMinutes(): Int {
        return extractMinutes(deep_sleep)
    }
    
    fun getLightSleepMinutes(): Int {
        return extractMinutes(light_sleep)
    }
    
    private fun extractMinutes(str: String): Int {
        val regex = """(\d+)m""".toRegex()
        val match = regex.find(str)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}

data class EventsSummary(
    val wake_ups: Int = 0,
    val spasms: Int = 0,
    val sleep_cycles: Int = 0,
    val average_cycle_minutes: Double? = null
)

data class BreathingData(
    val average_rate_bpm: Double = 0.0,
    val status: String = "unknown",
    val variability: Double = 0.0,
    val current_phase: String = "unknown"
) {
    fun getStatusColorHex(): String {
        return when (status.lowercase()) {
            "normal" -> "#22C55E" // Green
            "slow" -> "#F59E0B" // Yellow
            "fast" -> "#F97316" // Orange
            else -> "#6B7280" // Gray
        }
    }
    
    fun getVariabilityDescription(): String {
        return when {
            variability < 15 -> "Very Regular"
            variability < 25 -> "Regular"
            variability < 35 -> "Slightly Variable"
            else -> "Variable"
        }
    }
}
