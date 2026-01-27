package com.babysleepmonitor.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [SleepSessionEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sleepDao(): SleepDao
}
