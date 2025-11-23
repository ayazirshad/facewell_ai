package com.example.fyp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.Selection
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Calendar

class EditProfileActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "EditProfileActivity"
        private val NAME_REGEX = Regex("^[A-Za-z ]+\$") // letters and spaces only
        private val MONTH_SHORTS = listOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
    }

    // Views
    private lateinit var cardAvatar: MaterialCardView
    private lateinit var ivAvatar: ImageView

    private lateinit var tilFirst: TextInputLayout
    private lateinit var tilLast: TextInputLayout
    private lateinit var tilCity: TextInputLayout
    private lateinit var tilPhone: TextInputLayout
    private lateinit var tilYear: TextInputLayout
    private lateinit var tilMonth: TextInputLayout
    private lateinit var tilDay: TextInputLayout
    private lateinit var tilGender: TextInputLayout

    private lateinit var etFirst: TextInputEditText
    private lateinit var etLast: TextInputEditText
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPhone: TextInputEditText
    private lateinit var etCity: MaterialAutoCompleteTextView
    private lateinit var etYear: TextInputEditText
    private lateinit var etMonth: MaterialAutoCompleteTextView
    private lateinit var etDay: TextInputEditText
    private lateinit var etGender: MaterialAutoCompleteTextView

    private lateinit var btnSave: MaterialButton

    // Overlays
    private lateinit var avatarOverlay: View
    private lateinit var dialogAvatar: View
    private lateinit var loadingOverlay: View
    private lateinit var pickA1: MaterialCardView
    private lateinit var pickA2: MaterialCardView
    private lateinit var pickA3: MaterialCardView
    private lateinit var pickA4: MaterialCardView

    // Data
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var uidFromIntent: String? = null
    private var selectedAvatar: String = "ic_profile"
    private var selectedMonthNumber: Int? = null

    // phone formatting guard
    private var phoneFormatting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_profile) // ensure layout file name matches

        // safe root: avoid NPE when using insets
        val root = findViewById<View?>(R.id.editRoot)
        if (root != null) ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets -> insets }

        bindViews()
        applyNameFilters()
        wireAvatarPopup()
        wireDropdowns()
        wirePhoneFormatting()
        wireDobWatchers()
        wireSimpleClearers()
        readIncomingExtrasAndPopulate()

        findViewById<View?>(R.id.btnBack)?.setOnClickListener { onBackPressed() }
        btnSave.setOnClickListener { onSave() }

        // show month dropdown on click
        etMonth.setOnClickListener { etMonth.showDropDown() }
    }

    private fun bindViews() {
        cardAvatar = findViewById(R.id.cardAvatar)
        ivAvatar   = findViewById(R.id.ivAvatar)

        tilFirst = findViewById(R.id.tilFirst)
        tilLast  = findViewById(R.id.tilLast)
        tilCity  = findViewById(R.id.tilCity)
        tilYear  = findViewById(R.id.tilYear)
        tilMonth = findViewById(R.id.tilMonth)
        tilDay   = findViewById(R.id.tilDay)
        tilGender = findViewById(R.id.tilGender)
        tilPhone = findViewById(R.id.tilPhone)

        etFirst = findViewById(R.id.etFirst)
        etLast  = findViewById(R.id.etLast)
        etEmail = findViewById(R.id.etEmail)
        etPhone = findViewById(R.id.etPhone)
        etCity  = findViewById(R.id.etCity)
        etYear  = findViewById(R.id.etYear)
        etMonth = findViewById(R.id.etMonth)
        etDay   = findViewById(R.id.etDay)
        etGender = findViewById(R.id.etGender)

        btnSave  = findViewById(R.id.btnSave)

        avatarOverlay  = findViewById(R.id.avatarOverlay)
        dialogAvatar   = findViewById(R.id.dialogAvatar)
        loadingOverlay = findViewById(R.id.loadingOverlay)

        pickA1 = findViewById(R.id.pickA1)
        pickA2 = findViewById(R.id.pickA2)
        pickA3 = findViewById(R.id.pickA3)
        pickA4 = findViewById(R.id.pickA4)

        // ensure email looks muted/read-only
        etEmail.isEnabled = false
        try { etEmail.setTextColor(getColor(R.color.text_muted)) } catch (_: Exception) {}
    }

    private fun applyNameFilters() {
        // block digits and punctuation in names
        val nameFilter = InputFilter { source, start, end, _, _, _ ->
            val input = source.subSequence(start, end).toString()
            if (input.isEmpty()) return@InputFilter null // allow deletions
            for (ch in input) {
                if (!(ch.isLetter() || ch == ' ')) return@InputFilter "" // reject
            }
            null
        }
        etFirst.filters = arrayOf(nameFilter)
        etLast.filters = arrayOf(nameFilter)
    }

    private fun wireSimpleClearers() {
        // clear errors while editing
        etFirst.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { tilFirst.error = null }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        etLast.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { tilLast.error = null }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        etCity.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { tilCity.error = null }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        etPhone.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { tilPhone.error = null }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        etYear.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { tilYear.error = null }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        etMonth.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { tilMonth.error = null }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        etDay.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { tilDay.error = null }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        etGender.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { tilGender.error = null }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun wireDropdowns() {
        val cities = try { resources.getStringArray(R.array.pk_cities).toList() } catch (_: Exception) { emptyList() }
        if (cities.isNotEmpty()) {
            etCity.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, cities))
            etCity.threshold = 1
        }

        val genderOptions = listOf("Male", "Female", "Prefer not to say")
        etGender.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, genderOptions))

        // month adapter (short names)
        val monthAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, MONTH_SHORTS)
        etMonth.setAdapter(monthAdapter)
        etMonth.setOnItemClickListener { _, _, pos, _ ->
            selectedMonthNumber = pos + 1
        }

        // limit phone length to allow optional hyphen: 0321-2345754 -> 12 chars max
        etPhone.filters = arrayOf(InputFilter.LengthFilter(12))
    }

    private fun wirePhoneFormatting() {
        etPhone.addTextChangedListener(object : TextWatcher {
            private var previous = ""
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { previous = s?.toString() ?: "" }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (phoneFormatting) return
                phoneFormatting = true
                try {
                    val raw = s?.toString() ?: ""
                    val digits = raw.filter { it.isDigit() }
                    val formatted = when {
                        digits.length <= 4 -> digits
                        digits.length <= 11 -> {
                            val first = digits.substring(0, 4)
                            val rest = digits.substring(4)
                            "$first-$rest"
                        }
                        else -> {
                            val first = digits.substring(0, 4)
                            val rest = digits.substring(4, 11)
                            "$first-$rest"
                        }
                    }
                    if (formatted != raw) {
                        etPhone.setText(formatted)
                        Selection.setSelection(etPhone.text, formatted.length.coerceIn(0, formatted.length))
                    }
                } catch (_: Exception) { }
                finally { phoneFormatting = false; tilPhone.error = null }
            }
        })
    }

    private fun wireDobWatchers() {
        // Day clamp
        etDay.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val txt = s?.toString().orEmpty()
                if (txt.isEmpty()) { tilDay.error = null; return }
                val num = txt.toIntOrNull()
                if (num == null) { tilDay.error = "Invalid"; return }
                if (num > 31) {
                    etDay.setText("31")
                    etDay.setSelection(etDay.text?.length ?: 0)
                } else tilDay.error = null
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Year clamp to current year
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        etYear.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val txt = s?.toString().orEmpty()
                if (txt.isEmpty()) { tilYear.error = null; return }
                val num = txt.toIntOrNull()
                if (num == null) { tilYear.error = "Invalid"; return }
                if (num > currentYear) {
                    etYear.setText(currentYear.toString())
                    etYear.setSelection(etYear.text?.length ?: 0)
                } else tilYear.error = null
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun wireAvatarPopup() {
        cardAvatar.setOnClickListener { showAvatarPicker(true) }
        findViewById<View?>(R.id.badgeEdit)?.setOnClickListener { showAvatarPicker(true) }
        findViewById<View?>(R.id.btnClosePicker)?.setOnClickListener { showAvatarPicker(false) }

        avatarOverlay.setOnClickListener { showAvatarPicker(false) }
        dialogAvatar.setOnClickListener { /* consume */ }

        fun chooseAvatar(name: String) {
            selectedAvatar = name
            val res = when (name) {
                "avatar1" -> R.drawable.avatar1
                "avatar2" -> R.drawable.avatar2
                "avatar3" -> R.drawable.avatar3
                "avatar4" -> R.drawable.avatar4
                else      -> R.drawable.ic_profile
            }
            ivAvatar.setImageResource(res)
            showAvatarPicker(false)
        }

        pickA1.setOnClickListener { chooseAvatar("avatar1") }
        pickA2.setOnClickListener { chooseAvatar("avatar2") }
        pickA3.setOnClickListener { chooseAvatar("avatar3") }
        pickA4.setOnClickListener { chooseAvatar("avatar4") }
    }

    private fun showAvatarPicker(show: Boolean) {
        avatarOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun readIncomingExtrasAndPopulate() {
        // using the same simple keys as before
        uidFromIntent = intent.getStringExtra("uid")
        etFirst.setText(intent.getStringExtra("firstName") ?: "")
        etLast.setText(intent.getStringExtra("lastName") ?: "")
        etEmail.setText(intent.getStringExtra("email") ?: "")
        etPhone.setText(intent.getStringExtra("phone") ?: "")
        etCity.setText(intent.getStringExtra("city") ?: "", false)

        val dob = intent.getStringExtra("dob") ?: ""
        if (dob.length >= 10 && dob.contains("-")) {
            val parts = dob.split("-")
            if (parts.size == 3) {
                etYear.setText(parts[0])
                val mNum = parts[1].toIntOrNull()
                if (mNum != null && mNum in 1..12) {
                    etMonth.setText(MONTH_SHORTS[mNum - 1], false)
                    selectedMonthNumber = mNum
                } else {
                    etMonth.setText(parts[1], false)
                }
                etDay.setText(parts[2])
            }
        }

        val avatar = intent.getStringExtra("avatar") ?: "ic_profile"
        selectedAvatar = avatar
        ivAvatar.setImageResource(
            when (avatar) {
                "avatar1" -> R.drawable.avatar1
                "avatar2" -> R.drawable.avatar2
                "avatar3" -> R.drawable.avatar3
                "avatar4" -> R.drawable.avatar4
                else      -> R.drawable.ic_profile
            }
        )

        val genderDisplay = intent.getStringExtra("gender") ?: ""
        etGender.setText(when (genderDisplay.lowercase()) {
            "male" -> "Male"
            "female" -> "Female"
            "prefer not to say", "prefer_not_to_say" -> "Prefer not to say"
            else -> genderDisplay
        }, false)
    }

    private fun onSave() {
        clearErrors()

        val uid = uidFromIntent ?: FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Toast.makeText(this, "Not authenticated.", Toast.LENGTH_LONG).show()
            return
        }

        val first = etFirst.text?.toString()?.trim().orEmpty()
        val last  = etLast.text?.toString()?.trim().orEmpty()
        val city  = etCity.text?.toString()?.trim().orEmpty()
        val phone = etPhone.text?.toString()?.trim().orEmpty()
        val yearS = etYear.text?.toString()?.trim().orEmpty()
        val monthS = etMonth.text?.toString()?.trim().orEmpty()
        val dayS  = etDay.text?.toString()?.trim().orEmpty()
        val genderDisplay = etGender.text?.toString()?.trim().orEmpty()

        var valid = true

        // Names: required + regex
        if (first.isEmpty()) { tilFirst.error = "Required"; valid = false }
        else if (!NAME_REGEX.matches(first)) { tilFirst.error = "Only letters and spaces allowed"; valid = false }

        if (last.isEmpty()) { tilLast.error = "Required"; valid = false }
        else if (!NAME_REGEX.matches(last)) { tilLast.error = "Only letters and spaces allowed"; valid = false }

        if (city.isEmpty()) { tilCity.error = "Required"; valid = false }

        // Phone: digits only, 11 digits after stripping non-digits
        val phoneDigits = phone.filter { it.isDigit() }
        if (phoneDigits.length != 11) { tilPhone.error = "Enter 11 digit phone number"; valid = false }

        // DOB validations
        val y = yearS.toIntOrNull()
        val nowYear = Calendar.getInstance().get(Calendar.YEAR)
        if (y == null || y < 1900 || y > nowYear) { tilYear.error = "Enter valid year"; valid = false }

        // month: numeric from selection preferred, otherwise parse short names
        var m: Int? = selectedMonthNumber
        if (m == null && monthS.isNotEmpty()) {
            val idx = MONTH_SHORTS.indexOfFirst { it.equals(monthS, ignoreCase = true) }
            if (idx >= 0) m = idx + 1
            else { tilMonth.error = "Select month"; valid = false }
        }
        if (m == null) { tilMonth.error = "Select month"; valid = false }

        val d = dayS.toIntOrNull()
        if (d == null || d !in 1..31) { tilDay.error = "Enter valid date"; valid = false }

        if (!valid) return

        val dob = String.format("%04d-%02d-%02d", y!!, m!!, d!!)

        showSaving(true)

        val genderStore = when (genderDisplay.lowercase()) {
            "male" -> "male"
            "female" -> "female"
            "prefer not to say" -> "prefer not to say"
            else -> genderDisplay.lowercase()
        }

        val payload = mapOf(
            "firstName" to first,
            "lastName"  to last,
            "city"      to city,
            "dob"       to dob,
            "gender"    to genderStore,
            "phone"     to phoneDigits,
            "avatar"    to selectedAvatar,
            "profileUpdatedAt" to System.currentTimeMillis()
        )

        db.collection("users").document(uid)
            .set(payload, SetOptions.merge())
            .addOnSuccessListener {
                showSaving(false)
                Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_OK)
                finish()
            }
            .addOnFailureListener {
                showSaving(false)
                Toast.makeText(this, "Failed to save. Try again.", Toast.LENGTH_LONG).show()
            }
    }

    private fun clearErrors() {
        tilFirst.error = null
        tilLast.error  = null
        tilCity.error  = null
        tilYear.error  = null
        tilMonth.error = null
        tilDay.error   = null
        tilGender.error = null
        tilPhone.error = null
    }

    private fun showSaving(saving: Boolean) {
        loadingOverlay.visibility = if (saving) View.VISIBLE else View.GONE
        btnSave.isEnabled = !saving
        cardAvatar.isEnabled = !saving
        findViewById<View?>(R.id.badgeEdit)?.isEnabled = !saving
    }
}
