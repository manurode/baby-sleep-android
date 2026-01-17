package com.babysleepmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Main Activity with WebView to display the baby monitor web interface
 * and controls for the background monitoring service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var serverUrlInput: EditText
    private lateinit var connectButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var connectionPanel: LinearLayout
    private lateinit var serviceStatusText: TextView
    private lateinit var toggleServiceButton: Button

    private val NOTIFICATION_PERMISSION_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        webView = findViewById(R.id.webView)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        connectButton = findViewById(R.id.connectButton)
        settingsButton = findViewById(R.id.settingsButton)
        connectionPanel = findViewById(R.id.connectionPanel)
        serviceStatusText = findViewById(R.id.serviceStatusText)
        toggleServiceButton = findViewById(R.id.toggleServiceButton)

        // Setup WebView
        setupWebView()

        // Load saved server URL
        val prefs = getSharedPreferences("BabySleepMonitor", MODE_PRIVATE)
        val savedUrl = prefs.getString("server_url", "http://192.168.1.100:5000")
        serverUrlInput.setText(savedUrl)

        // Connect button click
        connectButton.setOnClickListener {
            val url = serverUrlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                saveServerUrl(url)
                loadWebInterface(url)
            } else {
                Toast.makeText(this, "Please enter a server URL", Toast.LENGTH_SHORT).show()
            }
        }

        // Settings button click
        settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // Toggle service button click
        toggleServiceButton.setOnClickListener {
            if (MonitoringService.isRunning) {
                stopMonitoringService()
            } else {
                startMonitoringService()
            }
        }

        // Request notification permission on Android 13+
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun setupWebView() {
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
        webSettings.setSupportZoom(true)
        webSettings.mediaPlaybackRequiresUserGesture = false

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Hide connection panel after successful load
                connectionPanel.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                Toast.makeText(
                    this@MainActivity,
                    "Connection error: $description",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun loadWebInterface(baseUrl: String) {
        val fullUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        webView.loadUrl(fullUrl)
    }

    private fun saveServerUrl(url: String) {
        val prefs = getSharedPreferences("BabySleepMonitor", MODE_PRIVATE)
        prefs.edit().putString("server_url", url).apply()
    }

    private fun startMonitoringService() {
        val url = serverUrlInput.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Please enter a server URL first", Toast.LENGTH_SHORT).show()
            return
        }

        // Check notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    this,
                    "Please grant notification permission for alarm alerts",
                    Toast.LENGTH_LONG
                ).show()
                requestNotificationPermission()
                return
            }
        }

        saveServerUrl(url)

        val intent = Intent(this, MonitoringService::class.java).apply {
            putExtra("server_url", url)
        }
        ContextCompat.startForegroundService(this, intent)

        Toast.makeText(this, "Background monitoring started", Toast.LENGTH_SHORT).show()
        updateServiceStatus()
    }

    private fun stopMonitoringService() {
        val intent = Intent(this, MonitoringService::class.java)
        stopService(intent)

        Toast.makeText(this, "Background monitoring stopped", Toast.LENGTH_SHORT).show()
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        if (MonitoringService.isRunning) {
            serviceStatusText.text = "🟢 Background monitoring active"
            serviceStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            toggleServiceButton.text = "Stop Monitoring"
        } else {
            serviceStatusText.text = "⚪ Background monitoring inactive"
            serviceStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            toggleServiceButton.text = "Start Background Monitoring"
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Notification permission is required for alarm alerts!",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else if (webView.visibility == View.VISIBLE) {
            webView.visibility = View.GONE
            connectionPanel.visibility = View.VISIBLE
        } else {
            super.onBackPressed()
        }
    }
}
