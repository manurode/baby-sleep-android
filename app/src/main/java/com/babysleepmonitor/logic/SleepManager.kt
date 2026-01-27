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


interface ISleepLogger {
    fun i(tag: String, msg: String)
    fun e(tag: String, msg: String, tr: Throwable?)
}

enum class SleepState(val value: String) {
    UNKNOWN("unknown"),
    CALIBRATING("calibrating"),
    NO_BREATHING("no_breathing"),
    DEEP_SLEEP("deep_sleep"),
    LIGHT_SLEEP("light_sleep"),
    REM_SLEEP("rem_sleep"),
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
    val LOW_VARIABILITY_THRESHOLD = 0.10
    val HIGH_VARIABILITY_THRESHOLD = 0.20

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
    private val scope: CoroutineScope,
    private val logger: ISleepLogger? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val TAG = "SleepManager"
    
    // Thresholds
    private val NO_MOTION_THRESHOLD = 10_000.0
    private val HIGH_MOTION_THRESHOLD = 3_000_000.0
    private val DEEP_SLEEP_RANGE = 100_000.0..800_000.0
    private val REM_SLEEP_RANGE = 800_000.0..2_000_000.0
    
    // Timing
    private val BUFFER_DURATION = 60.0
    private val ANALYSIS_WINDOW = 10.0
    private val WARMUP_DURATION = 10.0
    private val CONFIRMATION_TIME_ALARM = 20.0
    private val CONFIRMATION_TIME_AWAKE = 5.0
    
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
        sessionStartTime = timeProvider() / 1000.0
        breathingAnalyzer.reset()
        motionBuffer.clear()
        currentState = SleepState.UNKNOWN
        totalSleepSeconds = 0.0
        wakeUpCount = 0
    }

    fun stopSession() {
        if (sessionStartTime != null) {
             val endTime = timeProvider() / 1000.0
             val duration = (endTime - sessionStartTime!!).toLong()
             log("Session stopped. Duration: $duration s")

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
                     log("Session saved to DB")
                 } catch (e: Exception) {
                     logError("Failed to save session", e)
                 }
             }
        }
    }
    
    fun update(motionScore: Double): SleepState {
        val currentTime = timeProvider() / 1000.0
        
        // Warm-up check
        if (sessionStartTime != null && (currentTime - sessionStartTime!!) < WARMUP_DURATION) {
             currentState = SleepState.CALIBRATING
             return currentState
        }

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
        
        val recentStart = currentTime - 2.0 // Short window for spikes
        val recentScores = motionBuffer.filter { it.first >= recentStart }.map { it.second }

        val recentMean = if (recentScores.isNotEmpty()) recentScores.average() else 0.0
        val recentMax = if (recentScores.isNotEmpty()) recentScores.maxOrNull() ?: 0.0 else 0.0
        
        val quietStart = currentTime - 30.0
        val quietScores = motionBuffer.filter { it.first >= quietStart }.map { it.second }
        val max30 = if (quietScores.isNotEmpty()) quietScores.maxOrNull() ?: 0.0 else 0.0

        if (windowScores.isEmpty()) return emptyMap()
        
        val mean = windowScores.average()

        return mapOf(
            "mean" to mean,
            "recentMean" to recentMean,
            "recentMax" to recentMax,
            "max30" to max30,
            "bpm" to breathingAnalyzer.getBreathingRate(),
            "variability" to breathingAnalyzer.getVariability(),
            "sleepPhase" to breathingAnalyzer.getSleepPhase()
        )
    }
    
    private fun determineState(analysis: Map<String, Any>): SleepState {
        if (analysis.isEmpty()) return currentState
        
        val mean = analysis["mean"] as Double

        val recentMean = analysis["recentMean"] as Double
        val recentMax = analysis["recentMax"] as Double
        val max30 = analysis["max30"] as Double
        val bpm = analysis["bpm"] as Double
        val variability = analysis["variability"] as Double
        
        // 1. Awake / Spasm Priority
        // High motion detected recently
        if (recentMax > HIGH_MOTION_THRESHOLD) {
            // If already AWAKE, stay AWAKE
            if (currentState == SleepState.AWAKE) return SleepState.AWAKE
            
            // Note: If this persists for > 5s, handleTransition will promote to AWAKE.
            // Initially we treat it as SPASM (Transient high motion).
            return SleepState.SPASM
        }
        
        // 2. No Breathing Priority
        // Low motion + No BPM for > 20s
        if (mean < NO_MOTION_THRESHOLD && bpm == 0.0) {
            return SleepState.NO_BREATHING
        }
        
        // 3. REM Sleep
        // Motion 800k-2M OR (BPM 35-50 + Unstable)
        val isRemMotion = mean in REM_SLEEP_RANGE
        val isRemBpm = (bpm in 35.0..50.0) && (variability > breathingAnalyzer.HIGH_VARIABILITY_THRESHOLD)
        if (isRemMotion || isRemBpm) {
            return SleepState.REM_SLEEP
        }
        
        // 4. Deep Sleep
        // Motion 100k-800k OR (BPM 25-35 + Stable) ... PLUS 30s quiet
        val isDeepMotion = mean in DEEP_SLEEP_RANGE
        val isDeepBpm = (bpm in 25.0..35.0) && (variability < breathingAnalyzer.LOW_VARIABILITY_THRESHOLD)
        val isQuiet = max30 < 800_000.0 // Quiet means staying below REM/High thresholds? Let's use 800k.
        
        if ((isDeepMotion || isDeepBpm) && isQuiet) {
            return SleepState.DEEP_SLEEP
        }
        
        // Fallback
        return if (currentState != SleepState.UNKNOWN && currentState != SleepState.CALIBRATING) currentState else SleepState.LIGHT_SLEEP
    }
    
    private fun handleTransition(target: SleepState, currentTime: Double) {
        if (target == currentState) {
            
            // Logic to promote SPASM to AWAKE if sustained
        if (currentState == SleepState.SPASM && target == SleepState.SPASM) {
                if (currentTime - stateStartTime > CONFIRMATION_TIME_AWAKE) {
                    executeTransition(SleepState.AWAKE, currentTime)
                }
            }
            
            pendingState = null
            return
        }
        
        var confirmTime = 3.0
        if (currentState == SleepState.CALIBRATING) {
            confirmTime = 0.0
        } else {
            when (target) {
                SleepState.NO_BREATHING -> confirmTime = CONFIRMATION_TIME_ALARM
                SleepState.AWAKE -> confirmTime = CONFIRMATION_TIME_AWAKE
                SleepState.SPASM -> confirmTime = 0.5 // Quick reaction for transient
                else -> confirmTime = 3.0
            }
        }
        
        if (pendingState != target) {
            pendingState = target
            pendingStateTime = currentTime
        }
        
        if (pendingState == target && (currentTime - pendingStateTime!!) >= confirmTime) {
             executeTransition(target, currentTime)
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
        
        log("Transition: $oldState -> $newState")
    }

    private fun log(msg: String) {
        if (logger != null) logger.i(TAG, msg)
        else Log.i(TAG, msg)
    }

    private fun logError(msg: String, tr: Throwable?) {
        if (logger != null) logger.e(TAG, msg, tr)
        else Log.e(TAG, msg, tr)
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
