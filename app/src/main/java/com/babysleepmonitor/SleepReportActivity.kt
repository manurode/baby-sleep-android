package com.babysleepmonitor

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.babysleepmonitor.data.SleepReportResponse
import com.babysleepmonitor.data.db.SleepSessionEntity
import com.babysleepmonitor.data.SleepSummary
import com.babysleepmonitor.data.SleepBreakdown
import com.babysleepmonitor.data.BreathingData
import com.babysleepmonitor.data.EventsSummary
import com.babysleepmonitor.BabySleepMonitorApp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Detailed Sleep Report screen showing comprehensive analysis of a single sleep session.
 */
class SleepReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_DATE_ISO = "date_iso"
    }
    
    // Views
    private lateinit var btnBack: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var tvDate: TextView
    private lateinit var tvQualityScore: TextView
    private lateinit var tvQualityRating: TextView
    private lateinit var tvQualityComparison: TextView
    private lateinit var progressQuality: ProgressBar
    
    private lateinit var tvTotalSleepHours: TextView
    private lateinit var tvTotalSleepMinutes: TextView
    private lateinit var tvWakeUps: TextView
    
    // Timeline
    private lateinit var timelineBarsContainer: LinearLayout
    private lateinit var timelineLabelsContainer: LinearLayout
    
    private lateinit var barDeepSleep: View
    private lateinit var barLightSleep: View
    private lateinit var barAwake: View
    private lateinit var tvDeepSleep: TextView
    private lateinit var tvLightSleep: TextView
    private lateinit var tvAwakeTime: TextView
    
    private lateinit var tvBreathingRate: TextView
    private lateinit var tvBreathingStatus: TextView
    private lateinit var tvSpasms: TextView
    private lateinit var tvInsight: TextView
    
    private lateinit var loadingOverlay: FrameLayout
    
    private var sessionId: String = ""
    private var dateIso: String = ""
    private var serverUrl: String = ""
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sleep_report)
        
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""
        dateIso = intent.getStringExtra(EXTRA_DATE_ISO) ?: ""
        serverUrl = getSavedServerUrl()
        
        initViews()
        setupListeners()
        loadReport()
    }
    
    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        btnShare = findViewById(R.id.btnShare)
        tvDate = findViewById(R.id.tvDate)
        tvQualityScore = findViewById(R.id.tvQualityScore)
        tvQualityRating = findViewById(R.id.tvQualityRating)
        tvQualityComparison = findViewById(R.id.tvQualityComparison)
        progressQuality = findViewById(R.id.progressQuality)
        
        tvTotalSleepHours = findViewById(R.id.tvTotalSleepHours)
        tvTotalSleepMinutes = findViewById(R.id.tvTotalSleepMinutes)
        tvWakeUps = findViewById(R.id.tvWakeUps)
        
        // Timeline
        timelineBarsContainer = findViewById(R.id.timelineBarsContainer)
        timelineLabelsContainer = findViewById(R.id.timelineLabelsContainer)
        
        barDeepSleep = findViewById(R.id.barDeepSleep)
        barLightSleep = findViewById(R.id.barLightSleep)
        barAwake = findViewById(R.id.barAwake)
        tvDeepSleep = findViewById(R.id.tvDeepSleep)
        tvLightSleep = findViewById(R.id.tvLightSleep)
        tvAwakeTime = findViewById(R.id.tvAwakeTime)
        
        tvBreathingRate = findViewById(R.id.tvBreathingRate)
        tvBreathingStatus = findViewById(R.id.tvBreathingStatus)
        tvSpasms = findViewById(R.id.tvSpasms)
        tvInsight = findViewById(R.id.tvInsight)
        
        loadingOverlay = findViewById(R.id.loadingOverlay)
        
        // Set initial date
        formatAndSetDate()
    }
    
    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
        }
        
        btnShare.setOnClickListener {
            shareReport()
        }
    }
    
    private fun formatAndSetDate() {
        try {
            // Parse ISO date like "2026-01-21T20:30:00"
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val outputFormat = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
            
            val date = inputFormat.parse(dateIso)
            if (date != null) {
                // Check if today
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
                
                val displayDate = if (today == parsed) {
                    "Today, ${SimpleDateFormat("MMM d", Locale.getDefault()).format(date)}"
                } else {
                    outputFormat.format(date)
                }
                tvDate.text = displayDate
            }
        } catch (e: Exception) {
            tvDate.text = dateIso.split("T").firstOrNull() ?: "Unknown"
        }
    }
    
    private fun loadReport() {
        if (sessionId.isEmpty()) {
            showError()
            return
        }
        
        showLoading()
        
        lifecycleScope.launch {
            try {
                // Fetch from Local DB
                val app = application as BabySleepMonitorApp
                val session = app.database.sleepDao().getSessionById(sessionId.toLong())
                
                if (session != null) {
                    val report = mapEntityToReport(session)
                    displayReport(report)
                    displayTimeline(session)
                } else {
                    showError()
                }
                hideLoading()
            } catch (e: Exception) {
                e.printStackTrace()
                hideLoading()
                showError()
            }
        }
    }
    
    private fun mapEntityToReport(entity: SleepSessionEntity): SleepReportResponse {
        // Map Local DB Entity to UI Response Model using REAL data
        
        val sessionDurationSec = (entity.endTime - entity.startTime) / 1000
        val totalSleepSec = entity.totalSleepSeconds
        val deepSleepSec = entity.deepSleepSeconds
        val lightSleepSec = entity.lightSleepSeconds
        val awakeSec = maxOf(0L, sessionDurationSec - totalSleepSec)
        
        // Calculate real percentages based on session duration
        val totalForPct = if (sessionDurationSec > 0) sessionDurationSec else 1L
        val deepPct = ((deepSleepSec * 100) / totalForPct).toInt().coerceIn(0, 100)
        val lightPct = ((lightSleepSec * 100) / totalForPct).toInt().coerceIn(0, 100)
        val awakePct = (100 - deepPct - lightPct).coerceIn(0, 100)
        
        val deepMin = (deepSleepSec / 60).toInt()
        val lightMin = (lightSleepSec / 60).toInt()
        val awakeMin = (awakeSec / 60).toInt()
        
        val totalMin = totalSleepSec / 60
        
        val rating = when {
            entity.qualityScore >= 85 -> "Excellent"
            entity.qualityScore >= 70 -> "Good"
            entity.qualityScore >= 50 -> "Fair"
            else -> "Poor"
        }
        
        // Determine breathing status
        val breathingStatus = when {
            entity.avgBreathingBpm <= 0 -> "unknown"
            entity.avgBreathingBpm < 25 -> "slow"
            entity.avgBreathingBpm > 60 -> "fast"
            else -> "normal"
        }
        
        // Generate a meaningful insight
        val insight = generateInsight(entity, deepPct, lightPct, awakePct)
         
        return SleepReportResponse(
            report_generated_at = Instant.now().epochSecond.toDouble(),
            summary = SleepSummary(
                total_sleep = "${totalMin / 60}h ${totalMin % 60}m",
                quality_score = entity.qualityScore,
                quality_rating = rating
            ),
            sleep_breakdown = SleepBreakdown(
                deep_sleep = "${deepMin}m (${deepPct}%)",
                light_sleep = "${lightMin}m (${lightPct}%)",
                description = insight
            ),
            breathing = BreathingData(
                average_rate_bpm = entity.avgBreathingBpm,
                status = breathingStatus
            ),
            events_summary = EventsSummary(
                wake_ups = entity.wakeUpCount,
                spasms = entity.spasmCount
            ),
            raw_stats = null
        )
    }
    
    /**
     * Generate a dynamic, meaningful insight based on actual session data.
     */
    private fun generateInsight(
        entity: SleepSessionEntity,
        deepPct: Int,
        lightPct: Int,
        awakePct: Int
    ): String {
        val parts = mutableListOf<String>()
        
        // Quality assessment
        when {
            entity.qualityScore >= 85 -> parts.add("Excellent sleep session! Baby rested very well with minimal disruptions.")
            entity.qualityScore >= 70 -> parts.add("Good sleep session overall. Baby had a restful night.")
            entity.qualityScore >= 50 -> parts.add("Moderate sleep quality. There is room for improvement.")
            else -> parts.add("Sleep quality was below average this session.")
        }
        
        // Deep sleep analysis
        when {
            deepPct >= 40 -> parts.add("Deep sleep was above average at ${deepPct}%, which is great for physical growth and development.")
            deepPct >= 25 -> parts.add("Good balance of deep sleep (${deepPct}%) — supports healthy brain development.")
            deepPct >= 10 -> parts.add("Deep sleep was on the lower side (${deepPct}%). Consider adjusting the sleep environment.")
            else -> parts.add("Very little deep sleep detected (${deepPct}%). The room may be too warm or noisy.")
        }
        
        // Wake-ups
        when {
            entity.wakeUpCount == 0 -> parts.add("No wake-ups detected — baby slept through without interruption.")
            entity.wakeUpCount == 1 -> parts.add("Only 1 brief wake-up was detected during the session.")
            entity.wakeUpCount <= 3 -> parts.add("${entity.wakeUpCount} wake-ups occurred, which is normal for this age.")
            else -> parts.add("${entity.wakeUpCount} wake-ups detected — baby may need a more consistent sleep routine.")
        }
        
        // Spasms
        if (entity.spasmCount > 0) {
            parts.add("${entity.spasmCount} movement spasm${if (entity.spasmCount > 1) "s" else ""} detected, which is typical in infant sleep.")
        }
        
        // Breathing
        if (entity.avgBreathingBpm > 0) {
            val bpmStr = String.format("%.0f", entity.avgBreathingBpm)
            when {
                entity.avgBreathingBpm in 25.0..45.0 -> parts.add("Average breathing rate of ${bpmStr} BPM is within the normal range.")
                entity.avgBreathingBpm < 25 -> parts.add("Breathing rate was slightly low at ${bpmStr} BPM — monitor closely.")
                else -> parts.add("Breathing rate was elevated at ${bpmStr} BPM — may indicate restlessness.")
            }
        }
        
        return parts.joinToString(" ")
    }
    
    private fun displayReport(report: SleepReportResponse) {
        // Quality Score
        val score = report.summary.quality_score
        tvQualityScore.text = score.toString()
        tvQualityRating.text = report.summary.quality_rating
        progressQuality.progress = score
        
        // Set quality color
        val qualityColor = Color.parseColor(report.summary.getQualityColorHex())
        tvQualityScore.setTextColor(qualityColor)
        
        // Comparison text
        val percentile = when {
            score >= 85 -> "Better than 90% of similar nights."
            score >= 70 -> "Better than 75% of similar nights."
            score >= 50 -> "Average compared to similar nights."
            else -> "Below average for similar nights."
        }
        tvQualityComparison.text = percentile
        
        // Total Sleep
        parseAndDisplaySleep(report.summary.total_sleep)
        
        // Wake ups
        tvWakeUps.text = report.events_summary.wake_ups.toString()
        
        // Sleep stages
        val breakdown = report.sleep_breakdown
        val deepPercent = breakdown.getDeepSleepPercent()
        val lightPercent = breakdown.getLightSleepPercent()
        val awakePercent = breakdown.getAwakePercent()
        
        updateSleepStagesBar(deepPercent, lightPercent, awakePercent)
        
        tvDeepSleep.text = breakdown.deep_sleep
        tvLightSleep.text = breakdown.light_sleep
        val deepMin = breakdown.getDeepSleepMinutes()
        val lightMin = breakdown.getLightSleepMinutes()
        val awakeMin = run {
            val regex = """(\d+)h\s*(\d+)m""".toRegex()
            val match = regex.find(report.summary.total_sleep)
            val totalSleepMin = if (match != null) match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt() else 0
            // Session duration ≈ total sleep / sleep efficiency. Use the percentage.
            val sessionMin = if (awakePercent < 100) (totalSleepMin * 100) / (100 - awakePercent) else totalSleepMin
            maxOf(0, sessionMin - totalSleepMin)
        }
        tvAwakeTime.text = "${awakeMin}m (${awakePercent}%)"
        
        // Breathing
        val breathing = report.breathing
        if (breathing.average_rate_bpm > 0) {
            tvBreathingRate.text = String.format("%.1f", breathing.average_rate_bpm)
        } else {
            tvBreathingRate.text = "--"
        }
        tvBreathingStatus.text = breathing.status.replaceFirstChar { it.uppercase() }
        
        // Set breathing status color
        when (breathing.status.lowercase()) {
            "normal" -> {
                tvBreathingStatus.setTextColor(Color.parseColor("#22C55E"))
                tvBreathingStatus.setBackgroundResource(R.drawable.badge_success_bg)
            }
            "slow" -> {
                tvBreathingStatus.setTextColor(Color.parseColor("#F59E0B"))
                tvBreathingStatus.setBackgroundResource(R.drawable.badge_warning_bg)
            }
            else -> {
                tvBreathingStatus.setTextColor(Color.parseColor("#6B7280"))
                tvBreathingStatus.setBackgroundResource(R.drawable.badge_neutral_bg)
            }
        }
        
        // Spasms
        tvSpasms.text = report.events_summary.spasms.toString()
        
        // Insight
        tvInsight.text = breakdown.description.ifEmpty {
            "Sleep session completed. Review the details above for more information."
        }
    }
    
    /**
     * Display the Sleep Timeline chart from the serialized state timeline.
     * 
     * The timeline is stored as "timestamp1:state1,timestamp2:state2,..." 
     * Each bar represents a time segment, colored by its sleep state.
     */
    private fun displayTimeline(entity: SleepSessionEntity) {
        timelineBarsContainer.removeAllViews()
        timelineLabelsContainer.removeAllViews()
        
        val timelineStr = entity.stateTimeline
        if (timelineStr.isBlank()) {
            // No timeline data — show placeholder message
            val tv = TextView(this).apply {
                text = "No timeline data available for this session"
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 12f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }
            timelineBarsContainer.addView(tv)
            return
        }
        
        // Parse timeline entries
        val entries = timelineStr.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size >= 2) {
                val ts = parts[0].toLongOrNull()
                val state = parts.drop(1).joinToString(":") // Handle state values with ':'
                if (ts != null) ts to state else null
            } else null
        }.sortedBy { it.first }
        
        if (entries.isEmpty()) return
        
        val sessionStartMs = entity.startTime
        val sessionEndMs = entity.endTime
        val sessionDurationMs = maxOf(sessionEndMs - sessionStartMs, 1L)
        
        // Build segments: each segment is (startMs, endMs, state)
        data class Segment(val startMs: Long, val endMs: Long, val state: String)
        
        val segments = mutableListOf<Segment>()
        for (i in entries.indices) {
            val startMs = entries[i].first
            val endMs = if (i + 1 < entries.size) entries[i + 1].first else sessionEndMs
            val state = entries[i].second
            segments.add(Segment(startMs, endMs, state))
        }
        
        // Map states to bar height fractions and colors
        fun stateToColor(state: String): Int {
            return when (state) {
                "deep_sleep" -> Color.parseColor("#3B82F6")   // Blue
                "light_sleep", "rem_sleep" -> Color.parseColor("#818CF8") // Indigo
                "awake", "spasm" -> Color.parseColor("#FDBA74")           // Orange
                "calibrating", "unknown" -> Color.parseColor("#D1D5DB")   // Gray
                "no_breathing" -> Color.parseColor("#EF4444")              // Red
                else -> Color.parseColor("#D1D5DB")
            }
        }
        
        fun stateToHeightFraction(state: String): Float {
            return when (state) {
                "deep_sleep" -> 0.85f
                "light_sleep", "rem_sleep" -> 0.50f
                "awake" -> 0.20f
                "spasm" -> 0.15f
                "calibrating", "unknown" -> 0.10f
                "no_breathing" -> 0.15f
                else -> 0.10f
            }
        }
        
        // Bucket into N bars (24 bars like the spec)
        val numBars = 24
        val bucketDurationMs = sessionDurationMs.toDouble() / numBars
        
        data class BarInfo(val color: Int, val heightFraction: Float)
        
        val bars = (0 until numBars).map { i ->
            val bucketStart = sessionStartMs + (i * bucketDurationMs).toLong()
            val bucketEnd = sessionStartMs + ((i + 1) * bucketDurationMs).toLong()
            
            // Find dominant state in this bucket
            var bestState = "unknown"
            var bestDuration = 0L
            
            val stateMap = mutableMapOf<String, Long>()
            for (seg in segments) {
                val overlapStart = maxOf(seg.startMs, bucketStart)
                val overlapEnd = minOf(seg.endMs, bucketEnd)
                if (overlapStart < overlapEnd) {
                    val duration = overlapEnd - overlapStart
                    stateMap[seg.state] = (stateMap[seg.state] ?: 0L) + duration
                }
            }
            
            for ((state, duration) in stateMap) {
                if (duration > bestDuration) {
                    bestDuration = duration
                    bestState = state
                }
            }
            
            BarInfo(stateToColor(bestState), stateToHeightFraction(bestState))
        }
        
        // Create bar views
        val barContainerHeight = 160 // dp (matches layout)
        val density = resources.displayMetrics.density
        val barWidthPx = (4 * density).toInt() // thin bars
        val gapPx = (1.5 * density).toInt()
        
        for (bar in bars) {
            val barView = View(this).apply {
                val shape = GradientDrawable()
                shape.setColor(bar.color)
                shape.cornerRadii = floatArrayOf(
                    3 * density, 3 * density,  // top-left
                    3 * density, 3 * density,  // top-right
                    0f, 0f, 0f, 0f            // bottom
                )
                background = shape
            }
            
            val heightPx = (barContainerHeight * density * bar.heightFraction).toInt()
            val params = LinearLayout.LayoutParams(0, heightPx, 1f).apply {
                marginEnd = gapPx
            }
            barView.layoutParams = params
            timelineBarsContainer.addView(barView)
        }
        
        // Add time labels
        val sdf = SimpleDateFormat("h a", Locale.getDefault())
        val labelCount = 6
        val labelInterval = sessionDurationMs / (labelCount - 1)
        
        for (i in 0 until labelCount) {
            val labelMs = sessionStartMs + (i * labelInterval)
            val labelText = sdf.format(Date(labelMs)).uppercase()
            
            val tv = TextView(this).apply {
                text = labelText
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 10f
                gravity = if (i == 0) Gravity.START else if (i == labelCount - 1) Gravity.END else Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            timelineLabelsContainer.addView(tv)
        }
    }
    
    private fun parseAndDisplaySleep(formatted: String) {
        // Parse "1h 55m" format
        try {
            val regex = """(\d+)h\s*(\d+)m""".toRegex()
            val match = regex.find(formatted)
            if (match != null) {
                tvTotalSleepHours.text = match.groupValues[1]
                tvTotalSleepMinutes.text = match.groupValues[2]
            } else {
                // Try minutes only "45m"
                val minRegex = """(\d+)m""".toRegex()
                val minMatch = minRegex.find(formatted)
                if (minMatch != null) {
                    tvTotalSleepHours.text = "0"
                    tvTotalSleepMinutes.text = minMatch.groupValues[1]
                } else {
                    tvTotalSleepHours.text = "0"
                    tvTotalSleepMinutes.text = "0"
                }
            }
        } catch (e: Exception) {
            tvTotalSleepHours.text = "0"
            tvTotalSleepMinutes.text = "0"
        }
    }
    
    private fun updateSleepStagesBar(deep: Int, light: Int, awake: Int) {
        val parent = barDeepSleep.parent as? LinearLayout ?: return
        
        // Calculate weights (minimum 1 to ensure visibility)
        val deepWeight = maxOf(deep, 1).toFloat()
        val lightWeight = maxOf(light, 1).toFloat()
        val awakeWeight = maxOf(awake, 1).toFloat()
        
        val deepParams = barDeepSleep.layoutParams as LinearLayout.LayoutParams
        deepParams.weight = deepWeight
        barDeepSleep.layoutParams = deepParams
        
        val lightParams = barLightSleep.layoutParams as LinearLayout.LayoutParams
        lightParams.weight = lightWeight
        barLightSleep.layoutParams = lightParams
        
        val awakeParams = barAwake.layoutParams as LinearLayout.LayoutParams
        awakeParams.weight = awakeWeight
        barAwake.layoutParams = awakeParams
    }
    
    private fun shareReport() {
        val shareText = buildString {
            appendLine("🌙 Baby Sleep Report")
            appendLine("📅 ${tvDate.text}")
            appendLine()
            appendLine("Quality Score: ${tvQualityScore.text} - ${tvQualityRating.text}")
            appendLine("Total Sleep: ${tvTotalSleepHours.text}h ${tvTotalSleepMinutes.text}m")
            appendLine("Wake Ups: ${tvWakeUps.text}")
            appendLine()
            appendLine("Sleep Stages:")
            appendLine("• Deep Sleep: ${tvDeepSleep.text}")
            appendLine("• Light Sleep: ${tvLightSleep.text}")
            appendLine()
            appendLine("Breathing: ${tvBreathingRate.text} BPM (${tvBreathingStatus.text})")
            appendLine("Spasms: ${tvSpasms.text}")
        }
        
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, shareText)
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "Share Sleep Report"))
    }
    
    private fun showLoading() {
        loadingOverlay.visibility = View.VISIBLE
    }
    
    private fun hideLoading() {
        loadingOverlay.visibility = View.GONE
    }
    
    private fun showError() {
        // Could show a toast or error state
        tvQualityScore.text = "--"
        tvQualityRating.text = "Error"
        tvQualityComparison.text = "Could not load report"
    }
    
    private fun getSavedServerUrl(): String {
        val prefs = getSharedPreferences("baby_sleep_prefs", MODE_PRIVATE)
        return prefs.getString("server_url", "") ?: ""
    }
}
