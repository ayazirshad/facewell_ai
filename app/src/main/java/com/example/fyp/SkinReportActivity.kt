package com.example.fyp

import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
//import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
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
    }

    private lateinit var ivPreview: ImageView
    private lateinit var ivHeatmap: ImageView
    private lateinit var tvSummaryText: TextView
    private lateinit var btnSave: MaterialButton
    private lateinit var btnVisit: MaterialButton
    private lateinit var btnGoHome: MaterialButton
    private lateinit var tvRecSummary: TextView
    private lateinit var llTips: LinearLayout
    private lateinit var btnBack: ImageButton

    // TF / model info
    private lateinit var interpreter: Interpreter
    private var labels: List<String> = emptyList()
    private var inputH = 128
    private var inputW = 128
    private var inputChannels = 3
    private var inputDataType = DataType.FLOAT32
    private var inputScale = 1.0f
    private var inputZeroPoint = 0

    // firebase
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }

    // cache URIs to save later
    private var previewUriStr: String? = null
    private var faceHeatmapUriStr: String? = null

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_skin_report)

        ivPreview = findViewById(R.id.ivPreview)
        ivHeatmap = findViewById(R.id.ivHeatmap)
        tvSummaryText = findViewById(R.id.tvSummaryText)
        btnSave = findViewById(R.id.btnSaveReport)
        btnVisit = findViewById(R.id.btnVisitClinic)
        btnGoHome = findViewById(R.id.btnGoHome)
        tvRecSummary = findViewById(R.id.tvRecSummary)
        llTips = findViewById(R.id.llTips)
        btnBack = findViewById(R.id.btnBack)

        btnBack.setOnClickListener { finish() }

        // Make sure skin recommendations loaded
        RecommendationProvider.loadFromAssets(this, "skin_recommendations.json")

        // load labels & model
        labels = loadLabels(LABELS_FILE)
        try {
            interpreter = loadModel(MODEL_NAME)
            // read input tensor info
            val t = interpreter.getInputTensor(0)
            val shape = t.shape() // typically [1,H,W,3]
            if (shape.size >= 3) { inputH = shape[1]; inputW = shape[2] }
            inputDataType = t.dataType()
            inputChannels = if (shape.size >= 4) shape[3] else 3
            // quant params
            try {
                val q = t.quantizationParams()
                inputScale = q.scale
                inputZeroPoint = q.zeroPoint
            } catch (_: Exception) {
                // no quant params -> defaults remain
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load model", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        // load preview image
        val imageUriStr = intent.getStringExtra(EXTRA_IMAGE_URI) ?: intent.getStringExtra("extra_image_uri")
        if (imageUriStr.isNullOrEmpty()) { finish(); return }
        val imageUri = Uri.parse(imageUriStr)
        previewUriStr = imageUri.toString()
        val bmp = decodeBitmap(imageUri) ?: run {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show(); finish(); return
        }
        ivPreview.setImageBitmap(bmp)

        // run analysis in background thread
        Thread {
            try {
                val summary = runSkinOnBitmap(bmp)
                runOnUiThread {
                    tvSummaryText.text = summary
                    ivHeatmap.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this, "Skin analysis failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()

        // Save report
        btnSave.setOnClickListener {
            val uid = auth.currentUser?.uid
            if (uid == null) {
                Toast.makeText(this, "Not authenticated. Please login.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnSave.isEnabled = false
            btnSave.text = "Saving..."
            // upload preview + heatmap images (if heatmap generated) then save Firestore doc
            uploadImagesAndSaveReport(uid, previewUriStr, faceHeatmapUriStr, type = "skin",
                summary = tvSummaryText.text.toString(), topLabel = tvRecSummary.text.toString()) { success ->
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

    /** Runs face detection -> sliding window -> creates heatmap and returns short summary */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun runSkinOnBitmap(fullBmp: Bitmap): String {
        // detect face
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

        // accumulators
        val heatAcc = Array(faceBmp.height) { FloatArray(faceBmp.width) }
        val heatCount = Array(faceBmp.height) { IntArray(faceBmp.width) }

        val winH = inputH
        val winW = inputW
        val stride = (winW * 0.5).roundToInt().coerceAtLeast(1)

        // Iterate sliding windows
        for (y in 0 until max(1, faceBmp.height - winH + 1) step stride) {
            for (x in 0 until max(1, faceBmp.width - winW + 1) step stride) {
                val w = if (x + winW <= faceBmp.width) winW else (faceBmp.width - x)
                val h = if (y + winH <= faceBmp.height) winH else (faceBmp.height - y)
                val patch = Bitmap.createBitmap(faceBmp, x, y, w, h)
                val resized = Bitmap.createScaledBitmap(patch, winW, winH, true)

                // prepare input buffer with correct dtype/quant
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
                            // quantized: convert normalized float (0..1) -> quantized integer using scale & zeroPoint
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

                // run inference (output as float array)
                val output = Array(1) { FloatArray(labels.size) }
                interpreter.run(inputBuffer, output)
                val probs = output[0]

                // use max probability as patch score
                var maxVal = probs[0]
                for (i in 1 until probs.size) if (probs[i] > maxVal) maxVal = probs[i]

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

        // build heatmap
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

        // overlay heatmap onto displayed preview
        // ensure ivPreview has measured dimensions; if not, scale against fullBmp size
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

        // save overlay image to cache to allow upload later
        val overlayUri = saveBitmapToCache(overlayBmp, "skin_heat_${System.currentTimeMillis()}.jpg")
        faceHeatmapUriStr = overlayUri?.toString()

        runOnUiThread {
            ivHeatmap.setImageBitmap(overlayBmp)
            ivHeatmap.visibility = View.VISIBLE
        }

        // quick coarse sampling for label counts
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
                val output = Array(1) { FloatArray(labels.size) }
                interpreter.run(inputBuffer, output)
                val probs = output[0]
                var maxi = 0; var mv = probs[0]
                for (i in probs.indices) if (probs[i] > mv) { mv = probs[i]; maxi = i }
                val lbl = labels.getOrNull(maxi) ?: "unknown"
                labelCounts[lbl] = (labelCounts[lbl] ?: 0) + 1
            }
        }

        val sorted = labelCounts.entries.sortedByDescending { it.value }.take(3)
        val summaryText = if (sorted.isEmpty()) "No strong findings." else sorted.joinToString(" • ") { "${it.key} (${it.value})" }

        val topLabel = sorted.firstOrNull()?.key ?: "normal"
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

    // -- Load labels & model utilities
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

    // -- save bitmap to temp cache and return Uri string
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

    // decodeBitmap with EXIF handling (same approach used before)
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

    // --- identical upload function to save report into Firestore and upload images to Storage
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
                // build report
                val report = hashMapOf<String, Any>(
                    "type" to type,
                    "summary" to summary,
                    "topLabel" to topLabel,
                    "confidence" to 0.0, // you may compute/store an actual confidence if available
                    "previewUrl" to (uploaded["preview"] ?: ""),
                    "heatUrl" to (uploaded["heat"] ?: ""),
                    "createdAt" to System.currentTimeMillis()
                )
                val userDoc = db.collection("users").document(uid)
                userDoc.update("reports", FieldValue.arrayUnion(report as Any))
                    .addOnSuccessListener { onComplete(true) }
                    .addOnFailureListener {
                        // if update fails, create field
                        val payload = hashMapOf("reports" to listOf(report))
                        userDoc.set(payload, SetOptions.merge())
                            .addOnSuccessListener { onComplete(true) }
                            .addOnFailureListener { e -> e.printStackTrace(); onComplete(false) }
                    }
            }
        }
    }
}
