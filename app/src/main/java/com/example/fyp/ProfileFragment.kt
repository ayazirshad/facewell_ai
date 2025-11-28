package com.example.fyp

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.fyp.utils.LocationHelper
import com.example.fyp.utils.NetworkUtils
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

private const val TAG = "ProfileFragment"

class ProfileFragment : Fragment(R.layout.activity_profile_fragment) {

    private lateinit var tvFirst: TextView
    private lateinit var tvLast: TextView
    private lateinit var tvDob: TextView
    private lateinit var tvGender: TextView
    private lateinit var ivAvatar: ImageView
    private lateinit var btnToggleLocation: MaterialButton

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    // keep a lightweight holder for the fetched user data (map-like)
    private var currentUserData: Map<String, String>? = null

    // Permission launcher for location requests in this fragment
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarse = perms[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
            Log.d(TAG, "permission result fine=$fine coarse=$coarse")
            if (fine || coarse) {
                // We got permission; fetch location and save
                fetchLocationAndSaveToUser()
            } else {
                val shouldShow = ActivityCompat.shouldShowRequestPermissionRationale(
                    requireActivity(), Manifest.permission.ACCESS_FINE_LOCATION
                )
                if (!shouldShow) {
                    // Possibly permanently denied
                    showSettingsDialog()
                } else {
                    // denied but not permanent
                    showToastShort("Location permission denied")
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvFirst  = view.findViewById(R.id.tvFirst)
        tvLast   = view.findViewById(R.id.tvLast)
        tvDob    = view.findViewById(R.id.tvDob)
        tvGender = view.findViewById(R.id.tvGender)
        ivAvatar = view.findViewById(R.id.ivAvatar)
        btnToggleLocation = view.findViewById(R.id.btnToggleLocation)

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

        // Toggle location button handling
        btnToggleLocation.setOnClickListener {
            val uid = auth.currentUser?.uid ?: return@setOnClickListener
            // Check current state: if user doc has location -> disabling, else enabling
            val hasLocation = try {
                val map = currentUserData
                map != null && map.containsKey("location_lat") // not guaranteed; fallback below
            } catch (_: Exception) { false }

            // Better: fetch fresh doc to be certain
            db.collection("users").document(uid).get().addOnSuccessListener { doc ->
                val locObj = doc.get("location")
                if (locObj == null) {
                    // enable location flow -> request permission then fetch
                    requestLocationPermissionOrFetch()
                } else {
                    // disable location flow -> remove field
                    confirmAndRemoveLocation()
                }
            }.addOnFailureListener {
                // fallback: try permission path
                requestLocationPermissionOrFetch()
            }
        }

        fetchUser()
    }

    override fun onResume() {
        super.onResume()
        // Re-fetch to make sure latest saved profile shows up
        fetchUser()
    }

    private fun requestLocationPermissionOrFetch() {
        // If offline: just show message
        if (!NetworkUtils.isOnline(requireContext())) {
            showToastShort("You are offline. Please connect to enable location.")
            return
        }
        // If already have permission, fetch immediately
        if (LocationHelper.hasLocationPermission(requireContext())) {
            fetchLocationAndSaveToUser()
            return
        }
        // else request permissions (will call permissionLauncher)
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    private fun fetchLocationAndSaveToUser() {
        val uid = auth.currentUser?.uid ?: return
        val dlg = android.app.Dialog(requireContext())
        val loader = layoutInflater.inflate(R.layout.dialog_simple_loader, null)
        dlg.setContentView(loader)
        dlg.setCancelable(false)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.show()

        LocationHelper.getLastLocation(requireActivity(),
            onSuccess = { loc: Location? ->
                dlg.dismiss()
                if (loc == null) {
                    showToastShort("Unable to obtain location.")
                    return@getLastLocation
                }
                val payload = mapOf("location" to mapOf("lat" to loc.latitude, "lng" to loc.longitude))
                db.collection("users").document(uid)
                    .set(payload, SetOptions.merge())
                    .addOnSuccessListener {
                        showToastShort("Location enabled")
                        // refresh user, update UI and broadcast update
                        fetchUserAndBroadcast()
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "failed saving location", e)
                        showToastShort("Failed to save location")
                        fetchUserAndBroadcast()
                    }
            },
            onFailure = { ex ->
                dlg.dismiss()
                Log.e(TAG, "LocationHelper failed", ex)
                showToastShort("Location error")
            })
    }

    private fun confirmAndRemoveLocation() {
        val uid = auth.currentUser?.uid ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("Disable location")
            .setMessage("Are you sure you want to remove saved location? App will ask for location again next time.")
            .setPositiveButton("Remove") { _, _ ->
                // remove 'location' field from user doc
                db.collection("users").document(uid)
                    .update("location", FieldValue.delete())
                    .addOnSuccessListener {
                        showToastShort("Location removed")
                        fetchUserAndBroadcast()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed remove location", e)
                        // fallback: set location to null (merge)
                        db.collection("users").document(uid)
                            .set(mapOf("location" to null), SetOptions.merge())
                            .addOnSuccessListener {
                                showToastShort("Location removed")
                                fetchUserAndBroadcast()
                            }
                            .addOnFailureListener { ex -> Log.e(TAG, "remove fallback failed", ex); showToastShort("Failed to remove location") }
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fetchUserAndBroadcast() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val map = snapToMap(uid, doc)
                currentUserData = map
                bindUserToUiFromMap(map)

                // broadcast updated user map so HomeFragment/MainActivity can refresh
                val intent = Intent("com.example.fyp.USER_UPDATED")
                intent.putExtra("updated_user_map", HashMap(map))
                LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(intent)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "fetchUserAndBroadcast failed", e)
            }
    }

    private fun fetchUserAndOpenEdit() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val map = snapToMap(uid, doc)
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
                val map = snapToMap(uid, doc)
                currentUserData = map
                bindUserToUiFromMap(map)
            }
    }

    private fun snapToMap(uid: String, doc: com.google.firebase.firestore.DocumentSnapshot): Map<String, String> {
        val locationObj = doc.get("location")
        // flatten small location flags for UI convenience (not required)
        val locationLat = when (val v = (locationObj as? Map<*, *>)?.get("lat")) {
            is Number -> v.toString()
            is String -> v
            else -> ""
        }
        val locationLng = when (val v = (locationObj as? Map<*, *>)?.get("lng")) {
            is Number -> v.toString()
            is String -> v
            else -> ""
        }
        return mapOf(
            "uid" to uid,
            "firstName" to (doc.getString("firstName") ?: ""),
            "lastName"  to (doc.getString("lastName") ?: ""),
            "city"      to (doc.getString("city") ?: ""),
            "dob"       to (doc.getString("dob") ?: ""),
            "gender"    to (doc.getString("gender") ?: ""),
            "email"     to (auth.currentUser?.email ?: (doc.getString("email") ?: "")),
            "phone"     to (doc.getString("phone") ?: ""),
            "avatar"    to (doc.getString("avatar") ?: "ic_profile"),
            "stage"     to (doc.getLong("stage")?.toString() ?: "0"),
            "location_lat" to locationLat,
            "location_lng" to locationLng
        )
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
            "prefer not to say", "prefer_not_to_say" -> "Prefer not to say"
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

        // Update toggle button text based on presence of location
        val hasLocation = !(data["location_lat"].isNullOrBlank() || data["location_lng"].isNullOrBlank())
        if (hasLocation) {
            btnToggleLocation.text = "Disable Location"
            btnToggleLocation.setTextColor(resources.getColor(R.color.red))
            btnToggleLocation.setBackgroundColor(resources.getColor(R.color.input_fill))
            btnToggleLocation.strokeColor = null // keep thin style
        } else {
            btnToggleLocation.text = "Enable Location"
            btnToggleLocation.setTextColor(resources.getColor(R.color.teal_bg))
            btnToggleLocation.setBackgroundColor(resources.getColor(R.color.white))
        }
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

    private fun showSettingsDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Location permission required")
            .setMessage("Location permission is disabled. To enable, open app settings and allow location.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", requireContext().packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showToastShort(msg: String) {
        try { android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }
}
