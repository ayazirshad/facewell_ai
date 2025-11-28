package com.example.fyp.models

import com.google.firebase.Timestamp
import com.google.firebase.firestore.ServerTimestamp

data class ScanResult(
    val summary: String = "",
    val accuracy: Double = 0.0,
    val recommendations: List<String> = emptyList()
)

data class Report(
    val reportId: String = "",
    val userId: String = "",
    val type: String = "general",                 // "eye" | "skin" | "mood" | "general"
    val summary: String = "",
    val imageUrl: String? = null,                 // currently null until we enable image saving
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val accuracy: Double = 0.0,
    val recommendations: List<String> = emptyList(),
    val eye_scan: ScanResult? = null,
    val skin_scan: ScanResult? = null,
    val mood_scan: ScanResult? = null,
    val tags: List<String> = emptyList(),
    val source: String? = null,
    val meta: Map<String, String>? = null,
    @ServerTimestamp
    val createdAt: Timestamp? = null
)
