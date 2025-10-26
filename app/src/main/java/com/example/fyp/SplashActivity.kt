package com.example.fyp

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SplashActivity : AppCompatActivity() {

    private val phase1Ms = 600L   // white
    private val phase2Ms = 1200L  // logo showing
    private val phase3Ms = 600L   // white again

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val logo = findViewById<View>(R.id.logo)

        // Phase 1: white only
        Handler(Looper.getMainLooper()).postDelayed({
            // Phase 2: show logo
            logo.visibility = View.VISIBLE

            Handler(Looper.getMainLooper()).postDelayed({
                // Phase 3: hide logo -> white
                logo.visibility = View.GONE

                Handler(Looper.getMainLooper()).postDelayed({
                    // Navigate to Login
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }, phase3Ms)

            }, phase2Ms)

        }, phase1Ms)
    }
}