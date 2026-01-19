package com.babysleepmonitor

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.babysleepmonitor.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Server Setup screen for entering the server IP address and port.
 * Validates connection before proceeding to the main monitor screen.
 */
class ServerSetupActivity : AppCompatActivity() {

    private lateinit var ipAddressInput: EditText
    private lateinit var portInput: EditText
    private lateinit var startMonitoringButton: Button
    private lateinit var cancelButton: Button

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
    }

    private fun loadSavedServerUrl() {
        val prefs = getSharedPreferences("BabySleepMonitor", MODE_PRIVATE)
        val savedUrl = prefs.getString("server_url", "") ?: ""

        // Parse saved URL to extract IP and port
        if (savedUrl.isNotEmpty()) {
            try {
                val urlWithoutProtocol = savedUrl.removePrefix("http://").removePrefix("https://")
                val parts = urlWithoutProtocol.split(":")
                if (parts.isNotEmpty()) {
                    ipAddressInput.setText(parts[0])
                    if (parts.size > 1) {
                        portInput.setText(parts[1])
                    }
                }
            } catch (e: Exception) {
                // Ignore parsing errors
            }
        }
    }

    private fun setupListeners() {
        startMonitoringButton.setOnClickListener {
            val ip = ipAddressInput.text.toString().trim()
            val port = portInput.text.toString().trim().ifEmpty { "5000" }

            if (ip.isEmpty()) {
                Toast.makeText(this, "Please enter a server IP address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val serverUrl = "http://$ip:$port"
            
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
                        
                        // Navigate to monitor screen
                        val intent = Intent(this@ServerSetupActivity, MonitorActivity::class.java)
                        intent.putExtra("server_url", serverUrl)
                        startActivity(intent)
                        finish()
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
        prefs.edit()
            .putString("server_url", url)
            .putBoolean("has_connected", true)
            .apply()
    }
}
