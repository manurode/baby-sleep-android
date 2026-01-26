package com.babysleepmonitor.data

/**
 * Represents an ONVIF-compliant camera discovered on the network.
 */
data class OnvifCamera(
    val hostname: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val firmwareVersion: String? = null,
    val serialNumber: String? = null,
    val streamUri: String? = null,
    val xAddr: String = "", // ONVIF service address
    val username: String? = null,
    val password: String? = null
) {
    /**
     * Display name for UI - prioritizes model, falls back to hostname
     */
    val displayName: String
        get() = when {
            !manufacturer.isNullOrBlank() && !model.isNullOrBlank() -> "$manufacturer $model"
            !model.isNullOrBlank() -> model
            !manufacturer.isNullOrBlank() -> manufacturer
            else -> hostname
        }
}
