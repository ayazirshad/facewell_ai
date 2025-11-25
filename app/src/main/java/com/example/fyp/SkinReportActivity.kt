package com.example.fyp

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.graphics.*
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
import com.google.android.material.card.MaterialCardView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class SkinReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val MODEL_NAME = "skin_full_int8.tflite"
        const val LABELS_FILE = "labels.txt"
        const val TAG = "SkinReportActivity"
    }

    private lateinit var ivPreview: ImageView
    private lateinit var ivHeatmap: ImageView
    private lateinit var tvSummaryText: TextView
    private lateinit var tvAccuracyLabel: TextView
    private lateinit var btnSave: MaterialButton
    private lateinit var btnVisit: MaterialButton
    private lateinit var btnGoHome: MaterialButton
    private lateinit var tvRecSummary: TextView
    private lateinit var llTips: LinearLayout
    private lateinit var btnBack: ImageButton
    private lateinit var rvPatches: RecyclerView
    private lateinit var tvPatchesTitle: TextView

    private lateinit var interpreter: Interpreter
    private var labels: List<String> = emptyList()
    private var inputH = 128
    private var inputW = 128
    private var inputChannels = 3
    private var inputDataType = DataType.FLOAT32
    private var inputScale = 1.0f
    private var inputZeroPoint = 0

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }

    private var previewUriStr: String? = null
    private var faceHeatmapUriStr: String? = null

    private val patchResults = mutableListOf<PatchResult>()

    private var lastFinalLabel: String = "unknown"
    private var lastFinalProb: Float = 0f

    data class PatchResult(val bmp: Bitmap, val label: String, val confidence: Double)

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_skin_report)

        ivPreview = findViewById(R.id.ivPreview)
        ivHeatmap = findViewById(R.id.ivHeatmap)
        tvSummaryText = findViewById(R.id.tvSummaryText)
        tvAccuracyLabel = findViewById(R.id.tvAccuracyLabel)
        btnSave = findViewById(R.id.btnSaveReport)
        btnVisit = findViewById(R.id.btnVisitClinic)
        btnGoHome = findViewById(R.id.btnGoHome)
        tvRecSummary = findViewById(R.id.tvRecSummary)
        llTips = findViewById(R.id.llTips)
        btnBack = findViewById(R.id.btnBack)
        rvPatches = findViewById(R.id.rvPatches)
        tvPatchesTitle = findViewById(R.id.tvPatchesTitle)

        btnBack.setOnClickListener { finish() }

        RecommendationProvider.loadFromAssets(this, "skin_recommendations.json")

        labels = loadLabels(LABELS_FILE)
        try {
            interpreter = loadModel(MODEL_NAME)
            val t = interpreter.getInputTensor(0)
            val shape = t.shape()
            if (shape.size >= 3) { inputH = shape[1]; inputW = shape[2] }
            inputDataType = t.dataType()
            inputChannels = if (shape.size >= 4) shape[3] else 3
            try {
                val q = t.quantizationParams()
                inputScale = q.scale
                inputZeroPoint = q.zeroPoint
            } catch (_: Exception) { }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load model", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        val imageUriStr = intent.getStringExtra(EXTRA_IMAGE_URI) ?: intent.getStringExtra("extra_image_uri")
        if (imageUriStr.isNullOrEmpty()) { finish(); return }
        val imageUri = Uri.parse(imageUriStr)
        previewUriStr = imageUri.toString()
        val bmp = decodeBitmap(imageUri) ?: run {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show(); finish(); return
        }
        ivPreview.setImageBitmap(bmp)

        rvPatches.layoutManager = GridLayoutManager(this, 2, RecyclerView.VERTICAL, false)
        rvPatches.adapter = PatchAdapter(patchResults)

        val loader = Dialog(this)
        val loaderView = LayoutInflater.from(this).inflate(R.layout.dialog_simple_loader, null)
        loader.setContentView(loaderView)
        loader.setCancelable(false)
        loader.window?.setBackgroundDrawableResource(android.R.color.transparent)
        loader.show()

        Thread {
            try {
                val summary = runSkinOnBitmap(bmp)
                val (validPatches, aggProbs) = analyzePatchesFromSelfie(bmp)

                patchResults.clear()
                for (p in validPatches) {
                    patchResults.add(PatchResult(p.bmp, p.label, p.confidence))
                }

                val totalPatches = patchResults.size
                var finalLabel = "unknown"
                var finalCount = 0
                var finalIdx = 0

                if (totalPatches > 0) {
                    val counts = mutableMapOf<String, Int>()
                    for (pr in patchResults) {
                        counts[pr.label] = (counts[pr.label] ?: 0) + 1
                    }
                    val maxCount = counts.values.maxOrNull() ?: 0
                    val labelsWithMax = counts.filter { it.value == maxCount }.keys.toList()

                    if (maxCount >= 3) {
                        finalLabel = labelsWithMax.first()
                    } else if (maxCount == 2) {
                        val bestAggIdx = aggProbs.indices.maxByOrNull { aggProbs[it] } ?: 0
                        finalLabel = labels.getOrNull(bestAggIdx) ?: labelsWithMax.first()
                    } else {
                        val bestAggIdx = aggProbs.indices.maxByOrNull { aggProbs[it] } ?: 0
                        finalLabel = labels.getOrNull(bestAggIdx) ?: "unknown"
                    }

                    finalCount = counts[finalLabel] ?: 0
                    finalIdx = labels.indexOf(finalLabel).coerceAtLeast(0)
                }

                val finalProb = if (aggProbs.isNotEmpty() && finalIdx in aggProbs.indices) aggProbs[finalIdx] else 0f

                lastFinalLabel = finalLabel
                lastFinalProb = finalProb

                val displaySummary = if (patchResults.isEmpty()) {
                    summary
                } else {
                    finalLabel.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase() else it.toString()
                    }
                }

                runOnUiThread {
                    tvSummaryText.text = displaySummary
                    tvAccuracyLabel.text = "Accuracy Level: ${String.format("%.1f", finalProb * 100.0)}%"
                    rvPatches.adapter?.notifyDataSetChanged()
                    val recKey = finalLabel.replace("\\s".toRegex(), "").lowercase()
                    val rec = RecommendationProvider.getSkinRecommendation(recKey)
                    tvRecSummary.text = rec?.summary ?: "Follow general skin care steps."
                    llTips.removeAllViews()
                    rec?.tips?.forEach { tip ->
                        val tv = layoutInflater.inflate(android.R.layout.simple_list_item_1, llTips, false) as TextView
                        tv.text = "\u2022  $tip"
                        tv.setTextColor(resources.getColor(R.color.black))
                        tv.setTextSize(14f)
                        llTips.addView(tv)
                    }
                    ivHeatmap.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this, "Skin analysis failed: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                runOnUiThread {
                    try { if (loader.isShowing) loader.dismiss() } catch (_: Exception) {}
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
            val topLabel = if (lastFinalLabel.isNotEmpty() && lastFinalLabel != "unknown") {
                lastFinalLabel
            } else {
                if (patchResults.isNotEmpty()) patchResults.maxByOrNull { it.confidence }?.label ?: "unknown" else "unknown"
            }
            uploadImagesAndSaveReport(uid, previewUriStr, faceHeatmapUriStr, type = "skin",
                summary = summary, topLabel = topLabel) { success ->
                runOnUiThread {
                    btnSave.isEnabled = true
                    btnSave.text = "Save Report"
                    if (success) {
                        Toast.makeText(this, "Report saved", Toast.LENGTH_SHORT).show()
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

    private fun analyzePatchesFromSelfie(fullBmp: Bitmap): Pair<List<PerPatch>, FloatArray> {
        val img = InputImage.fromBitmap(fullBmp, 0)
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
        val detector = FaceDetection.getClient(opts)
        val faces = com.google.android.gms.tasks.Tasks.await(detector.process(img))
        if (faces.isEmpty()) return Pair(emptyList(), FloatArray(labels.size) { 0f })

        val face = faces[0]
        val bbox = face.boundingBox
        val faceW = bbox.width().toFloat()
        val faceH = bbox.height().toFloat()

        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val mouth = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position

        val leftCheekCenter = if (leftEye != null && nose != null) {
            android.graphics.PointF((leftEye.x + nose.x) / 2f, (leftEye.y + nose.y) / 2f + 0.12f * faceH)
        } else {
            android.graphics.PointF(bbox.left + 0.28f * faceW, bbox.top + 0.55f * faceH)
        }
        val rightCheekCenter = if (rightEye != null && nose != null) {
            android.graphics.PointF((rightEye.x + nose.x) / 2f, (rightEye.y + nose.y) / 2f + 0.12f * faceH)
        } else {
            android.graphics.PointF(bbox.left + 0.72f * faceW, bbox.top + 0.55f * faceH)
        }
        val chinCenter = if (mouth != null) {
            android.graphics.PointF((bbox.left + bbox.right) / 2f, mouth.y + 0.18f * faceH)
        } else {
            android.graphics.PointF((bbox.left + bbox.right) / 2f, bbox.top + 0.85f * faceH)
        }
        val foreheadCenter = if (leftEye != null && rightEye != null) {
            android.graphics.PointF((leftEye.x + rightEye.x) / 2f, min(leftEye.y, rightEye.y) - 0.22f * faceH)
        } else {
            android.graphics.PointF((bbox.left + bbox.right) / 2f, bbox.top + 0.2f * faceH)
        }

        val patchSize = (0.35f * faceW).roundToInt().coerceAtLeast(48)

        fun cropSafeFromCenter(cx: Float, cy: Float, s: Int): Bitmap? {
            val left = (cx - s / 2f).toInt()
            val top = (cy - s / 2f).toInt()
            val l = left.coerceAtLeast(0)
            val t = top.coerceAtLeast(0)
            val r = (left + s).coerceAtMost(fullBmp.width)
            val b = (top + s).coerceAtMost(fullBmp.height)
            val w = r - l; val h = b - t
            if (w <= 10 || h <= 10) return null
            return Bitmap.createBitmap(fullBmp, l, t, w, h)
        }

        val centers = listOf(
            leftCheekCenter to "left_cheek",
            rightCheekCenter to "right_cheek",
            chinCenter to "chin",
            foreheadCenter to "forehead"
        )

        val valid = mutableListOf<PerPatch>()
        val agg = FloatArray(labels.size) { 0f }
        var count = 0

        for ((center, tag) in centers) {
            val pBmp = cropSafeFromCenter(center.x, center.y, patchSize) ?: continue
            val resized = Bitmap.createScaledBitmap(pBmp, inputW, inputH, true)

            val inputBuffer: ByteBuffer = if (inputDataType == DataType.UINT8 || inputDataType == DataType.INT8) {
                ByteBuffer.allocateDirect(inputW * inputH * inputChannels).order(ByteOrder.nativeOrder())
            } else {
                ByteBuffer.allocateDirect(4 * inputW * inputH * inputChannels).order(ByteOrder.nativeOrder())
            }
            inputBuffer.rewind()

            if (inputDataType == DataType.UINT8 || inputDataType == DataType.INT8) {
                for (y in 0 until inputH) {
                    for (x in 0 until inputW) {
                        val p = resized.getPixel(x, y)
                        val r = ((p shr 16) and 0xFF) / 255.0f
                        val g = ((p shr 8) and 0xFF) / 255.0f
                        val b = (p and 0xFF) / 255.0f
                        val rr = ((r / inputScale).roundToInt() + inputZeroPoint).coerceIn(0,255)
                        val gg = ((g / inputScale).roundToInt() + inputZeroPoint).coerceIn(0,255)
                        val bb = ((b / inputScale).roundToInt() + inputZeroPoint).coerceIn(0,255)
                        inputBuffer.put(rr.toByte()); inputBuffer.put(gg.toByte()); inputBuffer.put(bb.toByte())
                    }
                }
            } else {
                for (y in 0 until inputH) {
                    for (x in 0 until inputW) {
                        val p = resized.getPixel(x, y)
                        val r = ((p shr 16) and 0xFF) / 255.0f
                        val g = ((p shr 8) and 0xFF) / 255.0f
                        val b = (p and 0xFF) / 255.0f
                        inputBuffer.putFloat(r); inputBuffer.putFloat(g); inputBuffer.putFloat(b)
                    }
                }
            }
            inputBuffer.rewind()

            val raw = runModelGetOutputAsFloatArray(inputBuffer)
            val maxv = raw.maxOrNull() ?: 0f
            val exp = raw.map { Math.exp((it - maxv).toDouble()).toFloat() }
            val sum = exp.sum()
            val probs = if (sum > 0f) exp.map { it / sum } else exp.map { 0f }

            for (i in probs.indices) agg[i] += probs[i]
            count++

            val topIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
            val topLabel = labels.getOrNull(topIdx) ?: "unknown"
            val conf = probs[topIdx] * 100.0
            valid.add(PerPatch(resized, topLabel, conf))
        }

        if (count > 0) {
            for (i in agg.indices) agg[i] = agg[i] / count.toFloat()
        }

        return Pair(valid, agg)
    }

    data class PerPatch(val bmp: Bitmap, val label: String, val confidence: Double)

    @RequiresApi(Build.VERSION_CODES.O)
    private fun runSkinOnBitmap(fullBmp: Bitmap): String {
        val img = InputImage.fromBitmap(fullBmp, 0)
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        val detector = FaceDetection.getClient(opts)
        val faces = com.google.android.gms.tasks.Tasks.await(detector.process(img))
        if (faces.isEmpty()) return "No face detected."
        val face = faces[0]
        val bbox = face.boundingBox
        val pad = (0.15 * max(bbox.width(), bbox.height())).toInt()
        val left = max(0, bbox.left - pad)
        val top = max(0, bbox.top - pad)
        val right = min(fullBmp.width - 1, bbox.right + pad)
        val bottom = min(fullBmp.height - 1, bbox.bottom + pad)
        val faceRect = android.graphics.Rect(left, top, right, bottom)
        val faceBmp = Bitmap.createBitmap(fullBmp, faceRect.left, faceRect.top, faceRect.width(), faceRect.height())

        val heatAcc = Array(faceBmp.height) { FloatArray(faceBmp.width) }
        val heatCount = Array(faceBmp.height) { IntArray(faceBmp.width) }

        val winH = inputH
        val winW = inputW
        val stride = (winW * 0.5).roundToInt().coerceAtLeast(1)

        for (y in 0 until max(1, faceBmp.height - winH + 1) step stride) {
            for (x in 0 until max(1, faceBmp.width - winW + 1) step stride) {
                val w = if (x + winW <= faceBmp.width) winW else (faceBmp.width - x)
                val h = if (y + winH <= faceBmp.height) winH else (faceBmp.height - y)
                val patch = Bitmap.createBitmap(faceBmp, x, y, w, h)
                val resized = Bitmap.createScaledBitmap(patch, winW, winH, true)

                val inputBuffer = if (inputDataType == DataType.UINT8 || inputDataType == DataType.INT8) {
                    ByteBuffer.allocateDirect(winW * winH * inputChannels).order(ByteOrder.nativeOrder())
                } else {
                    ByteBuffer.allocateDirect(4 * winW * winH * inputChannels).order(ByteOrder.nativeOrder())
                }
                inputBuffer.rewind()

                for (py in 0 until winH) {
                    for (px in 0 until winW) {
                        val p = resized.getPixel(px, py)
                        val r = (p shr 16 and 0xFF)
                        val g = (p shr 8 and 0xFF)
                        val b = (p and 0xFF)
                        if (inputDataType == DataType.UINT8 || inputDataType == DataType.INT8) {
                            val rf = r / 255f
                            val gf = g / 255f
                            val bf = b / 255f
                            val qr = ((rf / inputScale).roundToInt() + inputZeroPoint).coerceIn(0, 255)
                            val qg = ((gf / inputScale).roundToInt() + inputZeroPoint).coerceIn(0, 255)
                            val qb = ((bf / inputScale).roundToInt() + inputZeroPoint).coerceIn(0, 255)
                            inputBuffer.put(qr.toByte())
                            inputBuffer.put(qg.toByte())
                            inputBuffer.put(qb.toByte())
                        } else {
                            inputBuffer.putFloat(r / 255f)
                            inputBuffer.putFloat(g / 255f)
                            inputBuffer.putFloat(b / 255f)
                        }
                    }
                }
                inputBuffer.rewind()

                val raw = runModelGetOutputAsFloatArray(inputBuffer)

                val maxv = raw.maxOrNull() ?: 0f
                val exp = raw.map { Math.exp((it - maxv).toDouble()).toFloat() }
                val sum = exp.sum()
                val soft = if (sum > 0f) exp.map { it / sum } else exp.map { 0f }

                var maxVal = soft[0]
                for (i in 1 until soft.size) if (soft[i] > maxVal) maxVal = soft[i]

                val pxLeft = x; val pxTop = y
                val pxRight = min(faceBmp.width - 1, x + winW - 1)
                val pxBottom = min(faceBmp.height - 1, y + winH - 1)
                for (yy in pxTop..pxBottom) {
                    val row = heatAcc[yy]
                    val cntRow = heatCount[yy]
                    for (xx in pxLeft..pxRight) {
                        row[xx] += maxVal
                        cntRow[xx] += 1
                    }
                }
            }
        }

        val heatBmp = Bitmap.createBitmap(faceBmp.width, faceBmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(heatBmp)
        val paint = Paint().apply { style = Paint.Style.FILL }

        var globalMax = 0f
        for (yy in 0 until faceBmp.height) for (xx in 0 until faceBmp.width) {
            val c = heatCount[yy][xx]
            if (c > 0) {
                val v = heatAcc[yy][xx] / c
                if (v > globalMax) globalMax = v
            }
        }

        for (yy in 0 until faceBmp.height) {
            for (xx in 0 until faceBmp.width) {
                val c = heatCount[yy][xx]
                val v = if (c > 0) (heatAcc[yy][xx] / c) / (globalMax.coerceAtLeast(1f)) else 0f
                val alpha = (v * 200).toInt().coerceIn(0, 200)
                val color = Color.argb(alpha, (255 * v).toInt(), (180 * (1 - v)).toInt(), 0)
                paint.color = color
                canvas.drawPoint(xx.toFloat(), yy.toFloat(), paint)
            }
        }

        val dispW = ivPreview.width.takeIf { it > 0 } ?: fullBmp.width
        val dispH = ivPreview.height.takeIf { it > 0 } ?: fullBmp.height
        val displayed = Bitmap.createScaledBitmap(fullBmp, dispW, dispH, true)
        val scaleX = displayed.width.toFloat() / fullBmp.width
        val scaleY = displayed.height.toFloat() / fullBmp.height

        val overlayBmp = Bitmap.createBitmap(displayed.width, displayed.height, Bitmap.Config.ARGB_8888)
        val overlayCanvas = Canvas(overlayBmp)
        overlayCanvas.drawBitmap(displayed, 0f, 0f, null)

        val heatScaled = Bitmap.createScaledBitmap(heatBmp, (faceRect.width() * scaleX).roundToInt(), (faceRect.height() * scaleY).roundToInt(), true)
        val leftOnDisp = (faceRect.left * scaleX).roundToInt()
        val topOnDisp = (faceRect.top * scaleY).roundToInt()
        val heatPaint = Paint().apply { alpha = 200 }
        overlayCanvas.drawBitmap(heatScaled, leftOnDisp.toFloat(), topOnDisp.toFloat(), heatPaint)

        val overlayUri = saveBitmapToCache(overlayBmp, "skin_heat_${System.currentTimeMillis()}.jpg")
        faceHeatmapUriStr = overlayUri?.toString()

        runOnUiThread {
            ivHeatmap.setImageBitmap(overlayBmp)
            ivHeatmap.visibility = View.VISIBLE
        }

        val labelCounts = mutableMapOf<String, Int>()
        val sampleStride = max(1, inputW / 2)
        for (yy in 0 until faceBmp.height step sampleStride) {
            for (xx in 0 until faceBmp.width step sampleStride) {
                val w = min(inputW, faceBmp.width - xx)
                val h = min(inputH, faceBmp.height - yy)
                val patch = Bitmap.createBitmap(faceBmp, xx, yy, w, h)
                val resized = Bitmap.createScaledBitmap(patch, inputW, inputH, true)

                val inputBuffer = if (inputDataType == DataType.UINT8 || inputDataType == DataType.INT8) {
                    ByteBuffer.allocateDirect(inputW * inputH * inputChannels).order(ByteOrder.nativeOrder())
                } else {
                    ByteBuffer.allocateDirect(4 * inputW * inputH * inputChannels).order(ByteOrder.nativeOrder())
                }
                inputBuffer.rewind()
                for (py in 0 until inputH) for (px in 0 until inputW) {
                    val p = resized.getPixel(px, py)
                    val r = (p shr 16 and 0xFF)
                    val g = (p shr 8 and 0xFF)
                    val b = (p and 0xFF)
                    if (inputDataType == DataType.UINT8 || inputDataType == DataType.INT8) {
                        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
                        val qr = ((rf / inputScale).roundToInt() + inputZeroPoint).coerceIn(0, 255)
                        val qg = ((gf / inputScale).roundToInt() + inputZeroPoint).coerceIn(0, 255)
                        val qb = ((bf / inputScale).roundToInt() + inputZeroPoint).coerceIn(0, 255)
                        inputBuffer.put(qr.toByte()); inputBuffer.put(qg.toByte()); inputBuffer.put(qb.toByte())
                    } else {
                        inputBuffer.putFloat(r / 255f); inputBuffer.putFloat(g / 255f); inputBuffer.putFloat(b / 255f)
                    }
                }
                inputBuffer.rewind()

                val raw = runModelGetOutputAsFloatArray(inputBuffer)

                val maxv = raw.maxOrNull() ?: 0f
                val exp = raw.map { Math.exp((it - maxv).toDouble()).toFloat() }
                val sum = exp.sum()
                val probs = if (sum > 0f) exp.map { it / sum } else exp.map { 0f }

                var maxi = 0; var mv = probs[0]
                for (i in probs.indices) if (probs[i] > mv) { mv = probs[i]; maxi = i }
                val lbl = labels.getOrNull(maxi) ?: "unknown"
                labelCounts[lbl] = (labelCounts[lbl] ?: 0) + 1
            }
        }

        val topEntry = labelCounts.maxByOrNull { it.value }
        val topLabel = topEntry?.key ?: "normal"
        val summaryText = topLabel.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }

        val recKey = topLabel.replace("\\s".toRegex(), "").lowercase()
        val rec = RecommendationProvider.getSkinRecommendation(recKey)

        runOnUiThread {
            tvRecSummary.text = rec?.summary ?: "Follow general skin care steps."
            llTips.removeAllViews()
            rec?.tips?.forEach { tip ->
                val tv = layoutInflater.inflate(android.R.layout.simple_list_item_1, llTips, false) as TextView
                tv.text = "\u2022  $tip"
                tv.setTextColor(resources.getColor(R.color.black))
                tv.setTextSize(14f)
                llTips.addView(tv)
            }
        }

        return summaryText
    }

    private fun runModelGetOutputAsFloatArray(inputBuffer: ByteBuffer): FloatArray {
        val outTensor = interpreter.getOutputTensor(0)
        val outShape = outTensor.shape()
        val num = if (outShape.isNotEmpty()) outShape.last() else labels.size
        val outType = outTensor.dataType()

        if (outType == DataType.FLOAT32) {
            val out = Array(1) { FloatArray(num) }
            interpreter.run(inputBuffer, out)
            return out[0]
        }

        if (outType == DataType.UINT8 || outType == DataType.INT8) {
            val outBuf = ByteBuffer.allocateDirect(num).order(ByteOrder.nativeOrder())
            outBuf.rewind()
            interpreter.run(inputBuffer, outBuf)
            outBuf.rewind()

            val q = try { outTensor.quantizationParams() } catch (_: Exception) { return FloatArray(num) { 0f } }
            val scale = q.scale
            val zp = q.zeroPoint

            val res = FloatArray(num)
            for (i in 0 until num) {
                val b = outBuf.get()
                val stored: Int = if (outType == DataType.UINT8) (b.toInt() and 0xFF) else b.toInt()
                res[i] = (stored - zp) * scale
            }
            return res
        }

        throw IllegalArgumentException("Unsupported output tensor data type: $outType")
    }

    private fun loadLabels(fileName: String): List<String> {
        return try {
            val input = assets.open(fileName)
            val br = BufferedReader(InputStreamReader(input))
            val out = br.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            br.close(); out
        } catch (e: Exception) { emptyList() }
    }

    private fun loadModel(modelName: String): Interpreter {
        val afd = assets.openFd(modelName)
        val stream = FileInputStream(afd.fileDescriptor)
        val map: MappedByteBuffer = stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        return Interpreter(map)
    }

    inner class PatchAdapter(private val items: List<PatchResult>) : RecyclerView.Adapter<PatchAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.ivPatch)
            val tvLbl: TextView = v.findViewById(R.id.tvPatchLabel)
            val card: MaterialCardView = v.findViewById(R.id.cardPatch)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_patch, parent, false)
            return VH(v)
        }
        @SuppressLint("MissingInflatedId")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val ite = items[position]
            holder.iv.setImageBitmap(ite.bmp)
            holder.tvLbl.text = "${ite.label} (${String.format("%.1f", ite.confidence)}%)"
            holder.card.setOnClickListener {
                val d = android.app.Dialog(this@SkinReportActivity)
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

    private fun decodeBitmap(u: Uri): Bitmap? {
        try {
            val firstStream = contentResolver.openInputStream(u) ?: return null
            val options = android.graphics.BitmapFactory.Options().apply {
                inMutable = true; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            val decoded = android.graphics.BitmapFactory.decodeStream(firstStream, null, options)
            firstStream.close()
            if (decoded == null) return null
            val exifStream = contentResolver.openInputStream(u)
            val exif = androidx.exifinterface.media.ExifInterface(exifStream!!)
            val orientation = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
            exifStream.close()
            val matrix = android.graphics.Matrix()
            when (orientation) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }
            if (matrix.isIdentity) return decoded
            val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (oriented != decoded) decoded.recycle()
            return oriented
        } catch (e: Exception) { e.printStackTrace(); return null }
    }

    private fun uploadImagesAndSaveReport(
        uid: String,
        previewUriStr: String?,
        heatmapUriStr: String?,
        type: String,
        summary: String,
        topLabel: String,
        onComplete: (Boolean) -> Unit
    ) {
        val previewUri = previewUriStr?.let { Uri.parse(it) }
        val heatUri = heatmapUriStr?.let { Uri.parse(it) }

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
            uploadOne(heatUri, "heat_${System.currentTimeMillis()}") { hUrl ->
                uploaded["heat"] = hUrl ?: ""
                val report = hashMapOf<String, Any>(
                    "type" to type,
                    "summary" to summary,
                    "topLabel" to topLabel,
                    "confidence" to 0.0,
                    "previewUrl" to (uploaded["preview"] ?: ""),
                    "heatUrl" to (uploaded["heat"] ?: ""),
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
