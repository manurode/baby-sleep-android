package com.babysleepmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.babysleepmonitor.data.StatusResponse
import com.babysleepmonitor.network.ApiClient
import com.babysleepmonitor.network.MjpegInputStream
import com.babysleepmonitor.ui.RoiSelectionView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Main Activity with modern glassmorphism UI for the baby monitor.
 * Features: native video display, ROI selection, auto-start background service.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val NOTIFICATION_PERMISSION_CODE = 1001
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
        private const val STATUS_POLL_INTERVAL_MS = 1500L
    }

    // Screens
    private lateinit var connectionScreen: ConstraintLayout
    private lateinit var videoScreen: ConstraintLayout

    // Connection panel views
    private lateinit var connectionPanel: CardView
    private lateinit var servicePanel: CardView
    private lateinit var serverUrlInput: EditText
    private lateinit var connectButton: Button
    private lateinit var settingsButton: FloatingActionButton
    private lateinit var serviceIndicator: View
    private lateinit var serviceStatusText: TextView
    private lateinit var toggleServiceButton: Button

    // Video container views
    private lateinit var videoContainer: FrameLayout
    private lateinit var videoView: ImageView
    private lateinit var roiSelectionView: RoiSelectionView
    private lateinit var connectionIndicator: View
    private lateinit var connectionStatusText: TextView
    private lateinit var connectionBadge: LinearLayout
    private lateinit var alarmBadge: LinearLayout
    private lateinit var motionScoreText: TextView
    private lateinit var alarmStatusText: TextView
    private lateinit var alarmOverlay: LinearLayout
    private lateinit var alarmSecondsText: TextView

    // FABs
    private lateinit var roiButton: FloatingActionButton
    private lateinit var toggleControlsButton: FloatingActionButton
    private lateinit var fabContainer: LinearLayout

    // ROI controls
    private lateinit var roiInstructions: LinearLayout
    private lateinit var roiActionBar: LinearLayout
    private lateinit var roiClearButton: Button
    private lateinit var roiSaveButton: Button
    private lateinit var roiCancelButton: Button

    // Controls panel views
    private lateinit var controlsPanel: CardView
    private lateinit var zoomSeekBar: SeekBar
    private lateinit var zoomValueText: TextView
    private lateinit var contrastSeekBar: SeekBar
    private lateinit var contrastValueText: TextView
    private lateinit var brightnessSeekBar: SeekBar
    private lateinit var brightnessValueText: TextView
    private lateinit var resetEnhancementsButton: Button

    // State
    private var serverUrl: String = ""
    private var isConnected = false
    private var controlsPanelVisible = false
    private var roiModeActive = false
    private var reconnectDelay = INITIAL_RECONNECT_DELAY_MS
    
    // Pending ROI coordinates (before save)
    private var pendingRoiX: Float? = null
    private var pendingRoiY: Float? = null
    private var pendingRoiW: Float? = null
    private var pendingRoiH: Float? = null

    // Coroutine jobs
    private var videoStreamJob: Job? = null
    private var statusPollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        loadSavedServerUrl()
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        
        // Resume streaming if we were connected
        if (isConnected && serverUrl.isNotEmpty()) {
            startStreaming()
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop streaming when app goes to background
        stopStreaming()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }

    private fun initViews() {
        // Screens
        connectionScreen = findViewById(R.id.connectionScreen)
        videoScreen = findViewById(R.id.videoScreen)

        // Connection panel
        connectionPanel = findViewById(R.id.connectionPanel)
        servicePanel = findViewById(R.id.servicePanel)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        connectButton = findViewById(R.id.connectButton)
        settingsButton = findViewById(R.id.settingsButton)
        serviceIndicator = findViewById(R.id.serviceIndicator)
        serviceStatusText = findViewById(R.id.serviceStatusText)
        toggleServiceButton = findViewById(R.id.toggleServiceButton)

        // Video container
        videoContainer = findViewById(R.id.videoContainer)
        videoView = findViewById(R.id.videoView)
        roiSelectionView = findViewById(R.id.roiSelectionView)
        connectionIndicator = findViewById(R.id.connectionIndicator)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        connectionBadge = findViewById(R.id.connectionBadge)
        alarmBadge = findViewById(R.id.alarmBadge)
        motionScoreText = findViewById(R.id.motionScoreText)
        alarmStatusText = findViewById(R.id.alarmStatusText)
        alarmOverlay = findViewById(R.id.alarmOverlay)
        alarmSecondsText = findViewById(R.id.alarmSecondsText)

        // FABs
        roiButton = findViewById(R.id.roiButton)
        toggleControlsButton = findViewById(R.id.toggleControlsButton)
        fabContainer = findViewById(R.id.fabContainer)

        // ROI controls
        roiInstructions = findViewById(R.id.roiInstructions)
        roiActionBar = findViewById(R.id.roiActionBar)
        roiClearButton = findViewById(R.id.roiClearButton)
        roiSaveButton = findViewById(R.id.roiSaveButton)
        roiCancelButton = findViewById(R.id.roiCancelButton)

        // Controls panel
        controlsPanel = findViewById(R.id.controlsPanel)
        zoomSeekBar = findViewById(R.id.zoomSeekBar)
        zoomValueText = findViewById(R.id.zoomValueText)
        contrastSeekBar = findViewById(R.id.contrastSeekBar)
        contrastValueText = findViewById(R.id.contrastValueText)
        brightnessSeekBar = findViewById(R.id.brightnessSeekBar)
        brightnessValueText = findViewById(R.id.brightnessValueText)
        resetEnhancementsButton = findViewById(R.id.resetEnhancementsButton)
    }

    private fun setupListeners() {
        // Connect button
        connectButton.setOnClickListener {
            val url = serverUrlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                serverUrl = url
                saveServerUrl(url)
                showVideoScreen()
                startStreaming()
                // Auto-start background service when connecting
                autoStartMonitoringService()
            } else {
                Toast.makeText(this, "Please enter a server URL", Toast.LENGTH_SHORT).show()
            }
        }

        // Settings button
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Toggle service button
        toggleServiceButton.setOnClickListener {
            if (MonitoringService.isRunning) {
                stopMonitoringService()
            } else {
                startMonitoringService()
            }
        }

        // Toggle controls button
        toggleControlsButton.setOnClickListener {
            toggleControlsPanel()
        }

        // ROI button
        roiButton.setOnClickListener {
            enterRoiMode()
        }

        // ROI action buttons
        roiSaveButton.setOnClickListener {
            saveRoi()
        }

        roiClearButton.setOnClickListener {
            clearRoi()
        }

        roiCancelButton.setOnClickListener {
            exitRoiMode()
        }

        // ROI selection callback
        roiSelectionView.onRoiSelected = { x, y, w, h ->
            pendingRoiX = x
            pendingRoiY = y
            pendingRoiW = w
            pendingRoiH = h
            Log.d(TAG, "ROI selected: x=$x, y=$y, w=$w, h=$h")
        }

        // Zoom SeekBar
        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val zoom = 1.0f + (progress / 10.0f) // 0-30 -> 1.0-4.0
                zoomValueText.text = String.format("%.1fx", zoom)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val zoom = 1.0f + ((seekBar?.progress ?: 0) / 10.0f)
                sendEnhancement(zoom = zoom)
            }
        })

        // Contrast SeekBar
        contrastSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val contrast = 1.0f + (progress / 10.0f) // 0-20 -> 1.0-3.0
                contrastValueText.text = String.format("%.1f", contrast)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val contrast = 1.0f + ((seekBar?.progress ?: 0) / 10.0f)
                sendEnhancement(contrast = contrast)
            }
        })

        // Brightness SeekBar
        brightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val brightness = progress - 50 // 0-100 -> -50 to +50
                brightnessValueText.text = if (brightness >= 0) "+$brightness" else "$brightness"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val brightness = (seekBar?.progress ?: 50) - 50
                sendEnhancement(brightness = brightness)
            }
        })

        // Reset button
        resetEnhancementsButton.setOnClickListener {
            resetEnhancements()
        }
    }

    private fun loadSavedServerUrl() {
        val prefs = getSharedPreferences("BabySleepMonitor", MODE_PRIVATE)
        val savedUrl = prefs.getString("server_url", "http://192.168.1.100:5000") ?: ""
        serverUrlInput.setText(savedUrl)
    }

    private fun saveServerUrl(url: String) {
        val prefs = getSharedPreferences("BabySleepMonitor", MODE_PRIVATE)
        prefs.edit().putString("server_url", url).apply()
    }

    private fun showVideoScreen() {
        connectionScreen.visibility = View.GONE
        videoScreen.visibility = View.VISIBLE
        isConnected = true
    }

    private fun showConnectionScreen() {
        videoScreen.visibility = View.GONE
        connectionScreen.visibility = View.VISIBLE
        controlsPanel.visibility = View.GONE
        isConnected = false
    }

    private fun toggleControlsPanel() {
        controlsPanelVisible = !controlsPanelVisible
        controlsPanel.visibility = if (controlsPanelVisible) View.VISIBLE else View.GONE
        
        if (controlsPanelVisible) {
            loadCurrentSettings()
        }
    }

    // ==================== ROI MODE ====================

    private fun enterRoiMode() {
        roiModeActive = true
        roiSelectionView.visibility = View.VISIBLE
        roiInstructions.visibility = View.VISIBLE
        roiActionBar.visibility = View.VISIBLE
        fabContainer.visibility = View.GONE
        controlsPanel.visibility = View.GONE
        controlsPanelVisible = false
        
        // Load existing ROI if any
        loadExistingRoi()
    }

    private fun exitRoiMode() {
        roiModeActive = false
        roiSelectionView.visibility = View.GONE
        roiInstructions.visibility = View.GONE
        roiActionBar.visibility = View.GONE
        fabContainer.visibility = View.VISIBLE
        roiSelectionView.clearRoi()
        clearPendingRoi()
    }

    private fun loadExistingRoi() {
        lifecycleScope.launch {
            try {
                val settings = ApiClient.getSettings(serverUrl)
                if (settings.has_roi && settings.roi != null && settings.roi.size == 4) {
                    withContext(Dispatchers.Main) {
                        roiSelectionView.setRoi(
                            settings.roi[0],
                            settings.roi[1],
                            settings.roi[2],
                            settings.roi[3]
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load existing ROI: ${e.message}")
            }
        }
    }

    private fun saveRoi() {
        val x = pendingRoiX
        val y = pendingRoiY
        val w = pendingRoiW
        val h = pendingRoiH

        if (x != null && y != null && w != null && h != null) {
            lifecycleScope.launch {
                try {
                    val success = ApiClient.setRoi(serverUrl, x, y, w, h)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            Toast.makeText(this@MainActivity, "Detection zone saved", Toast.LENGTH_SHORT).show()
                            exitRoiMode()
                        } else {
                            Toast.makeText(this@MainActivity, "Failed to save zone", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save ROI: ${e.message}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Error saving zone", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            Toast.makeText(this, "Please draw a detection zone first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearRoi() {
        lifecycleScope.launch {
            try {
                val success = ApiClient.resetRoi(serverUrl)
                withContext(Dispatchers.Main) {
                    if (success) {
                        roiSelectionView.clearRoi()
                        clearPendingRoi()
                        Toast.makeText(this@MainActivity, "Detection zone cleared", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear ROI: ${e.message}")
            }
        }
    }

    private fun clearPendingRoi() {
        pendingRoiX = null
        pendingRoiY = null
        pendingRoiW = null
        pendingRoiH = null
    }

    // ==================== STREAMING ====================

    private fun startStreaming() {
        stopStreaming() // Stop any existing streams
        
        videoStreamJob = lifecycleScope.launch {
            streamVideo()
        }
        
        statusPollJob = lifecycleScope.launch {
            pollStatus()
        }
    }

    private fun stopStreaming() {
        videoStreamJob?.cancel()
        statusPollJob?.cancel()
        videoStreamJob = null
        statusPollJob = null
    }

    private suspend fun streamVideo() {
        while (coroutineContext.isActive) {
            try {
                updateConnectionStatus("Connecting", ConnectionState.CONNECTING)
                
                val response = ApiClient.getVideoStream(serverUrl)
                
                if (!response.isSuccessful) {
                    Log.e(TAG, "Video stream request failed: ${response.code}")
                    updateConnectionStatus("Error ${response.code}", ConnectionState.ERROR)
                    response.close()
                    handleReconnect()
                    continue
                }
                
                val inputStream = response.body?.byteStream()
                if (inputStream == null) {
                    Log.e(TAG, "Response body is null")
                    response.close()
                    handleReconnect()
                    continue
                }
                
                // Successfully connected - reset reconnect delay
                reconnectDelay = INITIAL_RECONNECT_DELAY_MS
                updateConnectionStatus("Connected", ConnectionState.CONNECTED)
                
                val mjpegStream = MjpegInputStream(inputStream)
                
                try {
                    while (coroutineContext.isActive) {
                        val frame = withContext(Dispatchers.IO) {
                            mjpegStream.readFrame()
                        }
                        
                        if (frame != null) {
                            displayFrame(frame)
                        } else {
                            Log.d(TAG, "Null frame received, stream may have ended")
                            break
                        }
                    }
                } finally {
                    mjpegStream.close()
                    response.close()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Stream error: ${e.message}")
                updateConnectionStatus("Disconnected", ConnectionState.ERROR)
            }
            
            if (coroutineContext.isActive) {
                handleReconnect()
            }
        }
    }

    private enum class ConnectionState { CONNECTING, CONNECTED, ERROR }

    private suspend fun updateConnectionStatus(status: String, state: ConnectionState) {
        withContext(Dispatchers.Main) {
            connectionStatusText.text = status
            
            val indicatorDrawable = when (state) {
                ConnectionState.CONNECTING -> R.drawable.status_dot_connecting
                ConnectionState.CONNECTED -> R.drawable.status_dot_connected
                ConnectionState.ERROR -> R.drawable.status_dot_disconnected
            }
            connectionIndicator.setBackgroundResource(indicatorDrawable)
        }
    }

    private suspend fun handleReconnect() {
        updateConnectionStatus("Reconnecting...", ConnectionState.CONNECTING)
        delay(reconnectDelay)
        reconnectDelay = min(reconnectDelay * 2, MAX_RECONNECT_DELAY_MS)
    }

    private suspend fun displayFrame(bitmap: Bitmap) {
        withContext(Dispatchers.Main) {
            videoView.setImageBitmap(bitmap)
        }
    }

    private suspend fun pollStatus() {
        while (coroutineContext.isActive) {
            try {
                val status = ApiClient.getStatus(serverUrl)
                updateStatusUI(status)
            } catch (e: Exception) {
                Log.w(TAG, "Status poll failed: ${e.message}")
            }
            delay(STATUS_POLL_INTERVAL_MS)
        }
    }

    private suspend fun updateStatusUI(status: StatusResponse) {
        withContext(Dispatchers.Main) {
            // Update motion score
            motionScoreText.text = status.motion_score.toInt().toString()
            
            // Update alarm status
            if (status.alarm_active) {
                alarmStatusText.text = "⚠️ ALARM"
                alarmBadge.setBackgroundResource(R.drawable.badge_alarm)
                alarmOverlay.visibility = View.VISIBLE
                alarmSecondsText.text = "No movement for ${status.seconds_since_motion} seconds"
            } else {
                alarmStatusText.text = "✓ OK"
                alarmBadge.setBackgroundResource(R.drawable.badge_success)
                alarmOverlay.visibility = View.GONE
            }
        }
    }

    // ==================== ENHANCEMENTS ====================

    private fun loadCurrentSettings() {
        lifecycleScope.launch {
            try {
                val settings = ApiClient.getSettings(serverUrl)
                withContext(Dispatchers.Main) {
                    // Update zoom (1.0-4.0 -> 0-30)
                    val zoomProgress = ((settings.zoom - 1.0f) * 10).toInt().coerceIn(0, 30)
                    zoomSeekBar.progress = zoomProgress
                    zoomValueText.text = String.format("%.1fx", settings.zoom)
                    
                    // Update contrast (1.0-3.0 -> 0-20)
                    val contrastProgress = ((settings.contrast - 1.0f) * 10).toInt().coerceIn(0, 20)
                    contrastSeekBar.progress = contrastProgress
                    contrastValueText.text = String.format("%.1f", settings.contrast)
                    
                    // Update brightness (-50 to +50 -> 0-100)
                    val brightnessProgress = (settings.brightness + 50).coerceIn(0, 100)
                    brightnessSeekBar.progress = brightnessProgress
                    val b = settings.brightness
                    brightnessValueText.text = if (b >= 0) "+$b" else "$b"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load settings: ${e.message}")
            }
        }
    }

    private fun sendEnhancement(zoom: Float? = null, contrast: Float? = null, brightness: Int? = null) {
        lifecycleScope.launch {
            try {
                ApiClient.setEnhancements(serverUrl, zoom, contrast, brightness)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set enhancement: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to update settings", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun resetEnhancements() {
        lifecycleScope.launch {
            try {
                ApiClient.resetEnhancements(serverUrl)
                loadCurrentSettings() // Reload UI
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Settings reset", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset enhancements: ${e.message}")
            }
        }
    }

    // ==================== BACKGROUND SERVICE ====================

    /**
     * Auto-start the monitoring service when connecting to server.
     * This ensures background monitoring is always active.
     */
    private fun autoStartMonitoringService() {
        if (!MonitoringService.isRunning && hasNotificationPermission()) {
            startMonitoringService()
            Log.i(TAG, "Auto-started background monitoring service")
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun startMonitoringService() {
        if (serverUrl.isEmpty()) {
            val inputUrl = serverUrlInput.text.toString().trim()
            if (inputUrl.isEmpty()) {
                Toast.makeText(this, "Please enter a server URL first", Toast.LENGTH_SHORT).show()
                return
            }
            serverUrl = inputUrl
            saveServerUrl(inputUrl)
        }

        // Check notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Please grant notification permission for alarm alerts", Toast.LENGTH_LONG).show()
                requestNotificationPermission()
                return
            }
        }

        val intent = Intent(this, MonitoringService::class.java).apply {
            putExtra("server_url", serverUrl)
        }
        ContextCompat.startForegroundService(this, intent)

        Toast.makeText(this, "Background monitoring started", Toast.LENGTH_SHORT).show()
        updateServiceStatus()
    }

    private fun stopMonitoringService() {
        stopService(Intent(this, MonitoringService::class.java))
        Toast.makeText(this, "Background monitoring stopped", Toast.LENGTH_SHORT).show()
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        if (MonitoringService.isRunning) {
            serviceStatusText.text = "Background Monitoring"
            serviceIndicator.setBackgroundResource(R.drawable.status_indicator_active)
            toggleServiceButton.text = "Stop"
        } else {
            serviceStatusText.text = "Background Monitoring"
            serviceIndicator.setBackgroundResource(R.drawable.status_indicator_inactive)
            toggleServiceButton.text = "Start"
        }
    }

    // ==================== PERMISSIONS ====================

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
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
                Toast.makeText(this, "Notification permission is required for alarm alerts!", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==================== BACK NAVIGATION ====================

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            roiModeActive -> {
                exitRoiMode()
            }
            controlsPanelVisible -> {
                toggleControlsPanel()
            }
            isConnected -> {
                stopStreaming()
                showConnectionScreen()
            }
            else -> {
                super.onBackPressed()
            }
        }
    }
}
