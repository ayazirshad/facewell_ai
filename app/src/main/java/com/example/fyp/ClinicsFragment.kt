package com.example.fyp

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
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
private const val ARG_RADIUS = "radius_meters"

class ClinicsFragment : Fragment(R.layout.activity_clinics_fragment) {

    private lateinit var rv: RecyclerView
    private lateinit var tvNoClinics: TextView
    private lateinit var adapter: NearbyClinicAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout

    // filter controls
    private lateinit var actClinicType: AutoCompleteTextView
    private lateinit var actRadius: AutoCompleteTextView
    private lateinit var btnSearch: Button

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    // default radius used if not set (meters)
    private var selectedRadiusMeters: Int = 20000
    private val provider = OsmClinicProvider()
    private var cached: List<Clinic>? = null

    // selected category (maps from dropdown)
    private var selectedCategory: ClinicCategory = ClinicCategory.EYE

    // dialog shown when Search button clicked
    private var externalLoaderDialog: Dialog? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rv = view.findViewById(R.id.rvClinics)
        tvNoClinics = view.findViewById(R.id.tvNoClinics)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)

        // filter views
        actClinicType = view.findViewById(R.id.actClinicType)
        actRadius = view.findViewById(R.id.actRadius)
        btnSearch = view.findViewById(R.id.btnSearchClinics)

        // setup recycler
        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = NearbyClinicAdapter(requireContext(), listOf())
        rv.adapter = adapter

        // Setup dropdowns
        val clinicOptions = listOf("Eye Clinic", "Skin Clinic", "Psychiatrist", "All Clinics")
        val radiusOptions = listOf("5 km", "10 km", "20 km", "30 km")
        actClinicType.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, clinicOptions))
        actRadius.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, radiusOptions))

        // wire button -> show loader dialog and run search
        btnSearch.setOnClickListener {
            // read current selections
            val clinicSel = actClinicType.text?.toString()?.trim() ?: ""
            val radSel = actRadius.text?.toString()?.trim() ?: ""

            selectedCategory = when (clinicSel.lowercase()) {
                "eye clinic", "eye" -> ClinicCategory.EYE
                "skin clinic", "skin" -> ClinicCategory.SKIN
                "psychiatrist", "psychologist", "mood" -> ClinicCategory.MOOD
                "all clinics", "all" -> ClinicCategory.EYE // coreClauses include general clinics; keep broad
                else -> ClinicCategory.EYE
            }

            selectedRadiusMeters = when {
                radSel.startsWith("5") -> 5_000
                radSel.startsWith("10") -> 10_000
                radSel.startsWith("20") -> 20_000
                radSel.startsWith("30") -> 30_000
                else -> 20_000
            }

            // show full-screen loader dialog (same style as other activities)
            try {
                val dlg = Dialog(requireContext())
                val loaderView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_simple_loader, null)
                dlg.setContentView(loaderView)
                dlg.setCancelable(false)
                dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
                dlg.show()
                externalLoaderDialog = dlg
            } catch (e: Exception) {
                Log.w(TAG, "failed to show external loader dialog: ${e.message}")
                externalLoaderDialog = null
            }

            // clear cache and reload with filters
            cached = null
            loadClinicsForCurrentUser()
        }

        // pull-to-refresh: calls loadClinicsForCurrentUser
        swipeRefresh.setOnRefreshListener {
            cached = null
            loadClinicsForCurrentUser()
        }

        // If fragment created with args (coming from report activities), use them
        val catArg = arguments?.getString(ARG_CATEGORY)
        val radiusArg = arguments?.getInt(ARG_RADIUS, -1) ?: -1

        if (!catArg.isNullOrBlank()) {
            // set category dropdown from incoming category (e.g., "EYE", "SKIN", "MOOD")
            val incoming = catArg.uppercase()
            when (incoming) {
                "EYE" -> {
                    selectedCategory = ClinicCategory.EYE
                    actClinicType.setText("Eye Clinic", false)
                }
                "SKIN" -> {
                    selectedCategory = ClinicCategory.SKIN
                    actClinicType.setText("Skin Clinic", false)
                }
                "MOOD", "STRESS" -> {
                    selectedCategory = ClinicCategory.MOOD
                    actClinicType.setText("Psychiatrist", false)
                }
                else -> {
                    selectedCategory = ClinicCategory.EYE
                    actClinicType.setText("All Clinics", false)
                }
            }

            // default radius when coming from a report -> 20 km (unless radiusArg supplied)
            selectedRadiusMeters = if (radiusArg > 0) radiusArg else 20_000
            when (selectedRadiusMeters) {
                5_000 -> actRadius.setText("5 km", false)
                10_000 -> actRadius.setText("10 km", false)
                20_000 -> actRadius.setText("20 km", false)
                30_000 -> actRadius.setText("30 km", false)
                else -> actRadius.setText("${selectedRadiusMeters / 1000} km", false)
            }

            // run initial search automatically for flows coming from a report
            tvNoClinics.visibility = View.VISIBLE
            tvNoClinics.text = "Loading clinics..."
            swipeRefresh.isRefreshing = true
            cached = null
            loadClinicsForCurrentUser()
        } else {
            // no category arg -> set sane defaults (All + 20km)
            actClinicType.setText("All Clinics", false)
            actRadius.setText("20 km", false)
            selectedCategory = ClinicCategory.EYE
            selectedRadiusMeters = 20_000

            // initial load
            tvNoClinics.visibility = View.VISIBLE
            tvNoClinics.text = "Loading clinics..."
            swipeRefresh.isRefreshing = true
            loadClinicsForCurrentUser()
        }
    }

    private fun loadClinicsForCurrentUser() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            swipeRefresh.isRefreshing = false
            // dismiss external loader if present
            try { externalLoaderDialog?.dismiss() } catch (_: Exception) {}
            externalLoaderDialog = null

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
                    try { externalLoaderDialog?.dismiss() } catch (_: Exception) {}
                    externalLoaderDialog = null

                    tvNoClinics.visibility = View.VISIBLE
                    tvNoClinics.text = "Allow location or set it in profile."
                    return@addOnSuccessListener
                }

                // call provider with selectedRadiusMeters and selectedCategory
                Log.d(TAG, "Searching clinics at $lat,$lng radius=${selectedRadiusMeters} category=$selectedCategory")
                provider.searchClinics(lat, lng, selectedRadiusMeters, selectedCategory) { list ->
                    requireActivity().runOnUiThread {
                        swipeRefresh.isRefreshing = false

                        // dismiss external loader if present (search button case)
                        try { externalLoaderDialog?.dismiss() } catch (_: Exception) {}
                        externalLoaderDialog = null

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
                try { externalLoaderDialog?.dismiss() } catch (_: Exception) {}
                externalLoaderDialog = null

                tvNoClinics.visibility = View.VISIBLE
                tvNoClinics.text = "Failed to load location."
            }
    }

    companion object {
        /**
         * Convenience factory used previously by report activities.
         * cat: e.g. "EYE", "SKIN", "MOOD"
         * radiusMeters: optional radius in meters (if omitted default 20_000)
         */
        fun newInstanceForCategory(cat: String, radiusMeters: Int = 20_000): ClinicsFragment {
            val f = ClinicsFragment()
            val b = Bundle()
            b.putString(ARG_CATEGORY, cat)
            b.putInt(ARG_RADIUS, radiusMeters)
            f.arguments = b
            return f
        }
    }
}
