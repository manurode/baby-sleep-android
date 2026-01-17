package com.babysleepmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * BroadcastReceiver to restart the monitoring service after device reboot.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Device boot completed, checking if service should restart...")
            
            val prefs = context.getSharedPreferences("BabySleepMonitor", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start_on_boot", false)
            val serverUrl = prefs.getString("server_url", null)
            
            if (autoStart && !serverUrl.isNullOrEmpty()) {
                Log.i(TAG, "Auto-starting monitoring service...")
                val serviceIntent = Intent(context, MonitoringService::class.java).apply {
                    putExtra("server_url", serverUrl)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
