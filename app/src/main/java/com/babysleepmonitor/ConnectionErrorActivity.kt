package com.babysleepmonitor

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Connection Error screen shown when initial connection to server fails.
 * Displays error details and provides retry/settings options.
 */
class ConnectionErrorActivity : AppCompatActivity() {

    private var serverUrl: String = ""
    private var errorCode: String = "ERR_CONNECTION_TIMED_OUT"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connection_error)

        serverUrl = intent.getStringExtra("server_url") ?: ""
        errorCode = intent.getStringExtra("error_code") ?: "ERR_CONNECTION_TIMED_OUT"

        initViews()
        setupListeners()
    }

    private fun initViews() {
        // Display error details
        val errorCodeText = findViewById<TextView>(R.id.errorCodeText)
        val targetIpText = findViewById<TextView>(R.id.targetIpText)

        errorCodeText.text = errorCode
        
        // Extract IP from URL
        val ip = serverUrl.removePrefix("http://").removePrefix("https://").split(":").firstOrNull() ?: "Unknown"
        targetIpText.text = ip
    }

    private fun setupListeners() {
        val tryAgainButton = findViewById<Button>(R.id.tryAgainButton)
        val checkNetworkButton = findViewById<Button>(R.id.checkNetworkButton)

        tryAgainButton.setOnClickListener {
            // Go back to server setup
            val intent = Intent(this, ServerSetupActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish()
        }

        checkNetworkButton.setOnClickListener {
            // Open Wi-Fi settings
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            startActivity(intent)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        // Go back to server setup
        val intent = Intent(this, ServerSetupActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
        finish()
    }
}
