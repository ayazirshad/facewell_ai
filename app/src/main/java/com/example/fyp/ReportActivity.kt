package com.example.fyp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import java.io.InputStream
import java.util.*

class ReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EXTRA_SUMMARY = "extra_summary"
        const val EXTRA_LEFT_URI = "extra_left_uri"
        const val EXTRA_RIGHT_URI = "extra_right_uri"
        const val EXTRA_LEFT_LABEL = "extra_left_label"
        const val EXTRA_RIGHT_LABEL = "extra_right_label"
        const val EXTRA_CONFIDENCE = "extra_confidence"
    }

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)

        // back button
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
            finish()
        }

        // Load JSON into provider
        RecommendationProvider.loadFromAssets(this, "eye_recommendations.json")

        val type = intent.getStringExtra(EXTRA_TYPE) ?: "general"
        val imageUri = intent.getStringExtra(EXTRA_IMAGE_URI)
        val summary = intent.getStringExtra(EXTRA_SUMMARY) ?: ""
        val leftUri = intent.getStringExtra(EXTRA_LEFT_URI)
        val rightUri = intent.getStringExtra(EXTRA_RIGHT_URI)
        val leftLabel = intent.getStringExtra(EXTRA_LEFT_LABEL) ?: ""
        val rightLabel = intent.getStringExtra(EXTRA_RIGHT_LABEL) ?: ""
        val confidence = intent.getFloatExtra(EXTRA_CONFIDENCE, 0f)

        val tvTitle = findViewById<TextView>(R.id.tvReportTitle)
        val ivPreview = findViewById<ImageView>(R.id.ivPreview)
        val tvSummaryText = findViewById<TextView>(R.id.tvSummaryText)
        val ivLeftCrop = findViewById<ImageView>(R.id.ivLeftCrop)
        val ivRightCrop = findViewById<ImageView>(R.id.ivRightCrop)
        val tvLeftLabel = findViewById<TextView>(R.id.tvLeftLabel)
        val tvRightLabel = findViewById<TextView>(R.id.tvRightLabel)
        val tvAccuracy = findViewById<TextView>(R.id.tvAccuracyLabel)
        val btnSave = findViewById<MaterialButton>(R.id.btnSaveReport)
        val btnVisit = findViewById<MaterialButton>(R.id.btnVisitClinic)
        val btnGoHome = findViewById<MaterialButton>(R.id.btnGoHome)
        val containerEye = findViewById<View>(R.id.container_eye)
        val tvPlaceholder = findViewById<TextView>(R.id.tvPlaceholder)

        val tvRecSummary = findViewById<TextView>(R.id.tvRecSummary)
        val llTips = findViewById<LinearLayout>(R.id.llTips)
        val rvProducts = findViewById<RecyclerView>(R.id.rvProducts)
        val tvProductsTitle = findViewById<TextView>(R.id.tvProductsTitle)

        // title text
        tvTitle.text = when (type) {
            "eye" -> "Eye Report"
            "skin" -> "Skin Report"
            "mood" -> "Mood Report"
            else -> "Report"
        }

        // Load preview & summary
        if (!imageUri.isNullOrEmpty()) ivPreview.setImageURI(Uri.parse(imageUri))
        tvSummaryText.text = summary

        // Default visibility
        tvPlaceholder.visibility = View.GONE
        containerEye.visibility = View.VISIBLE

        if (type == "eye") {
            // populate eye UI
            tvLeftLabel.text = leftLabel
            tvRightLabel.text = rightLabel
            tvAccuracy.text = "Accuracy Level: ${(confidence * 100).toInt()}%"

            if (!leftUri.isNullOrEmpty()) ivLeftCrop.setImageURI(Uri.parse(leftUri))
            if (!rightUri.isNullOrEmpty()) ivRightCrop.setImageURI(Uri.parse(rightUri))

            // derive disease key (normalize)
            val diseaseKey = when {
                leftLabel.isNotBlank() && leftLabel != "normal" -> leftLabel.replace("\\s".toRegex(), "").lowercase(Locale.getDefault())
                rightLabel.isNotBlank() && rightLabel != "normal" -> rightLabel.replace("\\s".toRegex(), "").lowercase(Locale.getDefault())
                else -> "normal"
            }

            val rec = RecommendationProvider.getEyeRecommendation(diseaseKey)

            if (rec != null) {
                // show summary
                tvRecSummary.text = rec.summary

                // tips (clear first)
                llTips.removeAllViews()
                for (tip in rec.tips) {
                    val tv = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_1, llTips, false) as TextView
                    tv.text = "\u2022  $tip"
                    tv.setTextColor(resources.getColor(R.color.black))
                    tv.setTextSize(14f)
                    llTips.addView(tv)
                }

                // PRODUCTS: use GridLayoutManager(2) so each card is equal width
                if (rec.products.isNotEmpty()) {
                    tvProductsTitle.visibility = View.VISIBLE
                    rvProducts.visibility = View.VISIBLE
                    rvProducts.layoutManager = GridLayoutManager(this, 2, RecyclerView.VERTICAL, false)
                    val adapter = ProductAdapter(this, rec.products) { product ->
                        // show large product detail dialog
                        showProductDialog(product)
                    }
                    rvProducts.adapter = adapter
                } else {
                    tvProductsTitle.visibility = View.GONE
                    rvProducts.visibility = View.GONE
                }
            } else {
                // no rec entry
                tvRecSummary.text = "No recommendations available for this finding."
                llTips.removeAllViews()
                tvProductsTitle.visibility = View.GONE
                rvProducts.visibility = View.GONE
            }

            // Save report: will upload images to Firebase Storage and then save doc in user's reports array
            btnSave.setOnClickListener {
                val uid = auth.currentUser?.uid
                if (uid == null) {
                    Toast.makeText(this, "Not authenticated. Please login.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // Disable button while saving
                btnSave.isEnabled = false
                btnSave.text = "Saving..."
                // perform upload & save
                uploadImagesAndSaveReport(uid, imageUri, leftUri, rightUri, type, summary, leftLabel, rightLabel, confidence) { success ->
                    btnSave.isEnabled = true
                    btnSave.text = "Save Report"
                    if (success) {
                        Toast.makeText(this, "Report saved", Toast.LENGTH_SHORT).show()
                        // Redirect to MainActivity Home tab
                        val i = Intent(this, MainActivity::class.java)
                        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        i.putExtra("open_tab", "home")
                        startActivity(i)
                        finish()
                    } else {
                        Toast.makeText(this, "Failed to save report", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // Visit clinic button
            btnVisit.setOnClickListener {
                val i = Intent(this, MainActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                i.putExtra("open_tab", "clinics")
                startActivity(i)
                finish()
            }

            // Go home button (outlined)
            btnGoHome.setOnClickListener {
                val i = Intent(this, MainActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                i.putExtra("open_tab", "home")
                startActivity(i)
                finish()
            }
        } else {
            // Other types: show placeholder, hide eye UI
            containerEye.visibility = View.GONE
            tvPlaceholder.visibility = View.VISIBLE

            btnVisit.setOnClickListener {
                val i = Intent(this, MainActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                i.putExtra("open_tab", "clinics")
                startActivity(i)
                finish()
            }
            btnGoHome.setOnClickListener {
                val i = Intent(this, MainActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                i.putExtra("open_tab", "home")
                startActivity(i)
                finish()
            }
            btnSave.setOnClickListener {
                Toast.makeText(this, "Save not available for this scan type yet.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showProductDialog(p: RecProduct) {
        // inflate the big dialog layout
        val d = android.app.Dialog(this)
        val v = layoutInflater.inflate(R.layout.dialog_product_detail, null)
        d.setContentView(v)

        val iv = v.findViewById<ImageView>(R.id.ivDlgImage)
        val tvTitle = v.findViewById<TextView>(R.id.tvDlgTitle)
        val tvDesc = v.findViewById<TextView>(R.id.tvDlgDesc)
        val tvHow = v.findViewById<TextView>(R.id.tvDlgHow)
        val tvHowText = v.findViewById<TextView>(R.id.tvDlgHowText)
        val btnClose = v.findViewById<ImageButton>(R.id.btnCloseDialog)

        tvTitle.text = p.name
        // try product.longDescription or fallback to short
        tvDesc.text = (p.imageUrl ?: "").let { /* no remote text stored here by default */ "" }.ifEmpty { p.short }

        // If we had "howToUse" in RecProduct (not present currently), show it. For now hide:
        tvHow.visibility = View.GONE
        tvHowText.visibility = View.GONE

        if (!p.localImage.isNullOrEmpty()) {
            val resId = resources.getIdentifier(p.localImage, "drawable", packageName)
            if (resId != 0) iv.setImageResource(resId) else iv.setImageResource(android.R.color.transparent)
        } else if (!p.imageUrl.isNullOrEmpty()) {
            // If imageUrl exists (remote), attempt to load it — optional: you have no image loader (Glide). Use setImageURI fallback:
            try { iv.setImageURI(Uri.parse(p.imageUrl)) } catch (_: Exception) { iv.setImageResource(android.R.color.transparent) }
        } else {
            iv.setImageResource(android.R.color.transparent)
        }


        btnClose.setOnClickListener { d.dismiss() }

        // set dialog window size: full width, ~80% height
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)
        val w = (dm.widthPixels * 0.95).toInt()
        val h = (dm.heightPixels * 0.80).toInt()
        d.window?.setLayout(w, h)
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        d.show()
    }

    /** Upload images (if present) and then save report into user's reports array. */
    private fun uploadImagesAndSaveReport(
        uid: String,
        previewUriStr: String?,
        leftUriStr: String?,
        rightUriStr: String?,
        type: String,
        summary: String,
        leftLabel: String,
        rightLabel: String,
        confidence: Float,
        onComplete: (Boolean) -> Unit
    ) {
        val previewUri = previewUriStr?.let { Uri.parse(it) }
        val leftUri = leftUriStr?.let { Uri.parse(it) }
        val rightUri = rightUriStr?.let { Uri.parse(it) }

        val storageRef = storage.reference.child("reports").child(uid)
        val uploaded = mutableMapOf<String, String>()

        // helper to upload a single file
        fun uploadOne(uri: Uri?, name: String, cb: (String?) -> Unit) {
            if (uri == null) { cb(null); return }
            try {
                val ref = storageRef.child("$name.jpg")
                val stream: InputStream? = contentResolver.openInputStream(uri)
                if (stream == null) { cb(null); return }
                val uploadTask = ref.putStream(stream)
                uploadTask.continueWithTask { task ->
                    if (!task.isSuccessful) throw task.exception ?: Exception("upload failed")
                    ref.downloadUrl
                }.addOnCompleteListener { t ->
                    if (t.isSuccessful) {
                        cb(t.result.toString())
                    } else {
                        cb(null)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                cb(null)
            }
        }

        // sequentially upload preview -> left -> right (simpler control flow)
        uploadOne(previewUri, "preview_${System.currentTimeMillis()}") { previewUrl ->
            uploaded["preview"] = previewUrl ?: ""
            uploadOne(leftUri, "left_${System.currentTimeMillis()}") { leftUrl ->
                uploaded["left"] = leftUrl ?: ""
                uploadOne(rightUri, "right_${System.currentTimeMillis()}") { rightUrl ->
                    uploaded["right"] = rightUrl ?: ""

                    // build report map
                    val report = hashMapOf<String, Any>(
                        "type" to type,
                        "summary" to summary,
                        "leftLabel" to leftLabel,
                        "rightLabel" to rightLabel,
                        "confidence" to confidence,
                        "previewUrl" to (uploaded["preview"] ?: ""),
                        "leftUrl" to (uploaded["left"] ?: ""),
                        "rightUrl" to (uploaded["right"] ?: ""),
                        "createdAt" to System.currentTimeMillis()
                    )

                    // Save into user's reports array (use arrayUnion)
                    val userDoc = db.collection("users").document(uid)
                    // First attempt update with arrayUnion
                    userDoc.update("reports", FieldValue.arrayUnion(report as Any))
                        .addOnSuccessListener {
                            onComplete(true)
                        }
                        .addOnFailureListener {
                            // If update fails (maybe field doesn't exist), set with merge
                            val payload = hashMapOf("reports" to listOf(report))
                            userDoc.set(payload, SetOptions.merge())
                                .addOnSuccessListener { onComplete(true) }
                                .addOnFailureListener { e ->
                                    e.printStackTrace()
                                    onComplete(false)
                                }
                        }
                }
            }
        }
    }
}
