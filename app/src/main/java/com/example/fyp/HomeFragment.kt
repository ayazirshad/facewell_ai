package com.example.fyp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class HomeFragment : Fragment(R.layout.activity_home_fragment) {

    private lateinit var tvGreeting: TextView
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private var currentUser: UserProfile? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvGreeting = view.findViewById(R.id.tvGreeting)

        // Quick actions
        view.findViewById<View>(R.id.qaSkin).setOnClickListener {
            startActivity(Intent(requireContext(), ScanSkinActivity::class.java))
        }
        view.findViewById<View>(R.id.qaEye).setOnClickListener {
            startActivity(Intent(requireContext(), ScanEyeActivity::class.java))
        }
        view.findViewById<View>(R.id.qaStress).setOnClickListener {
            startActivity(Intent(requireContext(), StressCheckActivity::class.java))
        }

        // Lists
        view.findViewById<RecyclerView>(R.id.rvReports).apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = SimpleCardAdapter(listOf("Skin – 10/12", "Eye – 10/10", "Stress – 10/02"))
        }
        view.findViewById<RecyclerView>(R.id.rvClinics).apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = SimpleCardAdapter(listOf("Dermacare", "Eye Vision", "Mind Clinic"))
        }

        fetchUserOnce()
    }

    private fun fetchUserOnce() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { snap ->
                val user = UserProfile(
                    uid      = uid,
                    firstName= snap.getString("firstName") ?: "",
                    lastName = snap.getString("lastName") ?: "",
                    city     = snap.getString("city") ?: "",
                    dob      = snap.getString("dob") ?: "",
                    gender   = snap.getString("gender") ?: "",
                    email    = auth.currentUser?.email ?: (snap.getString("email") ?: ""),
                    phone    = snap.getString("phone") ?: "",
                    avatar   = snap.getString("avatar") ?: "ic_profile",
                    stage    = (snap.getLong("stage") ?: 0L).toInt()
                )
                currentUser = user
                setGreeting(user.firstName)
            }
            .addOnFailureListener {
                setGreeting("User")
            }
    }

    private fun setGreeting(first: String) {
        tvGreeting.text = "Hello, ${if (first.isBlank()) "User" else first}"
    }
}
