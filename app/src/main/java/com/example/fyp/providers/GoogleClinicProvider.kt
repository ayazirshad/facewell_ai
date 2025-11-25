package com.example.fyp.providers

import android.content.Context
import android.util.Log
import com.example.fyp.R
import com.example.fyp.models.Clinic
import com.example.fyp.network.PlacesApiService
import com.example.fyp.network.DirectionsApiService
import com.example.fyp.utils.GeoUtils
import com.google.gson.GsonBuilder
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import kotlin.math.roundToInt

private const val TAG = "GoogleClinicProvider"

class GoogleClinicProvider(private val context: Context) : ClinicProvider {
    private val apiKey: String = context.getString(R.string.google_maps_api_key)

    private val placesApi: PlacesApiService by lazy {
        val gson = GsonBuilder().create()
        val r = Retrofit.Builder()
            .baseUrl("https://maps.googleapis.com/")
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        r.create(PlacesApiService::class.java)
    }

    private val directionsApi: DirectionsApiService by lazy {
        val gson = GsonBuilder().create()
        val r = Retrofit.Builder()
            .baseUrl("https://maps.googleapis.com/")
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        r.create(DirectionsApiService::class.java)
    }

    override fun searchClinics(lat: Double, lng: Double, radiusMeters: Int, category: ClinicCategory, cb: (List<Clinic>?) -> Unit) {
        // map category -> keyword (Places NearbySearch supports keyword param)
        val keyword = when (category) {
            ClinicCategory.EYE -> "ophthalmologist OR eye clinic OR optometrist"
            ClinicCategory.SKIN -> "dermatologist OR skin clinic OR dermatology"
            ClinicCategory.MOOD -> "psychiatrist OR psychologist OR mental health clinic"
        }

        try {
            val call = placesApi.nearbySearch("$lat,$lng", radiusMeters, keyword, apiKey)
            call.enqueue(object : retrofit2.Callback<PlacesApiService.NearbySearchResponse> {
                override fun onResponse(call: retrofit2.Call<PlacesApiService.NearbySearchResponse>, response: retrofit2.Response<PlacesApiService.NearbySearchResponse>) {
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Nearby search failed code=${response.code()}")
                        cb(null)
                        return
                    }
                    val body = response.body()
                    if (body == null || body.results == null) {
                        Log.w(TAG, "Nearby search empty")
                        cb(emptyList())
                        return
                    }

                    // Convert to Clinic objects. Skip any result missing lat/lng
                    val results = body.results.mapNotNull { r ->
                        val loc = r.geometry?.location
                        val plat = loc?.lat ?: return@mapNotNull null   // skip if null
                        val plng = loc.lng ?: return@mapNotNull null     // skip if null
                        val dist = GeoUtils.haversineMeters(lat, lng, plat, plng)
                        Clinic(
                            id = r.place_id ?: "",
                            name = r.name ?: "Clinic",
                            lat = plat,
                            lng = plng,
                            address = r.vicinity ?: r.formatted_address,
                            phone = null, // will fetch via details if needed
                            email = null,
                            specialist = null,
                            distanceMeters = dist.roundToInt(),
                            travelTimeSecCar = null,
                            travelDistanceMeters = null,
                            rating = r.rating,
                            openNow = r.opening_hours?.open_now
                        )
                    }.sortedBy { it.distanceMeters ?: Int.MAX_VALUE }

                    // For better UX, fetch Directions for top N (e.g., top 6) only
                    val topN = results.take(6)
                    if (topN.isEmpty()) {
                        cb(results)
                        return
                    }

                    // For each top result call Directions origin->destination (driving)
                    var remaining = topN.size
                    val enriched = results.toMutableList()
                    for (item in topN) {
                        val dest = "${item.lat},${item.lng}"
                        val origin = "$lat,$lng"
                        val dirCall = directionsApi.getDirections(origin, dest, "driving", apiKey)
                        dirCall.enqueue(object : retrofit2.Callback<DirectionsApiService.DirectionsResponse> {
                            override fun onResponse(call: retrofit2.Call<DirectionsApiService.DirectionsResponse>, response: retrofit2.Response<DirectionsApiService.DirectionsResponse>) {
                                try {
                                    if (response.isSuccessful) {
                                        val dBody = response.body()
                                        val route = dBody?.routes?.firstOrNull()
                                        val leg = route?.legs?.firstOrNull()
                                        if (leg != null) {
                                            // find item in enriched and update (match by id)
                                            val idx = enriched.indexOfFirst { it.id == item.id }
                                            if (idx >= 0) {
                                                val old = enriched[idx]
                                                enriched[idx] = old.copy(
                                                    travelTimeSecCar = leg.duration?.value,
                                                    travelDistanceMeters = leg.distance?.value
                                                )
                                            }
                                        }
                                    } else {
                                        Log.w(TAG, "Directions call failed code=${response.code()}")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "directions parse err", e)
                                } finally {
                                    remaining -= 1
                                    if (remaining <= 0) cb(enriched)
                                }
                            }

                            override fun onFailure(call: retrofit2.Call<DirectionsApiService.DirectionsResponse>, t: Throwable) {
                                Log.w(TAG, "directions failure: ${t.message}")
                                remaining -= 1
                                if (remaining <= 0) cb(enriched)
                            }
                        })
                    }
                }

                override fun onFailure(call: retrofit2.Call<PlacesApiService.NearbySearchResponse>, t: Throwable) {
                    Log.e(TAG, "Nearby search failure: ${t.message}")
                    cb(null)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "searchClinics exception", e)
            cb(null)
        }
    }
}
