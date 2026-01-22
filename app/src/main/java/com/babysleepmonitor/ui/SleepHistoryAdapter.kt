package com.babysleepmonitor.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.babysleepmonitor.R
import com.babysleepmonitor.data.SleepHistoryItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for displaying sleep history items.
 */
class SleepHistoryAdapter(
    private val onItemClick: (SleepHistoryItem) -> Unit
) : ListAdapter<SleepHistoryItem, SleepHistoryAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sleep_history, parent, false)
        return ViewHolder(view, onItemClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val onItemClick: (SleepHistoryItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val dateBadge: FrameLayout = itemView.findViewById(R.id.dateBadge)
        private val tvDayName: TextView = itemView.findViewById(R.id.tvDayName)
        private val tvDayNumber: TextView = itemView.findViewById(R.id.tvDayNumber)
        private val tvQualityLabel: TextView = itemView.findViewById(R.id.tvQualityLabel)
        private val qualityDot: View = itemView.findViewById(R.id.qualityDot)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        private val tvQualityScore: TextView = itemView.findViewById(R.id.tvQualityScore)
        
        fun bind(item: SleepHistoryItem) {
            // Parse date
            val (dayName, dayNumber) = parseDateInfo(item.timestamp, item.date_iso)
            tvDayName.text = dayName
            tvDayNumber.text = dayNumber
            
            // Set date badge background color for today
            val isToday = dayName.equals("TODAY", ignoreCase = true)
            if (isToday) {
                val drawable = GradientDrawable()
                drawable.shape = GradientDrawable.RECTANGLE
                drawable.cornerRadius = 12 * itemView.context.resources.displayMetrics.density
                drawable.setColor(Color.parseColor("#EFF6FF")) // Light blue for today
                dateBadge.background = drawable
                tvDayName.setTextColor(Color.parseColor("#3B82F6"))
                tvDayNumber.setTextColor(Color.parseColor("#3B82F6"))
            } else {
                val drawable = GradientDrawable()
                drawable.shape = GradientDrawable.RECTANGLE
                drawable.cornerRadius = 12 * itemView.context.resources.displayMetrics.density
                drawable.setColor(Color.parseColor("#F3F4F6"))
                dateBadge.background = drawable
                tvDayName.setTextColor(Color.parseColor("#6B7280"))
                tvDayNumber.setTextColor(Color.parseColor("#374151"))
            }
            
            // Quality label and color
            tvQualityLabel.text = getQualityTitle(item.quality_rating)
            
            val qualityColor = Color.parseColor(item.getQualityColorHex())
            tvQualityScore.setTextColor(qualityColor)
            
            val dotDrawable = GradientDrawable()
            dotDrawable.shape = GradientDrawable.OVAL
            dotDrawable.setColor(Color.parseColor(item.getQualityDotColor()))
            qualityDot.background = dotDrawable
            
            // Duration
            tvDuration.text = "${item.duration_formatted} duration"
            
            // Quality score
            tvQualityScore.text = "${item.quality_score}%"
            
            // Click listener
            itemView.setOnClickListener { onItemClick(item) }
        }
        
        private fun parseDateInfo(timestamp: Double, dateIso: String): Pair<String, String> {
            try {
                val date = Date((timestamp * 1000).toLong())
                val cal = Calendar.getInstance()
                val today = Calendar.getInstance()
                
                cal.time = date
                
                // Check if it's today
                if (cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                    cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
                    return Pair("TODAY", cal.get(Calendar.DAY_OF_MONTH).toString())
                }
                
                // Check if it's yesterday
                today.add(Calendar.DAY_OF_YEAR, -1)
                if (cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                    cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
                    return Pair("YEST", cal.get(Calendar.DAY_OF_MONTH).toString())
                }
                
                // Otherwise show day of week
                val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
                return Pair(dayFormat.format(date).uppercase(), cal.get(Calendar.DAY_OF_MONTH).toString())
                
            } catch (e: Exception) {
                // Fallback to parsing ISO date
                try {
                    val parts = dateIso.split("T")[0].split("-")
                    if (parts.size == 3) {
                        val day = parts[2].toIntOrNull() ?: 0
                        return Pair("", day.toString())
                    }
                } catch (e2: Exception) {
                    // Ignore
                }
            }
            return Pair("", "")
        }
        
        private fun getQualityTitle(rating: String): String {
            return when (rating.lowercase()) {
                "excellent" -> "Excellent Sleep"
                "good" -> "Good Sleep"
                "fair" -> "Fair Night"
                "poor" -> "Restless Night"
                "very poor" -> "Difficult Night"
                else -> rating
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SleepHistoryItem>() {
        override fun areItemsTheSame(oldItem: SleepHistoryItem, newItem: SleepHistoryItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SleepHistoryItem, newItem: SleepHistoryItem): Boolean {
            return oldItem == newItem
        }
    }
}
