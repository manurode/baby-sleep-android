package com.babysleepmonitor

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
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
        // Map Local DB Entity to UI Response Model
        
        // Calculate percentages
        val totalSec = entity.totalSleepSeconds
        val totalMin = totalSec / 60
        
        // Mock breakdown for now since we don't store detailed phases yet
        // In real app, we would store breakdown in DB
        val deepPct = 50
        val lightPct = 30
        val awakePct = 20
        
        val rating = when {
             entity.qualityScore >= 85 -> "Excellent"
             entity.qualityScore >= 70 -> "Good"
             entity.qualityScore >= 50 -> "Fair"
             else -> "Poor"
         }
         
        return SleepReportResponse(
            report_generated_at = Instant.now().epochSecond.toDouble(), // Matches constructor
            summary = SleepSummary(
                total_sleep = "${totalMin / 60}h ${totalMin % 60}m",
                quality_score = entity.qualityScore,
                quality_rating = rating
            ),
            sleep_breakdown = SleepBreakdown(
                deep_sleep = "${(totalMin * 0.5).toInt()}m (50%)",
                light_sleep = "${(totalMin * 0.3).toInt()}m (30%)",
                // awake is calculated from remaining in the UI usually or implicitly. 
                // The data class only has deep/light/desc.
                description = "Sleep was consistent."
            ),
            breathing = BreathingData(
                average_rate_bpm = 32.0, // Placeholder
                status = "Normal"
            ),
            events_summary = EventsSummary(
                wake_ups = entity.wakeUpCount,
                spasms = 0
            ),
            raw_stats = null
        )
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
        val rawStats = report.raw_stats
        if (rawStats != null) {
            val totalMinutes = rawStats.total_sleep_minutes
            tvTotalSleepHours.text = (totalMinutes / 60).toString()
            tvTotalSleepMinutes.text = (totalMinutes % 60).toString()
        } else {
            // Parse from summary if available
            parseAndDisplaySleep(report.summary.total_sleep)
        }
        
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
        
        // Calculate awake time from percentages
        val totalMinutes = rawStats?.total_sleep_minutes ?: 0
        val awakeMinutes = if (awakePercent > 0 && totalMinutes > 0) {
            (totalMinutes * awakePercent / 100)
        } else {
            0
        }
        tvAwakeTime.text = "${awakeMinutes}m (${awakePercent}%)"
        
        // Breathing
        val breathing = report.breathing
        tvBreathingRate.text = String.format("%.1f", breathing.average_rate_bpm)
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
    
    private fun parseAndDisplaySleep(formatted: String) {
        // Parse "1h 55m" format
        try {
            val regex = """(\d+)h\s*(\d+)m""".toRegex()
            val match = regex.find(formatted)
            if (match != null) {
                tvTotalSleepHours.text = match.groupValues[1]
                tvTotalSleepMinutes.text = match.groupValues[2]
            } else {
                tvTotalSleepHours.text = "0"
                tvTotalSleepMinutes.text = "0"
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
