package com.babysleepmonitor

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings Activity for configuring the baby monitor connection and polling settings.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var serverUrlInput: EditText
    private lateinit var pollingIntervalSeekBar: SeekBar
    private lateinit var pollingIntervalValue: TextView
    private lateinit var saveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        serverUrlInput = findViewById(R.id.settingsServerUrl)
        pollingIntervalSeekBar = findViewById(R.id.pollingIntervalSeekBar)
        pollingIntervalValue = findViewById(R.id.pollingIntervalValue)
        saveButton = findViewById(R.id.saveSettingsButton)

        // Load current settings
        loadSettings()

        // Polling interval SeekBar listener
        pollingIntervalSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val interval = (progress + 5) * 100 // 500ms to 3000ms
                pollingIntervalValue.text = "${interval}ms"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Save button click
        saveButton.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }

        // Setup action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("BabySleepMonitor", MODE_PRIVATE)
        
        val serverUrl = prefs.getString("server_url", "http://192.168.1.100:5000")
        serverUrlInput.setText(serverUrl)
        
        val pollingInterval = prefs.getInt("polling_interval", 1500)
        val seekBarProgress = (pollingInterval / 100) - 5 // Convert back to SeekBar progress
        pollingIntervalSeekBar.progress = seekBarProgress.coerceIn(0, 25)
        pollingIntervalValue.text = "${pollingInterval}ms"
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("BabySleepMonitor", MODE_PRIVATE)
        val editor = prefs.edit()
        
        editor.putString("server_url", serverUrlInput.text.toString().trim())
        
        val pollingInterval = (pollingIntervalSeekBar.progress + 5) * 100
        editor.putInt("polling_interval", pollingInterval)
        
        editor.apply()
    }
}
