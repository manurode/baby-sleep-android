package com.babysleepmonitor

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.babysleepmonitor.data.OnvifCamera
import com.babysleepmonitor.network.ApiClient
import com.babysleepmonitor.network.OnvifDiscoveryManager
import com.babysleepmonitor.ui.CameraDiscoveryDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Server Setup screen with multi-step flow:
 * 1. Discovery (or Manual Manual entry link)
 * 2. Login (for discovered cameras)
 * 3. Manual Entry (fallback)
 */
class ServerSetupActivity : AppCompatActivity() {

    private lateinit var viewFlipper: ViewFlipper
    private lateinit var discoveryManager: OnvifDiscoveryManager
    
    // Discovery View
    private lateinit var btnStartDiscovery: View
    private lateinit var discoveryProgressBar: ProgressBar
    private lateinit var discoveryContent: View
    private lateinit var manualConnectButton: View
    
    // Login View
    private lateinit var tvCameraIp: TextView
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvCancel: TextView
    
    // Manual View
    private lateinit var manualIpInput: EditText
    private lateinit var manualPortInput: EditText
    private lateinit var manualStartButton: Button

    private lateinit var manualCancelButton: Button

    // Saved View
    private lateinit var tvSavedIp: TextView
    private lateinit var btnConnectSoSaved: View
    private lateinit var btnDiscoverNew: View
    private lateinit var btnConnectManuallySaved: View

    private var selectedCamera: OnvifCamera? = null

    companion object {
        const val VIEW_DISCOVERY = 0
        const val VIEW_LOGIN = 1
        const val VIEW_MANUAL = 2
        const val VIEW_SAVED = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_setup)

        discoveryManager = OnvifDiscoveryManager(this)
        
        initViews()
        setupListeners()
        loadSavedServerUrl()
    }

    private fun initViews() {
        viewFlipper = findViewById(R.id.viewFlipper)
        
        // Discovery Include
        btnStartDiscovery = findViewById(R.id.btnStartDiscovery)
        discoveryProgressBar = findViewById(R.id.discoveryProgressBar)
        discoveryContent = findViewById(R.id.discoveryContent)
        manualConnectButton = findViewById(R.id.manualConnectButton)
        
        // Login Include
        tvCameraIp = findViewById(R.id.tvCameraIp)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnConnect = findViewById(R.id.btnConnect)
        tvCancel = findViewById(R.id.tvCancel)
        
        // Manual Include
        manualIpInput = findViewById(R.id.manualIpInput)
        manualPortInput = findViewById(R.id.manualPortInput)
        manualStartButton = findViewById(R.id.manualStartButton)
        manualCancelButton = findViewById(R.id.manualCancelButton)
        
        // Saved Include
        tvSavedIp = findViewById(R.id.tvSavedIp)
        btnConnectSoSaved = findViewById(R.id.btnConnectSoSaved)
        btnDiscoverNew = findViewById(R.id.btnDiscoverNew)
        btnConnectManuallySaved = findViewById(R.id.btnConnectManuallySaved)
    }

    private fun setupListeners() {
        // Discovery Actions
        btnStartDiscovery.setOnClickListener {
            startDiscovery()
        }
        
        manualConnectButton.setOnClickListener {
            viewFlipper.displayedChild = VIEW_MANUAL
        }
        
        // Login Actions
        btnConnect.setOnClickListener {
            handleDiscoveredConnection()
        }
        
        tvCancel.setOnClickListener {
            // Cancel login, go back to discovery
            selectedCamera = null
            viewFlipper.displayedChild = VIEW_DISCOVERY
        }
        
        // Manual Actions
        manualStartButton.setOnClickListener {
            handleManualConnection()
        }
        
    manualCancelButton.setOnClickListener {
            viewFlipper.displayedChild = VIEW_DISCOVERY
        }
        
        // Saved Actions
        btnConnectSoSaved.setOnClickListener {
            val prefs = getSharedPreferences("BabySleepMonitor", MODE_PRIVATE)
            val url = prefs.getString("server_url", "") ?: ""
            val user = prefs.getString("rtsp_username", "") ?: ""
            val pass = prefs.getString("rtsp_password", "") ?: ""
            
            if (url.isNotEmpty()) {
                navigateToMonitor(url, user, pass)
            } else {
                Toast.makeText(this, "No saved credentials found", Toast.LENGTH_SHORT).show()
                viewFlipper.displayedChild = VIEW_DISCOVERY
            }
        }
        
        btnDiscoverNew.setOnClickListener {
            viewFlipper.displayedChild = VIEW_DISCOVERY
        }
        
        btnConnectManuallySaved.setOnClickListener {
            // Pre-fill manual inputs if possible (already handled in loadSavedServerUrl)
            viewFlipper.displayedChild = VIEW_MANUAL
        }
    }
    
    private fun startDiscovery() {
        setDiscoveryLoading(true)
        
        lifecycleScope.launch {
            try {
                // Short timeout for initial scan, we can use the dialog for full scan if needed
                // But user wants logic: Finding 1 -> Login. Finding >1 -> Dialog.
                
                val cameras = discoveryManager.discoverCamerasWithDetails(timeoutMs = 3000)
                setDiscoveryLoading(false)
                
                when {
                    cameras.isEmpty() -> {
                        Toast.makeText(this@ServerSetupActivity, "No cameras found. Try manual connection.", Toast.LENGTH_LONG).show()
                    }
                    cameras.size == 1 -> {
                        onCameraSelected(cameras.first())
                    }
                    else -> {
                        // Multiple cameras found, show dialog
                        showCameraDiscoveryDialog()
                    }
                }
            } catch (e: Exception) {
                setDiscoveryLoading(false)
                Toast.makeText(this@ServerSetupActivity, "Discovery error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun setDiscoveryLoading(loading: Boolean) {
        if (loading) {
            btnStartDiscovery.isEnabled = false
            discoveryContent.visibility = View.GONE
            discoveryProgressBar.visibility = View.VISIBLE
        } else {
            btnStartDiscovery.isEnabled = true
            discoveryContent.visibility = View.VISIBLE
            discoveryProgressBar.visibility = View.GONE
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
        
        // Populate Login View
        tvCameraIp.text = camera.hostname
        if (!camera.username.isNullOrBlank()) etUsername.setText(camera.username)
        if (!camera.password.isNullOrBlank()) etPassword.setText(camera.password)
        
        // Switch to Login View
        viewFlipper.displayedChild = VIEW_LOGIN
    }
    
    private fun handleDiscoveredConnection() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString().trim()
        
        val camera = selectedCamera ?: return
        
        // Update credentials
        camera.username = username
        camera.password = password
        
        // Construct RTSP URL if we have one, otherwise use xAddr or hostname
        // Ideally discoveryManager gave us a streamUri.
        // If streamUri is missing, we might need to fetch it now with credentials? 
        // Logic: "Validate inputs. Construct RTSP URL (with auth)."
        
        lifecycleScope.launch {
            btnConnect.isEnabled = false
            btnConnect.text = "Connecting..."
            
            try {
                var streamUri = camera.streamUri
                
                // If we don't have a stream URI yet, or we need to re-fetch with new credentials
                if (streamUri.isNullOrBlank() || (username.isNotEmpty() && password.isNotEmpty())) {
                     // Try to get fresh details with these credentials
                     val fullCamera = discoveryManager.getCameraDetails(
                         connectionUrl = if (camera.xAddr.isNotBlank()) camera.xAddr else camera.hostname,
                         username = username,
                         password = password
                     )
                     if (!fullCamera.streamUri.isNullOrBlank()) {
                         streamUri = fullCamera.streamUri
                     }
                }
                
                // Fallback construction if still null
                if (streamUri.isNullOrBlank()) {
                     streamUri = "rtsp://${camera.hostname}:8554/Streaming/Channels/101" // Generic fallback for Hikvision/similar
                }
                
                // Inject credentials into URL if needed? 
                // MonitorActivity handles credentials passed via Intent extras usually.
                // But saving to preferences is also good.
                
                saveServerUrl(streamUri!!, username, password)
                
                // Proceed to Monitor
                navigateToMonitor(streamUri, username, password)
                
            } catch (e: Exception) {
                Toast.makeText(this@ServerSetupActivity, "Connection failed: ${e.message}", Toast.LENGTH_SHORT).show()
                btnConnect.isEnabled = true
                btnConnect.text = "Connect"
            }
        }
    }

    private fun handleManualConnection() {
        val ip = manualIpInput.text.toString().trim()
        val port = manualPortInput.text.toString().trim().ifEmpty { "5000" }

        if (ip.isEmpty()) {
            Toast.makeText(this, "Please enter a server IP address", Toast.LENGTH_SHORT).show()
            return
        }

        // Determine if this is an RTSP URL or HTTP server
        val serverUrl = when {
            ip.startsWith("rtsp://") -> ip
            ip.startsWith("http://") || ip.startsWith("https://") -> ip
            else -> "http://$ip:$port"
        }
        
        manualStartButton.isEnabled = false
        manualStartButton.text = "Connecting..."
        
        lifecycleScope.launch {
             // For RTSP, skip HTTP test
            if (serverUrl.startsWith("rtsp://")) {
                saveServerUrl(serverUrl, "", "")
                navigateToMonitor(serverUrl, "", "")
                return@launch
            }
            
            try {
                if (testConnection(serverUrl)) {
                    saveServerUrl(serverUrl, "", "")
                    navigateToMonitor(serverUrl, "", "")
                } else {
                    showConnectionError(serverUrl)
                    manualStartButton.isEnabled = true
                    manualStartButton.text = "Start Monitoring"
                }
            } catch (e: Exception) {
                showConnectionError(serverUrl)
                manualStartButton.isEnabled = true
                manualStartButton.text = "Start Monitoring"
            }
        }
    }
    
    private fun navigateToMonitor(serverUrl: String, user: String, pass: String) {
        val intent = Intent(this, MonitorActivity::class.java)
        intent.putExtra("server_url", serverUrl)
        if (user.isNotEmpty()) intent.putExtra("username", user)
        if (pass.isNotEmpty()) intent.putExtra("password", pass)
        startActivity(intent)
        finish()
    }

    private fun loadSavedServerUrl() {
        val prefs = getSharedPreferences("BabySleepMonitor", MODE_PRIVATE)
        val savedUrl = prefs.getString("server_url", "") ?: ""

        if (savedUrl.isNotEmpty()) {
            if (savedUrl.startsWith("rtsp://")) {
                // Pre-fill manual
                manualIpInput.setText(savedUrl)
                manualPortInput.setText("")
            } else {
                val urlWithoutProtocol = savedUrl.removePrefix("http://").removePrefix("https://")
                val parts = urlWithoutProtocol.split(":")
                if (parts.isNotEmpty()) {
                    manualIpInput.setText(parts[0])
                    if (parts.size > 1) manualPortInput.setText(parts[1])
                }
            }
            
            // Check if we should show the "Welcome Back" saved screen
            val hasConnected = prefs.getBoolean("has_connected", false)
            if (hasConnected) {
                // Formatting IP for display (simple extraction)
                var displayIp = savedUrl
                    .removePrefix("rtsp://")
                    .removePrefix("http://")
                    .removePrefix("https://")
                if (displayIp.contains("@")) {
                     displayIp = displayIp.split("@").last()
                }
                displayIp = displayIp.split(":")[0].split("/")[0]
                
                tvSavedIp.text = displayIp
                viewFlipper.displayedChild = VIEW_SAVED
            }
        }
    }

    private suspend fun testConnection(serverUrl: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                ApiClient.getStatus(serverUrl)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun showConnectionError(serverUrl: String) {
        val intent = Intent(this, ConnectionErrorActivity::class.java)
        intent.putExtra("server_url", serverUrl)
        intent.putExtra("error_code", "ERR_CONNECTION_TIMED_OUT")
        startActivity(intent)
    }

    private fun saveServerUrl(url: String, user: String, pass: String) {
        val prefs = getSharedPreferences("BabySleepMonitor", MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString("server_url", url)
        editor.putBoolean("has_connected", true)
        if (user.isNotEmpty()) editor.putString("rtsp_username", user)
        if (pass.isNotEmpty()) editor.putString("rtsp_password", pass)
        editor.apply()
    }
}
