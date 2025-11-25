package com.example.fyp.models

data class Clinic(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val specialist: String? = null,
    val distanceMeters: Int? = null,
    val travelTimeSecCar: Int? = null,
    val travelDistanceMeters: Int? = null,
    val rating: Double? = null,
    val openNow: Boolean? = null
)
