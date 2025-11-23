package com.example.fyp.weather

import android.content.Context
import android.util.Log
import com.example.fyp.R
import com.google.gson.GsonBuilder
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.awaitResponse
import java.lang.Exception

class WeatherService(private val context: Context) {
    private val apiKey: String = context.getString(R.string.openweather_api_key)

    private val api: OpenWeatherApi by lazy {
        val gson = GsonBuilder().create()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.openweathermap.org/")
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        retrofit.create(OpenWeatherApi::class.java)
    }

    // suspend or callback version; here we use callback style for simplicity
    fun fetchCurrent(lat: Double, lon: Double, cb: (WeatherSnapshot?) -> Unit) {
        try {
            val call = api.oneCall(lat, lon, "minutely,daily,hourly,alerts", "metric", apiKey)
            // Use enqueue to make async call
            call.enqueue(object : retrofit2.Callback<OneCallResponse> {
                override fun onResponse(call: retrofit2.Call<OneCallResponse>, response: retrofit2.Response<OneCallResponse>) {
                    val body = response.body()
                    if (body?.current != null) {
                        val cur = body.current
                        val main = cur.weather?.firstOrNull()?.main ?: "Clear"
                        val precip = when {
                            cur.rain != null && cur.rain.isNotEmpty() -> cur.rain.values.first()
                            else -> null
                        }
                        val snap = WeatherSnapshot(
                            ts = cur.dt * 1000L,
                            tempC = cur.temp,
                            humidity = cur.humidity,
                            uvi = cur.uvi ?: 0.0,
                            weatherMain = main,
                            precipitationMm = precip,
                            windMs = cur.wind_speed
                        )
                        cb(snap)
                    } else {
                        cb(null)
                    }
                }

                override fun onFailure(call: retrofit2.Call<OneCallResponse>, t: Throwable) {
                    t.printStackTrace()
                    cb(null)
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
            cb(null)
        }
    }
}
