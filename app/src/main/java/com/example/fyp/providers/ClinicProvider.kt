package com.example.fyp.providers

import com.example.fyp.models.Clinic

enum class ClinicCategory {
    EYE, SKIN, MOOD
}

interface ClinicProvider {
    /**
     * Search clinics near lat/lng within radiusMeters for the given category.
     * cb returns list or null on error.
     */
    fun searchClinics(lat: Double, lng: Double, radiusMeters: Int, category: ClinicCategory, cb: (List<Clinic>?) -> Unit)
}
