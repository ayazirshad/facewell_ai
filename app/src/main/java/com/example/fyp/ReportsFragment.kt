package com.example.fyp

import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.fyp.models.Report
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

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
    private var currentFilter = "general" // default show all

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvReports = view.findViewById(R.id.rvReports)
        emptyPlaceholder = view.findViewById(R.id.emptyPlaceholder)

        btnEye = view.findViewById(R.id.btnEye)
        btnSkin = view.findViewById(R.id.btnSkin)
        btnMood = view.findViewById(R.id.btnMood)
        btnGeneral = view.findViewById(R.id.btnGeneral)

        // setup
        rvReports.layoutManager = LinearLayoutManager(requireContext())
        rvReports.adapter = ReportAdapter(listOf()) { /* placeholder */ }

        // filters
        btnEye.setOnClickListener { applyFilter("eye") }
        btnSkin.setOnClickListener { applyFilter("skin") }
        btnMood.setOnClickListener { applyFilter("mood") }
        btnGeneral.setOnClickListener { applyFilter("general") }

        // style default selected
        updateFilterButtons()

        // load reports from firestore
        loadReportsFromFirestore()
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
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val raw = doc.get("reports")
                allReports.clear()
                if (raw is List<*>) {
                    for (item in raw) {
                        if (item is Map<*, *>) {
                            try {
                                val rpt = Report(
                                    type = (item["type"] as? String) ?: "general",
                                    summary = (item["summary"] as? String) ?: "",
                                    leftLabel = (item["leftLabel"] as? String) ?: "",
                                    rightLabel = (item["rightLabel"] as? String) ?: "",
                                    confidence = ((item["confidence"] as? Number)?.toDouble() ?: 0.0),
                                    previewUrl = (item["previewUrl"] as? String) ?: "",
                                    leftUrl = (item["leftUrl"] as? String) ?: "",
                                    rightUrl = (item["rightUrl"] as? String) ?: "",
                                    createdAt = ((item["createdAt"] as? Number)?.toLong() ?: 0L)
                                )
                                allReports.add(rpt)
                            } catch (_: Exception) { /* skip bad entries */ }
                        }
                    }
                }
                showReportsForFilter()
            }
            .addOnFailureListener {
                // show empty placeholder on failure
                allReports.clear()
                showReportsForFilter()
            }
    }

    private fun showReportsForFilter() {
        val filtered = if (currentFilter == "general") allReports.sortedByDescending { it.createdAt }
        else allReports.filter { it.type.equals(currentFilter, ignoreCase = true) }.sortedByDescending { it.createdAt }

        if (filtered.isEmpty()) {
            rvReports.visibility = View.GONE
            emptyPlaceholder.visibility = View.VISIBLE
        } else {
            rvReports.visibility = View.VISIBLE
            emptyPlaceholder.visibility = View.GONE
            rvReports.adapter = ReportAdapter(filtered) { report ->
                // show detail dialog
                showReportDialog(report)
            }
        }
    }

    private fun showReportDialog(r: Report) {
        val d = android.app.Dialog(requireContext())
        val v = layoutInflater.inflate(R.layout.dialog_report_detail, null)
        d.setContentView(v)

        val btnClose = v.findViewById<ImageButton>(R.id.btnCloseReport)
        val ivPreviewLarge = v.findViewById<ImageView>(R.id.ivPreviewLarge)
        val tvType = v.findViewById<TextView>(R.id.tvDetailType)
        val tvDate = v.findViewById<TextView>(R.id.tvDetailDate)
        val tvSummary = v.findViewById<TextView>(R.id.tvDetailSummary)
        val ivLeft = v.findViewById<ImageView>(R.id.ivLeftReport)
        val ivRight = v.findViewById<ImageView>(R.id.ivRightReport)
        val tvFooter = v.findViewById<TextView>(R.id.tvDetailFooter)

        // fill
        tvType.text = r.type.capitalize()
        if (r.createdAt > 0) tvDate.text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(r.createdAt)) else tvDate.text = ""
        tvSummary.text = r.summary.ifEmpty { "${r.leftLabel} / ${r.rightLabel}" }
        tvFooter.text = "Accuracy: ${(r.confidence * 100).toInt()}%"

        try { if (r.previewUrl.isNotBlank()) ivPreviewLarge.setImageURI(Uri.parse(r.previewUrl)) } catch (_: Exception) {}
        try { if (r.leftUrl.isNotBlank()) ivLeft.setImageURI(Uri.parse(r.leftUrl)) } catch (_: Exception) {}
        try { if (r.rightUrl.isNotBlank()) ivRight.setImageURI(Uri.parse(r.rightUrl)) } catch (_: Exception) {}

        btnClose.setOnClickListener { d.dismiss() }

        // size dialog ~90% width and 80% height
        val dm = DisplayMetrics()
        requireActivity().windowManager.defaultDisplay.getMetrics(dm)
        val w = (dm.widthPixels * 0.95).toInt()
        val h = (dm.heightPixels * 0.80).toInt()
        d.window?.setLayout(w, h)
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        d.show()
    }
}
