package com.example.fyp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Calendar

class CreateProfileActivity : AppCompatActivity() {

    // Avatar preview + picker
    private lateinit var holderAvatar: View                 // FrameLayout (click target)
    private lateinit var cardAvatar: MaterialCardView       // circular card (clips child)
    private lateinit var ivAvatar: ImageView                // image inside circular card

    private lateinit var avatarOverlay: View                // full-screen dim overlay
    private lateinit var pickA1: View
    private lateinit var pickA2: View
    private lateinit var pickA3: View
    private lateinit var pickA4: View
    private lateinit var btnClosePicker: MaterialButton
    private var selectedAvatar: String? = null              // "avatar1".."avatar4"

    // Inputs
    private lateinit var tilFirst: TextInputLayout
    private lateinit var tilLast: TextInputLayout
    private lateinit var tilCity: TextInputLayout
    private lateinit var tilYear: TextInputLayout
    private lateinit var tilMonth: TextInputLayout
    private lateinit var tilDay: TextInputLayout

    private lateinit var etFirst: TextInputEditText
    private lateinit var etLast: TextInputEditText
    private lateinit var etCity: MaterialAutoCompleteTextView // <-- FIXED TYPE
    private lateinit var etYear: TextInputEditText
    private lateinit var etMonth: TextInputEditText
    private lateinit var etDay: TextInputEditText

    // Actions / overlays
    private lateinit var btnNext: MaterialButton
    private lateinit var loadingOverlay: View
    private lateinit var spinner: CircularProgressIndicator

    // Firebase
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_profile)

        bindViews()

        // Default avatar shown immediately
        setDefaultAvatar("avatar1", R.drawable.avatar1)

        // Wire autocomplete for Pakistan cities
        val cities = resources.getStringArray(R.array.pk_cities)
        val cityAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, cities)
        etCity.setAdapter(cityAdapter)
        etCity.threshold = 1

        setupAvatarPicker()
        btnNext.setOnClickListener { onNext() }
    }

    private fun bindViews() {
        // Avatar preview group
        holderAvatar   = findViewById(R.id.holderAvatar)
        cardAvatar     = findViewById(R.id.cardAvatar)
        ivAvatar       = findViewById(R.id.ivAvatar)

        // Picker overlay
        avatarOverlay  = findViewById(R.id.avatarOverlay)
        pickA1         = findViewById(R.id.pickA1)
        pickA2         = findViewById(R.id.pickA2)
        pickA3         = findViewById(R.id.pickA3)
        pickA4         = findViewById(R.id.pickA4)
        btnClosePicker = findViewById(R.id.btnClosePicker)

        // Inputs
        tilFirst = findViewById(R.id.tilFirst)
        tilLast  = findViewById(R.id.tilLast)
        tilCity  = findViewById(R.id.tilCity)
        tilYear  = findViewById(R.id.tilYear)
        tilMonth = findViewById(R.id.tilMonth)
        tilDay   = findViewById(R.id.tilDay)

        etFirst = findViewById(R.id.etFirst)
        etLast  = findViewById(R.id.etLast)
        etCity  = findViewById(R.id.etCity)   // <-- now the correct view type
        etYear  = findViewById(R.id.etYear)
        etMonth = findViewById(R.id.etMonth)
        etDay   = findViewById(R.id.etDay)

        // Buttons / overlays
        btnNext        = findViewById(R.id.btnNext)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        spinner        = findViewById(R.id.progress)
    }

    private fun setDefaultAvatar(name: String, resId: Int) {
        selectedAvatar = name
        ivAvatar.setImageResource(resId)
        ivAvatar.clearColorFilter()
        ivAvatar.scaleType = ImageView.ScaleType.CENTER_CROP   // fill the circle like popup items
    }

    private fun setupAvatarPicker() {
        // Open the picker by tapping the avatar or pencil
        val openPicker: (View) -> Unit = { avatarOverlay.visibility = View.VISIBLE }
        holderAvatar.setOnClickListener(openPicker)
        findViewById<View>(R.id.ivPencil).setOnClickListener(openPicker)

        // When user selects an avatar, preview it circular (same look as popup)
        fun select(name: String, resId: Int) {
            selectedAvatar = name
            ivAvatar.setImageResource(resId)
            ivAvatar.clearColorFilter()
            ivAvatar.scaleType = ImageView.ScaleType.CENTER_CROP
            avatarOverlay.visibility = View.GONE
        }

        pickA1.setOnClickListener { select("avatar1", R.drawable.avatar1) }
        pickA2.setOnClickListener { select("avatar2", R.drawable.avatar2) }
        pickA3.setOnClickListener { select("avatar3", R.drawable.avatar3) }
        pickA4.setOnClickListener { select("avatar4", R.drawable.avatar4) }

        // Close picker
        btnClosePicker.setOnClickListener { avatarOverlay.visibility = View.GONE }
        avatarOverlay.setOnClickListener {
            if (it.id == R.id.avatarOverlay) avatarOverlay.visibility = View.GONE
        }
    }

    private fun onNext() {
        clearErrors()

        val first = etFirst.text?.toString()?.trim().orEmpty()
        val last  = etLast.text?.toString()?.trim().orEmpty()
        val city  = etCity.text?.toString()?.trim().orEmpty()  // works with MaterialAutoCompleteTextView
        val year  = etYear.text?.toString()?.toIntOrNull()
        val month = etMonth.text?.toString()?.toIntOrNull()
        val day   = etDay.text?.toString()?.toIntOrNull()

        var valid = true

        if (selectedAvatar == null) {
            Toast.makeText(this, "Please select an avatar.", Toast.LENGTH_SHORT).show()
            valid = false
        }
        if (first.isEmpty()) { tilFirst.error = "Required"; valid = false }
        if (last.isEmpty())  { tilLast.error  = "Required"; valid = false }
        if (city.isEmpty())  { tilCity.error  = "Required"; valid = false }

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        if (year == null || year < 1900 || year > currentYear) { tilYear.error = "YYYY"; valid = false }
        if (month == null || month !in 1..12) { tilMonth.error = "MM"; valid = false }
        if (day == null || day !in 1..31) { tilDay.error = "DD"; valid = false }

        if (!valid) return

        val uid = auth.currentUser?.uid ?: run {
            Toast.makeText(this, "Not authenticated.", Toast.LENGTH_LONG).show()
            return
        }

        showSaving(true)

        val payload = mapOf(
            "firstName" to first,
            "lastName"  to last,
            "city"      to city,
            "dob"       to String.format("%04d-%02d-%02d", year, month, day),
            "avatar"    to selectedAvatar!!,
            "stage"     to 1, // profile completed, next step is gender
            "profileUpdatedAt" to System.currentTimeMillis()
        )

        db.collection("users").document(uid)
            .set(payload, SetOptions.merge())
            .addOnSuccessListener {
                // Go to gender selection; greet with first name
                startActivity(Intent(this, SelectGenderActivity::class.java).apply {
                    putExtra("firstName", first)
                })
                finish()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to save profile. Try again.", Toast.LENGTH_LONG).show()
                showSaving(false)
            }
    }

    private fun clearErrors() {
        tilFirst.error = null
        tilLast.error  = null
        tilCity.error  = null
        tilYear.error  = null
        tilMonth.error = null
        tilDay.error   = null
    }

    private fun showSaving(show: Boolean) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        btnNext.isEnabled = !show
        holderAvatar.isEnabled = !show
        cardAvatar.isEnabled = !show
    }
}
