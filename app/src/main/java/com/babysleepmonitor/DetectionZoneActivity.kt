package com.babysleepmonitor

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.babysleepmonitor.network.ApiClient
import com.babysleepmonitor.network.MjpegInputStream
import com.babysleepmonitor.ui.RoiSelectionView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Detection Zone screen for selecting the region of interest (ROI) for motion detection.
 * Features a draggable rectangle overlay on the video feed.
 */
class DetectionZoneActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DetectionZoneActivity"
    }

    private lateinit var videoView: ImageView
    private lateinit var roiSelectionView: RoiSelectionView
    private lateinit var backButton: FrameLayout
    private lateinit var helpButton: FrameLayout
    private lateinit var clearButton: Button
    private lateinit var saveZoneButton: Button
    private lateinit var cancelButton: Button

    private var serverUrl: String = ""
    private var videoStreamJob: Job? = null

    // Pending ROI coordinates
    private var pendingRoiX: Float? = null
    private var pendingRoiY: Float? = null
    private var pendingRoiW: Float? = null
    private var pendingRoiH: Float? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detection_zone)

        serverUrl = intent.getStringExtra("server_url") ?: getSavedServerUrl()
        
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "Server URL not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupListeners()
        loadExistingRoi()
    }

    override fun onResume() {
        super.onResume()
        startVideoStream()
    }

    override fun onPause() {
        super.onPause()
        stopVideoStream()
    }

    private fun initViews() {
        videoView = findViewById(R.id.videoView)
        roiSelectionView = findViewById(R.id.roiSelectionView)
        backButton = findViewById(R.id.backButton)
        helpButton = findViewById(R.id.helpButton)
        clearButton = findViewById(R.id.clearButton)
        saveZoneButton = findViewById(R.id.saveZoneButton)
        cancelButton = findViewById(R.id.cancelButton)
    }

    private fun setupListeners() {
        backButton.setOnClickListener {
            finish()
        }

        helpButton.setOnClickListener {
            Toast.makeText(this, "Drag corners to adjust the detection area", Toast.LENGTH_LONG).show()
        }

        clearButton.setOnClickListener {
            clearRoi()
        }

        saveZoneButton.setOnClickListener {
            saveRoi()
        }

        cancelButton.setOnClickListener {
            finish()
        }

        // ROI selection callback
        roiSelectionView.onRoiSelected = { x, y, w, h ->
            pendingRoiX = x
            pendingRoiY = y
            pendingRoiW = w
            pendingRoiH = h
            Log.d(TAG, "ROI selected: x=$x, y=$y, w=$w, h=$h")
        }
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
                        pendingRoiX = settings.roi[0]
                        pendingRoiY = settings.roi[1]
                        pendingRoiW = settings.roi[2]
                        pendingRoiH = settings.roi[3]
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
                            Toast.makeText(this@DetectionZoneActivity, "Detection zone saved", Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            Toast.makeText(this@DetectionZoneActivity, "Failed to save zone", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save ROI: ${e.message}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@DetectionZoneActivity, "Error saving zone", Toast.LENGTH_SHORT).show()
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
                        pendingRoiX = null
                        pendingRoiY = null
                        pendingRoiW = null
                        pendingRoiH = null
                        Toast.makeText(this@DetectionZoneActivity, "Detection zone cleared", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear ROI: ${e.message}")
            }
        }
    }

    // ==================== VIDEO STREAMING ====================

    private fun startVideoStream() {
        videoStreamJob = lifecycleScope.launch {
            streamVideo()
        }
    }

    private fun stopVideoStream() {
        videoStreamJob?.cancel()
        videoStreamJob = null
    }

    private suspend fun streamVideo() {
        while (coroutineContext.isActive) {
            try {
                val response = ApiClient.getVideoStream(serverUrl)
                
                if (!response.isSuccessful) {
                    response.close()
                    kotlinx.coroutines.delay(1000)
                    continue
                }
                
                val inputStream = response.body?.byteStream()
                if (inputStream == null) {
                    response.close()
                    kotlinx.coroutines.delay(1000)
                    continue
                }
                
                val mjpegStream = MjpegInputStream(inputStream)
                
                try {
                    while (coroutineContext.isActive) {
                        val frame = withContext(Dispatchers.IO) {
                            mjpegStream.readFrame()
                        }
                        
                        if (frame != null) {
                            displayFrame(frame)
                        } else {
                            break
                        }
                    }
                } finally {
                    mjpegStream.close()
                    response.close()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Stream error: ${e.message}")
            }
            
            if (coroutineContext.isActive) {
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private suspend fun displayFrame(bitmap: Bitmap) {
        withContext(Dispatchers.Main) {
            videoView.setImageBitmap(bitmap)
        }
    }

    private fun getSavedServerUrl(): String {
        val prefs = getSharedPreferences("BabySleepMonitor", MODE_PRIVATE)
        return prefs.getString("server_url", "") ?: ""
    }
}
