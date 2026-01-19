package com.babysleepmonitor

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button

/**
 * Welcome/Onboarding screen shown when the app is first launched
 * or when the user hasn't connected to a server yet.
 */
class WelcomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check if user has already connected before
        val prefs = getSharedPreferences("BabySleepMonitor", MODE_PRIVATE)
        val hasConnectedBefore = prefs.getBoolean("has_connected", false)
        
        if (hasConnectedBefore) {
            // Skip welcome screen and go directly to server setup
            navigateToServerSetup()
            return
        }
        
        setContentView(R.layout.activity_welcome)
        
        val getStartedButton = findViewById<Button>(R.id.getStartedButton)
        getStartedButton.setOnClickListener {
            navigateToServerSetup()
        }
    }
    
    private fun navigateToServerSetup() {
        val intent = Intent(this, ServerSetupActivity::class.java)
        startActivity(intent)
        finish()
    }
}
