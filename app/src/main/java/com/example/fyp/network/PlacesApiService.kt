package com.example.fyp.network

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface PlacesApiService {
    // Nearby Search: https://maps.googleapis.com/maps/api/place/nearbysearch/json
    @GET("maps/api/place/nearbysearch/json")
    fun nearbySearch(
        @Query("location") location: String,
        @Query("radius") radius: Int,
        @Query("keyword") keyword: String,
        @Query("key") apiKey: String
    ): Call<NearbySearchResponse>

    // Place Details if you need more info later (phone, website)
    @GET("maps/api/place/details/json")
    fun placeDetails(
        @Query("place_id") placeId: String,
        @Query("fields") fields: String, // e.g. "name,formatted_phone_number,formatted_address,website"
        @Query("key") apiKey: String
    ): Call<PlaceDetailsResponse>

    // Models (inner for brevity)
    data class NearbySearchResponse(val results: List<Result>?, val status: String?)
    data class Result(
        val place_id: String?,
        val name: String?,
        val geometry: Geometry?,
        val vicinity: String?,
        val formatted_address: String?,
        val rating: Double?,
        val opening_hours: OpeningHours?,
    )
    data class Geometry(val location: LocationObj?)
    data class LocationObj(val lat: Double?, val lng: Double?)
    data class OpeningHours(val open_now: Boolean?)
    data class PlaceDetailsResponse(val result: PlaceDetailResult?)
    data class PlaceDetailResult(
        val formatted_phone_number: String?,
        val international_phone_number: String?,
        val website: String?,
        val email: String?, // not guaranteed
        val name: String?,
        val formatted_address: String?
    )
}
