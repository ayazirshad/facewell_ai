package com.example.fyp.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import java.util.concurrent.Executors

object LocationHelper {
    // check runtime permission
    fun hasLocationPermission(ctx: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * getLastLocation with fallback to getCurrentLocation().
     * onSuccess receives nullable Location (null means failed).
     * onFailure receives throwable (optional).
     */
    fun getLastLocation(
        activity: Activity,
        onSuccess: (Location?) -> Unit,
        onFailure: ((Exception?) -> Unit)? = null
    ) {
        try {
            val client = LocationServices.getFusedLocationProviderClient(activity)

            // first try cached lastLocation
            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    onSuccess(loc)
                    return@addOnSuccessListener
                }

                // fallback: getCurrentLocation - best-effort (uses fused provider; needs Google Play services)
                val cancellationTokenSource = com.google.android.gms.tasks.CancellationTokenSource()
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellationTokenSource.token)
                    .addOnSuccessListener { current ->
                        onSuccess(current) // may be null
                    }
                    .addOnFailureListener { ex ->
                        onFailure?.invoke(ex as? Exception)
                        onSuccess(null)
                    }
            }.addOnFailureListener { ex ->
                // if lastLocation failed, still try getCurrentLocation
                val cancellationTokenSource = com.google.android.gms.tasks.CancellationTokenSource()
                val client2 = LocationServices.getFusedLocationProviderClient(activity)
                client2.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellationTokenSource.token)
                    .addOnSuccessListener { current ->
                        onSuccess(current)
                    }
                    .addOnFailureListener { ex2 ->
                        onFailure?.invoke(ex2 as? Exception)
                        onSuccess(null)
                    }
            }
        } catch (e: Exception) {
            onFailure?.invoke(e)
            onSuccess(null)
        }
    }
}
