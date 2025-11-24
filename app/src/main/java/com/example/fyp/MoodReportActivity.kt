package com.example.fyp

import android.content.Intent
import android.graphics.Bitmap
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
import com.google.firebase.storage.FirebaseStorage
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.BufferedReader
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MoodReportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val MODEL_NAME = "mood_detection_model.tflite"
    }

    private lateinit var ivPreview: ImageView
    private lateinit var tvSummaryText: TextView
    private lateinit var btnSave: MaterialButton
    private lateinit var btnVisit: MaterialButton
    private lateinit var btnGoHome: MaterialButton
    private lateinit var btnBack: ImageButton
    private lateinit var tvReportTitle: TextView

    private lateinit var interpreter: Interpreter
    private var inputH = 48
    private var inputW = 48
    private var inputChannels = 1
    private var inputDataType = DataType.FLOAT32
    private var inputScale = 1.0f
    private var inputZeroPoint = 0

    private val labels = listOf("Angry","Disgusted","Fear","Happy","Sad","Surprise","Neutral")

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }

    private var previewUriStr: String? = null

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mood_report)

        ivPreview = findViewById(R.id.ivPreview)
        tvSummaryText = findViewById(R.id.tvSummaryText)
        btnSave = findViewById(R.id.btnSaveReport)
        btnVisit = findViewById(R.id.btnVisitClinic)
        btnGoHome = findViewById(R.id.btnGoHome)
        btnBack = findViewById(R.id.btnBack)
        tvReportTitle = findViewById(R.id.tvReportTitle)

        tvReportTitle.text = "Mood Report"

        btnBack.setOnClickListener { finish() }

        try {
            interpreter = loadModel(MODEL_NAME)
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

        val imageUriStr = intent.getStringExtra(EXTRA_IMAGE_URI) ?: intent.getStringExtra("extra_image_uri")
        if (imageUriStr.isNullOrEmpty()) { finish(); return }
        val imageUri = Uri.parse(imageUriStr)
        previewUriStr = imageUri.toString()
        val bmp = decodeBitmap(imageUri) ?: run {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show(); finish(); return
        }
        ivPreview.setImageBitmap(bmp)

        Thread {
            try {
                val resultText = runMoodOnBitmap(bmp)
                runOnUiThread {
                    tvSummaryText.text = resultText
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this, "Mood analysis failed: ${e.message}", Toast.LENGTH_LONG).show() }
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
            val topLabel = extractTopLabel(summary)
            val confidence = extractConfidence(summary)
            uploadImagesAndSaveReport(uid, previewUriStr, "mood", summary, topLabel, confidence) { success ->
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

    private fun extractTopLabel(summary: String): String {
        val parts = summary.split("\n")
        if (parts.isEmpty()) return "unknown"
        val line = parts[0]
        val idx = line.indexOf('(')
        return if (idx > 0) line.substring(0, idx).trim() else line.trim()
    }

    private fun extractConfidence(summary: String): Double {
        val parts = summary.split("\n")
        if (parts.isEmpty()) return 0.0
        val line = parts[0]
        val idx1 = line.indexOf('(')
        val idx2 = line.indexOf('%')
        return if (idx1 > 0 && idx2 > idx1) {
            val num = line.substring(idx1 + 1, idx2).trim()
            try { num.toDouble() } catch (_: Exception) { 0.0 }
        } else 0.0
    }

    private fun runMoodOnBitmap(fullBmp: Bitmap): String {
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

        val resized = Bitmap.createScaledBitmap(faceBmp, inputW, inputH, true)
        val inputBuffer = if (inputDataType == DataType.UINT8 || inputDataType == DataType.INT8) {
            ByteBuffer.allocateDirect(inputW * inputH * inputChannels).order(ByteOrder.nativeOrder())
        } else {
            ByteBuffer.allocateDirect(4 * inputW * inputH * inputChannels).order(ByteOrder.nativeOrder())
        }
        inputBuffer.rewind()
        for (y in 0 until inputH) {
            for (x in 0 until inputW) {
                val p = resized.getPixel(x, y)
                val r = (p shr 16 and 0xFF)
                val g = (p shr 8 and 0xFF)
                val b = (p and 0xFF)
                val gray = ((0.299f * r) + (0.587f * g) + (0.114f * b)).roundToInt().coerceIn(0, 255)
                if (inputDataType == DataType.UINT8 || inputDataType == DataType.INT8) {
                    inputBuffer.put((gray and 0xFF).toByte())
                } else {
                    inputBuffer.putFloat(gray.toFloat())
                }
            }
        }
        inputBuffer.rewind()

        val output = Array(1) { FloatArray(labels.size) }
        interpreter.run(inputBuffer, output)
        val raw = output[0]
        val maxv = raw.maxOrNull() ?: 0f
        val exp = raw.map { Math.exp((it - maxv).toDouble()) }.map { it.toFloat() }
        val sum = exp.sum()
        val probs = exp.map { it / sum }
        val topIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
        val topLabel = labels.getOrNull(topIdx) ?: "unknown"
        val conf = (probs[topIdx] * 100.0)
        val sb = StringBuilder()
        sb.append("${topLabel} (${String.format("%.1f", conf)}%)\n")
        sb.append("Top-3:\n")
        val sorted = probs.mapIndexed { idx, v -> idx to v }.sortedByDescending { it.second }.take(3)
        for ((idx, v) in sorted) {
            val lab = labels.getOrNull(idx) ?: "IDX_$idx"
            sb.append("${lab}: ${String.format("%.3f", v)}\n")
        }

        return sb.toString()
    }

    private fun loadModel(modelName: String): Interpreter {
        val afd = assets.openFd(modelName)
        val stream = FileInputStream(afd.fileDescriptor)
        val map: MappedByteBuffer = stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        return Interpreter(map)
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
        type: String,
        summary: String,
        topLabel: String,
        confidence: Double,
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
                "confidence" to confidence,
                "previewUrl" to (uploaded["preview"] ?: ""),
                "createdAt" to System.currentTimeMillis()
            )
            val userDoc = db.collection("users").document(uid)
            userDoc.update("reports", FieldValue.arrayUnion(report as Any))
                .addOnSuccessListener { onComplete(true) }
                .addOnFailureListener {
                    val payload = hashMapOf("reports" to listOf(report))
                    userDoc.set(payload, com.google.firebase.firestore.SetOptions.merge())
                        .addOnSuccessListener { onComplete(true) }
                        .addOnFailureListener { e -> e.printStackTrace(); onComplete(false) }
                }
        }
    }
}
