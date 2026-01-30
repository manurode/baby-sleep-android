package com.babysleepmonitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
// import androidx.lifecycle.ViewModel // Not needed if using AndroidViewModel
import com.babysleepmonitor.BabySleepMonitorApp
import com.babysleepmonitor.logic.SleepManager

class SleepViewModel(application: Application) : AndroidViewModel(application) {
    val sleepManager: SleepManager

    init {
        val app = application as BabySleepMonitorApp
        val dao = app.database.sleepDao()
        val appScope = app.applicationScope
        
        // Pass viewModelScope for internal operations, appScope for external (saving)
        sleepManager = SleepManager(dao, viewModelScope, appScope, application)
    }
        
    // We can expose start/stop here if we want to wrap SleepManager, 
    // but the request implies SleepManager is the main logic class.
    // MonitorActivity will access sleepManager directly.
    
    fun startSessionIfNeeded() {
        // If we want to ensure it only starts once per logical session?
        // SleepManager doesn't track "isActive" publically but we can infer or add flag.
        // But MonitorActivity logic says "on camera connection".
        // If rotation happens, activity creates new viewmodel? NO.
        // ViewModel survives. So if we rotate, we don't need to restart session.
        // But MonitorActivity re-connects camera on rotate (VideoView/VLC might restart).
        // MonitorActivity says "Call sleepManager.startSession() when the video stream successfully connects (if not already started)."
        
        // SleepManager.startSession() resets everything.
        // We need a way to check if it's already running.
        // SleepManager doesn't have "isSessionRunning".
        // I should probably add that to SleepManager or track it here.
    }
    
    override fun onCleared() {
        super.onCleared()
        // Ensure session is saved if ViewModel is destroyed
        if (sleepManager.isSessionRunning) {
             android.util.Log.e("SleepViewModel", "ViewModel cleared, saving potentially open session (onCleared).")
             sleepManager.stopSession()
        } else {
             android.util.Log.e("SleepViewModel", "ViewModel cleared, NO session to save.")
        }
    }
}
