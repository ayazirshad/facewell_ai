package com.example.fyp

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import org.tensorflow.lite.Interpreter
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import java.util.Locale
import com.example.fyp.utils.ModelLoader
import com.example.fyp.utils.MLUtils

class EyeReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val MODEL_NAME = "eye_disease_model.tflite"
    }

    private lateinit var ivPreview: ImageView
    private lateinit var ivLeftCrop: ImageView
    private lateinit var ivRightCrop: ImageView
    private lateinit var tvLeftLabel: TextView
    private lateinit var tvRightLabel: TextView
    private lateinit var tvSummaryText: TextView
    private lateinit var tvAccuracy: TextView
    private lateinit var btnSave: MaterialButton
    private lateinit var btnVisit: MaterialButton
    private lateinit var btnGoHome: MaterialButton
    private lateinit var btnBack: ImageButton
    private lateinit var llTips: LinearLayout
    private lateinit var tvRecSummary: TextView
    private lateinit var rvProducts: RecyclerView
    private lateinit var tvProductsTitle: TextView

    private lateinit var interpreter: Interpreter

    // firebase
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }

    private var previewUriStr: String? = null
    private var leftCropUriStr: String? = null
    private var rightCropUriStr: String? = null

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_eye_report)

        // bind views
        ivPreview = findViewById(R.id.ivPreview)
        ivLeftCrop = findViewById(R.id.ivLeftCrop)
        ivRightCrop = findViewById(R.id.ivRightCrop)
        tvLeftLabel = findViewById(R.id.tvLeftLabel)
        tvRightLabel = findViewById(R.id.tvRightLabel)
        tvSummaryText = findViewById(R.id.tvSummaryText)
        tvAccuracy = findViewById(R.id.tvAccuracyLabel)
        btnSave = findViewById(R.id.btnSaveReport)
        btnVisit = findViewById(R.id.btnVisitClinic)
        btnGoHome = findViewById(R.id.btnGoHome)
        btnBack = findViewById(R.id.btnBack)
        llTips = findViewById(R.id.llTips)
        tvRecSummary = findViewById(R.id.tvRecSummary)
        rvProducts = findViewById(R.id.rvProducts)
        tvProductsTitle = findViewById(R.id.tvProductsTitle)

        btnBack.setOnClickListener { finish() }

        val tvTitle = findViewById<TextView>(R.id.tvReportTitle)
        tvTitle.text = "Eye Report"

        // load model using your ModelLoader helper
        try {
            interpreter = ModelLoader.loadModelFromAssets(this, MODEL_NAME)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load model", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // load recommendations
        RecommendationProvider.loadFromAssets(this, "eye_recommendations.json")

        // load image
        val imageUriStr = intent.getStringExtra(EXTRA_IMAGE_URI) ?: intent.getStringExtra("extra_image_uri")
        if (imageUriStr.isNullOrEmpty()) { finish(); return }
        val imageUri = Uri.parse(imageUriStr)
        previewUriStr = imageUri.toString()

        // decode bitmap using MLUtils (keeps EXIF orientation handling)
        val bmp = MLUtils.decodeBitmap(contentResolver, imageUri) ?: run {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show(); finish(); return
        }
        ivPreview.setImageBitmap(bmp)

        // show dialog_simple_loader (same as ConfirmPhotoActivity)
        val dlg = Dialog(this)
        val loaderView = LayoutInflater.from(this).inflate(R.layout.dialog_simple_loader, null)
        dlg.setContentView(loaderView)
        dlg.setCancelable(false)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.show()

        Thread {
            try {
                // detect & crop eyes using MLUtils (blocking helper)
                val eyeResult = MLUtils.detectAndCropEyesBlocking(bmp)
                val leftBmp = eyeResult.leftCrop
                val rightBmp = eyeResult.rightCrop

                if (leftBmp == null || rightBmp == null) {
                    runOnUiThread {
                        dlg.dismiss()
                        Toast.makeText(this, "Eyes not clearly detected", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                // run eye model via MLUtils helper (preserves original behavior & labels)
                val res = MLUtils.runEyeModel(interpreter, leftBmp, rightBmp)
                val lPair = res.first
                val rPair = res.second
                val overall = res.third

                // save crops to temp cache for upload later
                val leftUri = saveBitmapToCache(leftBmp, "left_eye_${System.currentTimeMillis()}.jpg")
                val rightUri = saveBitmapToCache(rightBmp, "right_eye_${System.currentTimeMillis()}.jpg")
                leftCropUriStr = leftUri?.toString(); rightCropUriStr = rightUri?.toString()

                // update UI (labels, confidences, crops)
                runOnUiThread {
                    dlg.dismiss()

                    ivLeftCrop.setImageBitmap(leftBmp)
                    ivRightCrop.setImageBitmap(rightBmp)

                    // Show per-eye label + accuracy
                    val leftText = "${lPair.first} (${(lPair.second * 100).toInt()}%)"
                    val rightText = "${rPair.first} (${(rPair.second * 100).toInt()}%)"
                    tvLeftLabel.text = leftText
                    tvRightLabel.text = rightText

                    // overall accuracy (average)
                    tvAccuracy.text = "Accuracy Level: ${(overall * 100).toInt()}%"

                    // summary
                    tvSummaryText.text = "Left: $leftText • Right: $rightText"

                    // load recommendation for top finding; populate tips & products
                    val topKey = if (lPair.second >= rPair.second) lPair.first else rPair.first
                    val rec = RecommendationProvider.getEyeRecommendation(topKey.replace("\\s".toRegex(), "").lowercase(Locale.getDefault()))
                    if (rec != null) {
                        tvRecSummary.text = rec.summary
                        llTips.removeAllViews()
                        for (tip in rec.tips) {
                            val tv = layoutInflater.inflate(android.R.layout.simple_list_item_1, llTips, false) as TextView
                            tv.text = "\u2022  $tip"
                            tv.setTextColor(resources.getColor(R.color.black))
                            tv.setTextSize(14f)
                            llTips.addView(tv)
                        }

                        // PRODUCTS: show horizontally as grid(2)
                        if (rec.products.isNotEmpty()) {
                            tvProductsTitle.visibility = View.VISIBLE
                            rvProducts.visibility = View.VISIBLE
                            rvProducts.layoutManager = GridLayoutManager(this, 2, RecyclerView.VERTICAL, false)
                            val adapter = ProductAdapter(this, rec.products) { product ->
                                showProductDialog(product)
                            }
                            rvProducts.adapter = adapter
                        } else {
                            tvProductsTitle.visibility = View.GONE
                            rvProducts.visibility = View.GONE
                        }
                    } else {
                        tvRecSummary.text = "No recommendation available."
                        llTips.removeAllViews()
                        tvProductsTitle.visibility = View.GONE
                        rvProducts.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(this, "Eye analysis failed.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()

        // Save report (uploads preview + left + right images and writes Firestore doc)
        btnSave.setOnClickListener {
            val uid = auth.currentUser?.uid
            if (uid == null) {
                Toast.makeText(this, "Not authenticated. Please login.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnSave.isEnabled = false; btnSave.text = "Saving..."
            uploadImagesAndSaveReport(uid, previewUriStr, leftCropUriStr, rightCropUriStr, "eye",
                summary = tvSummaryText.text.toString(), leftLabel = tvLeftLabel.text.toString(),
                rightLabel = tvRightLabel.text.toString()) { success ->
                runOnUiThread {
                    btnSave.isEnabled = true; btnSave.text = "Save Report"
                    if (success) {
                        Toast.makeText(this, "Report saved", Toast.LENGTH_SHORT).show()
                        val i = Intent(this, MainActivity::class.java)
                        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        i.putExtra("open_tab", "home")
                        startActivity(i); finish()
                    } else {
                        Toast.makeText(this, "Failed to save report", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnVisit.setOnClickListener {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            i.putExtra("open_tab", "clinics")
            startActivity(i); finish()
        }
        btnGoHome.setOnClickListener {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            i.putExtra("open_tab", "home")
            startActivity(i); finish()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun saveBitmapToCache(bmp: Bitmap, name: String): Uri? {
        return try {
            val f = kotlin.io.path.createTempFile(prefix = name, suffix = ".jpg").toFile()
            FileOutputStream(f).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 90, out) }
            Uri.fromFile(f)
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    // upload & save report (same as earlier)
    private fun uploadImagesAndSaveReport(
        uid: String,
        previewUriStr: String?,
        leftUriStr: String?,
        rightUriStr: String?,
        type: String,
        summary: String,
        leftLabel: String,
        rightLabel: String,
        onComplete: (Boolean) -> Unit
    ) {
        val previewUri = previewUriStr?.let { Uri.parse(it) }
        val leftUri = leftUriStr?.let { Uri.parse(it) }
        val rightUri = rightUriStr?.let { Uri.parse(it) }

        val storageRef = storage.reference.child("reports").child(uid)
        val uploaded = mutableMapOf<String, String>()

        fun uploadOne(uri: Uri?, name: String, cb: (String?) -> Unit) {
            if (uri == null) { cb(null); return }
            try {
                val ref = storageRef.child("$name.jpg")
                val stream = contentResolver.openInputStream(uri)
                if (stream == null) { cb(null); return }
                val uploadTask = ref.putStream(stream)
                uploadTask.continueWithTask { task ->
                    if (!task.isSuccessful) throw task.exception ?: Exception("upload failed")
                    ref.downloadUrl
                }.addOnCompleteListener { t ->
                    if (t.isSuccessful) cb(t.result.toString()) else cb(null)
                }
            } catch (e: Exception) {
                e.printStackTrace(); cb(null)
            }
        }

        uploadOne(previewUri, "preview_${System.currentTimeMillis()}") { pUrl ->
            uploaded["preview"] = pUrl ?: ""
            uploadOne(leftUri, "left_${System.currentTimeMillis()}") { lUrl ->
                uploaded["left"] = lUrl ?: ""
                uploadOne(rightUri, "right_${System.currentTimeMillis()}") { rUrl ->
                    uploaded["right"] = rUrl ?: ""
                    val report = hashMapOf<String, Any>(
                        "type" to type,
                        "summary" to summary,
                        "leftLabel" to leftLabel,
                        "rightLabel" to rightLabel,
                        "confidence" to 0.0,
                        "previewUrl" to (uploaded["preview"] ?: ""),
                        "leftUrl" to (uploaded["left"] ?: ""),
                        "rightUrl" to (uploaded["right"] ?: ""),
                        "createdAt" to System.currentTimeMillis()
                    )
                    val userDoc = db.collection("users").document(uid)
                    userDoc.update("reports", FieldValue.arrayUnion(report as Any))
                        .addOnSuccessListener { onComplete(true) }
                        .addOnFailureListener {
                            val payload = hashMapOf("reports" to listOf(report))
                            userDoc.set(payload, SetOptions.merge())
                                .addOnSuccessListener { onComplete(true) }
                                .addOnFailureListener { e -> e.printStackTrace(); onComplete(false) }
                        }
                }
            }
        }
    }

    // helper to show product dialog (re-use from ReportActivity if available)
    private fun showProductDialog(p: RecProduct) {
        val d = android.app.Dialog(this)
        val v = layoutInflater.inflate(R.layout.dialog_product_detail, null)
        d.setContentView(v)

        val iv = v.findViewById<ImageView>(R.id.ivDlgImage)
        val tvTitle = v.findViewById<TextView>(R.id.tvDlgTitle)
        val tvDesc = v.findViewById<TextView>(R.id.tvDlgDesc)
        val btnClose = v.findViewById<ImageButton>(R.id.btnCloseDialog)

        tvTitle.text = p.name
        tvDesc.text = p.short
        if (!p.localImage.isNullOrEmpty()) {
            val resId = resources.getIdentifier(p.localImage, "drawable", packageName)
            if (resId != 0) iv.setImageResource(resId) else iv.setImageResource(android.R.color.transparent)
        } else if (!p.imageUrl.isNullOrEmpty()) {
            try { iv.setImageURI(Uri.parse(p.imageUrl)) } catch (_: Exception) { iv.setImageResource(android.R.color.transparent) }
        } else iv.setImageResource(android.R.color.transparent)

        btnClose.setOnClickListener { d.dismiss() }

        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        d.show()
    }
}
