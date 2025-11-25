package com.example.fyp

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.fyp.models.Clinic
import com.example.fyp.providers.ClinicCategory
import com.example.fyp.providers.OsmClinicProvider
import com.example.fyp.ui.NearbyClinicAdapter
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

private const val TAG = "ClinicsFragment"
private const val ARG_CATEGORY = "category"

class ClinicsFragment : Fragment(R.layout.activity_clinics_fragment) {

    private lateinit var rv: RecyclerView
    private lateinit var tvNoClinics: TextView
    private lateinit var adapter: NearbyClinicAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val searchRadius = 25000
    private val provider = OsmClinicProvider()
    private var cached: List<Clinic>? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rv = view.findViewById(R.id.rvClinics)
        tvNoClinics = view.findViewById(R.id.tvNoClinics)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)

        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = NearbyClinicAdapter(requireContext(), listOf())
        rv.adapter = adapter

        // pull-to-refresh: calls loadClinicsForCurrentUser()
        swipeRefresh.setOnRefreshListener {
            // force re-fetch (ignore cache)
            cached = null
            loadClinicsForCurrentUser()
        }

        // initial load
        tvNoClinics.visibility = View.VISIBLE
        tvNoClinics.text = "Loading clinics..."
        swipeRefresh.isRefreshing = true
        loadClinicsForCurrentUser()
    }

    private fun loadClinicsForCurrentUser() {
        val catArg = arguments?.getString(ARG_CATEGORY) ?: "EYE"
        val category = when (catArg.uppercase()) {
            "EYE" -> ClinicCategory.EYE
            "SKIN" -> ClinicCategory.SKIN
            "MOOD", "STRESS" -> ClinicCategory.MOOD
            else -> ClinicCategory.EYE
        }

        val uid = auth.currentUser?.uid
        if (uid == null) {
            swipeRefresh.isRefreshing = false
            tvNoClinics.visibility = View.VISIBLE
            tvNoClinics.text = "Sign in to find nearby clinics"
            return
        }

        // show loading state
        tvNoClinics.visibility = View.VISIBLE
        tvNoClinics.text = "Loading clinics..."
        // if cache present show immediately but still refresh in background if desired:
        val local = cached
        if (local != null && local.isNotEmpty()) {
            adapter.update(local)
            tvNoClinics.visibility = View.GONE
        }

        // fetch user location from Firestore
        db.collection("users").document(uid).get()
            .addOnSuccessListener { snap ->
                val loc = snap.get("location") as? Map<*, *>
                val lat = when (val v = loc?.get("lat")) {
                    is Number -> v.toDouble()
                    is String -> v.toDoubleOrNull()
                    else -> null
                }
                val lng = when (val v = loc?.get("lng")) {
                    is Number -> v.toDouble()
                    is String -> v.toDoubleOrNull()
                    else -> null
                }
                if (lat == null || lng == null) {
                    swipeRefresh.isRefreshing = false
                    tvNoClinics.visibility = View.VISIBLE
                    tvNoClinics.text = "Allow location or set it in profile."
                    return@addOnSuccessListener
                }

                // call provider
                provider.searchClinics(lat, lng, searchRadius, category) { list ->
                    requireActivity().runOnUiThread {
                        swipeRefresh.isRefreshing = false
                        if (list == null) {
                            tvNoClinics.visibility = View.VISIBLE
                            tvNoClinics.text = "Failed to load clinics. Try again."
                            adapter.update(listOf())
                            return@runOnUiThread
                        }
                        if (list.isEmpty()) {
                            tvNoClinics.visibility = View.VISIBLE
                            tvNoClinics.text = "No clinics found in your area."
                            adapter.update(listOf())
                            cached = list
                            return@runOnUiThread
                        }

                        // success
                        cached = list
                        adapter.update(list)
                        tvNoClinics.visibility = View.GONE
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "fetch user failed", e)
                swipeRefresh.isRefreshing = false
                tvNoClinics.visibility = View.VISIBLE
                tvNoClinics.text = "Failed to load location."
            }
    }

    companion object {
        fun newInstanceForCategory(cat: String): ClinicsFragment {
            val f = ClinicsFragment()
            val b = Bundle()
            b.putString(ARG_CATEGORY, cat)
            f.arguments = b
            return f
        }
    }
}
