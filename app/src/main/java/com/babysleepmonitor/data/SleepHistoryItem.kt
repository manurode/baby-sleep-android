package com.babysleepmonitor.data

/**
 * Data class representing a single item in the sleep history list.
 * Contains summary data for displaying in the history RecyclerView.
 */
data class SleepHistoryItem(
    val id: String = "",
    val timestamp: Double = 0.0,
    val date_iso: String = "",
    val duration_seconds: Int = 0,
    val duration_formatted: String = "",
    val quality_score: Int = 0,
    val quality_rating: String = ""
) {
    /**
     * Get color resource based on quality rating.
     * Returns: green for Excellent/Good, yellow for Fair, orange/red for Poor.
     */
    fun getQualityColorHex(): String {
        return when {
            quality_score >= 85 -> "#14B8A6" // Teal/Green (Excellent)
            quality_score >= 70 -> "#22C55E" // Green (Good)
            quality_score >= 50 -> "#F59E0B" // Yellow/Amber (Fair)
            quality_score >= 30 -> "#F97316" // Orange (Poor)
            else -> "#EF4444" // Red (Very Poor)
        }
    }
    
    /**
     * Get formatted date string like "Today", "Yesterday", or "Mon 21"
     */
    fun getDisplayDate(): Pair<String, String> {
        // Parse the ISO date and format appropriately
        // Returns Pair(dayName, dayNumber)
        try {
            val parts = date_iso.split("T")[0].split("-")
            if (parts.size == 3) {
                val dayOfMonth = parts[2].toIntOrNull() ?: 0
                return Pair("", dayOfMonth.toString())
            }
        } catch (e: Exception) {
            // Fallback
        }
        return Pair("", "")
    }
    
    /**
     * Get quality indicator dot color.
     */
    fun getQualityDotColor(): String {
        return when {
            quality_score >= 70 -> "#22C55E" // Green
            quality_score >= 50 -> "#F59E0B" // Yellow
            else -> "#F97316" // Orange
        }
    }
}

/**
 * Response wrapper for the /sleep_history endpoint.
 */
data class SleepHistoryResponse(
    val history: List<SleepHistoryItem> = emptyList()
)
