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
    val deepSleepSeconds: Long,
    val lightSleepSeconds: Long,
    val wakeUps: Int,
    val spasmCount: Int,
    val eventsCount: Int,
    val sleepPhase: String,
    val sessionDurationSeconds: Long,
    val avgBreathingBpm: Double
)

class BreathingAnalyzer {
    // Thresholds
    private val BREATH_PEAK_THRESHOLD = 50_000.0
    private val MIN_BREATH_INTERVAL = 1.0
    private val MAX_BREATH_INTERVAL = 5.0
    val LOW_VARIABILITY_THRESHOLD = 0.10
    val HIGH_VARIABILITY_THRESHOLD = 0.20

    // If no new breath peak is detected within this time, BPM decays to 0.
    // This prevents stale BPM values from blocking the NO_BREATHING state.
    private val BPM_DECAY_TIMEOUT = 15.0 // seconds

    private val breathTimestamps = LinkedList<Double>() // last 100
    private val breathIntervals = LinkedList<Double>() // last 50
    private var lastPeakTime: Double? = null
    private var lastBreathDetectedTime: Double? = null // track when we last saw a valid breath
    private var inPeak = false

    fun reset() {
        breathTimestamps.clear()
        breathIntervals.clear()
        lastPeakTime = null
        lastBreathDetectedTime = null
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
                        
                        lastBreathDetectedTime = timestamp
                        return interval
                    } else if (interval > MAX_BREATH_INTERVAL) {
                        breathTimestamps.add(timestamp)
                        if (breathTimestamps.size > 100) breathTimestamps.removeFirst()
                    }
                } else {
                    lastPeakTime = timestamp
                    breathTimestamps.add(timestamp)
                    lastBreathDetectedTime = timestamp
                }
            }
        } else {
            inPeak = false
        }
        return null
    }

    /**
     * Returns true if BPM data has gone stale (no breath peaks in BPM_DECAY_TIMEOUT seconds).
     * When stale, getBreathingRate() returns 0.0 to allow NO_BREATHING detection.
     */
    private fun isStale(currentTime: Double): Boolean {
        val lastBreath = lastBreathDetectedTime ?: return true
        return (currentTime - lastBreath) > BPM_DECAY_TIMEOUT
    }

    fun getBreathingRate(currentTime: Double = Double.MAX_VALUE): Double {
        if (breathIntervals.size < 3) return 0.0
        // If no new breath detected in BPM_DECAY_TIMEOUT, report 0 BPM
        if (currentTime < Double.MAX_VALUE && isStale(currentTime)) return 0.0
        val recent = breathIntervals.takeLast(10)
        val avg = recent.average()
        return if (avg > 0) 60.0 / avg else 0.0
    }

    fun getVariability(currentTime: Double = Double.MAX_VALUE): Double {
        if (breathIntervals.size < 5) return 0.0
        // If stale, variability is meaningless
        if (currentTime < Double.MAX_VALUE && isStale(currentTime)) return 0.0
        val recent = breathIntervals.takeLast(20)
        val mean = recent.average()
        if (mean > 0 && recent.size >= 2) {
            val std = standardDeviation(recent)
            return std / mean
        }
        return 0.0
    }

    fun getSleepPhase(currentTime: Double = Double.MAX_VALUE): String {
        val v = getVariability(currentTime)
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
    private val externalScope: CoroutineScope, // For saving data after UI destruction
    private val context: android.content.Context? = null,
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
    
    val isSessionRunning: Boolean
        get() = sessionStartTime != null
    
    // Metrics
    private var totalSleepSeconds = 0.0
    private var deepSleepSeconds = 0.0
    private var lightSleepSeconds = 0.0
    private var wakeUpCount = 0
    private var spasmCount = 0
    private var lastUpdateTime = 0.0
    private var stateStartTime = 0.0
    
    // Breathing rate accumulator for session average
    private var breathingRateSum = 0.0
    private var breathingRateSamples = 0
    
    // State timeline: records every state transition as (timestampMs, stateValue)
    private val stateTimeline = mutableListOf<Pair<Long, String>>()
    
    // Transition Hysteresis
    private var pendingState: SleepState? = null
    private var pendingStateTime: Double? = null
    
    // Logic - Wake Up Tracking
    private var wasSleeping = false // Tracks if we were in a sleep state before current event

    init {
        // Removed startSession() from init. Must be called explicitly.
    }
    
    fun startSession() {
        log("startSession() called. Initializing new session.")
        sessionStartTime = timeProvider() / 1000.0
        breathingAnalyzer.reset()
        motionBuffer.clear()
        currentState = SleepState.UNKNOWN
        totalSleepSeconds = 0.0
        deepSleepSeconds = 0.0
        lightSleepSeconds = 0.0
        wakeUpCount = 0
        spasmCount = 0
        breathingRateSum = 0.0
        breathingRateSamples = 0
        stateTimeline.clear()
        lastUpdateTime = 0.0
        stateStartTime = sessionStartTime!!
        wasSleeping = false
        pendingState = null
        pendingStateTime = null
    }

    /**
     * Compute a real sleep quality score (0-100) based on session metrics.
     *
     * Factors:
     * - Deep sleep ratio (ideal: 30-50% of total sleep) — weight 40%
     * - Wake-up count (0 = perfect, each one penalizes) — weight 30%
     * - Spasm count (fewer is better) — weight 15%
     * - Total sleep vs session duration ratio — weight 15%
     */
    private fun computeQualityScore(
        totalSleep: Double,
        deepSleep: Double,
        lightSleep: Double,
        wakeUps: Int,
        spasms: Int,
        sessionDurationSec: Double
    ): Int {
        if (totalSleep <= 0 || sessionDurationSec <= 0) return 0

        // 1. Deep sleep ratio score (40 points max)
        val deepRatio = deepSleep / totalSleep
        val deepScore = when {
            deepRatio in 0.30..0.50 -> 40.0          // Ideal range
            deepRatio in 0.20..0.30 -> 30.0          // Acceptable
            deepRatio in 0.50..0.60 -> 35.0          // Slightly too much but ok
            deepRatio in 0.10..0.20 -> 20.0          // Low
            deepRatio > 0.60 -> 25.0                  // Too much deep sleep
            else -> 10.0                               // Very low deep sleep
        }

        // 2. Wake-up penalty (30 points max)
        val wakeUpScore = max(0.0, 30.0 - (wakeUps * 8.0))

        // 3. Spasm penalty (15 points max)
        val spasmScore = max(0.0, 15.0 - (spasms * 3.0))

        // 4. Sleep efficiency: total sleep / session duration (15 points max)
        val efficiency = totalSleep / sessionDurationSec
        val efficiencyScore = min(15.0, efficiency * 15.0)

        val total = (deepScore + wakeUpScore + spasmScore + efficiencyScore).toInt()
        return total.coerceIn(0, 100)
    }

    fun stopSession() {
        val start = sessionStartTime
        if (start != null) {
             val endTime = timeProvider() / 1000.0
             val duration = (endTime - start).toLong()
             log("Session stopped. Duration: $duration s")
             
             // Capture values locally to ensure thread safety inside coroutine
             val finalTotalSleep = totalSleepSeconds
             val finalDeepSleep = deepSleepSeconds
             val finalLightSleep = lightSleepSeconds
             val finalWakeUps = wakeUpCount
             val finalSpasms = spasmCount
             val finalAvgBpm = if (breathingRateSamples > 0) breathingRateSum / breathingRateSamples else 0.0
             val finalTimeline = stateTimeline.joinToString(",") { "${it.first}:${it.second}" }
             val finalQuality = computeQualityScore(
                 finalTotalSleep, finalDeepSleep, finalLightSleep,
                 finalWakeUps, finalSpasms, duration.toDouble()
             )
             
             log("Quality score computed: $finalQuality (deepSleep=${finalDeepSleep.toLong()}s, lightSleep=${finalLightSleep.toLong()}s, wakeUps=$finalWakeUps, spasms=$finalSpasms, avgBpm=${String.format("%.1f", finalAvgBpm)})")

             // Reset state immediately to prevent double-saving or inconsistent state
             sessionStartTime = null
             currentState = SleepState.UNKNOWN

     // Use externalScope to ensure DB operation survives SleepManager/ViewModel destruction
             externalScope.launch(Dispatchers.IO) {
                 val session = SleepSessionEntity(
                     startTime = (start * 1000).toLong(),
                     endTime = (endTime * 1000).toLong(),
                     totalSleepSeconds = finalTotalSleep.toLong(),
                     wakeUpCount = finalWakeUps,
                     qualityScore = finalQuality,
                     deepSleepSeconds = finalDeepSleep.toLong(),
                     lightSleepSeconds = finalLightSleep.toLong(),
                     spasmCount = finalSpasms,
                     avgBreathingBpm = finalAvgBpm,
                     stateTimeline = finalTimeline
                 )
                 try {
                     val id = sleepDao.insertSession(session)
                     log("Session saved to DB successfully. ID: $id, $session")
                     
                     // Visual confirmation on Main Thread
                     launch(Dispatchers.Main) {
                         context?.let {
                             val totalDuration = (session.endTime - session.startTime) / 1000
                             val msg = "Saved #${id}. Duration: ${totalDuration}s (Sleep: ${session.totalSleepSeconds}s, Q: ${session.qualityScore})"
                             android.widget.Toast.makeText(it, msg, android.widget.Toast.LENGTH_LONG).show()
                         }
                     }
                 } catch (e: Exception) {
                     logError("Failed to save session to DB", e)
                     launch(Dispatchers.Main) {
                         context?.let {
                             android.widget.Toast.makeText(it, "Save Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                         }
                     }
                 }
             }
        } else {
             log("stopSession called but sessionStartTime is NULL. Session was not running.")
             externalScope.launch(Dispatchers.Main) {
                 context?.let {
                      android.widget.Toast.makeText(it, "STOP FAILED: Session was null/not started!", android.widget.Toast.LENGTH_LONG).show()
                 }
             }
        }
    }
    
    fun update(motionScore: Double): SleepState {
        val currentTime = timeProvider() / 1000.0
        
        log("Update: motionScore=$motionScore")
        
        // Warm-up check
        if (sessionStartTime != null && (currentTime - sessionStartTime!!) < WARMUP_DURATION) {
             currentState = SleepState.CALIBRATING
             // Only log once per second or if state changes to avoid spam? 
             // For now, logging every update might be too much, but requested.
             log("StateLogic: Calibrating... ${(currentTime - sessionStartTime!!)}")
             return currentState
        }

        // Buffer
        motionBuffer.add(currentTime to motionScore)
        cleanBuffer(currentTime)
        
        // Breathing
        breathingAnalyzer.processMotion(motionScore, currentTime)
        
        // Analysis
        val analysis = analyzeBuffer(currentTime)
        log("Analysis: $analysis")
        
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
            "bpm" to breathingAnalyzer.getBreathingRate(currentTime),
            "variability" to breathingAnalyzer.getVariability(currentTime),
            "sleepPhase" to breathingAnalyzer.getSleepPhase(currentTime)
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
            if (currentState == SleepState.AWAKE) {
                log("StateLogic: RecentMax ($recentMax) > HIGH -> Maintaining AWAKE")
                return SleepState.AWAKE
            }
            
            // Note: If this persists for > 5s, handleTransition will promote to AWAKE.
            // Initially we treat it as SPASM (Transient high motion).
            log("StateLogic: RecentMax ($recentMax) > HIGH ($HIGH_MOTION_THRESHOLD) -> SPASM")
            return SleepState.SPASM
        }
        
        // 2. No Breathing Priority
        // Low motion + No BPM for > 20s
        if (mean < NO_MOTION_THRESHOLD && bpm == 0.0) {
            log("StateLogic: Mean ($mean) < LOW + No BPM -> NO_BREATHING")
            return SleepState.NO_BREATHING
        }
        
        // 3. REM Sleep
        // Motion 800k-2M OR (BPM 35-50 + Unstable)
        val isRemMotion = mean in REM_SLEEP_RANGE
        val isRemBpm = (bpm in 35.0..50.0) && (variability > breathingAnalyzer.HIGH_VARIABILITY_THRESHOLD)
        if (isRemMotion || isRemBpm) {
            log("StateLogic: REM detected (Motion: $isRemMotion, BPM: $isRemBpm)")
            return SleepState.REM_SLEEP
        }
        
        // 4. Deep Sleep
        // Motion 100k-800k OR (BPM 25-35 + Stable) ... PLUS 30s quiet
        val isDeepMotion = mean in DEEP_SLEEP_RANGE
        val isDeepBpm = (bpm in 25.0..35.0) && (variability < breathingAnalyzer.LOW_VARIABILITY_THRESHOLD)
        val isQuiet = max30 < 800_000.0 // Quiet means staying below REM/High thresholds? Let's use 800k.
        
        if ((isDeepMotion || isDeepBpm) && isQuiet) {
            log("StateLogic: Deep Sleep (Motion: $isDeepMotion, BPM: $isDeepBpm, Quiet: $isQuiet)")
            return SleepState.DEEP_SLEEP
        }
        
        // Fallback — log diagnostic info showing why we didn't match any specific state
        log("StateLogic: Fallback to LIGHT_SLEEP " +
            "(mean=${String.format("%.0f", mean)} noMotion=${mean < NO_MOTION_THRESHOLD}, " +
            "bpm=${String.format("%.1f", bpm)} bpmZero=${bpm == 0.0}, " +
            "deepRange=${isDeepMotion}, remRange=${mean in REM_SLEEP_RANGE}, " +
            "recentMax=${String.format("%.0f", recentMax)}, max30=${String.format("%.0f", max30)})")
        return SleepState.LIGHT_SLEEP
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
            log("Transition: PendingState changed to $target. Waiting $confirmTime s")
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
        
        // Record state change in timeline
        val timestampMs = (currentTime * 1000).toLong()
        stateTimeline.add(timestampMs to newState.value)
        
        // Update sleeping status
        val isSleeping = newState == SleepState.DEEP_SLEEP || newState == SleepState.LIGHT_SLEEP || newState == SleepState.REM_SLEEP
        
        // Track spasms
        if (newState == SleepState.SPASM) {
            spasmCount++
            log("Spasm detected! Count: $spasmCount")
        }
        
        // Check for Wake Up: Transition from Sleep (directly or via Spasm) to Awake
        if (newState == SleepState.AWAKE) {
             if (wasSleeping) {
                 wakeUpCount++
                 log("Wake up detected! Count: $wakeUpCount")
                 wasSleeping = false 
             }
        } else if (isSleeping) {
            wasSleeping = true
        } else if (newState == SleepState.NO_BREATHING) {
            wasSleeping = false // Reset? Or keep? Usually alarm.
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
            when (currentState) {
                SleepState.DEEP_SLEEP -> {
                    totalSleepSeconds += delta
                    deepSleepSeconds += delta
                }
                SleepState.LIGHT_SLEEP, SleepState.REM_SLEEP -> {
                    totalSleepSeconds += delta
                    lightSleepSeconds += delta
                }
                else -> { /* awake, spasm, etc. — not counted as sleep */ }
            }
        }
        lastUpdateTime = currentTime
        
        // Accumulate breathing rate samples for average
        val bpm = breathingAnalyzer.getBreathingRate(currentTime)
        if (bpm > 0) {
            breathingRateSum += bpm
            breathingRateSamples++
        }
    }
    
    fun getStats(): SleepStats {
        val currentTime = timeProvider() / 1000.0
        val duration = if (sessionStartTime != null) {
            (currentTime - sessionStartTime!!).toLong()
        } else {
            0L
        }
        val avgBpm = if (breathingRateSamples > 0) breathingRateSum / breathingRateSamples else 0.0
        val qualityScore = computeQualityScore(
            totalSleepSeconds, deepSleepSeconds, lightSleepSeconds,
            wakeUpCount, spasmCount, duration.toDouble()
        )
        
        return SleepStats(
            currentState = currentState,
            breathingDetected = currentState == SleepState.DEEP_SLEEP || currentState == SleepState.LIGHT_SLEEP,
            breathingRateBpm = breathingAnalyzer.getBreathingRate(currentTime),
            sleepQualityScore = qualityScore,
            totalSleepSeconds = totalSleepSeconds.toLong(),
            deepSleepSeconds = deepSleepSeconds.toLong(),
            lightSleepSeconds = lightSleepSeconds.toLong(),
            wakeUps = wakeUpCount,
            spasmCount = spasmCount,
            eventsCount = 0,
            sleepPhase = breathingAnalyzer.getSleepPhase(currentTime),
            sessionDurationSeconds = duration,
            avgBreathingBpm = avgBpm
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
