package com.example.fyp

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import com.example.fyp.models.Report
import com.example.fyp.models.ScanResult
import com.google.android.material.button.MaterialButton
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import org.tensorflow.lite.Interpreter
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import java.util.Locale
import com.example.fyp.utils.ModelLoader
import com.example.fyp.utils.MLUtils

class EyeReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val MODEL_NAME = "eye_disease_model.tflite"
        private const val TAG = "EyeReportActivity"
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

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }

    private var previewUriStr: String? = null
    private var leftCropUriStr: String? = null
    private var rightCropUriStr: String? = null

    private var lastOverallAccuracy: Double = 0.0
    private var lastLeftLabelStr: String = ""
    private var lastRightLabelStr: String = ""
    private var lastSummaryStr: String = ""
    private var lastRecommendations: List<String> = emptyList()
    private var lastProductsList: List<RecProduct> = emptyList()

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_eye_report)

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

        try {
            interpreter = ModelLoader.loadModelFromAssets(this, MODEL_NAME)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load model", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        RecommendationProvider.loadFromAssets(this, "eye_recommendations.json")

        val imageUriStr = intent.getStringExtra(EXTRA_IMAGE_URI) ?: intent.getStringExtra("extra_image_uri")
        if (imageUriStr.isNullOrEmpty()) { finish(); return }
        val imageUri = Uri.parse(imageUriStr)
        previewUriStr = imageUri.toString()

        val bmp = MLUtils.decodeBitmap(contentResolver, imageUri) ?: run {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show(); finish(); return
        }
        ivPreview.setImageBitmap(bmp)

        val dlg = Dialog(this)
        val loaderView = LayoutInflater.from(this).inflate(R.layout.dialog_simple_loader, null)
        dlg.setContentView(loaderView)
        dlg.setCancelable(false)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.show()

        Thread {
            try {
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

                val res = MLUtils.runEyeModel(interpreter, leftBmp, rightBmp)
                val lPair = res.first
                val rPair = res.second
                val overall = res.third

                val leftUri = saveBitmapToCache(leftBmp, "left_eye_${System.currentTimeMillis()}.jpg")
                val rightUri = saveBitmapToCache(rightBmp, "right_eye_${System.currentTimeMillis()}.jpg")
                leftCropUriStr = leftUri?.toString(); rightCropUriStr = rightUri?.toString()

                runOnUiThread {
                    dlg.dismiss()

                    ivLeftCrop.setImageBitmap(leftBmp)
                    ivRightCrop.setImageBitmap(rightBmp)

                    val leftText = "${lPair.first} (${(lPair.second * 100).toInt()}%)"
                    val rightText = "${rPair.first} (${(rPair.second * 100).toInt()}%)"
                    tvLeftLabel.text = leftText
                    tvRightLabel.text = rightText

                    tvAccuracy.text = "Accuracy Level: ${(overall * 100).toInt()}%"

                    tvSummaryText.text = "Left: $leftText • Right: $rightText"

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

                        if (rec.products.isNotEmpty()) {
                            tvProductsTitle.visibility = View.VISIBLE
                            rvProducts.visibility = View.VISIBLE
                            rvProducts.layoutManager = GridLayoutManager(this, 2, RecyclerView.VERTICAL, false)
                            val adapter = ProductAdapter(this, rec.products) { product ->
                                showProductDialog(product)
                            }
                            rvProducts.adapter = adapter
                            lastProductsList = rec.products
                        } else {
                            tvProductsTitle.visibility = View.GONE
                            rvProducts.visibility = View.GONE
                            lastProductsList = emptyList()
                        }

                        lastRecommendations = rec.tips
                    } else {
                        tvRecSummary.text = "No recommendation available."
                        llTips.removeAllViews()
                        tvProductsTitle.visibility = View.GONE
                        rvProducts.visibility = View.GONE
                        lastProductsList = emptyList()
                        lastRecommendations = emptyList()
                    }

                    lastOverallAccuracy = overall.toDouble()
                    lastLeftLabelStr = leftText
                    lastRightLabelStr = rightText
                    lastSummaryStr = tvSummaryText.text.toString()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(this, "Eye analysis failed.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()

        btnSave.setOnClickListener {
            val uid = auth.currentUser?.uid
            if (uid == null) {
                Toast.makeText(this, "Not authenticated. Please login.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Check connectivity first
            if (!isOnline()) {
                Toast.makeText(this, "No internet connection. Please connect and try again.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSave.isEnabled = false; btnSave.text = "Saving..."
            createReportDocAndLinkToUser(uid, previewUriStr, "eye",
                summary = lastSummaryStr,
                accuracy = lastOverallAccuracy,
                recommendations = lastRecommendations) { success ->
                runOnUiThread {
                    btnSave.isEnabled = true; btnSave.text = "Save Report"
                    if (success) {
                        Toast.makeText(this, "Report saved", Toast.LENGTH_SHORT).show()
                        val userDoc = db.collection("users").document(uid)
                        userDoc.get().addOnSuccessListener { snap ->
                            val userMap = snap.data ?: emptyMap<String, Any>()
                            val i = Intent(this, MainActivity::class.java)
                            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            i.putExtra("open_tab", "home")
                            val serializableMap = HashMap(userMap)
                            i.putExtra("updated_user_map", serializableMap)
                            startActivity(i)
                            finish()
                        }.addOnFailureListener {
                            val i = Intent(this, MainActivity::class.java)
                            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            i.putExtra("open_tab", "home")
                            startActivity(i)
                            finish()
                        }
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
            i.putExtra("category", "EYE")
            startActivity(i); finish()
        }
        btnGoHome.setOnClickListener {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            i.putExtra("open_tab", "home")
            startActivity(i); finish()
        }
    }

    // simple connectivity check
    private fun isOnline(): Boolean {
        try {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val nw = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(nw) ?: return false
            return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
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

    private fun resizeKeepRatio(bitmap: Bitmap, maxLongSide: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val longSide = maxOf(w, h)
        if (longSide <= maxLongSide) return bitmap
        val scale = maxLongSide.toFloat() / longSide.toFloat()
        val newW = (w * scale).roundToInt()
        val newH = (h * scale).roundToInt()
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    private fun createReportDocAndLinkToUser(
        uid: String,
        previewUriStr: String?,
        type: String,
        summary: String,
        accuracy: Double,
        recommendations: List<String>,
        onComplete: (Boolean) -> Unit
    ) {
        try {
            // create report doc in top-level "reports" collection
            val reportsCol = db.collection("reports")
            val newDocRef = reportsCol.document() // auto-id
            val reportId = newDocRef.id

            val eyeScan = hashMapOf(
                "summary" to summary,
                "accuracy" to accuracy,
                "recommendations" to recommendations
            )

            val reportPayload = hashMapOf<String, Any?>(
                "reportId" to reportId,
                "userId" to uid,
                "type" to type,
                "summary" to summary,
                "imageUrl" to null,
                "imageWidth" to null,
                "imageHeight" to null,
                "accuracy" to accuracy,
                "recommendations" to recommendations,
                "eye_scan" to eyeScan,
                "skin_scan" to null,
                "mood_scan" to null,
                "tags" to listOf<String>(),
                "source" to "camera",
                "meta" to hashMapOf("orientation" to "unknown"),
                "createdAt" to Timestamp.now()
            )

            newDocRef.set(reportPayload)
                .addOnSuccessListener {
                    // push only the reportId into user's reports array with retry
                    val userDoc = db.collection("users").document(uid)
                    updateUserReportsWithRetry(userDoc, reportId, onComplete)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to create report doc", e)
                    onComplete(false)
                }
        } catch (e: Exception) {
            e.printStackTrace()
            onComplete(false)
        }
    }

    private fun updateUserReportsWithRetry(userDocRef: com.google.firebase.firestore.DocumentReference, reportId: String, onComplete: (Boolean) -> Unit, attempt: Int = 0) {
        userDocRef.update("reports", FieldValue.arrayUnion(reportId))
            .addOnSuccessListener {
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "update reports failed (attempt=$attempt): ${e.message}", e)
                if (attempt < 1) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        updateUserReportsWithRetry(userDocRef, reportId, onComplete, attempt + 1)
                    }, 800)
                } else {
                    val payload = hashMapOf("reports" to listOf(reportId))
                    userDocRef.set(payload, SetOptions.merge())
                        .addOnSuccessListener { onComplete(true) }
                        .addOnFailureListener { ex -> ex.printStackTrace(); onComplete(false) }
                }
            }
    }

    private fun showProductDialog(p: RecProduct) {
        val d = android.app.Dialog(this)
        val v = layoutInflater.inflate(R.layout.dialog_product_detail, null)
        d.setContentView(v)
        d.setCancelable(true)

        val iv = v.findViewById<ImageView>(R.id.ivDlgImage)
        val tvTitle = v.findViewById<TextView>(R.id.tvDlgTitle)
        val tvDesc = v.findViewById<TextView>(R.id.tvDlgDesc)
        val btnClose = v.findViewById<ImageButton>(R.id.btnCloseDialog)

        tvTitle.text = p.name
        tvDesc.text = p.short ?: ""

        // load image (local drawable name or remote)
        if (!p.localImage.isNullOrEmpty()) {
            val resId = resources.getIdentifier(p.localImage, "drawable", packageName)
            if (resId != 0) iv.setImageResource(resId) else iv.setImageResource(android.R.color.transparent)
        } else if (!p.imageUrl.isNullOrEmpty()) {
            // prefer Glide/Picasso if available. Fallback: setImageURI (may be slow)
            try {
                iv.setImageURI(Uri.parse(p.imageUrl))
            } catch (_: Exception) {
                iv.setImageResource(android.R.color.transparent)
            }
        } else {
            iv.setImageResource(android.R.color.transparent)
        }

        btnClose.setOnClickListener { d.dismiss() }

        // Make dialog window width ~90% of screen and height wrap content
        d.show()
        val window = d.window
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        val params = window?.attributes
        val w = (resources.displayMetrics.widthPixels * 0.90).toInt()
        window?.setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

}
