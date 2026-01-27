package com.babysleepmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.babysleepmonitor.data.StatusResponse

/**
 * Foreground Service that polls the baby monitor server for status updates.
 * Posts high-priority notifications with sound and vibration when alarm is triggered.
 */
class MonitoringService : Service() {

    companion object {
        const val TAG = "MonitoringService"
        const val CHANNEL_ID_SERVICE = "baby_monitor_service"
        const val CHANNEL_ID_ALARM = "baby_monitor_alarm"
        const val NOTIFICATION_ID_SERVICE = 1
        const val NOTIFICATION_ID_ALARM = 2
        const val NOTIFICATION_ID_CONNECTION_LOST = 3
        const val POLLING_INTERVAL_MS = 1500L // Poll every 1.5 seconds
        const val MAX_CONSECUTIVE_FAILURES = 3 // ~4.5 seconds without connection
        
        @Volatile
        var isRunning = false
            private set
        
        @Volatile
        var isConnectionLost = false
            private set
        
        /**
         * Stops the alarm sound and cancels the alarm notification.
         * Called from the connection lost dialog when user acknowledges.
         */
        fun stopAlarmSound(context: Context) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID_ALARM)
            notificationManager.cancel(NOTIFICATION_ID_CONNECTION_LOST)
            isConnectionLost = false
        }
    }

    private lateinit var httpClient: OkHttpClient
    private lateinit var handler: Handler
    private lateinit var wakeLock: PowerManager.WakeLock
    
    private var serverUrl: String = ""
    private var isAlarmActive = false
    private var consecutiveFailures = 0
    private var connectionLostAlarmActive = false
    private val gson = Gson()

    private val pollingRunnable = object : Runnable {
        override fun run() {
            checkServerStatus()
            handler.postDelayed(this, POLLING_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        
        handler = Handler(Looper.getMainLooper())
        
        // Acquire wake lock to keep polling while screen is off
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BabySleepMonitor::MonitoringWakeLock"
        )
        
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serverUrl = intent?.getStringExtra("server_url") ?: ""
        
        if (serverUrl.isEmpty() || !serverUrl.startsWith("http", ignoreCase = true)) {
            Log.e(TAG, "Invalid server URL (must be HTTP/HTTPS) or empty: $serverUrl. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }
        
        Log.i(TAG, "Starting monitoring service for: $serverUrl")
        
        // Start as foreground service
        startForeground(NOTIFICATION_ID_SERVICE, createServiceNotification())
        
        // Acquire wake lock
        if (!wakeLock.isHeld) {
            wakeLock.acquire(60 * 60 * 1000L) // 1 hour max, will reacquire
        }
        
        // Start polling
        isRunning = true
        handler.post(pollingRunnable)
        
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Stopping monitoring service")
        
        isRunning = false
        handler.removeCallbacks(pollingRunnable)
        
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        
        // Cancel any active alarm notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID_ALARM)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Service notification channel (low importance)
        val serviceChannel = NotificationChannel(
            CHANNEL_ID_SERVICE,
            "Baby Monitor Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows that the baby monitor is running in the background"
        }
        notificationManager.createNotificationChannel(serviceChannel)

        // Alarm notification channel (high importance with sound)
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val alarmChannel = NotificationChannel(
            CHANNEL_ID_ALARM,
            "Baby Monitor Alarm",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when no movement is detected"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
            setSound(
                alarmSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        notificationManager.createNotificationChannel(alarmChannel)
    }

    private fun createServiceNotification(): Notification {
        val openAppIntent = Intent(this, MonitorActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setContentTitle("Baby Monitor Active")
            .setContentText("Monitoring for movement...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun checkServerStatus() {
        val statusUrl = if (serverUrl.endsWith("/")) "${serverUrl}status" else "$serverUrl/status"
        
        val request = try {
            Request.Builder()
                .url(statusUrl)
                .get()
                .build()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Failed to build request for URL: $statusUrl", e)
            return
        }

        Thread {
            try {
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val status = gson.fromJson(body, StatusResponse::class.java)
                        // Reset connection failure counter on success
                        handler.post {
                            consecutiveFailures = 0
                            if (connectionLostAlarmActive) {
                                connectionLostAlarmActive = false
                                isConnectionLost = false
                                cancelConnectionLostNotification()
                            }
                        }
                        handleStatusUpdate(status)
                    }
                } else {
                    Log.w(TAG, "Server returned error: ${response.code}")
                    handleConnectionFailure()
                }
                response.close()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to connect to server: ${e.message}")
                handleConnectionFailure()
            }
        }.start()
    }

    private fun handleConnectionFailure() {
        handler.post {
            consecutiveFailures++
            Log.d(TAG, "Connection failure #$consecutiveFailures")
            
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES && !connectionLostAlarmActive) {
                connectionLostAlarmActive = true
                isConnectionLost = true
                showConnectionLostNotification()
                vibratePhone()
            }
        }
    }

    private fun handleStatusUpdate(status: StatusResponse) {
        handler.post {
            if (status.alarm_active && !isAlarmActive) {
                // Alarm just triggered
                isAlarmActive = true
                showAlarmNotification(status.seconds_since_motion)
                vibratePhone()
            } else if (!status.alarm_active && isAlarmActive) {
                // Alarm recovered
                isAlarmActive = false
                cancelAlarmNotification()
            }
        }
    }

    private fun showAlarmNotification(secondsSinceMotion: Int) {
        val openAppIntent = Intent(this, MonitorActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALARM)
            .setContentTitle("⚠️ ALARM: NO MOVEMENT DETECTED!")
            .setContentText("No movement for ${secondsSinceMotion} seconds. Check on baby immediately!")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOngoing(true)
            .setVibrate(longArrayOf(0, 500, 250, 500, 250, 500))
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_LIGHTS)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_ALARM, notification)
    }

    private fun cancelAlarmNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID_ALARM)
    }

    private fun showConnectionLostNotification() {
        val openAppIntent = Intent(this, MonitorActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALARM)
            .setContentTitle("⚠️ CONNECTION LOST!")
            .setContentText("Lost connection with the baby monitor server. Your baby is NOT being monitored!")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setVibrate(longArrayOf(0, 500, 250, 500, 250, 500))
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_LIGHTS)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_CONNECTION_LOST, notification)
    }

    private fun cancelConnectionLostNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID_CONNECTION_LOST)
    }

    private fun vibratePhone() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val pattern = longArrayOf(0, 500, 250, 500, 250, 500, 250, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}
