package com.example.fyp.weather

import android.content.Context
import android.util.Log
import com.example.fyp.R
import com.google.gson.GsonBuilder
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.lang.Exception

private const val TAG = "WeatherService"

class WeatherService(private val context: Context) {
    private val apiKey: String = try {
        context.getString(R.string.openweather_api_key)
    } catch (e: Exception) {
        ""
    }

    private val api: OpenWeatherApi by lazy {
        val gson = GsonBuilder().create()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.openweathermap.org/")
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        retrofit.create(OpenWeatherApi::class.java)
    }

    /**
     * Fetch current weather using /data/2.5/weather (units=metric).
     * cb returns WeatherSnapshot? — null on failure.
     */
    fun fetchCurrent(lat: Double, lon: Double, cb: (WeatherSnapshot?) -> Unit) {
        if (apiKey.isBlank() || apiKey.startsWith("your_")) {
            Log.e(TAG, "openweather_api_key missing or placeholder. Check strings.xml")
            cb(null)
            return
        }

        try {
            val call = api.currentWeather(lat, lon, "metric", apiKey)

            // SAFELY log request URL (avoid accessing package-private field)
            try {
                val req = call.request()
                // prefer getter url() which is public; fallback to request.toString()
                val urlStr = try {
                    req.url().toString()
                } catch (_: Throwable) {
                    try { req.toString() } catch (_: Throwable) { "unknown-url" }
                }
                Log.d(TAG, "Request URL: $urlStr")
            } catch (e: Exception) {
                Log.w(TAG, "Unable to log request URL safely", e)
            }

            call.enqueue(object : retrofit2.Callback<WeatherResponse> {
                override fun onResponse(call: retrofit2.Call<WeatherResponse>, response: retrofit2.Response<WeatherResponse>) {
                    try {
                        Log.d(TAG, "Response code: ${response.code()}")
                        if (!response.isSuccessful) {
                            val eb: ResponseBody? = response.errorBody()
                            try { Log.e(TAG, "OpenWeather error body: ${eb?.string()}") } catch (_: Exception) {}
                            cb(null)
                            return
                        }

                        val body = response.body()
                        if (body == null) {
                            Log.w(TAG, "Response body is null")
                            cb(null)
                            return
                        }

                        val main = body.main
                        val temp = main?.temp
                        val humidity = main?.humidity
                        val feels = main?.feels_like
                        val weatherMain = body.weather?.firstOrNull()?.main ?: "Clear"
                        val wind = body.wind?.speed

                        if (temp == null || humidity == null) {
                            Log.w(TAG, "Essential fields missing (temp/humidity). main=$main")
                            cb(null)
                            return
                        }

                        val snap = WeatherSnapshot(
                            ts = (body.dt ?: System.currentTimeMillis() / 1000) * 1000L,
                            tempC = temp,
                            humidity = humidity,
                            feelsLikeC = feels,
                            weatherMain = weatherMain,
                            precipitationMm = null,
                            windMs = wind,
                            placeName = body.name
                        )
                        cb(snap)
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception while handling response", e)
                        cb(null)
                    }
                }

                override fun onFailure(call: retrofit2.Call<WeatherResponse>, t: Throwable) {
                    Log.e(TAG, "Retrofit failure: ${t.message}", t)
                    cb(null)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception while making weather call", e)
            cb(null)
        }
    }
}
