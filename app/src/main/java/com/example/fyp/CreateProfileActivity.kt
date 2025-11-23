package com.example.fyp

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Toast
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

// Screenshot reference (uploaded): /mnt/data/7f57d5ba-df8e-46d3-a173-eac603fef161.png

class CreateProfileActivity : AppCompatActivity() {

    // Avatar preview + picker
    private lateinit var holderAvatar: View
    private lateinit var cardAvatar: MaterialCardView
    private lateinit var ivAvatar: ImageView

    private lateinit var avatarOverlay: View
    private lateinit var pickA1: View
    private lateinit var pickA2: View
    private lateinit var pickA3: View
    private lateinit var pickA4: View
    private lateinit var btnClosePicker: MaterialButton
    private var selectedAvatar: String? = null

    // Inputs
    private lateinit var tilFirst: TextInputLayout
    private lateinit var tilLast: TextInputLayout
    private lateinit var tilCity: TextInputLayout
    private lateinit var tilYear: TextInputLayout
    private lateinit var tilMonth: TextInputLayout
    private lateinit var tilDay: TextInputLayout

    private lateinit var etFirst: TextInputEditText
    private lateinit var etLast: TextInputEditText
    private lateinit var etCity: MaterialAutoCompleteTextView
    private lateinit var etYear: TextInputEditText
    private lateinit var etMonth: MaterialAutoCompleteTextView
    private lateinit var etDay: TextInputEditText

    private lateinit var btnNext: MaterialButton
    private lateinit var loadingOverlay: View
    private lateinit var spinner: CircularProgressIndicator

    // Firebase
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    // month short names shown to user (index 0 -> Jan => month number 1)
    private val monthShort = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_profile)
        bindViews()

        setDefaultAvatar("avatar1", R.drawable.avatar1)

        // City autocomplete (if you have R.array.pk_cities keep that; otherwise this is still safe)
        val cities = try {
            resources.getStringArray(R.array.pk_cities)
        } catch (e: Exception) {
            arrayOf<String>() // fallback empty
        }
        val cityAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, cities)
        etCity.setAdapter(cityAdapter)
        etCity.threshold = 1

        setupAvatarPicker()
        setupMonthDropdown()
        setupInputFiltersAndWatchers()
        btnNext.setOnClickListener { onNext() }
    }

    private fun bindViews() {
        holderAvatar   = findViewById(R.id.holderAvatar)
        cardAvatar     = findViewById(R.id.cardAvatar)
        ivAvatar       = findViewById(R.id.ivAvatar)

        avatarOverlay  = findViewById(R.id.avatarOverlay)
        pickA1         = findViewById(R.id.pickA1)
        pickA2         = findViewById(R.id.pickA2)
        pickA3         = findViewById(R.id.pickA3)
        pickA4         = findViewById(R.id.pickA4)
        btnClosePicker = findViewById(R.id.btnClosePicker)

        tilFirst = findViewById(R.id.tilFirst)
        tilLast  = findViewById(R.id.tilLast)
        tilCity  = findViewById(R.id.tilCity)
        tilYear  = findViewById(R.id.tilYear)
        tilMonth = findViewById(R.id.tilMonth)
        tilDay   = findViewById(R.id.tilDay)

        etFirst = findViewById(R.id.etFirst)
        etLast  = findViewById(R.id.etLast)
        etCity  = findViewById(R.id.etCity)
        etYear  = findViewById(R.id.etYear)
        etMonth = findViewById(R.id.etMonth)
        etDay   = findViewById(R.id.etDay)

        btnNext        = findViewById(R.id.btnNext)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        spinner        = findViewById(R.id.progress)
    }

    private fun setDefaultAvatar(name: String, resId: Int) {
        selectedAvatar = name
        ivAvatar.setImageResource(resId)
        ivAvatar.clearColorFilter()
        ivAvatar.scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private fun setupAvatarPicker() {
        val openPicker: (View) -> Unit = { avatarOverlay.visibility = View.VISIBLE }
        holderAvatar.setOnClickListener(openPicker)
        findViewById<View>(R.id.ivPencil).setOnClickListener(openPicker)

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

        btnClosePicker.setOnClickListener { avatarOverlay.visibility = View.GONE }
        avatarOverlay.setOnClickListener {
            if (it.id == R.id.avatarOverlay) avatarOverlay.visibility = View.GONE
        }
    }

    private fun setupMonthDropdown() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, monthShort)
        etMonth.setAdapter(adapter)
        // show dropdown when clicked
        etMonth.setOnClickListener { etMonth.showDropDown() }
    }

    private fun setupInputFiltersAndWatchers() {
        // Name input filter - allow letters and spaces only
        val nameFilter = InputFilter { source, start, end, dest, dstart, dend ->
            val allowed = Regex("^[A-Za-z ]+\$")
            val input = source.subSequence(start, end).toString()
            if (input.isEmpty()) return@InputFilter null // deletion allowed
            // if every char in input is either letter or space -> accept
            for (ch in input) {
                if (!(ch.isLetter() || ch == ' ')) return@InputFilter ""
            }
            null
        }
        etFirst.filters = arrayOf(nameFilter)
        etLast.filters  = arrayOf(nameFilter)

        // Clear errors while user types
        etFirst.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { tilFirst.error = null }
            override fun afterTextChanged(s: Editable?) {}
        })
        etLast.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { tilLast.error = null }
            override fun afterTextChanged(s: Editable?) {}
        })
        etCity.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { tilCity.error = null }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Day watcher: clamp to 31
        etDay.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val txt = s?.toString().orEmpty()
                if (txt.isEmpty()) { tilDay.error = null; return }
                val num = txt.toIntOrNull()
                if (num == null) { tilDay.error = "Invalid" ; return }
                if (num > 31) {
                    etDay.setText("31")
                    etDay.setSelection(etDay.text?.length ?: 0)
                } else {
                    tilDay.error = null
                }
            }
        })

        // Year watcher: max current year
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        etYear.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val txt = s?.toString().orEmpty()
                if (txt.isEmpty()) { tilYear.error = null; return }
                val num = txt.toIntOrNull()
                if (num == null) { tilYear.error = "Invalid"; return }
                if (num > currentYear) {
                    etYear.setText(currentYear.toString())
                    etYear.setSelection(etYear.text?.length ?: 0)
                } else {
                    tilYear.error = null
                }
            }
        })
    }

    private fun onNext() {
        clearErrors()

        val first = etFirst.text?.toString()?.trim().orEmpty()
        val last  = etLast.text?.toString()?.trim().orEmpty()
        val city  = etCity.text?.toString()?.trim().orEmpty()
        val yearTxt  = etYear.text?.toString()?.trim().orEmpty()
        val monthTxt = etMonth.text?.toString()?.trim().orEmpty()
        val dayTxt   = etDay.text?.toString()?.trim().orEmpty()

        var valid = true

        if (selectedAvatar == null) {
            Toast.makeText(this, "Please select an avatar.", Toast.LENGTH_SHORT).show()
            valid = false
        }

        // Name presence + regex check (letters + spaces)
        if (first.isEmpty()) {
            tilFirst.error = "Required"
            valid = false
        } else if (!first.matches(Regex("^[A-Za-z ]+\$"))) {
            tilFirst.error = "Only letters allowed"
            valid = false
        }

        if (last.isEmpty()) {
            tilLast.error = "Required"
            valid = false
        } else if (!last.matches(Regex("^[A-Za-z ]+\$"))) {
            tilLast.error = "Only letters allowed"
            valid = false
        }

        if (city.isEmpty()) {
            tilCity.error = "Required"
            valid = false
        }

        val year = yearTxt.toIntOrNull()
        val day  = dayTxt.toIntOrNull()
        val monthIndex = monthShort.indexOfFirst { it.equals(monthTxt, ignoreCase = true) } // 0..11

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        if (year == null) {
            tilYear.error = "Required"
            valid = false
        } else if (year < 1900 || year > currentYear) {
            tilYear.error = "Enter year between 1900 and $currentYear"
            valid = false
        }

        if (monthIndex == -1) {
            tilMonth.error = "Select month"
            valid = false
        } else {
            tilMonth.error = null
        }

        if (day == null) {
            tilDay.error = "Required"
            valid = false
        } else if (day < 1 || day > 31) {
            tilDay.error = "Day must be 1–31"
            valid = false
        }

        if (!valid) return

        // map monthIndex(0..11) to monthNumber (1..12)
        val monthNumber = monthIndex + 1

        val uid = auth.currentUser?.uid ?: run {
            Toast.makeText(this, "Not authenticated.", Toast.LENGTH_LONG).show()
            return
        }

        showSaving(true)

        val payload = mapOf(
            "firstName" to first,
            "lastName"  to last,
            "city"      to city,
            "dob"       to String.format("%04d-%02d-%02d", year, monthNumber, day),
            "avatar"    to selectedAvatar!!,
            "stage"     to 1,
            "profileUpdatedAt" to System.currentTimeMillis()
        )

        db.collection("users").document(uid)
            .set(payload, SetOptions.merge())
            .addOnSuccessListener {
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
