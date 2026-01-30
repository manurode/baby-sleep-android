package com.babysleepmonitor.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SleepDao {
    @Insert
    suspend fun insertSession(session: SleepSessionEntity): Long

    @Query("SELECT * FROM sleep_sessions ORDER BY startTime DESC")
    suspend fun getAllSessions(): List<SleepSessionEntity>

    @Query("SELECT * FROM sleep_sessions WHERE id = :id")
    suspend fun getSessionById(id: Long): SleepSessionEntity?
}
