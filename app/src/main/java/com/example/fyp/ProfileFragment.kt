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

    private var currentUser: UserProfile? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvFirst  = view.findViewById(R.id.tvFirst)
        tvLast   = view.findViewById(R.id.tvLast)
        tvDob    = view.findViewById(R.id.tvDob)
        tvGender = view.findViewById(R.id.tvGender)
        ivAvatar = view.findViewById(R.id.ivAvatar)

        view.findViewById<View>(R.id.badgeEdit).setOnClickListener {
            (requireActivity() as MainActivity)
                .openOverlay(EditProfileFragment.newInstance(currentUser), "editProfile")
        }

        view.findViewById<View>(R.id.rowLogout).setOnClickListener { showLogoutDialog() }

        fetchUser()
    }

    private fun fetchUser() {
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

    private fun bindUserToUi(user: UserProfile) {
        tvFirst.text = user.firstName
        tvLast.text  = user.lastName
        tvDob.text   = user.dob
        tvGender.text = user.gender.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }

        val avatarRes = when (user.avatar) {
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
        // Sign out Firebase (and Google if you use it)
        FirebaseAuth.getInstance().signOut()
        // GoogleSignIn.getClient(requireContext(), gso).signOut() // optional

        // Go to Login and clear the back stack so the user can't return
        val intent = Intent(requireContext(), LoginActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)

        // Finish the host activity of this fragment
        requireActivity().finish()
        // Or: requireActivity().finishAffinity()
    }
}
