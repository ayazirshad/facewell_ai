package com.example.fyp.models

data class Report(
    val type: String = "general",
    val summary: String = "",
    val leftLabel: String = "",
    val rightLabel: String = "",
    val confidence: Double = 0.0,
    val previewUrl: String = "",
    val leftUrl: String = "",
    val rightUrl: String = "",
    val createdAt: Long = 0L
)
