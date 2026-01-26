package com.babysleepmonitor

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.babysleepmonitor.data.OnvifCamera
import com.babysleepmonitor.network.ApiClient
import com.babysleepmonitor.ui.CameraDiscoveryDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Server Setup screen for entering the server IP address and port.
 * Also supports ONVIF camera discovery for direct RTSP streaming.
 * Validates connection before proceeding to the main monitor screen.
 */
class ServerSetupActivity : AppCompatActivity() {

    private lateinit var ipAddressInput: EditText
    private lateinit var portInput: EditText
    private lateinit var startMonitoringButton: Button
    private lateinit var cancelButton: Button
    private lateinit var discoverCamerasButton: Button
    
    // Holds selected ONVIF camera (if any)
    private var selectedCamera: OnvifCamera? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_setup)

        initViews()
        loadSavedServerUrl()
        setupListeners()
    }

    private fun initViews() {
        ipAddressInput = findViewById(R.id.ipAddressInput)
        portInput = findViewById(R.id.portInput)
        startMonitoringButton = findViewById(R.id.startMonitoringButton)
        cancelButton = findViewById(R.id.cancelButton)
        discoverCamerasButton = findViewById(R.id.discoverCamerasButton)
    }

    private fun loadSavedServerUrl() {
        val prefs = getSharedPreferences("BabySleepMonitor", MODE_PRIVATE)
        val savedUrl = prefs.getString("server_url", "") ?: ""

        // Parse saved URL to extract IP and port
        if (savedUrl.isNotEmpty()) {
            try {
                // Handle RTSP URLs differently
                if (savedUrl.startsWith("rtsp://")) {
                    ipAddressInput.setText(savedUrl)
                    portInput.setText("")
                } else {
                    val urlWithoutProtocol = savedUrl.removePrefix("http://").removePrefix("https://")
                    val parts = urlWithoutProtocol.split(":")
                    if (parts.isNotEmpty()) {
                        ipAddressInput.setText(parts[0])
                        if (parts.size > 1) {
                            portInput.setText(parts[1])
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore parsing errors
            }
        }
    }

    private fun setupListeners() {
        // ONVIF Camera Discovery button
        discoverCamerasButton.setOnClickListener {
            showCameraDiscoveryDialog()
        }
        
        startMonitoringButton.setOnClickListener {
            val ip = ipAddressInput.text.toString().trim()
            val port = portInput.text.toString().trim().ifEmpty { "5000" }

            if (ip.isEmpty()) {
                Toast.makeText(this, "Please enter a server IP address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Determine if this is an RTSP URL or HTTP server
            val serverUrl = when {
                ip.startsWith("rtsp://") -> ip
                ip.startsWith("http://") || ip.startsWith("https://") -> ip
                else -> "http://$ip:$port"
            }
            
            // For RTSP, skip connection test and go directly to monitor
            if (serverUrl.startsWith("rtsp://")) {
                saveServerUrl(serverUrl)
                navigateToMonitor(serverUrl)
                return@setOnClickListener
            }
            
            // Disable button and show loading state
            startMonitoringButton.isEnabled = false
            startMonitoringButton.text = "Connecting..."
            
            // Try to connect to the server
            lifecycleScope.launch {
                try {
                    val connected = testConnection(serverUrl)
                    
                    if (connected) {
                        // Save the server URL
                        saveServerUrl(serverUrl)
                        navigateToMonitor(serverUrl)
                    } else {
                        showConnectionError(serverUrl)
                    }
                } catch (e: Exception) {
                    showConnectionError(serverUrl)
                }
            }
        }

        cancelButton.setOnClickListener {
            finish()
        }
    }
    
    private fun showCameraDiscoveryDialog() {
        val dialog = CameraDiscoveryDialog.newInstance()
        dialog.setOnCameraSelectedListener { camera ->
            onCameraSelected(camera)
        }
        dialog.show(supportFragmentManager, CameraDiscoveryDialog.TAG)
    }
    
    private fun onCameraSelected(camera: OnvifCamera) {
        selectedCamera = camera
        
        // Populate the IP field with the RTSP stream URI if available
        if (!camera.streamUri.isNullOrBlank()) {
            ipAddressInput.setText(camera.streamUri)
            portInput.setText("")
            Toast.makeText(this, "Selected: ${camera.displayName}", Toast.LENGTH_SHORT).show()
        } else if (camera.hostname.isNotBlank()) {
            // Fallback to hostname if no stream URI
            ipAddressInput.setText(camera.hostname)
            Toast.makeText(this, "Camera found but no stream URI available. You may need to configure it manually.", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun navigateToMonitor(serverUrl: String) {
        val intent = Intent(this@ServerSetupActivity, MonitorActivity::class.java)
        intent.putExtra("server_url", serverUrl)
        
        // Pass credentials if available from selected camera
        selectedCamera?.let { camera ->
            // Use contains check as URL might differ slightly (e.g. port) or match exactly
            if (serverUrl == camera.streamUri || serverUrl.contains(camera.hostname)) {
                if (!camera.username.isNullOrBlank()) {
                    intent.putExtra("username", camera.username)
                }
                if (!camera.password.isNullOrBlank()) {
                    intent.putExtra("password", camera.password)
                }
            }
        }
        
        startActivity(intent)
        finish()
    }

    private suspend fun testConnection(serverUrl: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val status = ApiClient.getStatus(serverUrl)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun showConnectionError(serverUrl: String) {
        startMonitoringButton.isEnabled = true
        startMonitoringButton.text = "Start Monitoring"
        
        // Navigate to error screen
        val intent = Intent(this, ConnectionErrorActivity::class.java)
        intent.putExtra("server_url", serverUrl)
        intent.putExtra("error_code", "ERR_CONNECTION_TIMED_OUT")
        startActivity(intent)
    }

    private fun saveServerUrl(url: String) {
        val prefs = getSharedPreferences("BabySleepMonitor", MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString("server_url", url)
        editor.putBoolean("has_connected", true)
        
        // Save credentials if available from selected camera
        selectedCamera?.let {
            if (!it.username.isNullOrBlank()) editor.putString("rtsp_username", it.username)
            if (!it.password.isNullOrBlank()) editor.putString("rtsp_password", it.password)
        }
        
        editor.apply()
    }
}
