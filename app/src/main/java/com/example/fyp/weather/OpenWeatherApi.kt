package com.example.fyp.weather

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface OpenWeatherApi {
    // One-call removed for free plan: use current weather endpoint
    @GET("data/2.5/weather")
    fun currentWeather(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("units") units: String = "metric",
        @Query("appid") apiKey: String
    ): Call<WeatherResponse>
}
