package com.example.fyp

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

// Uploaded screenshot for reference (path in container):
// /mnt/data/7f57d5ba-df8e-46d3-a173-eac603fef161.png

class SplashActivity : AppCompatActivity() {

    private val phase1Ms = 600L   // white
    private val phase2Ms = 1500L  // logo showing
    private val phase3Ms = 600L   // white again

    private lateinit var auth: FirebaseAuth
    private val db by lazy { FirebaseFirestore.getInstance() }

    // state flags
    private var splashDone = false          // whether the 3-phase splash finished
    private var authCheckDone = false       // whether auth+firestore check finished
    private var routed = false              // whether we've already routed away

    // after auth check completes we store the target Intent here
    private var routingIntent: Intent? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        auth = FirebaseAuth.getInstance()

        // Begin background auth check immediately
        checkLoginAndPrepareRoute()

        // Play the 3-phase splash animation; only route after phases complete OR earlier if both done
        val logo = findViewById<View>(R.id.logo)
        // Phase 1
        mainHandler.postDelayed({
            // Phase 2: show logo
            logo.visibility = View.VISIBLE

            mainHandler.postDelayed({
                // Phase 3: hide logo -> white
                logo.visibility = View.GONE

                mainHandler.postDelayed({
                    // Splash finished
                    splashDone = true
                    // If auth check already prepared a route, execute. Else, ensure fallback to Login
                    if (!routed) {
                        if (authCheckDone && routingIntent != null) {
                            startAndFinish(routingIntent!!)
                        } else {
                            // authCheck not done yet — wait a short timeout for it to complete (avoid infinite wait)
                            waitForAuthThenRouteFallback()
                        }
                    }
                }, phase3Ms)

            }, phase2Ms)

        }, phase1Ms)
    }

    /**
     * Kick off auth + Firestore check. Prepare routingIntent depending on result.
     * Do not start activities here if splash hasn't finished; instead set routingIntent and when splashDone -> start.
     */
    private fun checkLoginAndPrepareRoute() {
        val user = auth.currentUser
        if (user == null) {
            // Not logged in => route to Login
            routingIntent = Intent(this, LoginActivity::class.java)
            authCheckDone = true
            // If splash already done — route now
            if (splashDone && !routed) startAndFinish(routingIntent!!)
            return
        }

        // Validate token quickly before calling Firestore to reduce invalid token cases.
        user.getIdToken(false)
            .addOnSuccessListener {
                // token ok -> proceed to check user doc
                val uid = user.uid
                db.collection("users").document(uid)
                    .get()
                    .addOnSuccessListener { doc ->
                        if (routed) return@addOnSuccessListener
                        val stage = if (doc.exists()) (doc.getLong("stage") ?: 0L).toInt() else 0
                        routingIntent = when {
                            stage <= 0 -> Intent(this, CreateProfileActivity::class.java)
                            stage == 1 -> Intent(this, SelectGenderActivity::class.java).apply {
                                putExtra("firstName", doc.getString("firstName") ?: "")
                            }
                            else -> Intent(this, MainActivity::class.java)
                        }
                        authCheckDone = true
                        if (splashDone && !routed) {
                            startAndFinish(routingIntent!!)
                        }
                    }
                    .addOnFailureListener {
                        // Firestore read failed — fallback to Login
                        if (routed) return@addOnFailureListener
                        routingIntent = Intent(this, LoginActivity::class.java)
                        authCheckDone = true
                        if (splashDone && !routed) startAndFinish(routingIntent!!)
                    }
            }
            .addOnFailureListener {
                // Token fetch failed -> treat as not authenticated and route to Login
                routingIntent = Intent(this, LoginActivity::class.java)
                authCheckDone = true
                if (splashDone && !routed) startAndFinish(routingIntent!!)
            }
    }

    /**
     * If splash finished but auth check still running, wait a short amount for it to complete.
     * If it doesn't complete in WAIT_FOR_AUTH_MS, fall back to Login to avoid blocking user forever.
     */
    private fun waitForAuthThenRouteFallback() {
        val WAIT_FOR_AUTH_MS = 800L
        mainHandler.postDelayed({
            if (routed) return@postDelayed
            if (authCheckDone && routingIntent != null) {
                startAndFinish(routingIntent!!)
            } else {
                // Fallback: open Login
                startAndFinish(Intent(this, LoginActivity::class.java))
            }
        }, WAIT_FOR_AUTH_MS)
    }

    private fun startAndFinish(intent: Intent) {
        if (routed) return
        routed = true
        startActivity(intent.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
