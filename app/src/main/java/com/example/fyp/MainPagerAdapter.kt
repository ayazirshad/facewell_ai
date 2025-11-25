package com.example.fyp

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {

    enum class Page { HOME, REPORTS, CLINICS, PROFILE }

    override fun getItemCount() = Page.values().size

    override fun createFragment(position: Int): Fragment = when (Page.values()[position]) {
        Page.HOME    -> HomeFragment()
        Page.REPORTS -> ReportsFragment()
        Page.CLINICS -> ClinicsFragment()
        Page.PROFILE -> ProfileFragment()
    }
}
