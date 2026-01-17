package com.babysleepmonitor.network

import com.babysleepmonitor.data.SettingsResponse
import com.babysleepmonitor.data.StatusResponse
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Singleton API client for communicating with the Baby Sleep Monitor server.
 * All methods are suspend functions for coroutine support.
 */
object ApiClient {
    
    private val gson = Gson()
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS) // Longer timeout for streaming
        .build()
    
    // Separate client for streaming with no read timeout
    private val streamingClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // No timeout for MJPEG stream
        .build()
    
    /**
     * Get the MJPEG video stream as a raw OkHttp Response.
     * The caller is responsible for consuming and closing the response.
     * 
     * @param baseUrl Server base URL (e.g., http://192.168.1.100:5000)
     * @return OkHttp Response containing the MJPEG stream
     */
    suspend fun getVideoStream(baseUrl: String): Response = withContext(Dispatchers.IO) {
        val url = normalizeUrl(baseUrl, "/video_feed")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        
        streamingClient.newCall(request).execute()
    }
    
    /**
     * Get current server status (motion detection, alarm state).
     * 
     * @param baseUrl Server base URL
     * @return StatusResponse with current server state
     */
    suspend fun getStatus(baseUrl: String): StatusResponse = withContext(Dispatchers.IO) {
        val url = normalizeUrl(baseUrl, "/status")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Status request failed: ${response.code}")
        }
        
        val body = response.body?.string() ?: throw IOException("Empty response body")
        response.close()
        
        gson.fromJson(body, StatusResponse::class.java)
    }
    
    /**
     * Get current camera enhancement settings.
     * 
     * @param baseUrl Server base URL
     * @return SettingsResponse with current enhancement values
     */
    suspend fun getSettings(baseUrl: String): SettingsResponse = withContext(Dispatchers.IO) {
        val url = normalizeUrl(baseUrl, "/get_settings")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Settings request failed: ${response.code}")
        }
        
        val body = response.body?.string() ?: throw IOException("Empty response body")
        response.close()
        
        gson.fromJson(body, SettingsResponse::class.java)
    }
    
    /**
     * Update camera enhancement settings.
     * Only provided values will be updated on the server.
     * 
     * @param baseUrl Server base URL
     * @param zoom Optional zoom level (1.0 to 4.0)
     * @param contrast Optional contrast level (1.0 to 3.0)
     * @param brightness Optional brightness level (-50 to 50)
     * @return true if successful
     */
    suspend fun setEnhancements(
        baseUrl: String,
        zoom: Float? = null,
        contrast: Float? = null,
        brightness: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val url = normalizeUrl(baseUrl, "/set_enhancements")
        
        val jsonMap = mutableMapOf<String, Any>()
        zoom?.let { jsonMap["zoom"] = it }
        contrast?.let { jsonMap["contrast"] = it }
        brightness?.let { jsonMap["brightness"] = it }
        
        if (jsonMap.isEmpty()) return@withContext true // Nothing to update
        
        val jsonBody = gson.toJson(jsonMap)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        
        val response = httpClient.newCall(request).execute()
        val success = response.isSuccessful
        response.close()
        
        success
    }
    
    /**
     * Reset all enhancements to default values.
     * 
     * @param baseUrl Server base URL
     * @return true if successful
     */
    suspend fun resetEnhancements(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val url = normalizeUrl(baseUrl, "/reset_enhancements")
        
        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody())
            .build()
        
        val response = httpClient.newCall(request).execute()
        val success = response.isSuccessful
        response.close()
        
        success
    }
    
    /**
     * Set the Region of Interest for motion detection.
     * Coordinates should be normalized (0.0 to 1.0).
     * 
     * @param baseUrl Server base URL
     * @param x X coordinate of ROI (0.0-1.0)
     * @param y Y coordinate of ROI (0.0-1.0)
     * @param w Width of ROI (0.0-1.0)
     * @param h Height of ROI (0.0-1.0)
     * @return true if successful
     */
    suspend fun setRoi(
        baseUrl: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float
    ): Boolean = withContext(Dispatchers.IO) {
        val url = normalizeUrl(baseUrl, "/set_roi")
        
        val jsonMap = mapOf(
            "x" to x,
            "y" to y,
            "w" to w,
            "h" to h
        )
        
        val jsonBody = gson.toJson(jsonMap)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
        
        val response = httpClient.newCall(request).execute()
        val success = response.isSuccessful
        response.close()
        
        success
    }
    
    /**
     * Reset/clear the Region of Interest.
     * 
     * @param baseUrl Server base URL
     * @return true if successful
     */
    suspend fun resetRoi(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val url = normalizeUrl(baseUrl, "/reset_roi")
        
        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody())
            .build()
        
        val response = httpClient.newCall(request).execute()
        val success = response.isSuccessful
        response.close()
        
        success
    }
    
    /**
     * Normalize URL to ensure proper format.
     */
    private fun normalizeUrl(baseUrl: String, endpoint: String): String {
        val base = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        return "$base$endpoint"
    }
}
