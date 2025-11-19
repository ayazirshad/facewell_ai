package com.example.fyp

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.viewpager2.widget.ViewPager2
import com.example.fyp.camera.CameraCaptureActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity(), ScanChooserSheet.Callbacks {

    private lateinit var viewPager: ViewPager2
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var fabScan: FloatingActionButton
    private lateinit var overlayContainer: android.view.View

    private var overlayVisible = false
    private var overlayListener: FragmentManager.OnBackStackChangedListener? = null

    private val idToPage = mapOf(
        R.id.nav_home    to MainPagerAdapter.Page.HOME,
        R.id.nav_reports to MainPagerAdapter.Page.REPORTS,
        R.id.nav_clinics to MainPagerAdapter.Page.CLINICS,
        R.id.nav_profile to MainPagerAdapter.Page.PROFILE
    )
    private val pageToId = idToPage.entries.associate { (id, page) -> page to id }

    // ===== Gallery picker (returns a Uri) =====
    private val pickFromGallery = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val i = Intent(this, ConfirmPhotoActivity::class.java)
            i.putExtra(ConfirmPhotoActivity.EXTRA_IMAGE_URI, uri.toString())
            startActivity(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewPager = findViewById(R.id.viewPager)
        bottomNav = findViewById(R.id.bottomNav)
        fabScan   = findViewById(R.id.fabScan)
        overlayContainer = findViewById(R.id.overlayContainer)

        // ViewPager config
        viewPager.adapter = MainPagerAdapter(this)
        viewPager.offscreenPageLimit = 3
        viewPager.isUserInputEnabled = true // swipe like WhatsApp

        // Sync bottom bar when swiping
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val page = MainPagerAdapter.Page.values()[position]
                pageToId[page]?.let { if (bottomNav.selectedItemId != it) bottomNav.selectedItemId = it }
            }
        })

        // Bottom bar -> set page (instant)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_scan_placeholder -> {
                    fabScan.performClick()
                    false
                }
                else -> {
                    val page = idToPage[item.itemId] ?: return@setOnItemSelectedListener false
                    val index = MainPagerAdapter.Page.values().indexOf(page)
                    if (overlayVisible) {
                        closeOverlayThen { viewPager.setCurrentItem(index, false) }
                    } else {
                        viewPager.setCurrentItem(index, false) // no animation
                    }
                    true
                }
            }
        }
        bottomNav.setOnItemReselectedListener { /* optional: scroll to top */ }

        // Default tab
        bottomNav.selectedItemId = R.id.nav_home

        // Handle incoming "open_tab" intent (so other activities can redirect here)
        handleOpenTabIntent(intent)

        // FAB → open the chooser sheet
        fabScan.setOnClickListener { ScanChooserSheet.show(this) }

        overlayContainer.visibility = android.view.View.GONE
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleOpenTabIntent(it) }
    }

    private fun handleOpenTabIntent(intent: Intent) {
        val open = intent.getStringExtra("open_tab") ?: return
        when (open) {
            "clinics" -> {
                // select clinics tab
                bottomNav.selectedItemId = R.id.nav_clinics
                val page = idToPage[R.id.nav_clinics] ?: MainPagerAdapter.Page.CLINICS
                val index = MainPagerAdapter.Page.values().indexOf(page)
                viewPager.setCurrentItem(index, false)
            }
            "reports" -> {
                bottomNav.selectedItemId = R.id.nav_reports
                val page = idToPage[R.id.nav_reports] ?: MainPagerAdapter.Page.REPORTS
                val index = MainPagerAdapter.Page.values().indexOf(page)
                viewPager.setCurrentItem(index, false)
            }
            // add more options if needed
        }
    }

    /** Called from ProfileFragment (or others) to open an overlay Fragment on top while keeping the bottom bar. */
    fun openOverlay(fragment: Fragment, tag: String) {
        overlayVisible = true
        overlayContainer.visibility = android.view.View.VISIBLE

        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .add(R.id.overlayContainer, fragment, tag)
            .addToBackStack(tag)
            .commit()

        overlayListener?.let { supportFragmentManager.removeOnBackStackChangedListener(it) }
        overlayListener = object : FragmentManager.OnBackStackChangedListener {
            override fun onBackStackChanged() {
                if (supportFragmentManager.backStackEntryCount == 0) {
                    supportFragmentManager.removeOnBackStackChangedListener(this)
                    overlayVisible = false
                    overlayContainer.visibility = android.view.View.GONE
                    overlayListener = null
                }
            }
        }
        supportFragmentManager.addOnBackStackChangedListener(overlayListener!!)
    }

    /** Pop the overlay and run [afterClosed] only after it has fully disappeared. */
    private fun closeOverlayThen(afterClosed: () -> Unit) {
        val listener = object : FragmentManager.OnBackStackChangedListener {
            override fun onBackStackChanged() {
                if (supportFragmentManager.backStackEntryCount == 0) {
                    supportFragmentManager.removeOnBackStackChangedListener(this)
                    overlayVisible = false
                    overlayContainer.visibility = android.view.View.GONE
                    afterClosed()
                }
            }
        }
        supportFragmentManager.addOnBackStackChangedListener(listener)
        supportFragmentManager.popBackStack()
    }

    // ===== ScanChooserSheet.Callbacks =====

    override fun onPickCamera() {
        startActivity(Intent(this, CameraCaptureActivity::class.java))
    }

    override fun onPickGallery() {
        pickFromGallery.launch("image/*")
    }
}
