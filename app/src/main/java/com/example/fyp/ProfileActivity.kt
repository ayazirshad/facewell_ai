package com.example.fyp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ProfileActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_USER = "extra_user_profile"
    }

    private lateinit var tvFirst: TextView
    private lateinit var tvLast: TextView
    private lateinit var tvDob: TextView
    private lateinit var tvGender: TextView
    private lateinit var ivAvatar: ImageView

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var fabScan: FloatingActionButton

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var currentUser: UserProfile? = null

    // Receive updated user from EditProfileActivity without refetching Firestore
    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val updated = res.data?.getSerializableExtra(EXTRA_USER) as? UserProfile
            if (updated != null) {
                currentUser = updated
                bindUserToUi(updated)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        tvFirst  = findViewById(R.id.tvFirst)
        tvLast   = findViewById(R.id.tvLast)
        tvDob    = findViewById(R.id.tvDob)
        tvGender = findViewById(R.id.tvGender)
        ivAvatar = findViewById(R.id.ivAvatar)

        bottomNav = findViewById(R.id.bottomNav)
        fabScan   = findViewById(R.id.fabScan)

        // highlight profile tab
        bottomNav.selectedItemId = R.id.nav_profile

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { startActivity(Intent(this, MainActivity::class.java)); true }
                R.id.nav_clinics -> { startActivity(Intent(this, ClinicsActivity::class.java)); true }
                R.id.nav_scan_placeholder -> { fabScan.performClick(); false }
                R.id.nav_reports -> { startActivity(Intent(this, ReportsActivity::class.java)); true }
                R.id.nav_profile -> true
                else -> false
            }
        }
        fabScan.setOnClickListener {
            startActivity(Intent(this, ScanChooserSheet::class.java))
        }

        findViewById<android.view.View>(R.id.badgeEdit).setOnClickListener {
            currentUser?.let { user ->
                editLauncher.launch(
                    Intent(this, EditProfileFragment::class.java).putExtra(EXTRA_USER, user)
                )
            } ?: run {
                // Fallback: if somehow user is null (cold launch), fetch & then open
                fetchFromFirestoreAndOpenEdit()
            }
        }

        findViewById<android.view.View>(R.id.rowLogout).setOnClickListener {
            showLogoutDialog() // your custom dialog or the simple confirm you had earlier
        }

        // === Use user passed from Intent if available; otherwise fetch ===
        val fromIntent = intent.getSerializableExtra(EXTRA_USER) as? UserProfile
        if (fromIntent != null) {
            currentUser = fromIntent
            bindUserToUi(fromIntent)
        } else {
            fetchUserFromFirestore()
        }
    }

    private fun fetchUserFromFirestore() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val user = UserProfile(
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
                currentUser = user
                bindUserToUi(user)
            }
    }

    private fun fetchFromFirestoreAndOpenEdit() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val user = UserProfile(
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
                currentUser = user
                editLauncher.launch(
                    Intent(this, EditProfileFragment::class.java).putExtra(EXTRA_USER, user)
                )
            }
    }

    private fun bindUserToUi(user: UserProfile) {
        tvFirst.text = user.firstName
        tvLast.text  = user.lastName
        tvDob.text   = user.dob
        tvGender.text = user.gender.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        val avatarRes = when (user.avatar) {
            "avatar1" -> R.drawable.avatar1
            "avatar2" -> R.drawable.avatar2
            "avatar3" -> R.drawable.avatar3
            "avatar4" -> R.drawable.avatar4
            else      -> R.drawable.ic_profile
        }
        ivAvatar.setImageResource(avatarRes)
    }

    // You already implemented a custom dialog before; keep your version here.
    private fun showLogoutDialog() {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_logout)
        dialog.setCancelable(true)

        dialog.findViewById<TextView>(R.id.tvTitle)?.text = "Logout"
        dialog.findViewById<TextView>(R.id.tvMsg)?.text  = "Are you sure you want to log out?"

        dialog.findViewById<android.view.View>(R.id.btnCancel)?.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<android.view.View>(R.id.btnLogoutConfirm)?.setOnClickListener {
            dialog.dismiss()
            doLogout()
        }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun doLogout() {
        // Firebase logout
        FirebaseAuth.getInstance().signOut()
        // Also sign-out Google if used (optional):
        // GoogleSignIn.getClient(...).signOut()

        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}
