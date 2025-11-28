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
import android.view.ViewGroup
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
import com.example.fyp.utils.ModelLoader
import com.example.fyp.utils.MLUtils
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

class FullFaceReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EYE_MODEL = "eye_disease_model.tflite"
        const val SKIN_MODEL = "skin_full_int8.tflite"
        const val SKIN_LABELS = "labels.txt"
        const val MOOD_MODEL = "mood_detection_model.tflite"
        private const val TAG = "FullFaceReportActivity"
    }

    // UI
    private lateinit var ivPreview: ImageView
    private lateinit var tvSkinLabel: TextView
    private lateinit var tvEyeLabel: TextView
    private lateinit var tvMoodLabel: TextView
    private lateinit var tvSkinAcc: TextView
    private lateinit var tvEyeAcc: TextView
    private lateinit var tvMoodAcc: TextView
    private lateinit var btnSave: MaterialButton
    private lateinit var btnVisit: MaterialButton
    private lateinit var btnGoHome: MaterialButton
    private lateinit var btnBack: ImageButton
    private lateinit var llSkinTips: LinearLayout
    private lateinit var llEyeTipsCard: LinearLayout
    private lateinit var llMoodTipsCard: LinearLayout
    private lateinit var rvSkinPatches: RecyclerView

    // rec summaries inside cards
    private lateinit var tvSkinRecSummary: TextView
    private lateinit var tvEyeRecSummary: TextView
    private lateinit var tvMoodRecSummary: TextView

    // models/labels
    private lateinit var eyeInterpreter: Interpreter
    private lateinit var skinInterpreter: Interpreter
    private lateinit var moodInterpreter: Interpreter
    private var skinLabels: List<String> = emptyList()

    // firebase
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }

    private var previewUriStr: String? = null
    private val skinPatchResults = mutableListOf<MLUtils.PatchResult>()

    // last analysis values (used when saving)
    private var lastSkinSummary: String = ""
    private var lastSkinAccuracy: Double = 0.0
    private var lastSkinRecommendations: List<String> = emptyList()

    private var lastEyeSummary: String = ""
    private var lastEyeAccuracy: Double = 0.0
    private var lastEyeRecommendations: List<String> = emptyList()
    private var lastEyeLeftUrl: String? = null
    private var lastEyeRightUrl: String? = null

    private var lastMoodSummary: String = ""
    private var lastMoodAccuracy: Double = 0.0
    private var lastMoodRecommendations: List<String> = emptyList()

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_face_report)

        // bind views
        ivPreview = findViewById(R.id.ivPreview)
        tvSkinLabel = findViewById(R.id.tvSkinLabel)
        tvEyeLabel = findViewById(R.id.tvEyeLabel)
        tvMoodLabel = findViewById(R.id.tvMoodLabel)
        tvSkinAcc = findViewById(R.id.tvSkinAcc)
        tvEyeAcc = findViewById(R.id.tvEyeAcc)
        tvMoodAcc = findViewById(R.id.tvMoodAcc)

        btnSave = findViewById(R.id.btnSaveReport)
        btnVisit = findViewById(R.id.btnVisitClinic)
        btnGoHome = findViewById(R.id.btnGoHome)
        btnBack = findViewById(R.id.btnBack)

        tvSkinRecSummary = findViewById(R.id.tvSkinRecSummary)
        tvEyeRecSummary = findViewById(R.id.tvEyeRecSummary)
        tvMoodRecSummary = findViewById(R.id.tvMoodRecSummary)

        llSkinTips = findViewById(R.id.llSkinTips)
        llEyeTipsCard = findViewById(R.id.llEyeTipsCard)
        llMoodTipsCard = findViewById(R.id.llMoodTipsCard)

        rvSkinPatches = findViewById(R.id.rvSkinPatches)
        rvSkinPatches.layoutManager = GridLayoutManager(this, 2, RecyclerView.VERTICAL, false)

        btnBack.setOnClickListener { finish() }

        // load models
        try {
            eyeInterpreter = ModelLoader.loadModelFromAssets(this, EYE_MODEL)
            skinInterpreter = ModelLoader.loadModelFromAssets(this, SKIN_MODEL)
            moodInterpreter = ModelLoader.loadModelFromAssets(this, MOOD_MODEL)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load models", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        skinLabels = loadLabels(SKIN_LABELS)

        RecommendationProvider.loadFromAssets(this, "eye_recommendations.json")
        RecommendationProvider.loadFromAssets(this, "skin_recommendations.json")
        RecommendationProvider.loadFromAssets(this, "mood_recommendations.json")

        val imageUriStr = intent.getStringExtra(EXTRA_IMAGE_URI) ?: intent.getStringExtra("extra_image_uri")
        if (imageUriStr.isNullOrEmpty()) { finish(); return }
        val imageUri = Uri.parse(imageUriStr)
        previewUriStr = imageUri.toString()

        val bmp = MLUtils.decodeBitmap(contentResolver, imageUri) ?: run {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show(); finish(); return
        }
        ivPreview.setImageBitmap(bmp)

        // loader
        val dlg = Dialog(this)
        val loaderView = LayoutInflater.from(this).inflate(R.layout.dialog_simple_loader, null)
        dlg.setContentView(loaderView)
        dlg.setCancelable(false)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.show()

        Thread {
            try {
                // EYE
                val eyeRes = MLUtils.detectAndCropEyesBlocking(bmp)
                runOnUiThread {
                    llEyeTipsCard.removeAllViews()
                    tvEyeRecSummary.text = ""
                }
                if (eyeRes.leftCrop != null && eyeRes.rightCrop != null) {
                    val (lPair, rPair, overall) = MLUtils.runEyeModel(eyeInterpreter, eyeRes.leftCrop!!, eyeRes.rightCrop!!)
                    val topKey = if (lPair.second >= rPair.second) lPair.first else rPair.first
                    val leftText = "${lPair.first} (${(lPair.second * 100).toInt()}%)"
                    val rightText = "${rPair.first} (${(rPair.second * 100).toInt()}%)"
                    val eyeAcc = overall * 100f

                    val rec = RecommendationProvider.getEyeRecommendation(topKey.replace("\\s".toRegex(), "").lowercase())

                    // store last eye info for saving
                    lastEyeSummary = "Left: $leftText • Right: $rightText"
                    lastEyeAccuracy = overall.toDouble()
                    lastEyeRecommendations = rec?.tips ?: emptyList()

                    // attempt to save left/right crops to cache URIs so report can reference them (non-blocking)
                    lastEyeLeftUrl = try {
                        saveBitmapToCache(eyeRes.leftCrop!!, "left_eye_${System.currentTimeMillis()}")?.toString()
                    } catch (e: Exception) { null }
                    lastEyeRightUrl = try {
                        saveBitmapToCache(eyeRes.rightCrop!!, "right_eye_${System.currentTimeMillis()}")?.toString()
                    } catch (e: Exception) { null }

                    runOnUiThread {
                        tvEyeLabel.text = topKey.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        tvEyeAcc.text = "Accuracy Level: ${String.format("%.1f", eyeAcc)}%"
                        tvEyeRecSummary.text = rec?.summary ?: "Follow general eye care steps."
                        llEyeTipsCard.removeAllViews()
                        rec?.tips?.forEach { tip ->
                            val tv = layoutInflater.inflate(android.R.layout.simple_list_item_1, llEyeTipsCard, false) as TextView
                            tv.text = "\u2022  $tip"
                            tv.setTextColor(resources.getColor(R.color.black))
                            tv.setTextSize(14f)
                            llEyeTipsCard.addView(tv)
                        }
                    }
                } else {
                    runOnUiThread {
                        tvEyeLabel.text = "Not detected"
                        tvEyeAcc.text = "Accuracy Level: 0.0%"
                        lastEyeSummary = "Not detected"
                        lastEyeAccuracy = 0.0
                        lastEyeRecommendations = emptyList()
                    }
                }

                // SKIN
                val skinInputTensor = skinInterpreter.getInputTensor(0)
                val skinShape = skinInputTensor.shape()
                val sH = if (skinShape.size >= 3) skinShape[1] else 128
                val sW = if (skinShape.size >= 3) skinShape[2] else 128
                val sChannels = if (skinShape.size >= 4) skinShape[3] else 3
                var sScale = 1.0f; var sZp = 0
                try {
                    val q = skinInputTensor.quantizationParams()
                    sScale = q.scale; sZp = q.zeroPoint
                } catch (_: Exception) {}

                val (patches, aggProbs) = MLUtils.analyzePatchesFromSelfieBlocking(bmp, skinInterpreter, skinLabels, sW, sH, sChannels,
                    skinInputTensor.dataType(), sScale, sZp)

                skinPatchResults.clear()
                skinPatchResults.addAll(patches)

                var finalLabel = "unknown"; var finalIdx = 0
                if (skinPatchResults.isNotEmpty()) {
                    val counts = mutableMapOf<String, Int>()
                    for (pr in skinPatchResults) counts[pr.label] = (counts[pr.label] ?: 0) + 1
                    val maxCount = counts.values.maxOrNull() ?: 0
                    val labelsWithMax = counts.filter { it.value == maxCount }.keys.toList()
                    if (maxCount >= 3) {
                        finalLabel = labelsWithMax.first()
                    } else if (maxCount == 2) {
                        val bestAggIdx = aggProbs.indices.maxByOrNull { aggProbs[it] } ?: 0
                        finalLabel = skinLabels.getOrNull(bestAggIdx) ?: labelsWithMax.first()
                    } else {
                        val bestAggIdx = aggProbs.indices.maxByOrNull { aggProbs[it] } ?: 0
                        finalLabel = skinLabels.getOrNull(bestAggIdx) ?: "unknown"
                    }
                    finalIdx = skinLabels.indexOf(finalLabel).coerceAtLeast(0)
                }

                val finalProb = if (aggProbs.isNotEmpty() && finalIdx in aggProbs.indices) aggProbs[finalIdx] else 0f
                val displaySkinSummary = if (skinPatchResults.isEmpty()) {
                    MLUtils.coarseLabelBySampling(bmp, skinInterpreter, skinLabels, sW, sH, sChannels, skinInputTensor.dataType(), sScale, sZp)
                } else {
                    finalLabel.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }

                val skinRecKey = finalLabel.replace("\\s".toRegex(), "").lowercase()
                val skinRec = RecommendationProvider.getSkinRecommendation(skinRecKey)

                // store last skin info for saving
                lastSkinSummary = displaySkinSummary
                lastSkinAccuracy = finalProb.toDouble()
                lastSkinRecommendations = skinRec?.tips ?: emptyList()

                runOnUiThread {
                    tvSkinLabel.text = displaySkinSummary
                    tvSkinAcc.text = "Accuracy Level: ${String.format("%.1f", finalProb * 100.0)}%"
                    rvSkinPatches.adapter = PatchAdapter(skinPatchResults)
                    tvSkinRecSummary.text = skinRec?.summary ?: "Follow general skin care steps."
                    llSkinTips.removeAllViews()
                    skinRec?.tips?.forEach { tip ->
                        val tv = layoutInflater.inflate(android.R.layout.simple_list_item_1, llSkinTips, false) as TextView
                        tv.text = "\u2022  $tip"
                        tv.setTextColor(resources.getColor(R.color.black))
                        tv.setTextSize(14f)
                        llSkinTips.addView(tv)
                    }
                }

                // MOOD
                val moodInputT = moodInterpreter.getInputTensor(0)
                val mShape = moodInputT.shape()
                val mH = if (mShape.size >= 3) mShape[1] else 48
                val mW = if (mShape.size >= 3) mShape[2] else 48
                val mChannels = if (mShape.size >= 4) mShape[3] else 1
                val (mTop, mConf) = MLUtils.runMoodOnBitmapForTopBlocking(bmp, moodInterpreter, mW, mH, mChannels, moodInputT.dataType())

                val moodRec = RecommendationProvider.getMoodRecommendation(mTop.replace("\\s".toRegex(), "").lowercase())

                // store last mood info for saving
                lastMoodSummary = mTop
                lastMoodAccuracy = mConf
                lastMoodRecommendations = moodRec?.tips ?: emptyList()

                runOnUiThread {
                    tvMoodLabel.text = mTop.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    tvMoodAcc.text = "Accuracy Level: ${String.format("%.1f", mConf)}%"
                    tvMoodRecSummary.text = moodRec?.summary ?: "Follow general mood care steps."
                    llMoodTipsCard.removeAllViews()
                    moodRec?.tips?.forEach { tip ->
                        val tv = layoutInflater.inflate(android.R.layout.simple_list_item_1, llMoodTipsCard, false) as TextView
                        tv.text = "\u2022  $tip"
                        tv.setTextColor(resources.getColor(R.color.black))
                        tv.setTextSize(14f)
                        llMoodTipsCard.addView(tv)
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Full face analysis failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread {
                    try { if (dlg.isShowing) dlg.dismiss() } catch (_: Exception) {}
                }
            }
        }.start()

        // Save report (updated methodology)
        btnSave.setOnClickListener {
            val uid = auth.currentUser?.uid
            if (uid == null) {
                Toast.makeText(this, "Not authenticated. Please login.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnSave.isEnabled = false; btnSave.text = "Saving..."
            val summary = "Skin: ${tvSkinLabel.text}\nEye: ${tvEyeLabel.text}\nMood: ${tvMoodLabel.text}"
            val topLabel = tvSkinLabel.text.toString()
            uploadImagesAndSaveReport(uid, previewUriStr, "general", summary, topLabel,
                eyeSummary = lastEyeSummary,
                eyeAccuracy = lastEyeAccuracy,
                eyeRecommendations = lastEyeRecommendations,
                eyeLeftUrl = lastEyeLeftUrl,
                eyeRightUrl = lastEyeRightUrl,
                skinSummary = lastSkinSummary,
                skinAccuracy = lastSkinAccuracy,
                skinRecommendations = lastSkinRecommendations,
                moodSummary = lastMoodSummary,
                moodAccuracy = lastMoodAccuracy,
                moodRecommendations = lastMoodRecommendations
            ) { success ->
                runOnUiThread {
                    btnSave.isEnabled = true; btnSave.text = "Save Report"
                    if (success) {
                        Toast.makeText(this, "Report saved", Toast.LENGTH_SHORT).show()
                        // fetch updated user doc then navigate so MainActivity receives updated_user_map
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

        // Visit clinic -> open clinics tab in MainActivity (same pattern used across app)
        btnVisit.setOnClickListener {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            i.putExtra("open_tab", "clinics")
            startActivity(i); finish()
        }

        // Go home -> open home tab
        btnGoHome.setOnClickListener {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            i.putExtra("open_tab", "home")
            startActivity(i); finish()
        }
    }

    private fun loadLabels(fileName: String): List<String> {
        return try {
            val input = assets.open(fileName)
            val br = BufferedReader(InputStreamReader(input))
            val out = br.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            br.close(); out
        } catch (e: Exception) { emptyList() }
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
        eyeSummary: String,
        eyeAccuracy: Double,
        eyeRecommendations: List<String>,
        eyeLeftUrl: String?,
        eyeRightUrl: String?,
        skinSummary: String,
        skinAccuracy: Double,
        skinRecommendations: List<String>,
        moodSummary: String,
        moodAccuracy: Double,
        moodRecommendations: List<String>,
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

        // upload preview first (we don't require uploading local eye crops to storage; we save their cache URIs if available)
        uploadOne(previewUri, "preview_${System.currentTimeMillis()}") { pUrl ->
            uploaded["preview"] = pUrl ?: ""

            // Create scan payloads
            val eyeScan = hashMapOf<String, Any?>(
                "summary" to eyeSummary,
                "accuracy" to eyeAccuracy,
                "recommendations" to eyeRecommendations,
                "leftPreview" to (eyeLeftUrl ?: null),
                "rightPreview" to (eyeRightUrl ?: null)
            )

            val skinScan = hashMapOf<String, Any?>(
                "summary" to skinSummary,
                "accuracy" to skinAccuracy,
                "recommendations" to skinRecommendations
            )

            val moodScan = hashMapOf<String, Any?>(
                "summary" to moodSummary,
                "accuracy" to moodAccuracy,
                "recommendations" to moodRecommendations
            )

            // create report doc in top-level "reports" collection
            try {
                val reportsCol = db.collection("reports")
                val newDocRef = reportsCol.document()
                val reportId = newDocRef.id

                val reportPayload = hashMapOf<String, Any?>(
                    "reportId" to reportId,
                    "userId" to uid,
                    "type" to type, // "general"
                    "summary" to summary,
                    "imageUrl" to (uploaded["preview"] ?: ""),
                    "imageWidth" to null,
                    "imageHeight" to null,
                    "accuracy" to ((eyeAccuracy + skinAccuracy + moodAccuracy) / 3.0),
                    "recommendations" to (eyeRecommendations + skinRecommendations + moodRecommendations),
                    "eye_scan" to eyeScan,
                    "skin_scan" to skinScan,
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

    // adapter (same as before)
    inner class PatchAdapter(private val items: List<MLUtils.PatchResult>) : RecyclerView.Adapter<PatchAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.ivPatch)
            val tvLbl: TextView = v.findViewById(R.id.tvPatchLabel)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_patch, parent, false)
            return VH(v)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val ite = items[position]
            holder.iv.setImageBitmap(ite.bmp)
            holder.tvLbl.text = ite.label.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } + " (" + String.format("%.1f", ite.confidence) + "%)"
            holder.itemView.setOnClickListener {
                val d = android.app.Dialog(this@FullFaceReportActivity)
                val vi = layoutInflater.inflate(R.layout.dialog_patch_full, null)
                val ivf = vi.findViewById<ImageView>(R.id.ivFull)
                val tv = vi.findViewById<TextView>(R.id.tvFullLabel)
                if (ivf != null) ivf.setImageBitmap(ite.bmp)
                if (tv != null) tv.text = holder.tvLbl.text
                d.setContentView(vi)
                d.window?.setBackgroundDrawableResource(android.R.color.transparent)
                d.show()
            }
        }
        override fun getItemCount(): Int = items.size
    }
}
