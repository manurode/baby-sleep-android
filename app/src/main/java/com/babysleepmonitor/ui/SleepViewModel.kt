package com.babysleepmonitor.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.babysleepmonitor.BabySleepMonitorApp
import com.babysleepmonitor.logic.SleepManager

/**
 * ViewModel that provides access to the shared SleepManager.
 * 
 * Uses the Application-level shared SleepManager instead of creating its own instance.
 * This ensures session continuity across foreground/background transitions.
 */
class SleepViewModel(application: Application) : AndroidViewModel(application) {
    val sleepManager: SleepManager

    init {
        val app = application as BabySleepMonitorApp
        // Use the shared SleepManager from Application level
        sleepManager = app.sharedSleepManager
        Log.d("SleepViewModel", "Using shared SleepManager from Application")
    }
    
    override fun onCleared() {
        super.onCleared()
        // DO NOT stop the session here — the BackgroundMonitoringService may still be running.
        // Session is only stopped explicitly by the user pressing "Stop Monitoring".
        Log.d("SleepViewModel", "ViewModel cleared. Session running: ${sleepManager.isSessionRunning}")
    }
}
