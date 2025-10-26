package com.example.fyp

import java.io.Serializable

data class UserProfile(
    val uid: String? = null,
    val firstName: String = "",
    val lastName: String = "",
    val city: String = "",
    val dob: String = "",         // "YYYY-MM-DD"
    val gender: String = "",      // "male" / "female" or capitalized
    val email: String = "",
    val phone: String = "",
    val avatar: String = "ic_profile", // "avatar1".. "avatar4" or "ic_profile"
    val stage: Int = 0
) : Serializable
