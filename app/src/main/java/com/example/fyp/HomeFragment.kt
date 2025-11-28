package com.example.fyp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.fyp.weather.WeatherService
import com.example.fyp.utils.LocationHelper
import com.example.fyp.utils.NetworkUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Locale

private const val TAG = "HomeFragment"

/**
 * Design reference (uploaded by user):
 * sandbox:/mnt/data/ccb89a78-5d83-4e45-b691-2eaeced8983f.png
 */
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
    private var tvWeatherFeels: TextView? = null
    private var tvWeatherPlace: TextView? = null

    private var currentUserUid: String? = null

    private lateinit var swipeRefresh: SwipeRefreshLayout

    // Permission launcher for location
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarse = perms[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
            Log.d(TAG, "permission result fine=$fine coarse=$coarse")

            if (fine || coarse) {
                // Granted now (covers "Allow while using" and "Allow only this time")
                fetchAndSaveLocationThenLoadWeather()
                return@registerForActivityResult
            }

            // Not granted. Detect if user permanently denied (Don't ask again) via shouldShowRequestPermissionRationale
            val shouldShow = ActivityCompat.shouldShowRequestPermissionRationale(
                requireActivity(),
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            if (shouldShow) {
                // User denied but we can ask again later — show placeholder and friendly toast
                showWeatherPlaceholder("Location denied")
                Toast.makeText(requireContext(), "Location denied — weather disabled", Toast.LENGTH_SHORT).show()
            } else {
                // If shouldShow is false and permission not granted -> either first-time denied or permanently denied.
                // Treat this as "permanently denied" case (safe fallback): show UI so user can enable in settings / profile.
                showWeatherPlaceholder("Location denied")
                showLocationSettingsDialog()
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvGreeting = view.findViewById(R.id.tvGreeting)
        tvPromoTitle = view.findViewById(R.id.tvPromoTitle)

        cardWeather = view.findViewById(R.id.cardWeather)
        ivWeatherIcon = view.findViewById(R.id.ivWeatherIcon)
        tvWeatherTemp = view.findViewById(R.id.tvWeatherTemp)
        tvWeatherMain = view.findViewById(R.id.tvWeatherMain)
        tvWeatherHumidity = view.findViewById(R.id.tvWeatherHumidity)
        tvWeatherFeels = view.findViewById(R.id.tvWeatherFeels)
        tvWeatherPlace = view.findViewById(R.id.tvWeatherPlace)

        cardWeather?.visibility = View.VISIBLE
        showWeatherPlaceholder("Loading...")

        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        swipeRefresh.setOnRefreshListener {
            fetchUserOnce {
                swipeRefresh.isRefreshing = false
            }
        }

        tvPromoTitle.text = String.format(Locale.getDefault(), "Good %s", greetingByHour())

//        view.findViewById<View>(R.id.qaSkin).setOnClickListener {
//            startActivity(Intent(requireContext(), ScanSkinActivity::class.java))
//        }
//        view.findViewById<View>(R.id.qaEye).setOnClickListener {
//            startActivity(Intent(requireContext(), ScanEyeActivity::class.java))
//        }
//        view.findViewById<View>(R.id.qaStress).setOnClickListener {
//            startActivity(Intent(requireContext(), StressCheckActivity::class.java))
//        }
//        view.findViewById<View>(R.id.qaFullFace).setOnClickListener {
//            startActivity(Intent(requireContext(), FullFaceScanActivity::class.java))
//        }

        fetchUserOnce()
    }

    private fun fetchUserOnce(done: (() -> Unit)? = null) {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            setGreeting("User")
            showWeatherPlaceholder("Not signed in")
            done?.invoke()
            return
        }
        currentUserUid = uid

        db.collection("users").document(uid).get()
            .addOnSuccessListener { snap ->
                val firstName = snap.getString("firstName") ?: ""
                setGreeting(firstName)

                val locObj = snap.get("location")
                if (locObj == null) {
                    Log.d(TAG, "no location in user doc")
                    ensureLocationSavedForUser()
                    done?.invoke()
                } else {
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
                        Log.d(TAG, "found saved location: $lat, $lng")
                        loadWeatherUsingCoords(lat, lng)
                    } else {
                        Log.d(TAG, "Location malformed-> requesting fresh")
                        ensureLocationSavedForUser()
                    }
                    done?.invoke()
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "User fetching failed", e)
                setGreeting("User")
                showWeatherPlaceholder("Error loading user")
                done?.invoke()
            }
    }

    override fun onResume() {
        super.onResume()
        if (currentUserUid != null) {
            db.collection("users").document(currentUserUid!!).get()
                .addOnSuccessListener { snap ->
                    val locObj = snap.get("location")
                    if (locObj == null) {
                        ensureLocationSavedForUser()
                    } else {
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
                .addOnFailureListener { e ->
                    Log.w(TAG, "resume: couldn't fetch user doc", e)
                }
        }
    }

    private fun setGreeting(first: String) {
        tvGreeting.text = "Hello, ${if (first.isBlank()) "User" else first}"
    }

    private fun ensureLocationSavedForUser() {
        if (!NetworkUtils.isOnline(requireContext())) {
            showWeatherPlaceholder("Offline")
            Toast.makeText(requireContext(), "You appear offline, weather may be stale.", Toast.LENGTH_SHORT).show()
            return
        }

        if (LocationHelper.hasLocationPermission(requireContext())) {
            // Has permission right now (covers both "while using" and "allow only this time")
            fetchAndSaveLocationThenLoadWeather()
            return
        }

        // No permission right now. Before launching, check if we should show rationale.
        val shouldShow = ActivityCompat.shouldShowRequestPermissionRationale(
            requireActivity(),
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (shouldShow) {
            // Explain briefly then request
            AlertDialog.Builder(requireContext())
                .setTitle("Location required")
                .setMessage("We use your location to show local weather. Please allow location access.")
                .setPositiveButton("Allow") { _, _ ->
                    permissionLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ))
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            // Either first-time request or permanently denied.
            // We'll request permissions once — if this is permanently denied, the permissionLauncher code will show settings dialog.
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            ))
        }
    }

    private fun fetchAndSaveLocationThenLoadWeather() {
        val uid = currentUserUid ?: run {
            showWeatherPlaceholder("No user")
            return
        }

        LocationHelper.getLastLocation(requireActivity(),
            onSuccess = { loc: Location? ->
                if (loc == null) {
                    Log.w(TAG, "getLastLocation returned null")
                    showWeatherPlaceholder("Unable to get location")
                    return@getLastLocation
                }

                val payload = mapOf("location" to mapOf("lat" to loc.latitude, "lng" to loc.longitude))
                db.collection("users").document(uid)
                    .set(payload, SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d(TAG, "saved location to user doc")
                        loadWeatherUsingCoords(loc.latitude, loc.longitude)
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "failed saving location but will still try to load weather", e)
                        loadWeatherUsingCoords(loc.latitude, loc.longitude)
                    }
            },
            onFailure = { ex ->
                Log.e(TAG, "LocationHelper failed", ex)
                showWeatherPlaceholder("Location error")
            })
    }

    private fun loadWeatherUsingCoords(lat: Double, lng: Double) {
        if (!NetworkUtils.isOnline(requireContext())) {
            showWeatherPlaceholder("Offline")
            return
        }

        Log.d(TAG, "loading weather for $lat,$lng")
        showWeatherPlaceholder("Loading...")

        val ws = WeatherService(requireContext())
        ws.fetchCurrent(lat, lng) { snap ->
            requireActivity().runOnUiThread {
                if (snap == null) {
                    Log.w(TAG, "WeatherService returned null snapshot")
                    showWeatherPlaceholder("No data")
                    return@runOnUiThread
                }

                try {
                    tvWeatherTemp?.text = String.format(Locale.getDefault(), "%.0f°C", snap.tempC)
                    tvWeatherMain?.text = snap.weatherMain ?: "—"
                    tvWeatherHumidity?.text = "Humidity: ${snap.humidity}%"
                    tvWeatherFeels?.text = "Feels: ${snap.feelsLikeC?.let { String.format(Locale.getDefault(),"%.0f°C", it) } ?: "--°C"}"
                    tvWeatherPlace?.text = snap.placeName ?: "Unknown"

                    val main = snap.weatherMain ?: ""
                    val iconRes = when {
                        main.contains("Clear", ignoreCase = true) -> R.drawable.ic_weather_sunny
                        main.contains("Cloud", ignoreCase = true) -> R.drawable.ic_weather_cloudy
                        main.contains("Rain", ignoreCase = true) || main.contains("Drizzle", ignoreCase = true) -> R.drawable.ic_weather_rain
                        main.contains("Snow", ignoreCase = true) -> R.drawable.ic_weather_snow
                        else -> R.drawable.ic_weather_placeholder
                    }
                    ivWeatherIcon?.setImageResource(iconRes)

//                    tvPromoTitle.text = String.format(Locale.getDefault(), "Good %s · %.0f°C", greetingByHour(), snap.tempC)
                    Log.d(TAG, "weather updated UI")
                } catch (e: Exception) {
                    Log.e(TAG, "error updating weather UI", e)
                    showWeatherPlaceholder("UI error")
                }
            }
        }
    }

    private fun showWeatherPlaceholder(reason: String) {
        Log.d(TAG, "showWeatherPlaceholder: $reason")
        requireActivity().runOnUiThread {
            cardWeather?.visibility = View.VISIBLE
            tvWeatherTemp?.text = "--°C"
            tvWeatherMain?.text = when (reason) {
                "Loading..." -> "Loading..."
                "Offline" -> "Offline"
                "Location denied" -> "Location denied"
                "Not signed in" -> "Sign in to fetch weather"
                else -> "Weather unavailable"
            }
            tvWeatherHumidity?.text = "Humidity: --"
            tvWeatherFeels?.text = "Feels: --°C"
            tvWeatherPlace?.text = "" // keep muted/empty when placeholder shown
            try { ivWeatherIcon?.setImageResource(R.drawable.ic_weather_placeholder) } catch (_: Exception) {}
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

    private fun showLocationSettingsDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Location permission required")
            .setMessage("Location permission is disabled. To enable weather and clinic features, open app settings and allow location.")
            .setPositiveButton("Open Settings") { _, _ ->
                // Open app settings; user can enable location permission or go to Profile later as you planned
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", requireContext().packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
