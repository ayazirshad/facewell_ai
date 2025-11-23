package com.example.fyp.weather

data class OneCallResponse(
    val current: Current?,
    val lat: Double?,
    val lon: Double?
)

data class Current(
    val dt: Long,
    val temp: Double,
    val humidity: Int,
    val uvi: Double?,
    val weather: List<WeatherItem>?,
    val wind_speed: Double?,
    val rain: Map<String, Double>? // sometimes "1h":value
)

data class WeatherItem(
    val id: Int,
    val main: String,
    val description: String,
    val icon: String
)

// Snapshot used across app
data class WeatherSnapshot(
    val ts: Long,
    val tempC: Double,
    val humidity: Int,
    val uvi: Double,
    val weatherMain: String,
    val precipitationMm: Double?,
    val windMs: Double?
)
