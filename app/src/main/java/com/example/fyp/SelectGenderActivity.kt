package com.example.fyp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class SelectGenderActivity : AppCompatActivity() {

    private lateinit var tvHello: TextView
    private lateinit var cardMale: MaterialCardView
    private lateinit var cardFemale: MaterialCardView
    private lateinit var maleIcon: ImageView
    private lateinit var femaleIcon: ImageView
    private lateinit var btnNext: MaterialButton
    private lateinit var overlay: View
    private lateinit var spinner: CircularProgressIndicator

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var gender: String? = null // "male" or "female"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_gender)

        tvHello = findViewById(R.id.tvHello)
        cardMale = findViewById(R.id.cardMale)
        cardFemale = findViewById(R.id.cardFemale)
        maleIcon = cardMale.getChildAt(0) as ImageView
        femaleIcon = cardFemale.getChildAt(0) as ImageView
        btnNext = findViewById(R.id.btnNextGender)
        overlay = findViewById(R.id.loadingOverlay)
        spinner = findViewById(R.id.progress)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Greeting
        val firstName = intent.getStringExtra("firstName")
        if (!firstName.isNullOrBlank()) {
            tvHello.text = "Hello, $firstName"
        } else {
            auth.currentUser?.uid?.let { uid ->
                db.collection("users").document(uid).get()
                    .addOnSuccessListener { doc ->
                        val fn = doc.getString("firstName") ?: "User"
                        tvHello.text = "Hello, $fn"
                    }
            }
        }

        cardMale.setOnClickListener { pickGender("male") }
        cardFemale.setOnClickListener { pickGender("female") }

        btnNext.setOnClickListener { onNext() }
    }

    private fun pickGender(value: String) {
        gender = value
        // reset
        cardMale.strokeWidth = 0
        cardFemale.strokeWidth = 0
        maleIcon.setColorFilter(getColor(R.color.text_muted))
        femaleIcon.setColorFilter(getColor(R.color.text_muted))
        // select
        if (value == "male") {
            cardMale.strokeWidth = 4
            cardMale.strokeColor = getColor(R.color.scin_blue)
            maleIcon.setColorFilter(getColor(R.color.scin_blue))
        } else {
            cardFemale.strokeWidth = 4
            cardFemale.strokeColor = getColor(R.color.red) // ensure you have @color/red
            femaleIcon.setColorFilter(getColor(R.color.red))
        }
    }

    private fun onNext() {
        val uid = auth.currentUser?.uid ?: run {
            Toast.makeText(this, "Not authenticated.", Toast.LENGTH_LONG).show()
            return
        }
        if (gender == null) {
            Toast.makeText(this, "Please select a gender.", Toast.LENGTH_SHORT).show()
            return
        }

        showLoading(true)

        val payload = mapOf(
            "gender" to gender!!,
            "stage" to 2,
            "genderUpdatedAt" to System.currentTimeMillis()
        )

        db.collection("users").document(uid)
            .set(payload, SetOptions.merge())
            .addOnSuccessListener {
                // Final hop: clear the whole stack so back doesn’t return here
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                // no need to manual finish(); flags cleared the task
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to save. Try again.", Toast.LENGTH_LONG).show()
                showLoading(false)
            }
    }

    private fun showLoading(loading: Boolean) {
        overlay.visibility = if (loading) View.VISIBLE else View.GONE
        btnNext.isEnabled = !loading
        cardMale.isEnabled = !loading
        cardFemale.isEnabled = !loading
    }
}
