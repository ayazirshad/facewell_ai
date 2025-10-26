package com.example.fyp

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class EditProfileFragment : Fragment(R.layout.fragment_edit_profile) {

    companion object {
        private const val ARG_USER = "arg_user_profile"
        fun newInstance(user: UserProfile?): EditProfileFragment {
            return EditProfileFragment().apply {
                arguments = Bundle().apply { putSerializable(ARG_USER, user) }
            }
        }
    }

    // Views
    private lateinit var cardAvatar: MaterialCardView
    private lateinit var ivAvatar: ImageView
    private lateinit var etFirst: TextInputEditText
    private lateinit var etLast: TextInputEditText
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPhone: TextInputEditText
    private lateinit var etCity: MaterialAutoCompleteTextView
    private lateinit var etYear: TextInputEditText
    private lateinit var etMonth: TextInputEditText
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

    private var user: UserProfile? = null
    private var selectedAvatar: String = "ic_profile"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        wireAvatarPopup()
        wireDropdowns()

        // Block back press while saving
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (loadingOverlay.visibility == View.VISIBLE) {
                // Ignore back while saving
                return@addCallback
            }
            isEnabled = false
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // Prefill
        user = arguments?.getSerializable(ARG_USER) as? UserProfile
        if (user != null) {
            populateFields(user!!)
        } else {
            val uid = auth.currentUser?.uid
            if (uid != null) {
                db.collection("users").document(uid).get()
                    .addOnSuccessListener { doc ->
                        val fetched = UserProfile(
                            uid = uid,
                            firstName = doc.getString("firstName") ?: "",
                            lastName  = doc.getString("lastName") ?: "",
                            city      = doc.getString("city") ?: "",
                            dob       = doc.getString("dob") ?: "",
                            gender    = doc.getString("gender") ?: "",
                            email     = auth.currentUser?.email ?: (doc.getString("email") ?: ""),
                            phone     = doc.getString("phone") ?: "",
                            avatar    = doc.getString("avatar") ?: "ic_profile",
                            stage     = (doc.getLong("stage") ?: 0L).toInt()
                        )
                        user = fetched
                        populateFields(fetched)
                    }
            }
        }

        view.findViewById<View>(R.id.btnBack)?.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        btnSave.setOnClickListener { onSave() }
    }

    private fun bindViews(v: View) {
        cardAvatar = v.findViewById(R.id.cardAvatar)
        ivAvatar   = v.findViewById(R.id.ivAvatar)
        etFirst = v.findViewById(R.id.etFirst)
        etLast  = v.findViewById(R.id.etLast)
        etEmail = v.findViewById(R.id.etEmail)
        etPhone = v.findViewById(R.id.etPhone)
        etCity  = v.findViewById(R.id.etCity)
        etYear  = v.findViewById(R.id.etYear)
        etMonth = v.findViewById(R.id.etMonth)
        etDay   = v.findViewById(R.id.etDay)
        etGender = v.findViewById(R.id.etGender)
        btnSave  = v.findViewById(R.id.btnSave)

        avatarOverlay  = v.findViewById(R.id.avatarOverlay)
        dialogAvatar   = v.findViewById(R.id.dialogAvatar)
        loadingOverlay = v.findViewById(R.id.loadingOverlay)

        pickA1 = v.findViewById(R.id.pickA1)
        pickA2 = v.findViewById(R.id.pickA2)
        pickA3 = v.findViewById(R.id.pickA3)
        pickA4 = v.findViewById(R.id.pickA4)
    }

    private fun wireDropdowns() {
        val cities = try { resources.getStringArray(R.array.pk_cities).toList() } catch (_: Exception) { emptyList() }
        if (cities.isNotEmpty()) {
            etCity.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, cities))
        }
        val genderOptions = listOf("Male", "Female")
        etGender.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, genderOptions))
    }

    private fun wireAvatarPopup() {
        cardAvatar.setOnClickListener { showAvatarPicker(true) }
        view?.findViewById<View>(R.id.badgeEdit)?.setOnClickListener { showAvatarPicker(true) }
        view?.findViewById<View>(R.id.btnClosePicker)?.setOnClickListener { showAvatarPicker(false) }

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

    private fun populateFields(u: UserProfile) {
        etFirst.setText(u.firstName)
        etLast.setText(u.lastName)
        etEmail.setText(u.email)
        etPhone.setText(u.phone)
        etCity.setText(u.city, false)

        selectedAvatar = u.avatar
        ivAvatar.setImageResource(
            when (u.avatar) {
                "avatar1" -> R.drawable.avatar1
                "avatar2" -> R.drawable.avatar2
                "avatar3" -> R.drawable.avatar3
                "avatar4" -> R.drawable.avatar4
                else      -> R.drawable.ic_profile
            }
        )

        if (u.dob.length >= 10 && u.dob.contains("-")) {
            val parts = u.dob.split("-")
            if (parts.size == 3) {
                etYear.setText(parts[0]); etMonth.setText(parts[1]); etDay.setText(parts[2])
            }
        }
        val displayGender = when (u.gender.lowercase()) {
            "male" -> "Male"
            "female" -> "Female"
            else -> u.gender.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        etGender.setText(displayGender, false)
    }

    private fun showAvatarPicker(show: Boolean) {
        avatarOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun onSave() {
        val uid = user?.uid ?: FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Toast.makeText(requireContext(), "Not authenticated.", Toast.LENGTH_LONG).show()
            return
        }

        val first = etFirst.text?.toString()?.trim().orEmpty()
        val last  = etLast.text?.toString()?.trim().orEmpty()
        val city  = etCity.text?.toString()?.trim().orEmpty()
        val phone = etPhone.text?.toString()?.trim().orEmpty()
        val year  = etYear.text?.toString()?.trim().orEmpty()
        val month = etMonth.text?.toString()?.trim().orEmpty()
        val day   = etDay.text?.toString()?.trim().orEmpty()

        val genderDisplay = etGender.text?.toString()?.trim().orEmpty()
        val genderStore = when (genderDisplay.lowercase()) {
            "male" -> "male"
            "female" -> "female"
            else -> genderDisplay.lowercase()
        }

        if (first.isEmpty() || last.isEmpty() || city.isEmpty()
            || year.length != 4 || month.isEmpty() || day.isEmpty()
        ) { Toast.makeText(requireContext(), "Please complete all required fields.", Toast.LENGTH_SHORT).show(); return }

        val y = year.toIntOrNull(); val m = month.toIntOrNull(); val d = day.toIntOrNull()
        val nowYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        if (y == null || y < 1900 || y > nowYear) { Toast.makeText(requireContext(), "Enter a valid year.", Toast.LENGTH_SHORT).show(); return }
        if (m == null || m !in 1..12)             { Toast.makeText(requireContext(), "Enter a valid month.", Toast.LENGTH_SHORT).show(); return }
        if (d == null || d !in 1..31)             { Toast.makeText(requireContext(), "Enter a valid day.",   Toast.LENGTH_SHORT).show(); return }

        val dob = String.format("%04d-%02d-%02d", y, m, d)

        showSaving(true)

        val payload = mapOf(
            "firstName" to first,
            "lastName"  to last,
            "city"      to city,
            "dob"       to dob,
            "gender"    to genderStore,
            "phone"     to phone,
            "avatar"    to selectedAvatar,
            "profileUpdatedAt" to System.currentTimeMillis()
        )

        db.collection("users").document(uid)
            .set(payload, SetOptions.merge())
            .addOnSuccessListener {
                // 1) Hide loader BEFORE navigating back so it never sticks
                showSaving(false)

                // 2) Let ProfileFragment remain visible underneath
                Toast.makeText(requireContext(), "Profile updated", Toast.LENGTH_SHORT).show()

                // 3) Close this overlay/fragment
                val fm = parentFragmentManager
                // popBackStackImmediate avoids animation delay (instant return)
                if (!fm.popBackStackImmediate()) {
                    // Fallback to back press if not on back stack
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "Failed to save. Try again.", Toast.LENGTH_LONG).show()
                showSaving(false)
            }
    }

    private fun showSaving(saving: Boolean) {
        loadingOverlay.visibility = if (saving) View.VISIBLE else View.GONE
        btnSave.isEnabled = !saving
        cardAvatar.isEnabled = !saving
        view?.findViewById<View>(R.id.badgeEdit)?.isEnabled = !saving
    }
}
