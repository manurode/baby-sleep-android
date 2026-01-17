package com.babysleepmonitor.data

/**
 * Data class representing camera enhancement settings from /get_settings endpoint.
 */
data class SettingsResponse(
    val zoom: Float = 1.0f,
    val contrast: Float = 1.0f,
    val brightness: Int = 0,
    val has_roi: Boolean = false,
    val roi: List<Float>? = null
)
