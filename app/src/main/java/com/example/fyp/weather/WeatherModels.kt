package com.example.fyp.weather

// Response mapping for /data/2.5/weather
data class WeatherResponse(
    val coord: Coord?,
    val weather: List<WeatherItem>?,
    val base: String?,
    val main: Main?,
    val visibility: Int?,
    val wind: Wind?,
    val clouds: Clouds?,
    val dt: Long?,
    val sys: Sys?,
    val timezone: Int?,
    val id: Long?,
    val name: String?,
    val cod: Int?
)

data class Coord(val lon: Double?, val lat: Double?)
data class WeatherItem(val id: Int, val main: String, val description: String, val icon: String)
data class Main(
    val temp: Double?,
    val feels_like: Double?,
    val temp_min: Double?,
    val temp_max: Double?,
    val pressure: Int?,
    val humidity: Int?
)
data class Wind(val speed: Double?, val deg: Int?, val gust: Double?)
data class Clouds(val all: Int?)
data class Sys(val country: String?, val sunrise: Long?, val sunset: Long?)

// Snapshot used across app (unified)
data class WeatherSnapshot(
    val ts: Long,
    val tempC: Double,
    val humidity: Int,
    val feelsLikeC: Double?,
    val weatherMain: String,
    val precipitationMm: Double? = null,
    val windMs: Double? = null,
    val placeName: String? = null
)
