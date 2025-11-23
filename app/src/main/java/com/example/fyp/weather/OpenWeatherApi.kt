package com.example.fyp.weather

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface OpenWeatherApi {
    // One Call 3.0 (some accounts use /onecall)
    @GET("data/2.5/onecall")
    fun oneCall(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("exclude") exclude: String = "minutely,daily,hourly,alerts",
        @Query("units") units: String = "metric",
        @Query("appid") apiKey: String
    ): Call<OneCallResponse>
}
