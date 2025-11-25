package com.example.fyp

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import kotlin.math.max
import kotlin.math.min

class FullFaceReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EYE_MODEL = "eye_disease_model.tflite"
        const val SKIN_MODEL = "skin_full_int8.tflite"
        const val SKIN_LABELS = "labels.txt"
        const val MOOD_MODEL = "mood_detection_model.tflite"
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

                runOnUiThread {
                    tvMoodLabel.text = mTop.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    tvMoodAcc.text = "Accuracy Level: ${String.format("%.1f", mConf)}%"
                    val rec = RecommendationProvider.getMoodRecommendation(mTop.replace("\\s".toRegex(), "").lowercase())
                    tvMoodRecSummary.text = rec?.summary ?: "Follow general mood care steps."
                    llMoodTipsCard.removeAllViews()
                    rec?.tips?.forEach { tip ->
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

        // Save report (existing behavior)
        btnSave.setOnClickListener {
            val uid = auth.currentUser?.uid
            if (uid == null) {
                Toast.makeText(this, "Not authenticated. Please login.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnSave.isEnabled = false; btnSave.text = "Saving..."
            val summary = "Skin: ${tvSkinLabel.text}\nEye: ${tvEyeLabel.text}\nMood: ${tvMoodLabel.text}"
            val topLabel = tvSkinLabel.text.toString()
            uploadImagesAndSaveReport(uid, previewUriStr, "fullface", summary, topLabel) { success ->
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

    private fun uploadImagesAndSaveReport(
        uid: String,
        previewUriStr: String?,
        type: String,
        summary: String,
        topLabel: String,
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

        uploadOne(previewUri, "preview_${System.currentTimeMillis()}") { pUrl ->
            uploaded["preview"] = pUrl ?: ""
            val report = hashMapOf<String, Any>(
                "type" to type,
                "summary" to summary,
                "topLabel" to topLabel,
                "confidence" to 0.0,
                "previewUrl" to (uploaded["preview"] ?: ""),
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
