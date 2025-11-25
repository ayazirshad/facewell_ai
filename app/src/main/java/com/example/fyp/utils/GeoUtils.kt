package com.example.fyp.utils

import kotlin.math.*

object GeoUtils {
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat/2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon/2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1-a))
        return R * c
    }
    fun formatMetersToKmString(meters: Int): String {
        return if (meters >= 1000) String.format("%.1f km", meters/1000.0) else "$meters m"
    }
    fun secondsToMinStr(sec: Int?): String {
        return sec?.let { "${(it+30)/60} min" } ?: "--"
    }
}
