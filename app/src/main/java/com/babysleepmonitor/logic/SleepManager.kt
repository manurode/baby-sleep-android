package com.babysleepmonitor.logic

import android.util.Log
import java.util.LinkedList
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.babysleepmonitor.data.db.SleepDao
import com.babysleepmonitor.data.db.SleepSessionEntity


enum class SleepState(val value: String) {
    UNKNOWN("unknown"),
    NO_BREATHING("no_breathing"),
    DEEP_SLEEP("deep_sleep"),
    LIGHT_SLEEP("light_sleep"),
    SPASM("spasm"),
    AWAKE("awake")
}

data class SleepStats(
    val currentState: SleepState,
    val breathingDetected: Boolean,
    val breathingRateBpm: Double,
    val sleepQualityScore: Int,
    val totalSleepSeconds: Long,
    val wakeUps: Int,
    val eventsCount: Int,
    val sleepPhase: String
)

class BreathingAnalyzer {
    // Thresholds
    private val BREATH_PEAK_THRESHOLD = 50_000.0
    private val MIN_BREATH_INTERVAL = 1.0
    private val MAX_BREATH_INTERVAL = 5.0
    private val LOW_VARIABILITY_THRESHOLD = 0.15
    private val HIGH_VARIABILITY_THRESHOLD = 0.30

    private val breathTimestamps = LinkedList<Double>() // last 100
    private val breathIntervals = LinkedList<Double>() // last 50
    private var lastPeakTime: Double? = null
    private var inPeak = false

    fun reset() {
        breathTimestamps.clear()
        breathIntervals.clear()
        lastPeakTime = null
        inPeak = false
    }

    fun processMotion(motionScore: Double, timestamp: Double): Double? {
        if (motionScore > BREATH_PEAK_THRESHOLD) {
            if (!inPeak) {
                inPeak = true
                if (lastPeakTime != null) {
                    val interval = timestamp - lastPeakTime!!
                    lastPeakTime = timestamp
                    
                    if (interval in MIN_BREATH_INTERVAL..MAX_BREATH_INTERVAL) {
                        breathTimestamps.add(timestamp)
                        if (breathTimestamps.size > 100) breathTimestamps.removeFirst()
                        
                        breathIntervals.add(interval)
                        if (breathIntervals.size > 50) breathIntervals.removeFirst()
                        
                        return interval
                    } else if (interval > MAX_BREATH_INTERVAL) {
                        breathTimestamps.add(timestamp)
                        if (breathTimestamps.size > 100) breathTimestamps.removeFirst()
                    }
                } else {
                    lastPeakTime = timestamp
                    breathTimestamps.add(timestamp)
                }
            }
        } else {
            inPeak = false
        }
        return null
    }

    fun getBreathingRate(): Double {
        if (breathIntervals.size < 3) return 0.0
        val recent = breathIntervals.takeLast(10)
        val avg = recent.average()
        return if (avg > 0) 60.0 / avg else 0.0
    }

    fun getVariability(): Double {
        if (breathIntervals.size < 5) return 0.0
        val recent = breathIntervals.takeLast(20)
        val mean = recent.average()
        if (mean > 0 && recent.size >= 2) {
            val std = standardDeviation(recent)
            return std / mean
        }
        return 0.0
    }

    fun getSleepPhase(): String {
        val v = getVariability()
        return when {
            v == 0.0 -> "unknown"
            v < LOW_VARIABILITY_THRESHOLD -> "deep"
            v > HIGH_VARIABILITY_THRESHOLD -> "light"
            else -> "transitional"
        }
    }
}



class SleepManager(
    private val sleepDao: SleepDao,
    private val scope: CoroutineScope
) {
    private val TAG = "SleepManager"
    
    // Thresholds
    private val NO_MOTION_THRESHOLD = 10_000.0
    private val BREATHING_LOW = 10_000.0
    private val MOVEMENT_THRESHOLD = 5_000_000.0
    private val AWAKE_THRESHOLD = 10_000_000.0
    
    // Timing
    private val BUFFER_DURATION = 60.0
    private val ANALYSIS_WINDOW = 10.0
    private val SPASM_WINDOW = 5.0
    
    // State Logic
    private val breathingAnalyzer = BreathingAnalyzer()
    private val motionBuffer = LinkedList<Pair<Double, Double>>() // timestamp, score
    
    var currentState = SleepState.UNKNOWN
    private var sessionStartTime: Double? = null
    
    // Metrics
    private var totalSleepSeconds = 0.0
    private var deepSleepSeconds = 0.0
    private var lightSleepSeconds = 0.0
    private var wakeUpCount = 0
    private var lastUpdateTime = 0.0
    private var stateStartTime = 0.0
    
    // Transition Hysteresis
    private var pendingState: SleepState? = null
    private var pendingStateTime: Double? = null
    
    init {
        startSession()
    }
    
    fun startSession() {
        sessionStartTime = System.currentTimeMillis() / 1000.0
        breathingAnalyzer.reset()
        motionBuffer.clear()
        currentState = SleepState.UNKNOWN
        totalSleepSeconds = 0.0
        wakeUpCount = 0
    }

    fun stopSession() {
        if (sessionStartTime != null) {
             val endTime = System.currentTimeMillis() / 1000.0
             val duration = (endTime - sessionStartTime!!).toLong()
             Log.i(TAG, "Session stopped. Duration: $duration s")

             scope.launch(Dispatchers.IO) {
                 val session = SleepSessionEntity(
                     startTime = (sessionStartTime!! * 1000).toLong(),
                     endTime = (endTime * 1000).toLong(),
                     totalSleepSeconds = totalSleepSeconds.toLong(),
                     wakeUpCount = wakeUpCount,
                     qualityScore = 85 // Placeholder
                 )
                 try {
                     sleepDao.insertSession(session)
                     Log.i(TAG, "Session saved to DB")
                 } catch (e: Exception) {
                     Log.e(TAG, "Failed to save session", e)
                 }
             }
        }
    }
    
    fun update(motionScore: Double): SleepState {
        val currentTime = System.currentTimeMillis() / 1000.0
        
        // Buffer
        motionBuffer.add(currentTime to motionScore)
        cleanBuffer(currentTime)
        
        // Breathing
        breathingAnalyzer.processMotion(motionScore, currentTime)
        
        // Analysis
        val analysis = analyzeBuffer(currentTime)
        
        // Target State
        val targetState = determineState(analysis)
        
        // Transition
        handleTransition(targetState, currentTime)
        
        // Metrics Update
        updateMetrics(currentTime)
        
        return currentState
    }
    
    private fun cleanBuffer(currentTime: Double) {
        val cutoff = currentTime - BUFFER_DURATION
        while (motionBuffer.isNotEmpty() && motionBuffer.first().first < cutoff) {
            motionBuffer.removeFirst()
        }
    }
    
    private fun analyzeBuffer(currentTime: Double): Map<String, Any> {
        val windowStart = currentTime - ANALYSIS_WINDOW
        val windowScores = motionBuffer.filter { it.first >= windowStart }.map { it.second }
        
        if (windowScores.isEmpty()) return emptyMap()
        
        val mean = windowScores.average()
        val highMovementRatio = windowScores.count { it > MOVEMENT_THRESHOLD }.toDouble() / windowScores.size
        val isNoMotion = mean < NO_MOTION_THRESHOLD
        
        return mapOf(
            "mean" to mean,
            "highMovementRatio" to highMovementRatio,
            "isNoMotion" to isNoMotion,
            "sleepPhase" to breathingAnalyzer.getSleepPhase()
        )
    }
    
    private fun determineState(analysis: Map<String, Any>): SleepState {
        if (analysis.isEmpty()) return currentState
        
        val isNoMotion = analysis["isNoMotion"] as Boolean
        if (isNoMotion) return SleepState.NO_BREATHING
        
        val mean = analysis["mean"] as Double
        val highRatio = analysis["highMovementRatio"] as Double
        
        if (highRatio > 0.5 && mean > MOVEMENT_THRESHOLD) {
            return SleepState.AWAKE
        }
        
        if (mean < AWAKE_THRESHOLD) {
            return when (analysis["sleepPhase"] as String) {
                "deep" -> SleepState.DEEP_SLEEP
                "light", "transitional" -> SleepState.LIGHT_SLEEP
                else -> if (currentState == SleepState.UNKNOWN) SleepState.LIGHT_SLEEP else currentState
            }
        }
        
        return if (currentState != SleepState.UNKNOWN) currentState else SleepState.LIGHT_SLEEP
    }
    
    private fun handleTransition(target: SleepState, currentTime: Double) {
        if (target == currentState) {
            pendingState = null
            return
        }
        
        // Simple hysteresis for now (3s confirmation)
        val confirmTime = 3.0
        
        if (pendingState == target) {
            if (currentTime - pendingStateTime!! >= confirmTime) {
                executeTransition(target, currentTime)
            }
        } else {
            pendingState = target
            pendingStateTime = currentTime
        }
    }
    
    private fun executeTransition(newState: SleepState, currentTime: Double) {
        val oldState = currentState
        currentState = newState
        stateStartTime = currentTime
        pendingState = null
        
        if (oldState != SleepState.AWAKE && newState == SleepState.AWAKE && 
            (oldState == SleepState.DEEP_SLEEP || oldState == SleepState.LIGHT_SLEEP)) {
            wakeUpCount++
        }
        
        Log.i(TAG, "Transition: $oldState -> $newState")
    }
    
    private fun updateMetrics(currentTime: Double) {
        if (lastUpdateTime > 0) {
            val delta = currentTime - lastUpdateTime
            if (currentState == SleepState.DEEP_SLEEP || currentState == SleepState.LIGHT_SLEEP) {
                totalSleepSeconds += delta
            }
        }
        lastUpdateTime = currentTime
    }
    
    fun getStats(): SleepStats {
        return SleepStats(
            currentState = currentState,
            breathingDetected = currentState == SleepState.DEEP_SLEEP || currentState == SleepState.LIGHT_SLEEP,
            breathingRateBpm = breathingAnalyzer.getBreathingRate(),
            sleepQualityScore = 85, // Placeholder logic
            totalSleepSeconds = totalSleepSeconds.toLong(),
            wakeUps = wakeUpCount,
            eventsCount = 0,
            sleepPhase = breathingAnalyzer.getSleepPhase()
        )
    }
}

// Helper
fun standardDeviation(list: List<Double>): Double {
    if (list.size < 2) return 0.0
    val mean = list.average()
    val sumSq = list.sumOf { (it - mean) * (it - mean) }
    return sqrt(sumSq / (list.size - 1))
}
