package com.babysleepmonitor.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.babysleepmonitor.data.OnvifCamera
import com.seanproctor.onvifcamera.OnvifDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Manager class that handles ONVIF camera discovery using WS-Discovery
 * and retrieves camera information using the seanproctor/ONVIF-Camera-Kotlin library.
 */
class OnvifDiscoveryManager(private val context: Context) {
    
    companion object {
        private const val TAG = "OnvifDiscoveryManager"
        private const val DEFAULT_DISCOVERY_TIMEOUT = 5000
        private const val WS_DISCOVERY_MULTICAST_IP = "239.255.255.250"
        private const val WS_DISCOVERY_PORT = 3702
        
        // WS-Discovery Probe message for ONVIF devices
        private val PROBE_MESSAGE = """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" 
                           xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing" 
                           xmlns:tns="http://schemas.xmlsoap.org/ws/2005/04/discovery">
                <soap:Header>
                    <wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</wsa:Action>
                    <wsa:MessageID>urn:uuid:${java.util.UUID.randomUUID()}</wsa:MessageID>
                    <wsa:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</wsa:To>
                </soap:Header>
                <soap:Body>
                    <tns:Probe>
                        <tns:Types>dn:NetworkVideoTransmitter</tns:Types>
                    </tns:Probe>
                </soap:Body>
            </soap:Envelope>
        """.trimIndent()
    }
    
    private var multicastLock: WifiManager.MulticastLock? = null
    
    /**
     * Acquires the multicast lock required for WS-Discovery on Android.
     */
    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifi != null && multicastLock == null) {
                multicastLock = wifi.createMulticastLock("ONVIF_Discovery")
                multicastLock?.setReferenceCounted(true)
                multicastLock?.acquire()
                Log.d(TAG, "Multicast lock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire multicast lock", e)
        }
    }
    
    /**
     * Releases the multicast lock.
     */
    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "Multicast lock released")
                }
            }
            multicastLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release multicast lock", e)
        }
    }
    
    /**
     * Discovers ONVIF cameras on the local network using WS-Discovery.
     * @param timeoutMs Discovery timeout in milliseconds
     * @return List of discovered cameras (basic info only - hostnames and service addresses)
     */
    suspend fun discoverCameras(timeoutMs: Int = DEFAULT_DISCOVERY_TIMEOUT): List<OnvifCamera> = withContext(Dispatchers.IO) {
        val discoveredDevices = mutableMapOf<String, String>() // IP -> XAddr
        
        try {
            acquireMulticastLock()
            
            Log.d(TAG, "Starting WS-Discovery with timeout: ${timeoutMs}ms")
            
            val socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = timeoutMs
            
            val multicastAddress = InetAddress.getByName(WS_DISCOVERY_MULTICAST_IP)
            val probeBytes = PROBE_MESSAGE.toByteArray()
            val sendPacket = DatagramPacket(
                probeBytes,
                probeBytes.size,
                multicastAddress,
                WS_DISCOVERY_PORT
            )
            
            // Send the probe message
            socket.send(sendPacket)
            Log.d(TAG, "Sent WS-Discovery probe")
            
            // Receive responses
            val receiveBuffer = ByteArray(65535)
            val endTime = System.currentTimeMillis() + timeoutMs
            
            while (System.currentTimeMillis() < endTime) {
                try {
                    val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    socket.receive(receivePacket)
                    
                    val senderAddress = receivePacket.address.hostAddress
                    val responseData = String(receivePacket.data, 0, receivePacket.length)
                    
                    if (senderAddress != null && !discoveredDevices.containsKey(senderAddress)) {
                        // Parse XAddrs from the response to get service URL with port
                        val xAddr = parseXAddrs(responseData) ?: "http://$senderAddress:80/onvif/device_service"
                        discoveredDevices[senderAddress] = xAddr
                        Log.d(TAG, "Discovered device at: $senderAddress, XAddr: $xAddr")
                    }
                } catch (e: SocketTimeoutException) {
                    // Timeout reached, stop listening
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Error receiving response: ${e.message}")
                }
            }
            
            socket.close()
            Log.d(TAG, "Discovery completed: found ${discoveredDevices.size} devices")
            
        } catch (e: Exception) {
            Log.e(TAG, "Discovery failed", e)
        } finally {
            releaseMulticastLock()
        }
        
        discoveredDevices.map { (ip, xAddr) ->
            // Extract host:port from xAddr
            val hostWithPort = extractHostPort(xAddr) ?: ip
            OnvifCamera(
                hostname = hostWithPort,
                xAddr = xAddr
            )
        }
    }
    
    /**
     * Parses XAddrs from WS-Discovery ProbeMatch response.
     * XAddrs typically looks like: http://192.168.1.145:8899/onvif/device_service
     */
    private fun parseXAddrs(response: String): String? {
        return try {
            // Look for XAddrs element in the XML
            val xAddrsPattern = Regex("<[^:]*:?XAddrs>([^<]+)</[^:]*:?XAddrs>", RegexOption.IGNORE_CASE)
            val match = xAddrsPattern.find(response)
            
            match?.groupValues?.get(1)?.trim()?.split("\\s+".toRegex())?.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse XAddrs: ${e.message}")
            null
        }
    }
    
    /**
     * Extracts host:port from a URL like http://192.168.1.145:8899/onvif/device_service
     */
    private fun extractHostPort(url: String): String? {
        return try {
            val withoutProtocol = url.removePrefix("http://").removePrefix("https://")
            val pathStart = withoutProtocol.indexOf('/')
            if (pathStart > 0) {
                withoutProtocol.substring(0, pathStart)
            } else {
                withoutProtocol
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Gets detailed device information and stream URI for a camera.
     * @param connectionUrl The camera's connection URL (e.g., "http://192.168.1.100:80/onvif/device_service" or just "192.168.1.100")
     * @param username Optional username for authentication
     * @param password Optional password for authentication
     * @return OnvifCamera with device information and stream URI populated
     */
    suspend fun getCameraDetails(
        connectionUrl: String,
        username: String? = null,
        password: String? = null
    ): OnvifCamera = withContext(Dispatchers.IO) {
        // Ensure we have a valid URL
        var currentUrl = if (connectionUrl.startsWith("http")) connectionUrl else "http://$connectionUrl"
        val originalHost = try { java.net.URI(currentUrl).host ?: connectionUrl } catch(e: Exception) { connectionUrl }
        
        try {
            Log.d(TAG, "Getting camera details for: $originalHost (URL: $currentUrl)")
            
            // Attempt connection
            var device: OnvifDevice? = null
            var lastError: Exception? = null

            // Strategy: Try the provided URL first. If it fails and looks like a base URL, try appending standard ONVIF path.
            val urlsToTry = mutableListOf(currentUrl)
            if (!currentUrl.endsWith("/onvif/device_service") && !currentUrl.contains("/onvif/")) {
                urlsToTry.add(if (currentUrl.endsWith("/")) "${currentUrl}onvif/device_service" else "$currentUrl/onvif/device_service")
            }

            for (url in urlsToTry) {
                try {
                    Log.d(TAG, "Connecting to ONVIF device at: $url")
                    device = OnvifDevice.requestDevice(url, username, password)
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to connect to $url: ${e.message}")
                    lastError = e
                }
            }
            
            if (device == null) {
                throw lastError ?: Exception("Failed to connect to any attempted URL")
            }

            Log.d(TAG, "Successfully initialized device wrapper, fetching details...")
            
            // 1. Try to get device information
            val deviceInfo = try {
                device.getDeviceInformation()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get device info: ${e.message}")
                if (e.message?.contains("400") == true || e.message?.contains("Unauthorized") == true) {
                    Log.e(TAG, "Authentication failed. Attempting manual fallback...")
                    // If we failed with 400/401, the library might be incompatible.
                    // We can try a manual raw SOAP request later for the stream URI.
                }
                null
            }
            
            // 2. Try to get stream URI independently
            // If the library failed authentication earlier (deviceInfo is null), we might still want to try manual fallback here.
            val streamUri = try {
                val profiles = device.getProfiles()
                if (profiles.isEmpty()) {
                    Log.w(TAG, "No profiles found on camera")
                    null
                } else {
                    val profile = profiles.firstOrNull { it.canStream() } ?: profiles[0]
                    Log.d(TAG, "Using profile '${profile.name}' for stream")
                    
                    val uri = device.getStreamURI(profile)
                    
                    if (uri.isNotEmpty()) {
                        val uriObj = java.net.URI(uri)
                        val cleanHost = try { java.net.URI(currentUrl).host } catch (e: Exception) { originalHost.split(":")[0] }
                        
                        if (uriObj.host != cleanHost && cleanHost != null) {
                            Log.d(TAG, "Correcting stream URI host from ${uriObj.host} to $cleanHost")
                            uri.replace(uriObj.host, cleanHost)
                        } else {
                            uri
                        }
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Library failed to get stream URI: ${e.message}. Attempting manual SOAP fallback.")
                
                // MANUAL FALLBACK: Try raw SOAP request if library fails
                if (username != null && password != null) {
                    try {
                         getStreamUriManual(currentUrl, username, password)
                    } catch (manualEx: Exception) {
                         Log.e(TAG, "Manual fallback also failed: ${manualEx.message}")
                         null
                    }
                } else {
                    null
                }
            }
            
            OnvifCamera(
                hostname = originalHost, 
                manufacturer = deviceInfo?.manufacturer,
                model = deviceInfo?.model,
                firmwareVersion = deviceInfo?.firmwareVersion,
                serialNumber = deviceInfo?.serialNumber,
                streamUri = streamUri,
                xAddr = currentUrl,
                username = username,
                password = password
            )

        } catch (e: Exception) {
            Log.e(TAG, "Critical failure getting camera details: ${e.message}", e)
            OnvifCamera(hostname = originalHost, xAddr = connectionUrl, username = username, password = password)
        }
    }
    
    /**
     * Full discovery flow: discovers cameras and retrieves their device info and stream URIs.
     * @param timeoutMs Discovery timeout in milliseconds
     * @param username Optional username for camera authentication
     * @param password Optional password for camera authentication
     * @param onCameraFound Callback for each camera as it's fully discovered
     */
    suspend fun discoverCamerasWithDetails(
        timeoutMs: Int = DEFAULT_DISCOVERY_TIMEOUT,
        username: String? = null,
        password: String? = null,
        onCameraFound: (OnvifCamera) -> Unit = {}
    ): List<OnvifCamera> = withContext(Dispatchers.IO) {
        val basicCameras = discoverCameras(timeoutMs)
        
        basicCameras.map { basicCamera ->
            try {
                // Use the full XAddr if available for best connection success
                val connectionUrl = if (basicCamera.xAddr.isNotBlank()) basicCamera.xAddr else basicCamera.hostname
                
                val fullCamera = getCameraDetails(connectionUrl, username, password)
                onCameraFound(fullCamera)
                fullCamera
            } catch (e: Exception) {
                Log.e(TAG, "Error getting details for ${basicCamera.hostname}", e)
                onCameraFound(basicCamera)
                basicCamera
            }
        }
    }
    /**
     * Helper to call the manual client on IO thread
     */
    private suspend fun getStreamUriManual(url: String, username: String, password: String): String? {
        return withContext(Dispatchers.IO) {
            ManualOnvifClient.getStreamUri(url, username, password)
        }
    }
}
