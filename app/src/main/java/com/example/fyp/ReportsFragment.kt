package com.example.fyp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.fyp.models.Report
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Locale

class ReportsFragment : Fragment(R.layout.activity_reports_fragment) {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private lateinit var rvReports: RecyclerView
    private lateinit var emptyPlaceholder: View

    private lateinit var btnEye: MaterialButton
    private lateinit var btnSkin: MaterialButton
    private lateinit var btnMood: MaterialButton
    private lateinit var btnGeneral: MaterialButton

    private var allReports = mutableListOf<Report>()
    // default to "eye" so Eye tab is active when fragment opens
    private var currentFilter = "eye" // "general" means show only general reports


    private val userUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadReportsFromFirestore()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvReports = view.findViewById(R.id.rvReports)
        emptyPlaceholder = view.findViewById(R.id.emptyPlaceholder)

        btnEye = view.findViewById(R.id.btnEye)
        btnSkin = view.findViewById(R.id.btnSkin)
        btnMood = view.findViewById(R.id.btnMood)
        btnGeneral = view.findViewById(R.id.btnGeneral)

        rvReports.layoutManager = LinearLayoutManager(requireContext())
        rvReports.adapter = ReportAdapter(listOf(), { /* placeholder */ }, null)

        btnEye.setOnClickListener { applyFilter("eye") }
        btnSkin.setOnClickListener { applyFilter("skin") }
        btnMood.setOnClickListener { applyFilter("mood") }
        btnGeneral.setOnClickListener { applyFilter("general") }

        currentFilter = "eye"
        applyFilter("eye")

        updateFilterButtons()

        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(userUpdatedReceiver, IntentFilter("com.example.fyp.USER_UPDATED"))

        loadReportsFromFirestore()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(userUpdatedReceiver)
        } catch (_: Exception) { /* ignore */ }
    }

    private fun updateFilterButtons() {
        fun mark(b: MaterialButton, active: Boolean) {
            if (active) {
                b.setBackgroundColor(resources.getColor(R.color.teal_bg))
                b.setTextColor(resources.getColor(R.color.on_teal))
            } else {
                b.setBackgroundColor(resources.getColor(android.R.color.transparent))
                b.setTextColor(resources.getColor(R.color.text_muted))
            }
        }
        mark(btnEye, currentFilter == "eye")
        mark(btnSkin, currentFilter == "skin")
        mark(btnMood, currentFilter == "mood")
        mark(btnGeneral, currentFilter == "general")


    }

    private fun applyFilter(type: String) {
        currentFilter = type
        updateFilterButtons()
        showReportsForFilter()
    }

    private fun loadReportsFromFirestore() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("reports")
            .whereEqualTo("userId", uid)
            .get()
            .addOnSuccessListener { snap ->
                allReports.clear()
                for (doc in snap.documents) {
                    try {
                        val rpt = doc.toObject(Report::class.java)
                        if (rpt != null) {
                            if (rpt.reportId.isNullOrBlank()) {
                                val newRpt = rpt.copy(reportId = doc.id)
                                allReports.add(newRpt)
                            } else {
                                allReports.add(rpt)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                showReportsForFilter()
            }
            .addOnFailureListener { e ->
                e.printStackTrace()
                allReports.clear()
                showReportsForFilter()
            }
    }

    private fun showReportsForFilter() {
        val filtered = when (currentFilter) {
            "general" -> {
                // show only general (full-face) reports
                allReports.filter { it.type.equals("general", ignoreCase = true) }
                    .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
            }
            else -> {
                allReports.filter { it.type.equals(currentFilter, ignoreCase = true) }
                    .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
            }
        }

        if (filtered.isEmpty()) {
            rvReports.visibility = View.GONE
            emptyPlaceholder.visibility = View.VISIBLE
        } else {
            rvReports.visibility = View.VISIBLE
            emptyPlaceholder.visibility = View.GONE
            rvReports.adapter = ReportAdapter(
                filtered,
                { report -> showReportDialog(report) },
                { report -> confirmAndDeleteReport(report) }
            )
        }
    }

    private fun showReportDialog(r: Report) {
        val d = android.app.Dialog(requireContext())
        val v = layoutInflater.inflate(R.layout.dialog_report_detail, null)
        d.setContentView(v)

        val btnClose = v.findViewById<ImageButton>(R.id.btnCloseReport)
        val tvType = v.findViewById<TextView>(R.id.tvDetailType)
        val tvDate = v.findViewById<TextView>(R.id.tvDetailDate)
        val tvSummary = v.findViewById<TextView>(R.id.tvDetailSummary)
        val tvFooter = v.findViewById<TextView>(R.id.tvDetailFooter)
        val llSections = v.findViewById<LinearLayout>(R.id.llSections)

        // populate
        tvType.text = r.type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

        val createdAtDate = r.createdAt?.toDate()
        if (createdAtDate != null) {
            tvDate.text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(createdAtDate)
        } else {
            tvDate.text = ""
        }

        val topSummary = when {
            !r.summary.isNullOrBlank() -> r.summary!!
            r.eye_scan != null && !r.eye_scan.summary.isNullOrBlank() -> r.eye_scan.summary!!
            r.skin_scan != null && !r.skin_scan.summary.isNullOrBlank() -> r.skin_scan.summary!!
            r.mood_scan != null && !r.mood_scan.summary.isNullOrBlank() -> r.mood_scan.summary!!
            else -> ""
        }
        tvSummary.text = topSummary

        tvFooter.text = "Accuracy: ${(r.accuracy * 100).toInt()}%"

        // fill sections dynamically (recommendations + summary per scan)
        llSections.removeAllViews()
        fun addScanSection(title: String, summary: String?, recommendations: List<String>?) {
            if (summary.isNullOrBlank() && (recommendations == null || recommendations.isEmpty())) return

            val ctx = requireContext()
            val header = TextView(ctx)
            header.text = title
            header.setTextColor(resources.getColor(R.color.teal_mid))
            header.textSize = 15f
            header.setTypeface(header.typeface, android.graphics.Typeface.BOLD)
            header.setPadding(0, 10, 0, 6)
            llSections.addView(header)

            if (!summary.isNullOrBlank()) {
                val s = TextView(ctx)
                s.text = summary
                s.setTextColor(resources.getColor(R.color.black))
                s.textSize = 14f
                s.setPadding(0, 0, 0, 6)
                llSections.addView(s)
            }

            if (recommendations != null && recommendations.isNotEmpty()) {
                val recHead = TextView(ctx)
                recHead.text = "Recommendations:"
                recHead.setTextColor(resources.getColor(R.color.text_muted))
                recHead.textSize = 13f
                recHead.setPadding(0, 4, 0, 4)
                llSections.addView(recHead)

                for (tip in recommendations) {
                    val tv = TextView(ctx)
                    tv.text = "\u2022  $tip"
                    tv.setTextColor(resources.getColor(R.color.black))
                    tv.textSize = 13f
                    tv.setPadding(6, 0, 0, 4)
                    llSections.addView(tv)
                }
            }

            val div = View(ctx)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            lp.setMargins(0, 10, 0, 10)
            div.layoutParams = lp
            div.setBackgroundColor(resources.getColor(R.color.text_muted))
            llSections.addView(div)
        }

        if (r.type.equals("general", ignoreCase = true)) {
            addScanSection("Eye", r.eye_scan?.summary, r.eye_scan?.recommendations)
            addScanSection("Skin", r.skin_scan?.summary, r.skin_scan?.recommendations)
            addScanSection("Mood", r.mood_scan?.summary, r.mood_scan?.recommendations)
        } else {
            when (r.type.lowercase(Locale.getDefault())) {
                "eye" -> addScanSection("Eye", r.eye_scan?.summary ?: r.summary, r.eye_scan?.recommendations ?: r.recommendations)
                "skin" -> addScanSection("Skin", r.skin_scan?.summary ?: r.summary, r.skin_scan?.recommendations ?: r.recommendations)
                "mood" -> addScanSection("Mood", r.mood_scan?.summary ?: r.summary, r.mood_scan?.recommendations ?: r.recommendations)
                else -> addScanSection(r.type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }, r.summary, r.recommendations)
            }
        }

        btnClose.setOnClickListener { d.dismiss() }

        val dm = DisplayMetrics()
        requireActivity().windowManager.defaultDisplay.getMetrics(dm)
        val w = (dm.widthPixels * 0.95).toInt()
        val h = (dm.heightPixels * 0.85).toInt()
        d.window?.setLayout(w, h)
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        d.show()
    }

    private fun confirmAndDeleteReport(report: Report) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete report")
            .setMessage("Are you sure you want to delete this report? This action cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                performDeleteReport(report)
            }
            .show()
    }

    private fun performDeleteReport(report: Report) {
        val uid = auth.currentUser?.uid ?: return
        val userDocRef = db.collection("users").document(uid)
        val reportDocRef = db.collection("reports").document(report.reportId)

        reportDocRef.delete()
            .addOnSuccessListener {
                userDocRef.update("reports", FieldValue.arrayRemove(report.reportId))
                    .addOnSuccessListener {
                        Toast.makeText(requireContext(), "Report deleted", Toast.LENGTH_SHORT).show()
                        loadReportsFromFirestore()
                    }
                    .addOnFailureListener { e ->
                        e.printStackTrace()
                        Toast.makeText(requireContext(), "Report deleted (but failed to update user list)", Toast.LENGTH_LONG).show()
                        loadReportsFromFirestore()
                    }
            }
            .addOnFailureListener { e ->
                e.printStackTrace()
                Toast.makeText(requireContext(), "Failed to delete report: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }
}
