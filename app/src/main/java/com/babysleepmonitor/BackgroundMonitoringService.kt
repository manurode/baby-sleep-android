package com.babysleepmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.ImageReader
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.babysleepmonitor.logic.SimpleMotionDetector
import com.babysleepmonitor.logic.SleepManager
import com.babysleepmonitor.logic.SleepState
import com.babysleepmonitor.network.OnvifSnapshotClient
import kotlinx.coroutines.*
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer
import kotlin.coroutines.coroutineContext

/**
 * Background Monitoring Service — Two-phase frame capture strategy.
 *
 * Phase 1: ONVIF HTTP Snapshot (cameras with HTTP server)
 *   → Fetches JPEG snapshots via HTTP GET. Simplest, most reliable.
 *
 * Phase 2 (fallback): RTSP via LibVLC + ImageReader
 *   → For cameras with NO HTTP server (like the user's).
 *   → Creates a headless LibVLC player with SOFTWARE decoding (--avcodec-hw=none).
 *   → Renders to an ImageReader's offscreen Surface (no Window/GPU needed).
 *   → ImageReader provides decoded frames as CPU-accessible Images.
 *   → Frames are throttled to ~3 FPS for motion detection.
 *
 * Key insight: ImageReader's Surface is backed by gralloc buffers managed by
 * the HAL, NOT by a Window or GL context. It works in background services.
 * Previous attempts failed because they transitioned an EXISTING foreground
 * player to background (causing format changes). This service creates a FRESH
 * player that was NEVER in foreground — no transition, no format change.
 */
class BackgroundMonitoringService : Service() {

    companion object {
        private const val TAG = "BgMonitoringSvc"

        private const val CHANNEL_ID_SERVICE = "bg_monitor_service"
        private const val CHANNEL_ID_ALARM = "bg_monitor_alarm"
        private const val NOTIFICATION_ID_SERVICE = 100
        private const val NOTIFICATION_ID_ALARM = 101

        private const val SNAPSHOT_INTERVAL_MS = 200L // ~5 FPS (was 300/3.3 FPS)
        private const val MAX_CONSECUTIVE_FAILURES = 10
        private const val SNAPSHOT_DISCOVERY_RETRY_MS = 15_000L
        private val ONVIF_PORTS = listOf(8899, 80, 8080, 2020, 8000)

        // ImageReader config — higher resolution = better motion detection.
        // 1920x1080 is a good balance: much better than 640x480, and software decoding
        // on modern SoCs handles 1080p fine at 5 FPS.
        private const val CAPTURE_WIDTH = 1920
        private const val CAPTURE_HEIGHT = 1080
        private const val IMAGE_READER_MAX_IMAGES = 4

        // Reference resolution for motion score scaling.
        // SleepManager thresholds (HIGH_MOTION=3M, REM=800K-2M, DEEP=100K-800K) were
        // calibrated against foreground frames at ~1920x1080.
        // At 1280x720 the scale factor is ~2.25x (was ~6.75x at 640x480).
        private const val REFERENCE_WIDTH = 1920
        private const val REFERENCE_HEIGHT = 1080
        private const val REFERENCE_AREA = REFERENCE_WIDTH * REFERENCE_HEIGHT // 2,073,600

        @Volatile var isRunning = false; private set
        @Volatile var lastMotionScore = 0.0; private set
        @Volatile var frameCount = 0L; private set
        @Volatile var lastState: SleepState = SleepState.UNKNOWN; private set
    }

    // Core
    private var snapshotClient: OnvifSnapshotClient? = null
    private var motionDetector: SimpleMotionDetector? = null
    private var sleepManager: SleepManager? = null

    // RTSP capture (Phase 2)
    private var libVLC: LibVLC? = null
    private var vlcPlayer: VlcMediaPlayer? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var lastFrameProcessedMs = 0L
    @Volatile private var rtspFrameReceived = false

    // Duplicate frame detection: VLC with software decoding sometimes outputs the same
    // decoded frame twice. SimpleMotionDetector's absdiff then produces 0.0 even during
    // real movement. We track the last non-zero raw score and its timestamp to detect
    // these false zeros and skip them (so SleepManager's buffer isn't poisoned).
    private var lastNonZeroRawScore = 0.0
    private var lastNonZeroRawTime = 0L
    private var consecutiveZeroFrames = 0

    // Coroutines
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitoringJob: Job? = null

    // System
    private lateinit var wakeLock: PowerManager.WakeLock
    private val mainHandler = Handler(Looper.getMainLooper())

    // State
    private var rtspUrl = ""
    private var username = ""
    private var password = ""
    private var consecutiveFailures = 0
    private var isAlarmActive = false
    private var totalFramesProcessed = 0L
    private var serviceStartTimeMs = 0L

    // ==================== Lifecycle ====================

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BabySleepMonitor::BgMonWake")
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        rtspUrl = intent?.getStringExtra("rtsp_url") ?: ""
        username = intent?.getStringExtra("username") ?: ""
        password = intent?.getStringExtra("password") ?: ""

        if (rtspUrl.isEmpty()) {
            Log.e(TAG, "No RTSP URL, stopping"); stopSelf(); return START_NOT_STICKY
        }

        Log.i(TAG, "Starting background monitoring (URL: ${rtspUrl.take(50)}...)")
        startForeground(NOTIFICATION_ID_SERVICE, createServiceNotification("Initializing..."))
        if (!wakeLock.isHeld) wakeLock.acquire(24 * 60 * 60 * 1000L)
        isRunning = true
        serviceStartTimeMs = System.currentTimeMillis()
        startMonitoring()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed. Frames processed: $totalFramesProcessed")
        isRunning = false
        monitoringJob?.cancel()
        serviceScope.cancel()
        stopRtspCapture()
        snapshotClient?.release()
        snapshotClient = null
        motionDetector = null
        sleepManager = null
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== Monitoring Orchestration ====================

    private fun startMonitoring() {
        motionDetector = SimpleMotionDetector().apply {
            onCalibrationComplete = { debugBitmap ->
                saveCalibrationDebugImage(debugBitmap)
            }
        }
        sleepManager = (application as BabySleepMonitorApp).sharedSleepManager
        snapshotClient = OnvifSnapshotClient(username, password)

        monitoringJob = serviceScope.launch {
            // Phase 1: Try ONVIF snapshot
            Log.i(TAG, "=== Phase 1: ONVIF Snapshot Discovery ===")
            val snapshotReady = discoverSnapshotUri()
            if (snapshotReady) {
                withContext(Dispatchers.Main) { updateNotification("Monitoring (snapshot)") }
                snapshotPollingLoop()
                return@launch
            }

            // Phase 2: Fall back to RTSP via LibVLC + ImageReader
            Log.i(TAG, "=== Phase 2: RTSP Frame Capture (LibVLC + ImageReader) ===")
            withContext(Dispatchers.Main) {
                updateNotification("Starting RTSP capture...")
                startRtspCapture()
            }

            // Wait for first frame to confirm it works
            delay(10_000)
            if (rtspFrameReceived) {
                Log.i(TAG, "✅ RTSP frame capture is working!")
            } else {
                Log.w(TAG, "⚠️ No RTSP frames received after 10s. Capture may not work with this device.")
                withContext(Dispatchers.Main) {
                    updateNotification("⚠️ Camera capture issue — retrying...")
                }
                // Retry loop
                while (coroutineContext.isActive && !rtspFrameReceived) {
                    delay(SNAPSHOT_DISCOVERY_RETRY_MS)
                    withContext(Dispatchers.Main) {
                        stopRtspCapture()
                        startRtspCapture()
                    }
                    delay(10_000)
                }
            }
        }
    }

    // ==================== Phase 1: ONVIF Snapshot ====================

    private fun extractCameraHost(url: String): String? =
        """rtsp://(?:[^@]+@)?([^:/]+)""".toRegex().find(url)?.groupValues?.get(1)

    private fun getStoredOnvifPort(): String {
        val prefs = getSharedPreferences("BabySleepMonitor", Context.MODE_PRIVATE)
        return prefs.getString("onvif_port", null) ?: "80"
    }

    private suspend fun discoverSnapshotUri(): Boolean {
        val client = snapshotClient ?: return false
        val host = extractCameraHost(rtspUrl) ?: return false
        val storedPort = getStoredOnvifPort()
        val ports = mutableListOf(storedPort).apply {
            ONVIF_PORTS.forEach { p -> if (p.toString() != storedPort) add(p.toString()) }
        }

        Log.d(TAG, "Trying ONVIF on ports: $ports (stored=$storedPort)")

        // Try ONVIF GetSnapshotUri on each port
        for (port in ports) {
            val onvifUrl = "http://$host:$port/onvif/device_service"
            Log.d(TAG, "ONVIF GetSnapshotUri → $onvifUrl")
            try {
                val uri = withContext(Dispatchers.IO) { client.discoverSnapshotUri(onvifUrl) }
                if (uri != null) {
                    val fixed = uri.replace("0.0.0.0", host).replace("127.0.0.1", host)
                    if (fixed != uri) client.setSnapshotUri(fixed)
                    val bmp = withContext(Dispatchers.IO) { client.fetchSnapshot() }
                    if (bmp != null) {
                        Log.i(TAG, "✅ Snapshot works (${bmp.width}x${bmp.height})")
                        bmp.recycle(); return true
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "ONVIF port $port failed: ${e.message}")
            }
        }

        // Try common URL patterns
        for (port in ports) {
            for (path in snapshotPaths()) {
                val url = "http://$host:$port$path"
                try {
                    client.setSnapshotUri(url)
                    val bmp = withContext(Dispatchers.IO) { client.fetchSnapshot() }
                    if (bmp != null) {
                        Log.i(TAG, "✅ Snapshot URL works: $url"); bmp.recycle(); return true
                    }
                } catch (_: Exception) { }
            }
        }

        Log.w(TAG, "ONVIF snapshot unavailable on all ports for $host")
        return false
    }

    private fun snapshotPaths() = listOf(
        "/onvif-http/snapshot",
        "/ISAPI/Streaming/channels/101/picture",
        "/Streaming/channels/1/picture",
        "/cgi-bin/snapshot.cgi",
        "/snapshot.jpg", "/snap.jpg",
        "/tmpfs/snap.jpg", "/image/jpeg.cgi"
    )

    private suspend fun snapshotPollingLoop() {
        Log.i(TAG, "Snapshot polling loop started")
        val client = snapshotClient ?: return
        while (coroutineContext.isActive) {
            val start = System.currentTimeMillis()
            try {
                val bmp = withContext(Dispatchers.IO) { client.fetchSnapshot() }
                if (bmp != null) {
                    try { processFrame(bmp) } finally { bmp.recycle() }
                    consecutiveFailures = 0
                } else {
                    handleFetchFailure()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Snapshot loop error: ${e.message}")
            }
            val elapsed = System.currentTimeMillis() - start
            delay((SNAPSHOT_INTERVAL_MS - elapsed).coerceAtLeast(10))
        }
    }

    // ==================== Phase 2: RTSP via LibVLC + ImageReader ====================

    /**
     * Creates a headless LibVLC player that renders to an ImageReader surface.
     * Must be called on the main thread (VLC requirement).
     */
    private fun startRtspCapture() {
        Log.i(TAG, "Starting RTSP capture: LibVLC + ImageReader (software decoding)")

        // Background thread for ImageReader callbacks
        captureThread = HandlerThread("BgCapture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        // Create ImageReader — its Surface is offscreen (no Window/GL needed)
        imageReader = ImageReader.newInstance(
            CAPTURE_WIDTH, CAPTURE_HEIGHT, PixelFormat.RGBX_8888, IMAGE_READER_MAX_IMAGES
        )
        imageReader!!.setOnImageAvailableListener({ reader ->
            onImageAvailable(reader)
        }, captureHandler)

        // Create LibVLC with software decoding to avoid GPU surface requirements.
        // CRITICAL: --no-drop-late-frames and --no-skip-frames are ESSENTIAL.
        // Software decoding is slower than real-time, so VLC considers ALL frames
        // "late". Without these flags, VLC drops every single frame and ImageReader
        // never receives anything.
        val vlcOptions = arrayListOf(
            "--no-audio",                // No audio needed
            "--avcodec-hw=none",         // Force software decoding (no GPU/MediaCodec)
            "--avcodec-skiploopfilter=2", // Faster decoding (skip deblock filter)
            "--avcodec-fast",            // Fast decoding mode
            "--network-caching=1500",    // 1.5s buffer — gives decoder time to catch up
            "--rtsp-tcp",                // TCP transport for reliability
            "--no-drop-late-frames",     // CRITICAL: Render ALL frames even if late
            "--no-skip-frames",          // CRITICAL: Don't skip any frames
            "--clock-jitter=0",          // Reduce clock jitter sensitivity
            "--no-stats",
            "--no-sub-autodetect-file",
            "--no-spu",
            "-v"
        )
        libVLC = LibVLC(applicationContext, vlcOptions)

        // Create MediaPlayer and attach ImageReader's surface
        vlcPlayer = VlcMediaPlayer(libVLC!!).apply {
            vlcVout.setVideoSurface(imageReader!!.surface, null)
            vlcVout.attachViews()

            setEventListener { event ->
                when (event.type) {
                    VlcMediaPlayer.Event.Playing ->
                        Log.i(TAG, "VLC: Playing (headless RTSP)")
                    VlcMediaPlayer.Event.EncounteredError ->
                        Log.e(TAG, "VLC: Playback error")
                    VlcMediaPlayer.Event.Stopped ->
                        Log.i(TAG, "VLC: Stopped")
                    VlcMediaPlayer.Event.Buffering ->
                        Log.d(TAG, "VLC: Buffering ${event.buffering}%")
                }
            }
        }

        // Build the RTSP media URI with credentials
        val mediaUri = buildRtspUri(rtspUrl, username, password)
        val media = Media(libVLC!!, Uri.parse(mediaUri)).apply {
            addOption(":rtsp-tcp")
            addOption(":network-caching=300")
            // Force chroma to match ImageReader format
            addOption(":android-display-chroma=RV32")
        }
        vlcPlayer!!.media = media
        media.release()
        vlcPlayer!!.play()

        Log.i(TAG, "VLC headless player started. Waiting for frames via ImageReader...")
    }

    private fun buildRtspUri(url: String, user: String, pass: String): String {
        if (user.isEmpty()) return url
        // Insert credentials into RTSP URL: rtsp://user:pass@host:port/path
        return url.replace("rtsp://", "rtsp://$user:$pass@")
    }

    /**
     * Called by ImageReader when a new frame is available.
     * Throttled to ~3 FPS to avoid wasting CPU.
     */
    private fun onImageAvailable(reader: ImageReader) {
        val now = System.currentTimeMillis()

        // Throttle processing to SNAPSHOT_INTERVAL_MS
        if (now - lastFrameProcessedMs < SNAPSHOT_INTERVAL_MS) {
            // Drain the buffer to prevent backpressure
            try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}
            return
        }

        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "acquireLatestImage failed: ${e.message}")
            return
        } ?: return

        try {
            lastFrameProcessedMs = now
            rtspFrameReceived = true

            // Convert RGBA Image to Bitmap
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmapWidth = image.width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop to actual dimensions if there was row padding
            val finalBitmap = if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also {
                    bitmap.recycle()
                }
            } else {
                bitmap
            }

            try {
                processFrame(finalBitmap)
            } finally {
                finalBitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing ImageReader frame: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun stopRtspCapture() {
        try {
            vlcPlayer?.stop()
            vlcPlayer?.vlcVout?.detachViews()
            vlcPlayer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping VLC: ${e.message}")
        }
        vlcPlayer = null

        try { libVLC?.release() } catch (_: Exception) {}
        libVLC = null

        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        Log.d(TAG, "RTSP capture stopped and cleaned up")
    }

    // ==================== Shared Frame Processing ====================

    private fun processFrame(bitmap: Bitmap) {
        val result = motionDetector?.processFrame(bitmap) ?: return
        val now = System.currentTimeMillis()

        // --- Duplicate frame detection ---
        // VLC with software decoding + --no-drop-late-frames sometimes outputs the
        // same decoded frame twice. When SimpleMotionDetector compares two identical
        // frames, absdiff=0 → raw motionScore=0.0, even during violent movement.
        // Pattern: 0, 1728135, 0, 659238, 0, 733252, 0, 0, 0 ...
        //
        // Fix: If raw score is 0.0 but we recently (within 1s) had a non-zero score,
        // this is likely a duplicate frame → skip it (don't feed to SleepManager).
        // Only after several consecutive zeros (>= 5 = ~1s at 5 FPS) do we consider
        // it real absence of motion and pass it through.
        val rawScore = result.motionScore

        if (rawScore == 0.0) {
            consecutiveZeroFrames++
            // If we had motion recently and haven't seen enough consecutive real zeros,
            // this is probably a duplicate frame from VLC — skip it.
            if (lastNonZeroRawScore > 0.0 &&
                (now - lastNonZeroRawTime) < 1000L &&
                consecutiveZeroFrames < 5) {
                // Skip this frame — don't pollute SleepManager buffer
                totalFramesProcessed++
                frameCount = totalFramesProcessed
                if (totalFramesProcessed % 10 == 0L) {
                    Log.d(TAG, "Frame #$totalFramesProcessed: SKIPPED duplicate (raw=0, " +
                        "lastNonZero=${lastNonZeroRawScore.toInt()}, zeroStreak=$consecutiveZeroFrames)")
                }
                return
            }
        } else {
            lastNonZeroRawScore = rawScore
            lastNonZeroRawTime = now
            consecutiveZeroFrames = 0
        }

        // --- Scale motion score ---
        // motionScore is sum of white pixels → proportional to frame area.
        // Scale to match SleepManager thresholds (calibrated for ~1920x1080).
        val frameArea = result.width * result.height
        val scaleFactor = if (frameArea > 0) REFERENCE_AREA.toDouble() / frameArea else 1.0
        val scaledScore = rawScore * scaleFactor

        val state = sleepManager?.update(scaledScore)

        totalFramesProcessed++
        frameCount = totalFramesProcessed
        lastMotionScore = scaledScore
        lastState = state ?: SleepState.UNKNOWN
        consecutiveFailures = 0

        // Debug log every 10 frames
        if (totalFramesProcessed % 10 == 0L) {
            Log.d(TAG, "Frame #$totalFramesProcessed: raw=${rawScore.toInt()}, " +
                "scaled=${scaledScore.toInt()} (x${String.format("%.2f", scaleFactor)}), " +
                "res=${result.width}x${result.height}, state=${state?.value}")
        }

        // Alarm logic
        if (state == SleepState.NO_BREATHING && !isAlarmActive) {
            isAlarmActive = true
            mainHandler.post { showAlarmNotification(); vibratePhone() }
        } else if (state != SleepState.NO_BREATHING && isAlarmActive) {
            isAlarmActive = false
            mainHandler.post { cancelAlarmNotification() }
        }

        // Periodic status log (~every 15s)
        if (totalFramesProcessed % 50 == 0L) {
            val upMin = (System.currentTimeMillis() - serviceStartTimeMs) / 60_000
            Log.i(TAG, "Status: frames=$totalFramesProcessed, " +
                "rawScore=${rawScore.toInt()}, scaledScore=${scaledScore.toInt()}, " +
                "scale=${String.format("%.2f", scaleFactor)}x, state=${state?.value}, uptime=${upMin}min")
            mainHandler.post {
                updateNotification("Active • ${totalFramesProcessed} frames • ${state?.value}")
            }
        }
    }

    private fun handleFetchFailure() {
        consecutiveFailures++
        if (consecutiveFailures % 10 == 0)
            Log.w(TAG, "Fetch failures: $consecutiveFailures")
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            mainHandler.post { updateNotification("⚠️ Camera connection issue") }
            consecutiveFailures = 0
        }
    }
    // ==================== Debug Image ====================

    private fun saveCalibrationDebugImage(bitmap: Bitmap) {
        try {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val filename = "calibration_debug_$timestamp.png"

            // Save to app's external files dir: /sdcard/Android/data/com.babysleepmonitor/files/
            val dir = getExternalFilesDir(null)
            if (dir != null) {
                val file = java.io.File(dir, filename)
                java.io.FileOutputStream(file).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                Log.i(TAG, "📸 Calibration debug image saved: ${file.absolutePath}")
                Log.i(TAG, "   → Use Device File Explorer in Android Studio to view it")
                Log.i(TAG, "   → Or: adb pull ${file.absolutePath}")
            } else {
                Log.w(TAG, "External files dir unavailable, cannot save debug image")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save calibration debug image: ${e.message}")
        } finally {
            bitmap.recycle()
        }
    }

    // ==================== Notifications ====================

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID_SERVICE, "Background Monitoring", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Background sleep monitoring status" })

        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID_ALARM, "Sleep Monitor Alarm", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "No movement alerts"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        })
    }

    private fun createServiceNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MonitorActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setContentTitle("🛌 Baby Sleep Monitor")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi).setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID_SERVICE, createServiceNotification(status))
    }

    private fun showAlarmNotification() {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MonitorActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val n = NotificationCompat.Builder(this, CHANNEL_ID_ALARM)
            .setContentTitle("⚠️ NO MOVEMENT DETECTED!")
            .setContentText("Check on baby immediately!")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setVibrate(longArrayOf(0, 500, 250, 500, 250, 500))
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_LIGHTS)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID_ALARM, n)
    }

    private fun cancelAlarmNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIFICATION_ID_ALARM)
    }

    // ==================== Vibration ====================

    private fun vibratePhone() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 250, 500, 250, 500), -1))
    }
}
