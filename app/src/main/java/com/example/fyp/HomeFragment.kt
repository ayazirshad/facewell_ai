package com.example.fyp

import android.Manifest
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.fyp.weather.WeatherService
import com.example.fyp.utils.LocationHelper
import com.example.fyp.utils.NetworkUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Locale

class HomeFragment : Fragment(R.layout.activity_home_fragment) {

    private lateinit var tvGreeting: TextView
    private lateinit var tvPromoTitle: TextView

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    // weather views
    private var cardWeather: View? = null
    private var ivWeatherIcon: ImageView? = null
    private var tvWeatherTemp: TextView? = null
    private var tvWeatherMain: TextView? = null
    private var tvWeatherHumidity: TextView? = null
    private var tvWeatherUvi: TextView? = null

    private var currentUserUid: String? = null

    // Permission launcher for location (works on Android 6+ and Android 13 behavior)
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarse = perms[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
            if (fine || coarse) {
                // permission granted -> fetch location and save
                fetchAndSaveLocationThenLoadWeather()
            } else {
                // denied -> keep location null, hide weather card
                cardWeather?.visibility = View.GONE
                Toast.makeText(requireContext(), "Location not granted — skipping weather & clinics.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvGreeting = view.findViewById(R.id.tvGreeting)
        tvPromoTitle = view.findViewById(R.id.tvPromoTitle)

        // bind weather views
        cardWeather = view.findViewById(R.id.cardWeather)
        ivWeatherIcon = view.findViewById(R.id.ivWeatherIcon)
        tvWeatherTemp = view.findViewById(R.id.tvWeatherTemp)
        tvWeatherMain = view.findViewById(R.id.tvWeatherMain)
        tvWeatherHumidity = view.findViewById(R.id.tvWeatherHumidity)
        tvWeatherUvi = view.findViewById(R.id.tvWeatherUvi)

        // Default promo title by time (will be updated by weather when available)
        tvPromoTitle.text = String.format(Locale.getDefault(), "Good %s", greetingByHour())

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

    /** Fetch the user doc once; if location is missing/null -> request perms and save location */
    private fun fetchUserOnce() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            setGreeting("User")
            return
        }
        currentUserUid = uid

        db.collection("users").document(uid).get()
            .addOnSuccessListener { snap ->
                val firstName = snap.getString("firstName") ?: ""
                setGreeting(firstName)
                // check if 'location' exists
                val locObj = snap.get("location")
                if (locObj == null) {
                    // No location saved yet -> ask for permission (if needed) and save
                    ensureLocationSavedForUser()
                } else {
                    // location exists -> use it to load weather
                    val map = locObj as? Map<*, *>
                    val lat = when (val v = map?.get("lat")) {
                        is Number -> v.toDouble()
                        is String -> v.toDoubleOrNull()
                        else -> null
                    }
                    val lng = when (val v = map?.get("lng")) {
                        is Number -> v.toDouble()
                        is String -> v.toDoubleOrNull()
                        else -> null
                    }

                    if (lat != null && lng != null) {
                        // we have saved coordinates -> load weather using them
                        loadWeatherUsingCoords(lat, lng)
                    } else {
                        // malformed -> attempt to fetch again
                        ensureLocationSavedForUser()
                    }
                }
            }
            .addOnFailureListener {
                setGreeting("User")
                // do not crash; weather card hidden
                cardWeather?.visibility = View.GONE
            }
    }

    override fun onResume() {
        super.onResume()
        // re-check location saving for the user in case permission/flow changed while fragment was not visible
        // if we already have uid and user doc previously had no location, this will request/fetch now
        if (currentUserUid != null) {
            // Query user doc quickly to see if location exists (safe & ensures we don't re-request unnecessarily)
            db.collection("users").document(currentUserUid!!).get()
                .addOnSuccessListener { snap ->
                    val locObj = snap.get("location")
                    if (locObj == null) {
                        // ask/save
                        ensureLocationSavedForUser()
                    } else {
                        // already saved - you may still choose to call loadWeatherUsingCoords here
                        val map = locObj as? Map<*, *>
                        val lat = when (val v = map?.get("lat")) {
                            is Number -> v.toDouble()
                            is String -> v.toDoubleOrNull()
                            else -> null
                        }
                        val lng = when (val v = map?.get("lng")) {
                            is Number -> v.toDouble()
                            is String -> v.toDoubleOrNull()
                            else -> null
                        }
                        if (lat != null && lng != null) {
                            loadWeatherUsingCoords(lat, lng)
                        } else {
                            ensureLocationSavedForUser()
                        }
                    }
                }
                .addOnFailureListener {
                    // ignore; you may show a toast if needed
                }
        }
    }


    private fun setGreeting(first: String) {
        tvGreeting.text = "Hello, ${if (first.isBlank()) "User" else first}"
    }

    /** Check permission status; if granted -> fetch & save; otherwise request permission */
    private fun ensureLocationSavedForUser() {
        // offline guard
        if (!NetworkUtils.isOnline(requireContext())) {
            cardWeather?.visibility = View.GONE
            Toast.makeText(requireContext(), "You appear to be offline — weather will not be fetched.", Toast.LENGTH_SHORT).show()
            return
        }

        // If we already have permission -> go fetch now
        if (LocationHelper.hasLocationPermission(requireContext())) {
            fetchAndSaveLocationThenLoadWeather()
            return
        }

        // else trigger permission dialog
        val permissions = mutableListOf<String>()
        permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        permissions += Manifest.permission.ACCESS_FINE_LOCATION
        // ask
        permissionLauncher.launch(permissions.toTypedArray())
    }

    /** Get last location via helper; if found, save to Firestore and load weather */
    private fun fetchAndSaveLocationThenLoadWeather() {
        val uid = currentUserUid ?: run {
            cardWeather?.visibility = View.GONE
            return
        }
        // fetch location
        LocationHelper.getLastLocation(requireActivity(),
            onSuccess = { loc: Location? ->
                if (loc == null) {
                    // couldn't obtain
                    requireActivity().runOnUiThread {
                        cardWeather?.visibility = View.GONE
                        Toast.makeText(requireContext(), "Unable to obtain location.", Toast.LENGTH_SHORT).show()
                    }
                    return@getLastLocation
                }
                // Save to Firestore user doc
                val payload = mapOf("location" to mapOf("lat" to loc.latitude, "lng" to loc.longitude))
                db.collection("users").document(uid)
                    .set(payload, SetOptions.merge())
                    .addOnSuccessListener {
                        // Now load weather using these coords
                        loadWeatherUsingCoords(loc.latitude, loc.longitude)
                    }
                    .addOnFailureListener { ex ->
                        ex.printStackTrace()
                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), "Failed to save location.", Toast.LENGTH_SHORT).show()
                        }
                        // still try to load weather (best-effort)
                        loadWeatherUsingCoords(loc.latitude, loc.longitude)
                    }
            },
            onFailure = { ex ->
                ex?.printStackTrace()
                requireActivity().runOnUiThread {
                    cardWeather?.visibility = View.GONE
                    Toast.makeText(requireContext(), "Failed to get location.", Toast.LENGTH_SHORT).show()
                }
            })
    }

    /** Use coordinates to fetch weather via your WeatherService (same interface as earlier). */
    private fun loadWeatherUsingCoords(lat: Double, lng: Double) {
        // check connectivity
        if (!NetworkUtils.isOnline(requireContext())) {
            requireActivity().runOnUiThread { cardWeather?.visibility = View.GONE }
            return
        }

        val ws = WeatherService(requireContext())
        ws.fetchCurrent(lat, lng) { snap ->
            if (snap == null) {
                requireActivity().runOnUiThread { cardWeather?.visibility = View.GONE }
                return@fetchCurrent
            }
            requireActivity().runOnUiThread {
                try {
                    cardWeather?.visibility = View.VISIBLE
                    tvWeatherTemp?.text = String.format(Locale.getDefault(), "%.0f°C", snap.tempC)
                    tvWeatherMain?.text = snap.weatherMain ?: ""
                    tvWeatherHumidity?.text = "Humidity: ${snap.humidity}%"
                    tvWeatherUvi?.text = "UVI: ${snap.uvi}"
                    val iconRes = when {
//                        ye pics ko dobar change karna hy bhai
                        (snap.weatherMain ?: "").contains("Clear", ignoreCase = true) -> R.drawable.ic_weather_placeholder
                        (snap.weatherMain ?: "").contains("Cloud", ignoreCase = true) -> R.drawable.ic_weather_placeholder
                        (snap.weatherMain ?: "").contains("Rain", ignoreCase = true) -> R.drawable.ic_weather_placeholder
                        (snap.weatherMain ?: "").contains("Snow", ignoreCase = true) -> R.drawable.ic_weather_placeholder
                        else -> R.drawable.ic_weather_placeholder
                    }
                    try { ivWeatherIcon?.setImageResource(iconRes) } catch (_: Exception) {}

                    // Update promo title to include greeting and temp
                    tvPromoTitle.text = String.format(Locale.getDefault(), "Good %s · %.0f°C", greetingByHour(), snap.tempC)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun greetingByHour(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (h) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..20 -> "Evening"
            else -> "Night"
        }
    }
}
