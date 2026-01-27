package com.babysleepmonitor

import android.content.Context
import org.opencv.android.OpenCVLoader
import android.util.Log

class BabySleepMonitorApp : android.app.Application() {
    lateinit var database: com.babysleepmonitor.data.db.AppDatabase
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
        ).build()
    }
}
