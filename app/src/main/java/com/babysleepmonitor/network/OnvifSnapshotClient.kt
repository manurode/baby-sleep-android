package com.babysleepmonitor.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Fetches JPEG snapshots from ONVIF cameras via HTTP.
 * 
 * This is the KEY to background monitoring: instead of decoding a video stream
 * (which requires GPU surfaces that fail in background), we simply fetch
 * individual JPEG frames via HTTP — a purely CPU-based operation that works
 * perfectly in a foreground service with WakeLock.
 * 
 * Flow:
 * 1. Discover snapshot URI via ONVIF GetSnapshotUri SOAP call
 * 2. Fetch JPEG snapshots at regular intervals using HTTP GET
 * 3. Convert JPEGs to Bitmaps for motion detection
 */
class OnvifSnapshotClient(
    private val username: String,
    private val password: String
) {
    companion object {
        private const val TAG = "OnvifSnapshotClient"
        private val MEDIA_TYPE_SOAP = "application/soap+xml; charset=utf-8".toMediaType()
    }

    // HTTP client with digest auth support for snapshot fetching
    private val snapshotClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .authenticator(object : Authenticator {
            override fun authenticate(route: Route?, response: Response): Request? {
                if (response.request.header("Authorization") != null) {
                    return null // Already tried auth, give up
                }
                val credential = Credentials.basic(username, password)
                return response.request.newBuilder()
                    .header("Authorization", credential)
                    .build()
            }
        })
        .build()

    // SOAP client (no auth interceptor — uses WS-Security in body)
    private val soapClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private var snapshotUri: String? = null

    /**
     * Discovers the ONVIF snapshot URI for the camera.
     * 
     * @param onvifServiceUrl The camera's ONVIF service URL (e.g., http://192.168.1.100:8899/onvif/device_service)
     * @return The snapshot URI, or null if discovery fails
     */
    suspend fun discoverSnapshotUri(onvifServiceUrl: String): String? {
        try {
            Log.d(TAG, "Discovering snapshot URI from: $onvifServiceUrl")
            
            // Step 1: Get media service profiles
            val profilesBody = """
                <s:Body xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                    <GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>
                </s:Body>
            """.trimIndent()
            
            val profilesResponse = sendSoapRequest(onvifServiceUrl, profilesBody)
            val profileToken = extractProfileToken(profilesResponse)
            
            if (profileToken == null) {
                Log.e(TAG, "Failed to extract profile token from ONVIF response")
                return null
            }
            Log.d(TAG, "Found profile token: $profileToken")
            
            // Step 2: Get snapshot URI using the profile token
            val snapshotBody = """
                <s:Body xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                    <GetSnapshotUri xmlns="http://www.onvif.org/ver10/media/wsdl">
                        <ProfileToken>$profileToken</ProfileToken>
                    </GetSnapshotUri>
                </s:Body>
            """.trimIndent()
            
            val snapshotResponse = sendSoapRequest(onvifServiceUrl, snapshotBody)
            val uri = extractUri(snapshotResponse)
            
            if (uri != null) {
                snapshotUri = uri
                Log.i(TAG, "Discovered snapshot URI: $uri")
            } else {
                Log.e(TAG, "Failed to extract snapshot URI from ONVIF response. Raw: ${snapshotResponse.take(500)}")
            }
            
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to discover snapshot URI", e)
            return null
        }
    }

    /**
     * Sets the snapshot URI directly (bypassing ONVIF discovery).
     */
    fun setSnapshotUri(uri: String) {
        this.snapshotUri = uri
        Log.d(TAG, "Snapshot URI set manually: $uri")
    }

    /**
     * Fetches a single JPEG snapshot from the camera and returns it as a Bitmap.
     * 
     * This is the core operation for background monitoring:
     * - Simple HTTP GET
     * - No video decoding needed
     * - No GPU/Surface needed
     * - Works perfectly in a background service
     * 
     * @return A Bitmap of the current camera frame, or null if the request fails
     */
    fun fetchSnapshot(): Bitmap? {
        val uri = snapshotUri ?: return null
        
        try {
            // Build request with Basic auth pre-applied for speed
            val credential = Credentials.basic(username, password)
            val request = Request.Builder()
                .url(uri)
                .header("Authorization", credential)
                .get()
                .build()
            
            snapshotClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Snapshot request failed: ${response.code} for $uri")
                    return null
                }
                
                val bytes = response.body?.bytes() ?: return null
                if (bytes.isEmpty()) {
                    Log.w(TAG, "Snapshot response body is empty")
                    return null
                }
                
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    Log.w(TAG, "Failed to decode snapshot JPEG (${bytes.size} bytes)")
                } 
                return bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching snapshot: ${e.message}")
            return null
        }
    }

    /**
     * Returns whether the snapshot URI has been configured (either via discovery or manually).
     */
    fun isReady(): Boolean = snapshotUri != null

    /**
     * Releases resources.
     */
    fun release() {
        snapshotUri = null
    }

    // ==================== SOAP Helpers ====================

    private fun sendSoapRequest(url: String, soapBody: String): String {
        val nonceRaw = UUID.randomUUID().toString().replace("-", "").toByteArray()
        val created = getCurrentTimestamp()
        val nonceBase64 = Base64.encodeToString(nonceRaw, Base64.NO_WRAP)
        val passwordDigest = generatePasswordDigest(nonceRaw, created, password)

        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
                <s:Header>
                    <wsse:Security s:mustUnderstand="1">
                        <wsse:UsernameToken wsu:Id="UsernameToken-1">
                            <wsse:Username>$username</wsse:Username>
                            <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$passwordDigest</wsse:Password>
                            <wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceBase64</wsse:Nonce>
                            <wsu:Created>$created</wsu:Created>
                        </wsse:UsernameToken>
                    </wsse:Security>
                </s:Header>
                $soapBody
            </s:Envelope>
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .post(envelope.toRequestBody(MEDIA_TYPE_SOAP))
            .build()

        soapClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("SOAP request failed: ${response.code} ${response.message}")
            return response.body?.string() ?: ""
        }
    }

    private fun generatePasswordDigest(nonce: ByteArray, created: String, pass: String): String {
        val createdBytes = created.toByteArray(StandardCharsets.UTF_8)
        val passwordBytes = pass.toByteArray(StandardCharsets.UTF_8)
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(nonce)
        sha1.update(createdBytes)
        sha1.update(passwordBytes)
        return Base64.encodeToString(sha1.digest(), Base64.NO_WRAP)
    }

    private fun getCurrentTimestamp(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        return dateFormat.format(Date())
    }

    private fun extractProfileToken(xml: String): String? {
        val regex = """token="([^"]+)"""".toRegex()
        return regex.find(xml)?.groupValues?.get(1)
    }

    private fun extractUri(xml: String): String? {
        val regex = """<[^:]*:?Uri>([^<]+)</[^:]*:?Uri>""".toRegex()
        return regex.find(xml)?.groupValues?.get(1)
    }
}
