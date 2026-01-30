package com.babysleepmonitor

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import org.videolan.libvlc.util.VLCVideoLayout
import com.babysleepmonitor.data.SleepStatsResponse
import com.babysleepmonitor.data.StatusResponse
import com.babysleepmonitor.network.ApiClient
import com.babysleepmonitor.network.MjpegInputStream
import com.babysleepmonitor.network.RtspPlayerManager
import com.babysleepmonitor.ui.RoiSelectionView
import com.babysleepmonitor.ui.OverlayView
import com.babysleepmonitor.logic.MotionDetector
import com.babysleepmonitor.logic.SleepManager
import com.babysleepmonitor.logic.SleepState
import com.babysleepmonitor.ui.SleepViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Main monitoring screen with modern UI design.
 * Features: native video display, enhancement controls, ROI selection.
 */
class MonitorActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MonitorActivity"
        private const val NOTIFICATION_PERMISSION_CODE = 1001
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
        private const val STATUS_POLL_INTERVAL_MS = 1500L
        private const val SLEEP_STATS_POLL_INTERVAL_MS = 5000L
    }

    // Views
    private lateinit var videoView: ImageView
    private lateinit var rtspPlayerView: VLCVideoLayout
    private lateinit var roiSelectionView: RoiSelectionView
    private lateinit var overlayView: OverlayView
    private lateinit var connectionIndicator: View
    private lateinit var connectionStatusText: TextView
    private lateinit var motionStatusTitle: TextView
    private lateinit var motionStatusSubtitle: TextView
    private lateinit var alarmOverlay: LinearLayout
    private lateinit var alarmSecondsText: TextView
    private lateinit var settingsButton: FrameLayout
    private lateinit var enhancementCard: LinearLayout
    private lateinit var detectionAreaCard: LinearLayout
    private lateinit var sleepHistoryCard: LinearLayout
    
    // Enhancement bottom sheet
    private lateinit var enhancementBottomSheet: FrameLayout
    private lateinit var scrim: View
    private lateinit var closeEnhancementButton: ImageView
    private lateinit var zoomSeekBar: SeekBar
    private lateinit var zoomValueText: TextView
    private lateinit var contrastSeekBar: SeekBar
    private lateinit var contrastValueText: TextView
    private lateinit var brightnessSeekBar: SeekBar
    private lateinit var brightnessValueText: TextView
    private lateinit var resetEnhancementsButton: Button

    // ROI controls
    private lateinit var roiInstructions: LinearLayout
    private lateinit var roiActionBar: LinearLayout
    private lateinit var roiClearButton: Button
    private lateinit var roiSaveButton: Button
    
    // Stop monitoring button
    private var stopMonitoringButton: Button? = null
    
    // Sleep Stats card and bottom sheet
    private lateinit var sleepStatsCard: LinearLayout
    private lateinit var sleepStatsPreview: TextView
    private lateinit var sleepStateEmoji: TextView
    private lateinit var sleepStateLabel: TextView
    private lateinit var sleepStatsBottomSheet: FrameLayout
    private lateinit var sleepSheetEmoji: TextView
    private lateinit var sleepSheetState: TextView
    private lateinit var breathingIndicatorSheet: View
    private lateinit var closeSleepStatsButton: ImageView
    private lateinit var totalSleepTimeSheet: TextView
    private lateinit var wakeUpsSheet: TextView
    private lateinit var sleepQualitySheet: TextView
    private lateinit var sessionDurationSheet: TextView
    private lateinit var motionScoreSheet: TextView
    private lateinit var lastBreathSheet: TextView

    // State
    private var serverUrl: String = ""
    private var reconnectDelay = INITIAL_RECONNECT_DELAY_MS
    private var roiModeActive = false
    private var enhancementPanelVisible = false
    private var sleepStatsPanelVisible = false
    private var connectionLostDialogShown = false
    private var connectionRetryCount = 0
    private val MAX_RETRIES_BEFORE_DIALOG = 2
    private var isRtspMode = false
    
    // RTSP Player
    private var rtspPlayerManager: RtspPlayerManager? = null

    // Pending ROI coordinates
    private var pendingRoiX: Float? = null
    private var pendingRoiY: Float? = null
    private var pendingRoiW: Float? = null
    private var pendingRoiH: Float? = null

    // Coroutine jobs
    private var videoStreamJob: Job? = null
    private var statusPollJob: Job? = null
    private var sleepStatsPollJob: Job? = null
    private var processingJob: Job? = null
    
    // Local Logic
    private val motionDetector = MotionDetector()
    private lateinit var sleepViewModel: SleepViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)

        // Initialize SleepViewModel
        val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        sleepViewModel = ViewModelProvider(this, factory)[SleepViewModel::class.java]

        serverUrl = intent.getStringExtra("server_url") ?: getSavedServerUrl()
        
        if (serverUrl.isEmpty()) {
            navigateToSetup()
            return
        }

        initViews()
        setupListeners()
        requestNotificationPermission()
        autoStartMonitoringService()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent) // Important! Update the intent so getIntent() returns the new one
        
        val newUrl = intent?.getStringExtra("server_url")
        if (!newUrl.isNullOrEmpty()) {
            serverUrl = newUrl
            // Restart streaming with new params
            if (isRtspMode) {
                 startStreaming()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        
        // Reset dialog shown flag when coming back to the activity
        // so that the dialog can be shown again if needed
        connectionLostDialogShown = false
        
        Log.d(TAG, "onResume: isConnectionLost=${MonitoringService.isConnectionLost}, isRunning=${MonitoringService.isRunning}")
        
        // Check if connection was lost while app was in background
        if (MonitoringService.isConnectionLost) {
            Log.d(TAG, "Connection lost detected, showing dialog")
            showConnectionLostDialog()
            return
        }
        
        if (serverUrl.isNotEmpty()) {
            startStreaming()
        }
    }

    override fun onPause() {
        super.onPause()
        stopStreaming()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        releaseRtspPlayer()
        
        // Failsafe: If activity is finishing (user exit), ensure session is stopped/saved
        if (isFinishing) {
             if (::sleepViewModel.isInitialized && sleepViewModel.sleepManager.isSessionRunning) {
                 Log.d(TAG, "Activity finishing, saving session")
                 Toast.makeText(this, "Saving session (Finishing)", Toast.LENGTH_SHORT).show()
                 sleepViewModel.sleepManager.stopSession()
             }
        }
    }


    private fun initViews() {
        // Video container
        videoView = findViewById(R.id.videoView)
        rtspPlayerView = findViewById(R.id.rtspPlayerView)
        roiSelectionView = findViewById(R.id.roiSelectionView)
        overlayView = findViewById(R.id.overlayView)
        
        // Status
        connectionIndicator = findViewById(R.id.connectionIndicator)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        motionStatusTitle = findViewById(R.id.motionStatusTitle)
        motionStatusSubtitle = findViewById(R.id.motionStatusSubtitle)
        alarmOverlay = findViewById(R.id.alarmOverlay)
        alarmSecondsText = findViewById(R.id.alarmSecondsText)
        
        // Action cards
        settingsButton = findViewById(R.id.settingsButton)
        enhancementCard = findViewById(R.id.enhancementCard)
        detectionAreaCard = findViewById(R.id.detectionAreaCard)
        sleepHistoryCard = findViewById(R.id.sleepHistoryCard)
        
        // Enhancement bottom sheet
        enhancementBottomSheet = findViewById(R.id.enhancementBottomSheet)
        scrim = findViewById(R.id.scrim)
        closeEnhancementButton = findViewById(R.id.closeEnhancementButton)
        zoomSeekBar = findViewById(R.id.zoomSeekBar)
        zoomValueText = findViewById(R.id.zoomValueText)
        contrastSeekBar = findViewById(R.id.contrastSeekBar)
        contrastValueText = findViewById(R.id.contrastValueText)
        brightnessSeekBar = findViewById(R.id.brightnessSeekBar)
        brightnessValueText = findViewById(R.id.brightnessValueText)
        resetEnhancementsButton = findViewById(R.id.resetEnhancementsButton)
        
        // ROI controls
        roiInstructions = findViewById(R.id.roiInstructions)
        roiActionBar = findViewById(R.id.roiActionBar)
        roiClearButton = findViewById(R.id.roiClearButton)
        roiSaveButton = findViewById(R.id.roiSaveButton)
        
        // Stop monitoring button (optional - may not exist in all layouts)
        stopMonitoringButton = findViewById(R.id.stopMonitoringButton)
        Log.d(TAG, "initViews: stopMonitoringButton found: ${stopMonitoringButton != null}")
        
        // Sleep Stats views
        sleepStatsCard = findViewById(R.id.sleepStatsCard)
        sleepStatsPreview = findViewById(R.id.sleepStatsPreview)
        sleepStateEmoji = findViewById(R.id.sleepStateEmoji)
        sleepStateLabel = findViewById(R.id.sleepStateLabel)
        sleepStatsBottomSheet = findViewById(R.id.sleepStatsBottomSheet)
        sleepSheetEmoji = findViewById(R.id.sleepSheetEmoji)
        sleepSheetState = findViewById(R.id.sleepSheetState)
        breathingIndicatorSheet = findViewById(R.id.breathingIndicatorSheet)
        closeSleepStatsButton = findViewById(R.id.closeSleepStatsButton)
        totalSleepTimeSheet = findViewById(R.id.totalSleepTimeSheet)
        wakeUpsSheet = findViewById(R.id.wakeUpsSheet)
        sleepQualitySheet = findViewById(R.id.sleepQualitySheet)
        sessionDurationSheet = findViewById(R.id.sessionDurationSheet)
        motionScoreSheet = findViewById(R.id.motionScoreSheet)
        lastBreathSheet = findViewById(R.id.lastBreathSheet)
    }

    private fun setupListeners() {
        // Settings button
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Enhancement card
        enhancementCard.setOnClickListener {
            showEnhancementPanel()
        }

        // Detection area card
        detectionAreaCard.setOnClickListener {
            val intent = Intent(this, DetectionZoneActivity::class.java)
            intent.putExtra("server_url", serverUrl)
            startActivity(intent)
        }

        // Sleep history card
        sleepHistoryCard.setOnClickListener {
            startActivity(Intent(this, SleepHistoryActivity::class.java))
        }

        // Close enhancement button
        closeEnhancementButton.setOnClickListener {
            hideEnhancementPanel()
        }

        // Scrim
        scrim.setOnClickListener {
            if (sleepStatsPanelVisible) {
                hideSleepStatsPanel()
            }
            if (enhancementPanelVisible) {
                hideEnhancementPanel()
            }
        }

        // ROI action buttons
        roiSaveButton.setOnClickListener {
            saveRoi()
        }

        roiClearButton.setOnClickListener {
            clearRoi()
        }

        // ROI selection callback
        roiSelectionView.onRoiSelected = { x, y, w, h ->
            pendingRoiX = x
            pendingRoiY = y
            pendingRoiW = w
            pendingRoiH = h
            Log.d(TAG, "ROI selected: x=$x, y=$y, w=$w, h=$h")
        }

        // SeekBar listeners
        setupSeekBarListeners()
        
        // Reset button
        resetEnhancementsButton.setOnClickListener {
            resetEnhancements()
        }
        
        // Stop monitoring button
        stopMonitoringButton?.setOnClickListener {
            Log.d(TAG, "Stop Monitoring Button Clicked")
            stopMonitoringAndNavigate()
        }
        
        // Sleep Stats card
        sleepStatsCard.setOnClickListener {
            showSleepStatsPanel()
        }
        
        // Close sleep stats button
        closeSleepStatsButton.setOnClickListener {
            hideSleepStatsPanel()
        }
    }

    private fun setupSeekBarListeners() {
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
                contrastValueText.text = String.format("+%.0f%%", (contrast - 1) * 100)
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
                brightnessValueText.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val brightness = (seekBar?.progress ?: 50) - 50
                sendEnhancement(brightness = brightness)
            }
        })
    }

    // ==================== ENHANCEMENT PANEL ====================

    private fun showEnhancementPanel() {
        enhancementPanelVisible = true
        enhancementBottomSheet.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        loadCurrentSettings()
    }

    private fun hideEnhancementPanel() {
        enhancementPanelVisible = false
        enhancementBottomSheet.visibility = View.GONE
        scrim.visibility = View.GONE
    }

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
                    contrastValueText.text = String.format("+%.0f%%", (settings.contrast - 1) * 100)

                    // Update brightness (-50 to +50 -> 0-100)
                    val brightnessProgress = (settings.brightness + 50).coerceIn(0, 100)
                    brightnessSeekBar.progress = brightnessProgress
                    brightnessValueText.text = "$brightnessProgress%"
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
                    Toast.makeText(this@MonitorActivity, "Failed to update settings", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun resetEnhancements() {
        lifecycleScope.launch {
            try {
                ApiClient.resetEnhancements(serverUrl)
                loadCurrentSettings()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MonitorActivity, "Settings reset", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset enhancements: ${e.message}")
            }
        }
    }

    // ==================== SLEEP STATS PANEL ====================

    private fun showSleepStatsPanel() {
        sleepStatsPanelVisible = true
        sleepStatsBottomSheet.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        
        // Start polling sleep stats
        startSleepStatsPoll()
        
        // Load immediately
        loadSleepStats()
    }

    private fun hideSleepStatsPanel() {
        sleepStatsPanelVisible = false
        sleepStatsBottomSheet.visibility = View.GONE
        if (!enhancementPanelVisible) {
            scrim.visibility = View.GONE
        }
        
        // Stop polling when panel is hidden
        sleepStatsPollJob?.cancel()
        sleepStatsPollJob = null
    }

    private fun startSleepStatsPoll() {
        sleepStatsPollJob?.cancel()
        sleepStatsPollJob = lifecycleScope.launch {
            while (isActive) {
                loadSleepStats()
                delay(SLEEP_STATS_POLL_INTERVAL_MS)
            }
        }
    }

    private fun loadSleepStats() {
        lifecycleScope.launch {
            try {
                val stats = sleepViewModel.sleepManager.getStats()
                withContext(Dispatchers.Main) {
                    updateSleepStatsUI(stats)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load sleep stats: ${e.message}")
            }
        }
    }

    private fun updateSleepStatsUI(stats: com.babysleepmonitor.logic.SleepStats) {
        val emoji = when(stats.currentState) {
             SleepState.AWAKE -> "👀"
             SleepState.LIGHT_SLEEP -> "😴"
             SleepState.DEEP_SLEEP -> "🛌"
             SleepState.NO_BREATHING -> "⚠️"
             else -> "❓"
        }
        sleepStateEmoji.text = emoji
        sleepStateLabel.text = stats.currentState.value.replace("_", " ").replaceFirstChar { it.uppercase() }
        
        val totalMin = stats.totalSleepSeconds / 60
        val h = totalMin / 60
        val m = totalMin % 60
        val timeStr = "${h}h ${m}m"

        val previewText = when {
            stats.totalSleepSeconds > 0 -> "Sleep: $timeStr • ${stats.wakeUps} wake-ups"
            else -> "Tap to view sleep quality metrics"
        }
        sleepStatsPreview.text = previewText
        
        // Update bottom sheet
        sleepSheetEmoji.text = emoji
        sleepSheetState.text = stats.currentState.value.replace("_", " ").replaceFirstChar { it.uppercase() }
        
        // Breathing indicator
        val breathingDrawable = if (stats.breathingDetected) {
            R.drawable.breathing_indicator_on
        } else {
            R.drawable.breathing_indicator_off
        }
        breathingIndicatorSheet.setBackgroundResource(breathingDrawable)
        
        // Stats values
        totalSleepTimeSheet.text = timeStr
        wakeUpsSheet.text = stats.wakeUps.toString()
        sleepQualitySheet.text = "${stats.sleepQualityScore}%"
        
        // Session Duration (Wall clock time)
        val durationS = stats.sessionDurationSeconds
        val dH = durationS / 3600
        val dM = (durationS % 3600) / 60
        val dS = durationS % 60
        val durationStr = String.format("%02d:%02d:%02d", dH, dM, dS)
        sessionDurationSheet.text = durationStr
        
        // Debug info - show breathing rate and phase
        motionScoreSheet.text = String.format("%.1f BPM", stats.breathingRateBpm)
        lastBreathSheet.text = stats.sleepPhase
    }

    // ==================== ROI ====================

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
                            Toast.makeText(this@MonitorActivity, "Detection zone saved", Toast.LENGTH_SHORT).show()
                            exitRoiMode()
                        } else {
                            Toast.makeText(this@MonitorActivity, "Failed to save zone", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save ROI: ${e.message}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MonitorActivity, "Error saving zone", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this@MonitorActivity, "Detection zone cleared", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear ROI: ${e.message}")
            }
        }
    }

    private fun enterRoiMode() {
        roiModeActive = true
        roiSelectionView.visibility = View.VISIBLE
        roiInstructions.visibility = View.VISIBLE
        roiActionBar.visibility = View.VISIBLE
        loadExistingRoi()
    }

    private fun exitRoiMode() {
        roiModeActive = false
        roiSelectionView.visibility = View.GONE
        roiInstructions.visibility = View.GONE
        roiActionBar.visibility = View.GONE
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

    private fun clearPendingRoi() {
        pendingRoiX = null
        pendingRoiY = null
        pendingRoiW = null
        pendingRoiH = null
    }

    // ==================== STREAMING ====================

    private fun startStreaming() {
        stopStreaming()
        
        // Detect stream type based on URL
        isRtspMode = serverUrl.startsWith("rtsp://")
        
        if (isRtspMode) {
            // Use ExoPlayer for RTSP
            startRtspStreaming()
        } else {
            // Use MJPEG streaming for HTTP
            startMjpegStreaming()
        }
    }
    
    private fun startRtspStreaming() {
        Log.d(TAG, "Starting RTSP streaming: $serverUrl")
        
        // Show RTSP player, hide MJPEG view
        videoView.visibility = View.GONE
        rtspPlayerView.visibility = View.VISIBLE
        
        // Initialize RTSP player
        if (rtspPlayerManager == null) {
            rtspPlayerManager = RtspPlayerManager(this)
        }
        
        rtspPlayerManager?.apply {
            initialize(rtspPlayerView)
            setPlayerStateListener(object : RtspPlayerManager.PlayerStateListener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    lifecycleScope.launch {
                        if (isPlaying) {
                            updateConnectionStatus("Connected", ConnectionState.CONNECTED)
                            // Start session if not running
                            if (!sleepViewModel.sleepManager.isSessionRunning) {
                                sleepViewModel.sleepManager.startSession()
                                Log.d(TAG, "Sleep Session Started")
                            }
                        }
                    }
                }
                
                override fun onError(error: String) {
                    lifecycleScope.launch {
                        updateConnectionStatus("Error", ConnectionState.ERROR)
                        Log.e(TAG, "RTSP Error: $error")
                    }
                }
                
                override fun onBuffering(isBuffering: Boolean) {
                    lifecycleScope.launch {
                        if (isBuffering) {
                            updateConnectionStatus("Buffering...", ConnectionState.CONNECTING)
                        }
                    }
                }
            })
            
            // Get credentials from intent if available
            var username = intent.getStringExtra("username")
            var password = intent.getStringExtra("password")
            
            // Fallback to shared prefs if not in intent
            if (username == null || password == null) {
                 val prefs = getSharedPreferences("BabySleepMonitor", MODE_PRIVATE)
                 username = username ?: prefs.getString("rtsp_username", null)
                 password = password ?: prefs.getString("rtsp_password", null)
            }

            val userLog = if (username.isNullOrBlank()) "EMPTY" else username
            val passLog = if (password.isNullOrBlank()) "EMPTY" else "******"
            Log.d(TAG, "RtspPlayerManager.play -> URL: $serverUrl, User: $userLog, Pass: $passLog")
            
            play(serverUrl, username, password)
        }
        
        lifecycleScope.launch {
            updateConnectionStatus("Connecting", ConnectionState.CONNECTING)
            
            // Force start session immediately when we attempt to stream
            if (!sleepViewModel.sleepManager.isSessionRunning) {
                 sleepViewModel.sleepManager.startSession()
                 Log.d(TAG, "Sleep Session Started (Force - Pre-Stream)")
                 Toast.makeText(this@MonitorActivity, "Session Started (Force)", Toast.LENGTH_SHORT).show()
            }
        }
        
        
        // Start local video processing loop
        startLocalProcessing()
    }
    
    private fun startLocalProcessing() {
        processingJob?.cancel()
        processingJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                val loopStart = System.currentTimeMillis()
                
                try {
                    // 1. Capture Frame
                    val bitmap = rtspPlayerManager?.getCurrentFrame()
                    
                    if (bitmap != null) {
                        try {
                            // Failsafe: Ensure session is running if we are getting frames
                            if (!sleepViewModel.sleepManager.isSessionRunning) {
                                withContext(Dispatchers.Main) {
                                    sleepViewModel.sleepManager.startSession()
                                    Log.d(TAG, "Sleep Session Started (Failsafe via Frame)")
                                    Toast.makeText(this@MonitorActivity, "Session Started (Auto)", Toast.LENGTH_SHORT).show()
                                }
                            }

                            // 2. Process Motion (OpenCV)
                            val result = motionDetector.processFrame(bitmap)
                            
                            // 3. Update Sleep Logic
                            val sleepState = sleepViewModel.sleepManager.update(result.motionScore)
                            
                            // 4. Update UI
                            withContext(Dispatchers.Main) {
                                // Draw boxes
                                overlayView.updateBoxes(result.boxes, result.width, result.height)
                                
                                // Update Stats UI (every few loops or always)
                                val stats = sleepViewModel.sleepManager.getStats()
                                updateLocalStatsUI(stats, result.motionScore)
                            }
                            
                        } finally {
                            // Crucial: Recycle bitmap to prevent OOM
                            // Actually, depending on how getCurrentFrame works. 
                            // TextureView.getBitmap() creates a NEW bitmap. We MUST recycle it.
                            bitmap.recycle()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in local processing loop", e)
                }
                
                // Maintain ~5 FPS (200ms)
                val elapsed = System.currentTimeMillis() - loopStart
                val delayTime = (200 - elapsed).coerceAtLeast(10)
                delay(delayTime)
            }
        }
    }

    private fun updateLocalStatsUI(stats: com.babysleepmonitor.logic.SleepStats, currentScore: Double) {
        // Update Motion Title
        if (currentScore > 500) { // Threshold
             motionStatusTitle.text = "Motion Detected"
             motionStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.primary))
        } else if (stats.currentState == SleepState.CALIBRATING || stats.currentState == SleepState.UNKNOWN) {
             motionStatusTitle.text = "Calibrating..."
             motionStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
        } else {
             // Logic for 'No Motion' vs 'Movement'
             motionStatusTitle.text = if (currentScore > 100) "Slight Movement" else "Still"
             motionStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
        }
        
        // Update emoji
        val emoji = when(stats.currentState) {
             SleepState.AWAKE -> "👀"
             SleepState.LIGHT_SLEEP -> "😴"
             SleepState.DEEP_SLEEP -> "🛌"
             SleepState.NO_BREATHING -> "⚠️"
             else -> "❓"
        }
        
        sleepStateEmoji.text = emoji
        sleepStateLabel.text = stats.currentState.value
        motionScoreSheet.text = "Score: ${currentScore.toInt()}"
        
        // Alert Logic
        if (stats.currentState == SleepState.NO_BREATHING) {
             // Show alarm overlay
             alarmOverlay.visibility = View.VISIBLE
        } else {
             alarmOverlay.visibility = View.GONE
        }
    }
    
    private fun startMjpegStreaming() {
        // Show MJPEG view, hide RTSP player
        videoView.visibility = View.VISIBLE
        rtspPlayerView.visibility = View.GONE
        
        videoStreamJob = lifecycleScope.launch {
            streamVideo()
        }
        
        statusPollJob = lifecycleScope.launch {
            pollStatus()
        }
        
        // Start session for MJPEG mode too
        if (!sleepViewModel.sleepManager.isSessionRunning) {
             sleepViewModel.sleepManager.startSession()
             Log.d(TAG, "Sleep Session Started (MJPEG)")
        }
    }

    private fun stopStreaming() {
        videoStreamJob?.cancel()
        statusPollJob?.cancel()
        videoStreamJob = null
        statusPollJob = null
        
        // Stop RTSP player if active
        rtspPlayerManager?.stop()
        
        // Stop processing
        processingJob?.cancel()
        processingJob = null
    }
    
    private fun releaseRtspPlayer() {
        rtspPlayerManager?.release()
        rtspPlayerManager = null
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
                
                // Successfully connected - reset counters
                reconnectDelay = INITIAL_RECONNECT_DELAY_MS
                connectionLostDialogShown = false
                connectionRetryCount = 0
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
                connectionRetryCount++
                if (connectionRetryCount >= MAX_RETRIES_BEFORE_DIALOG && !connectionLostDialogShown) {
                    withContext(Dispatchers.Main) {
                        showConnectionLostDialog()
                    }
                } else {
                    handleReconnect()
                }
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
            // Update motion status
            if (status.motion_score > 10) {
                motionStatusTitle.text = "Motion Detected"
                motionStatusSubtitle.text = "Movement sensed just now"
            } else {
                motionStatusTitle.text = "No Motion"
                motionStatusSubtitle.text = "Last movement ${status.seconds_since_motion}s ago"
            }
            
            // Update alarm status
            if (status.alarm_active) {
                alarmOverlay.visibility = View.VISIBLE
                alarmSecondsText.text = "No movement for ${status.seconds_since_motion} seconds"
            } else {
                alarmOverlay.visibility = View.GONE
            }
        }
    }

    // ==================== CONNECTION LOST DIALOG ====================

    private fun showConnectionLostDialog() {
        if (connectionLostDialogShown) return
        connectionLostDialogShown = true
        
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_connection_lost)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(false)
        
        val understoodButton = dialog.findViewById<Button>(R.id.understoodButton)
        val tryReconnectingButton = dialog.findViewById<Button>(R.id.tryReconnectingButton)
        
        understoodButton.setOnClickListener {
            dialog.dismiss()
            // Stop monitoring service, alarms, and navigate to setup
            stopMonitoringAndNavigate()
        }
        
        tryReconnectingButton.setOnClickListener {
            dialog.dismiss()
            connectionLostDialogShown = false
            reconnectDelay = INITIAL_RECONNECT_DELAY_MS
            startStreaming()
        }
        
        dialog.show()
    }

    /**
     * Stops the monitoring service, stops any alarms, and navigates to server setup.
     * Called from Stop Monitoring button and Connection Lost dialog.
     */
    private fun stopMonitoringAndNavigate() {
        Log.d(TAG, "stopMonitoringAndNavigate called")
        Toast.makeText(this, "Stopping...", Toast.LENGTH_SHORT).show()
        
        // Stop the monitoring service
        if (MonitoringService.isRunning) {
            stopService(Intent(this, MonitoringService::class.java))
        }
        
        // Stop any alarm sounds
        MonitoringService.stopAlarmSound(this)
        
        // Stop session explicitly
        Log.d(TAG, "Stopping sleep session")
        sleepViewModel.sleepManager.stopSession()

        // Stop streaming
        stopStreaming()
        
        // Navigate to server setup
        navigateToSetup()
    }

    // ==================== BACKGROUND SERVICE ====================

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
        // MonitoringService relies on HTTP polling. Do not start it for RTSP or other protocols.
        if (!serverUrl.startsWith("http", ignoreCase = true)) {
            Log.i(TAG, "Skipping MonitoringService start for non-HTTP URL: $serverUrl")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission()
                return
            }
        }

        val intent = Intent(this, MonitoringService::class.java).apply {
            putExtra("server_url", serverUrl)
        }
        ContextCompat.startForegroundService(this, intent)
    }

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

    // ==================== NAVIGATION ====================

    private fun navigateToSetup() {
        val intent = Intent(this, ServerSetupActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun getSavedServerUrl(): String {
        val prefs = getSharedPreferences("BabySleepMonitor", MODE_PRIVATE)
        return prefs.getString("server_url", "") ?: ""
    }

    // ==================== BACK NAVIGATION ====================

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            sleepStatsPanelVisible -> {
                hideSleepStatsPanel()
            }
            enhancementPanelVisible -> {
                hideEnhancementPanel()
            }
            roiModeActive -> {
                exitRoiMode()
            }
            else -> {
                // Stop monitoring and go back
                stopMonitoringAndNavigate()
            }
        }
    }
}
