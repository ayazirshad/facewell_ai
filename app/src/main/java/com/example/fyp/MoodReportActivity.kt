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
import com.google.android.material.button.MaterialButton
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileOutputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.example.fyp.utils.ModelLoader
import com.example.fyp.utils.MLUtils

class MoodReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val MODEL_NAME = "mood_detection_model.tflite"
        private const val TAG = "MoodReportActivity"
    }

    private lateinit var ivPreview: ImageView
    private lateinit var tvSummaryText: TextView
    private lateinit var tvAccuracyLabel: TextView
    private lateinit var btnSave: MaterialButton
    private lateinit var btnVisit: MaterialButton
    private lateinit var btnGoHome: MaterialButton
    private lateinit var btnBack: ImageButton
    private lateinit var tvReportTitle: TextView
    private lateinit var llTips: LinearLayout
    private lateinit var tvRecSummary: TextView
    private lateinit var tvProductsTitle: TextView
    private lateinit var rvProducts: RecyclerView

    private lateinit var interpreter: Interpreter
    private var inputH = 48
    private var inputW = 48
    private var inputChannels = 1
    private var inputDataType = DataType.FLOAT32
    private var inputScale = 1.0f
    private var inputZeroPoint = 0

    private val labels = listOf("Angry","Disgust","Fear","Happy","Sad","Surprise","Neutral")

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }

    private var previewUriStr: String? = null
    private var lastTopLabel: String = "unknown"
    private var lastConfidence: Double = 0.0
    private var lastRecommendations: List<String> = emptyList()

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mood_report)

        ivPreview = findViewById(R.id.ivPreview)
        tvSummaryText = findViewById(R.id.tvSummaryText)
        tvAccuracyLabel = findViewById(R.id.tvAccuracyLabel)
        btnSave = findViewById(R.id.btnSaveReport)
        btnVisit = findViewById(R.id.btnVisitClinic)
        btnGoHome = findViewById(R.id.btnGoHome)
        btnBack = findViewById(R.id.btnBack)
        tvReportTitle = findViewById(R.id.tvReportTitle)
        llTips = findViewById(R.id.llTips)
        tvRecSummary = findViewById(R.id.tvRecSummary)
        tvProductsTitle = findViewById(R.id.tvProductsTitle)
        rvProducts = findViewById(R.id.rvProducts)

        tvReportTitle.text = "Mood Report"
        btnBack.setOnClickListener { finish() }

        try {
            // use ModelLoader helper to load interpreter
            interpreter = ModelLoader.loadModelFromAssets(this, MODEL_NAME)
            val t = interpreter.getInputTensor(0)
            val shape = t.shape()
            if (shape.size >= 3) { inputH = shape[1]; inputW = shape[2] }
            inputChannels = if (shape.size >= 4) shape[3] else 1
            inputDataType = t.dataType()
            try {
                val q = t.quantizationParams()
                inputScale = q.scale
                inputZeroPoint = q.zeroPoint
            } catch (_: Exception) {}
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load mood model", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        // load mood recommendations file
        RecommendationProvider.loadFromAssets(this, "mood_recommendations.json")

        val imageUriStr = intent.getStringExtra(EXTRA_IMAGE_URI) ?: intent.getStringExtra("extra_image_uri")
        if (imageUriStr.isNullOrEmpty()) { finish(); return }
        val imageUri = Uri.parse(imageUriStr)
        previewUriStr = imageUri.toString()

        // decode via MLUtils helper (EXIF-aware)
        val bmp = MLUtils.decodeBitmap(contentResolver, imageUri) ?: run {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show(); finish(); return
        }
        ivPreview.setImageBitmap(bmp)

        // show dialog_simple_loader while running analysis (same loader as ConfirmPhotoActivity)
        val dlg = Dialog(this)
        val loaderView = LayoutInflater.from(this).inflate(R.layout.dialog_simple_loader, null)
        dlg.setContentView(loaderView)
        dlg.setCancelable(false)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.show()

        // run model + populate recommendations using MLUtils helper (blocking call inside background thread)
        Thread {
            try {
                // MLUtils provides blocking helper that preserves original behavior
                val (topLabel, conf) = MLUtils.runMoodOnBitmapForTopBlocking(bmp, interpreter, inputW, inputH, inputChannels, inputDataType)
                lastTopLabel = topLabel
                lastConfidence = conf
                // compute recommendations now so we can save them later
                val key = topLabel.replace("\\s".toRegex(), "").lowercase()
                val rec = RecommendationProvider.getMoodRecommendation(key)
                lastRecommendations = rec?.tips ?: emptyList()

                runOnUiThread {
                    // dismiss loader
                    dlg.dismiss()

                    // show only top predicted mood + accuracy
                    val confFmt = String.format("%.1f", conf)
                    tvSummaryText.text = "${topLabel} (${confFmt}%)"
                    tvAccuracyLabel.text = "Accuracy Level: ${confFmt}%"

                    // load recommendation and populate tips
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
                        // mood products are empty by design -> hide products
                        tvProductsTitle.visibility = View.GONE
                        rvProducts.visibility = View.GONE
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
                    Toast.makeText(this, "Mood analysis failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()

        btnSave.setOnClickListener {
            val uid = auth.currentUser?.uid
            if (uid == null) {
                Toast.makeText(this, "Not authenticated. Please login.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnSave.isEnabled = false
            btnSave.text = "Saving..."
            val summary = tvSummaryText.text.toString()
            val topLabel = lastTopLabel
            val confidence = lastConfidence
            val recsToSave = lastRecommendations
            uploadImagesAndSaveReport(uid, previewUriStr, "mood", summary, topLabel, confidence, recsToSave) { success ->
                runOnUiThread {
                    btnSave.isEnabled = true
                    btnSave.text = "Save Report"
                    if (success) {
                        Toast.makeText(this, "Report saved", Toast.LENGTH_SHORT).show()
                        // fetch updated user doc then navigate (so MainActivity receives updated_user_map)
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
        } catch (e: Exception) {
            e.printStackTrace(); null
        }
    }

    private fun uploadImagesAndSaveReport(
        uid: String,
        previewUriStr: String?,
        type: String,
        summary: String,
        topLabel: String,
        confidence: Double,
        recommendations: List<String>,
        onComplete: (Boolean) -> Unit
    ) {
        val previewUri = previewUriStr?.let { Uri.parse(it) }
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

        // Upload preview -> create top-level report doc -> link to user.reports (with retry)
        uploadOne(previewUri, "preview_${System.currentTimeMillis()}") { pUrl ->
            uploaded["preview"] = pUrl ?: ""

            // Build mood_scan payload (ScanResult-like)
            val moodScan = hashMapOf<String, Any>(
                "summary" to summary,
                "accuracy" to confidence,
                "topLabel" to topLabel,
                "previewUrl" to (uploaded["preview"] ?: ""),
                "recommendations" to recommendations
            )

            // create report doc in top-level "reports" collection
            try {
                val reportsCol = db.collection("reports")
                val newDocRef = reportsCol.document()
                val reportId = newDocRef.id

                val reportPayload = hashMapOf<String, Any?>(
                    "reportId" to reportId,
                    "userId" to uid,
                    "type" to type,
                    "summary" to summary,
                    "imageUrl" to (uploaded["preview"] ?: ""),
                    "imageWidth" to null,
                    "imageHeight" to null,
                    "accuracy" to confidence,
                    "recommendations" to recommendations,
                    "eye_scan" to null,
                    "skin_scan" to null,
                    "mood_scan" to moodScan,
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
}
