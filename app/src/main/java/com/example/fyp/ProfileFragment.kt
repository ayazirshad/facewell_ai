package com.example.fyp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ProfileFragment : Fragment(R.layout.activity_profile_fragment) {

    private lateinit var tvFirst: TextView
    private lateinit var tvLast: TextView
    private lateinit var tvDob: TextView
    private lateinit var tvGender: TextView
    private lateinit var ivAvatar: ImageView

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    // keep a lightweight holder for the fetched user data (map-like)
    private var currentUserData: Map<String, String>? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvFirst  = view.findViewById(R.id.tvFirst)
        tvLast   = view.findViewById(R.id.tvLast)
        tvDob    = view.findViewById(R.id.tvDob)
        tvGender = view.findViewById(R.id.tvGender)
        ivAvatar = view.findViewById(R.id.ivAvatar)

        // NEW EDIT BUTTON -> open the standalone EditProfileActivity
        view.findViewById<View>(R.id.btnEditProfile).setOnClickListener {
            val data = currentUserData
            if (data != null) {
                val intent = Intent(requireContext(), EditProfileActivity::class.java).apply {
                    putExtra("uid", data["uid"])
                    putExtra("firstName", data["firstName"])
                    putExtra("lastName", data["lastName"])
                    putExtra("city", data["city"])
                    putExtra("dob", data["dob"])
                    putExtra("gender", data["gender"])
                    putExtra("email", data["email"])
                    putExtra("phone", data["phone"])
                    putExtra("avatar", data["avatar"])
                    putExtra("stage", data["stage"])
                }
                startActivity(intent)
            } else {
                // if user not loaded yet, fetch then open
                fetchUserAndOpenEdit()
            }
        }

        view.findViewById<View>(R.id.rowLogout).setOnClickListener {
            showLogoutDialog()
        }

        fetchUser()
    }

    // If the fragment becomes visible again, refresh the profile (in case edit changed data)
    override fun onResume() {
        super.onResume()
        // Re-fetch to make sure latest saved profile shows up
        fetchUser()
    }

    private fun fetchUserAndOpenEdit() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val map = mapOf(
                    "uid" to uid,
                    "firstName" to (doc.getString("firstName") ?: ""),
                    "lastName"  to (doc.getString("lastName") ?: ""),
                    "city"      to (doc.getString("city") ?: ""),
                    "dob"       to (doc.getString("dob") ?: ""),
                    "gender"    to (doc.getString("gender") ?: ""),
                    "email"     to (auth.currentUser?.email ?: (doc.getString("email") ?: "")),
                    "phone"     to (doc.getString("phone") ?: ""),
                    "avatar"    to (doc.getString("avatar") ?: "ic_profile"),
                    "stage"     to (doc.getLong("stage")?.toString() ?: "0")
                )
                currentUserData = map
                bindUserToUiFromMap(map)

                // now open EditProfileActivity with extras (same keys)
                val intent = Intent(requireContext(), EditProfileActivity::class.java).apply {
                    putExtra("uid", map["uid"])
                    putExtra("firstName", map["firstName"])
                    putExtra("lastName", map["lastName"])
                    putExtra("city", map["city"])
                    putExtra("dob", map["dob"])
                    putExtra("gender", map["gender"])
                    putExtra("email", map["email"])
                    putExtra("phone", map["phone"])
                    putExtra("avatar", map["avatar"])
                    putExtra("stage", map["stage"])
                }
                startActivity(intent)
            }
    }

    private fun fetchUser() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val map = mapOf(
                    "uid" to uid,
                    "firstName" to (doc.getString("firstName") ?: ""),
                    "lastName"  to (doc.getString("lastName") ?: ""),
                    "city"      to (doc.getString("city") ?: ""),
                    "dob"       to (doc.getString("dob") ?: ""),
                    "gender"    to (doc.getString("gender") ?: ""),
                    "email"     to (auth.currentUser?.email ?: (doc.getString("email") ?: "")),
                    "phone"     to (doc.getString("phone") ?: ""),
                    "avatar"    to (doc.getString("avatar") ?: "ic_profile"),
                    "stage"     to (doc.getLong("stage")?.toString() ?: "0")
                )
                currentUserData = map
                bindUserToUiFromMap(map)
            }
    }

    private fun bindUserToUiFromMap(data: Map<String, String>) {
        tvFirst.text = data["firstName"] ?: ""
        tvLast.text  = data["lastName"] ?: ""
        tvDob.text   = data["dob"] ?: ""

        // --- FIX GENDER TEXT (remove underscores, proper spacing, title case) ---
        val genderRaw = (data["gender"] ?: "").replace('_', ' ').trim()
        val genderDisplay = when (genderRaw.lowercase()) {
            "male" -> "Male"
            "female" -> "Female"
            "prefer not to say", "prefer not to say" -> "Prefer not to say"
            else -> genderRaw.split("\\s+".toRegex())
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
        tvGender.text = genderDisplay

        val avatar = data["avatar"] ?: ""
        val avatarRes = when (avatar) {
            "avatar1" -> R.drawable.avatar1
            "avatar2" -> R.drawable.avatar2
            "avatar3" -> R.drawable.avatar3
            "avatar4" -> R.drawable.avatar4
            else      -> R.drawable.ic_profile
        }
        ivAvatar.setImageResource(avatarRes)
    }

    private fun showLogoutDialog() {
        val dialog = android.app.Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_logout)
        dialog.setCancelable(true)

        dialog.findViewById<View>(R.id.btnCancel)?.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.btnLogoutConfirm)?.setOnClickListener {
            dialog.dismiss()
            doLogout()
        }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun doLogout() {
        FirebaseAuth.getInstance().signOut()

        val intent = Intent(requireContext(), LoginActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)

        requireActivity().finish()
    }
}
