package com.babysleepmonitor

import android.content.Context
import android.util.Log
import com.babysleepmonitor.logic.SleepManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.opencv.android.OpenCVLoader

class BabySleepMonitorApp : android.app.Application() {
    lateinit var database: com.babysleepmonitor.data.db.AppDatabase
        private set

    val applicationScope = CoroutineScope(SupervisorJob())

    /**
     * Shared SleepManager instance at Application level.
     * 
     * This is the single source of truth for session state. Both MonitorActivity
     * (foreground, for UI updates) and BackgroundMonitoringService (background,
     * for headless motion tracking) use this same instance.
     * 
     * This ensures:
     * - Session continuity when switching between foreground and background
     * - No duplicate sessions when resuming the app
     * - Consistent state across all components
     */
    lateinit var sharedSleepManager: SleepManager
        private set

    override fun onCreate() {
        super.onCreate()
        if (OpenCVLoader.initDebug()) {
            Log.i("BabySleepMonitorApp", "OpenCV loaded successfully")
        } else {
            Log.e("BabySleepMonitorApp", "OpenCV initialization failed!")
        }

        database = androidx.room.Room.databaseBuilder(
            applicationContext,
            com.babysleepmonitor.data.db.AppDatabase::class.java, "baby-sleep-db"
        ).fallbackToDestructiveMigration().build()

        // Initialize shared SleepManager
        val dao = database.sleepDao()
        sharedSleepManager = SleepManager(
            sleepDao = dao,
            scope = applicationScope,
            externalScope = applicationScope,
            context = this
        )
        Log.i("BabySleepMonitorApp", "Shared SleepManager initialized")
    }
}
