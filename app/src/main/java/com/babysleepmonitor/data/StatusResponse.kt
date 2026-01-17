package com.babysleepmonitor.data

/**
 * Data class representing the server status response from /status endpoint.
 * Shared between MainActivity and MonitoringService.
 */
data class StatusResponse(
    val motion_detected: Boolean = false,
    val motion_score: Double = 0.0,
    val alarm_active: Boolean = false,
    val seconds_since_motion: Int = 0
)
