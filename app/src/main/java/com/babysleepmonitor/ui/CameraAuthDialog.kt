package com.babysleepmonitor.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.babysleepmonitor.R
import com.babysleepmonitor.data.OnvifCamera
import com.babysleepmonitor.network.OnvifDiscoveryManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog for entering ONVIF camera authentication credentials.
 * Allows user to specify port, username, and password.
 */
class CameraAuthDialog : DialogFragment() {
    
    companion object {
        const val TAG = "CameraAuthDialog"
        private const val ARG_HOSTNAME = "hostname"
        private const val ARG_CAMERA_NAME = "camera_name"
        private const val ARG_SERVICE_URL = "service_url"
        
        fun newInstance(hostname: String, cameraName: String, serviceUrl: String = ""): CameraAuthDialog {
            return CameraAuthDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_HOSTNAME, hostname)
                    putString(ARG_CAMERA_NAME, cameraName)
                    putString(ARG_SERVICE_URL, serviceUrl)
                }
            }
        }
    }
    
    private lateinit var discoveryManager: OnvifDiscoveryManager
    
    // Views
    private lateinit var tvCameraName: TextView
    private lateinit var tvCameraHost: TextView
    private lateinit var etPort: TextInputEditText
    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var progressContainer: LinearLayout
    private lateinit var tvProgressMessage: TextView
    private lateinit var tvError: TextView
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnConnect: MaterialButton
    
    private var onConnectedListener: ((OnvifCamera) -> Unit)? = null
    
    fun setOnConnectedListener(listener: (OnvifCamera) -> Unit) {
        onConnectedListener = listener
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_MaterialAlertDialog)
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_camera_auth, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        discoveryManager = OnvifDiscoveryManager(requireContext())
        
        // Initialize views
        tvCameraName = view.findViewById(R.id.tvCameraName)
        tvCameraHost = view.findViewById(R.id.tvCameraHost)
        etPort = view.findViewById(R.id.etPort)
        etUsername = view.findViewById(R.id.etUsername)
        etPassword = view.findViewById(R.id.etPassword)
        progressContainer = view.findViewById(R.id.progressContainer)
        tvProgressMessage = view.findViewById(R.id.tvProgressMessage)
        tvError = view.findViewById(R.id.tvError)
        btnCancel = view.findViewById(R.id.btnCancel)
        btnConnect = view.findViewById(R.id.btnConnect)
        
        // Populate camera info
        val rawHostname = arguments?.getString(ARG_HOSTNAME) ?: ""
        val cameraName = arguments?.getString(ARG_CAMERA_NAME) ?: rawHostname
        
        // Parse IP and port from hostname (may be "192.168.1.145:8000" or just "192.168.1.145")
        val (ip, detectedPort) = parseHostPort(rawHostname)
        
        tvCameraName.text = cameraName
        tvCameraHost.text = ip
        
        // Load saved credentials, but use detected port if available
        loadSavedCredentials(detectedPort)
        
        // Button listeners
        btnCancel.setOnClickListener { dismiss() }
        btnConnect.setOnClickListener { attemptConnection() }
    }
    
    /**
     * Parses hostname to extract IP and port separately.
     * Examples: "192.168.1.145:8000" -> ("192.168.1.145", "8000")
     *           "192.168.1.145" -> ("192.168.1.145", null)
     */
    private fun parseHostPort(hostname: String): Pair<String, String?> {
        return try {
            if (hostname.contains(":")) {
                val parts = hostname.split(":")
                if (parts.size == 2 && parts[1].all { it.isDigit() }) {
                    Pair(parts[0], parts[1])
                } else {
                    Pair(hostname, null)
                }
            } else {
                Pair(hostname, null)
            }
        } catch (e: Exception) {
            Pair(hostname, null)
        }
    }
    
    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    
    private fun loadSavedCredentials(detectedPort: String?) {
        val prefs = requireContext().getSharedPreferences("BabySleepMonitor", android.content.Context.MODE_PRIVATE)
        // Use detected port from WS-Discovery if available, otherwise use saved or default
        val savedPort = detectedPort ?: prefs.getString("onvif_port", "80") ?: "80"
        val savedUsername = prefs.getString("onvif_username", "admin") ?: "admin"
        
        etPort.setText(savedPort)
        etUsername.setText(savedUsername)
    }
    
    private fun saveCredentials(port: String, username: String) {
        val prefs = requireContext().getSharedPreferences("BabySleepMonitor", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString("onvif_port", port)
            .putString("onvif_username", username)
            .apply()
    }
    
    private fun attemptConnection() {
        val rawHostname = arguments?.getString(ARG_HOSTNAME) ?: return
        val savedServiceUrl = arguments?.getString(ARG_SERVICE_URL) ?: ""
        
        val (ip, _) = parseHostPort(rawHostname)  // Get just the IP, ignore any existing port
        val port = etPort.text.toString().trim().ifEmpty { "80" }
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()
        
        // Show progress
        showProgress("Connecting to camera...")
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Determine the URL to use.
                // If the user hasn't changed the port from what we detected, and we have a valid service URL, use the service URL.
                // Otherwise (custom port), we have to assume a standard path or just IP:Port.
                
                val userModifiedPort = port != parseHostPort(rawHostname).second && port != "80" // Simple check
                
                val connectionUrl = if (savedServiceUrl.isNotBlank() && !userModifiedPort) {
                   savedServiceUrl
                } else {
                   // Fallback logic: standard ONVIF path is usually /onvif/device_service
                   // If we just use IP:Port, the Manager will now attempt to fix it, but let's be explicit if we can.
                   "http://$ip:$port/onvif/device_service"
                }

                
                val camera = withContext(Dispatchers.IO) {
                    // Note: We use the smart getCameraDetails which handles retrying with/without path
                    discoveryManager.getCameraDetails(
                        connectionUrl = connectionUrl,
                        username = username.ifEmpty { null },
                        password = password.ifEmpty { null }
                    )
                }
                
                // Check if we got a stream URI
                if (!camera.streamUri.isNullOrBlank()) {
                    // Success! Save credentials and notify listener
                    saveCredentials(port, username)
                    onConnectedListener?.invoke(camera)
                    dismiss()
                } else {
                    // Connected but no stream found
                    showError("Connected to camera but no video stream was found. Try a different port or check camera settings.")
                }
                
            } catch (e: Exception) {
                val errorMsg = when {
                    e.message?.contains("Connection refused") == true -> 
                        "Connection refused. Try a different port (common: 80, 8080, 8899, 2020)"
                    e.message?.contains("401") == true || e.message?.contains("Unauthorized") == true ->
                        "Authentication failed. Check username and password."
                    e.message?.contains("timeout") == true ->
                        "Connection timed out. Check if the camera is online."
                    else -> "Connection failed: ${e.message}"
                }
                showError(errorMsg)
            }
        }
    }
    
    private fun showProgress(message: String) {
        tvError.visibility = View.GONE
        progressContainer.visibility = View.VISIBLE
        tvProgressMessage.text = message
        btnConnect.isEnabled = false
        etPort.isEnabled = false
        etUsername.isEnabled = false
        etPassword.isEnabled = false
    }
    
    private fun showError(message: String) {
        progressContainer.visibility = View.GONE
        tvError.visibility = View.VISIBLE
        tvError.text = message
        btnConnect.isEnabled = true
        etPort.isEnabled = true
        etUsername.isEnabled = true
        etPassword.isEnabled = true
    }
}
