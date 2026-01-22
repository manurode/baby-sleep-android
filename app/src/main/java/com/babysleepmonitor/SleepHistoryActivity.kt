package com.babysleepmonitor

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.babysleepmonitor.data.SleepHistoryItem
import com.babysleepmonitor.network.ApiClient
import com.babysleepmonitor.ui.SleepHistoryAdapter
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch

/**
 * Sleep History screen displaying list of past sleep sessions.
 * Users can tap on a session to view the detailed report.
 */
class SleepHistoryActivity : AppCompatActivity() {

    private lateinit var rvHistory: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var loadingState: View
    private lateinit var tvAvgSleep: TextView
    private lateinit var tvAvgQuality: TextView
    private lateinit var tvTrend: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var connectionIndicator: View
    private lateinit var tvConnectionStatus: TextView
    
    private lateinit var adapter: SleepHistoryAdapter
    private var serverUrl: String = ""
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sleep_history)
        
        serverUrl = getSavedServerUrl()
        
        initViews()
        setupAdapter()
        setupListeners()
        loadHistory()
    }
    
    private fun initViews() {
        rvHistory = findViewById(R.id.rvHistory)
        emptyState = findViewById(R.id.emptyState)
        loadingState = findViewById(R.id.loadingState)
        tvAvgSleep = findViewById(R.id.tvAvgSleep)
        tvAvgQuality = findViewById(R.id.tvAvgQuality)
        tvTrend = findViewById(R.id.tvTrend)
        btnSettings = findViewById(R.id.btnSettings)
        bottomNav = findViewById(R.id.bottomNav)
        connectionIndicator = findViewById(R.id.connectionIndicator)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        
        // Set current nav item
        bottomNav.selectedItemId = R.id.nav_history
    }
    
    private fun setupAdapter() {
        adapter = SleepHistoryAdapter { item ->
            openReport(item)
        }
        
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = adapter
    }
    
    private fun setupListeners() {
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_live -> {
                    navigateToMonitor()
                    true
                }
                R.id.nav_history -> true // Already here
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }
    
    private fun loadHistory() {
        if (serverUrl.isEmpty()) {
            showEmptyState()
            return
        }
        
        showLoading()
        
        lifecycleScope.launch {
            try {
                val response = ApiClient.getSleepHistory(serverUrl)
                
                if (response.history.isEmpty()) {
                    showEmptyState()
                } else {
                    showHistory(response.history)
                    calculateSummaries(response.history)
                }
                
                updateConnectionStatus(true)
                
            } catch (e: Exception) {
                e.printStackTrace()
                updateConnectionStatus(false)
                showEmptyState()
            }
        }
    }
    
    private fun showLoading() {
        loadingState.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        rvHistory.visibility = View.GONE
    }
    
    private fun showEmptyState() {
        loadingState.visibility = View.GONE
        emptyState.visibility = View.VISIBLE
        rvHistory.visibility = View.GONE
    }
    
    private fun showHistory(items: List<SleepHistoryItem>) {
        loadingState.visibility = View.GONE
        emptyState.visibility = View.GONE
        rvHistory.visibility = View.VISIBLE
        
        adapter.submitList(items)
    }
    
    private fun calculateSummaries(items: List<SleepHistoryItem>) {
        if (items.isEmpty()) return
        
        // Calculate average sleep (last 7 days)
        val recentItems = items.take(7)
        
        if (recentItems.isNotEmpty()) {
            val avgSeconds = recentItems.map { it.duration_seconds }.average()
            val hours = (avgSeconds / 3600).toInt()
            val minutes = ((avgSeconds % 3600) / 60).toInt()
            tvAvgSleep.text = "${hours}h ${minutes}m"
            
            val avgQuality = recentItems.map { it.quality_score }.average().toInt()
            tvAvgQuality.text = "${avgQuality}%"
            
            // Calculate trend (compare this week to previous week if available)
            if (items.size > 7) {
                val thisWeekAvg = items.take(7).map { it.duration_seconds }.average()
                val lastWeekAvg = items.drop(7).take(7).map { it.duration_seconds }.average()
                
                if (lastWeekAvg > 0) {
                    val change = ((thisWeekAvg - lastWeekAvg) / lastWeekAvg * 100).toInt()
                    if (change >= 0) {
                        tvTrend.text = "↑ ${change}%"
                        tvTrend.setTextColor(getColor(R.color.success_color))
                    } else {
                        tvTrend.text = "↓ ${-change}%"
                        tvTrend.setTextColor(getColor(R.color.warning_color))
                    }
                    tvTrend.visibility = View.VISIBLE
                } else {
                    tvTrend.visibility = View.GONE
                }
            } else {
                tvTrend.visibility = View.GONE
            }
        }
    }
    
    private fun updateConnectionStatus(connected: Boolean) {
        if (connected) {
            connectionIndicator.setBackgroundResource(R.drawable.circle_green)
            tvConnectionStatus.text = "Connected"
            tvConnectionStatus.setTextColor(getColor(R.color.teal_500))
        } else {
            connectionIndicator.setBackgroundResource(R.drawable.status_dot_disconnected)
            tvConnectionStatus.text = "Disconnected"
            tvConnectionStatus.setTextColor(getColor(R.color.danger_color))
        }
    }
    
    private fun openReport(item: SleepHistoryItem) {
        val intent = Intent(this, SleepReportActivity::class.java)
        intent.putExtra(SleepReportActivity.EXTRA_SESSION_ID, item.id)
        intent.putExtra(SleepReportActivity.EXTRA_DATE_ISO, item.date_iso)
        startActivity(intent)
    }
    
    private fun navigateToMonitor() {
        val intent = Intent(this, MonitorActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }
    
    private fun getSavedServerUrl(): String {
        val prefs = getSharedPreferences("baby_sleep_prefs", MODE_PRIVATE)
        return prefs.getString("server_url", "") ?: ""
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh on return
        loadHistory()
    }
}
