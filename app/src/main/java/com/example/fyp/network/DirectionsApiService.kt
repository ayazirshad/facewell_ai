package com.example.fyp.network

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface DirectionsApiService {
    // https://maps.googleapis.com/maps/api/directions/json
    @GET("maps/api/directions/json")
    fun getDirections(
        @Query("origin") origin: String,       // "lat,lng"
        @Query("destination") destination: String,
        @Query("mode") mode: String = "driving",
        @Query("key") apiKey: String
    ): Call<DirectionsResponse>

    data class DirectionsResponse(val routes: List<Route>?)
    data class Route(val legs: List<Leg>?)
    data class Leg(val distance: DistanceObj?, val duration: DurationObj?)
    data class DistanceObj(val text: String?, val value: Int?)
    data class DurationObj(val text: String?, val value: Int?)
}
