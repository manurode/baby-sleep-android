package com.babysleepmonitor.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sleep_sessions")
data class SleepSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long,
    val totalSleepSeconds: Long,
    val wakeUpCount: Int,
    val qualityScore: Int,
    val deepSleepSeconds: Long = 0,
    val lightSleepSeconds: Long = 0,
    val spasmCount: Int = 0,
    val avgBreathingBpm: Double = 0.0,
    // Serialized timeline: "timestamp1:state1,timestamp2:state2,..."
    // Each entry represents a state change during the session
    val stateTimeline: String = ""
)
