package com.example.fyp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import java.util.Locale

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

    private lateinit var interpreter: Interpreter

    // firebase
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }

    private var previewUriStr: String? = null
    private var leftCropUriStr: String? = null
    private var rightCropUriStr: String? = null

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

        btnBack.setOnClickListener { finish() }

        val tvTitle = findViewById<TextView>(R.id.tvReportTitle)
        tvTitle.text = "Eye Report"

        try {
            interpreter = loadModel()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load model", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        RecommendationProvider.loadFromAssets(this, "eye_recommendations.json")

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
                val (leftBmp, rightBmp) = detectAndCropEyes(bmp)
                if (leftBmp == null || rightBmp == null) {
                    runOnUiThread { Toast.makeText(this, "Eyes not clearly detected", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }

                val (lPair, rPair, overall) = runEyeModel(leftBmp, rightBmp)

                val leftUri = saveBitmapToCache(leftBmp, "left_eye_${System.currentTimeMillis()}.jpg")
                val rightUri = saveBitmapToCache(rightBmp, "right_eye_${System.currentTimeMillis()}.jpg")
                leftCropUriStr = leftUri?.toString(); rightCropUriStr = rightUri?.toString()

                runOnUiThread {
                    ivLeftCrop.setImageBitmap(leftBmp)
                    ivRightCrop.setImageBitmap(rightBmp)
                    tvLeftLabel.text = lPair.first
                    tvRightLabel.text = rPair.first
                    tvAccuracy.text = "Accuracy Level: ${(overall * 100).toInt()}%"
                    tvSummaryText.text = "Left: ${lPair.first} • Right: ${rPair.first}"

                    val topLabelKey = if (lPair.second >= rPair.second) lPair.first else rPair.first
                    val rec = RecommendationProvider.getEyeRecommendation(topLabelKey.replace("\\s".toRegex(), "").lowercase(Locale.getDefault()))
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
                    } else {
                        tvRecSummary.text = "No recommendation available."
                        llTips.removeAllViews()
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this, "Eye analysis failed.", Toast.LENGTH_SHORT).show() }
            }
        }.start()

        // Save report
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

    // detect & crop eyes
    private fun detectAndCropEyes(bmp: Bitmap): Pair<Bitmap?, Bitmap?> {
        val img = InputImage.fromBitmap(bmp, 0)
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
        val detector = FaceDetection.getClient(opts)
        val faces = com.google.android.gms.tasks.Tasks.await(detector.process(img))
        if (faces.isEmpty()) return Pair(null, null)
        val face = faces[0]
        val leftLand = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)?.position
        val rightLand = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)?.position
        if (leftLand == null || rightLand == null) return Pair(null, null)

        val box = face.boundingBox
        val w = (box.width() * 0.35).toInt().coerceAtLeast(1)
        val h = (box.height() * 0.35).toInt().coerceAtLeast(1)

        val leftRect = Rect(
            (leftLand.x - w / 2).toInt().coerceAtLeast(0),
            (leftLand.y - h / 2).toInt().coerceAtLeast(0),
            (leftLand.x + w / 2).toInt().coerceAtMost(bmp.width),
            (leftLand.y + h / 2).toInt().coerceAtMost(bmp.height)
        )
        val rightRect = Rect(
            (rightLand.x - w / 2).toInt().coerceAtLeast(0),
            (rightLand.y - h / 2).toInt().coerceAtLeast(0),
            (rightLand.x + w / 2).toInt().coerceAtMost(bmp.width),
            (rightLand.y + h / 2).toInt().coerceAtMost(bmp.height)
        )

        val leftW = max(1, leftRect.width()); val leftH = max(1, leftRect.height())
        val rightW = max(1, rightRect.width()); val rightH = max(1, rightRect.height())

        val leftBmp = Bitmap.createBitmap(bmp, leftRect.left, leftRect.top, leftW, leftH)
        val rightBmp = Bitmap.createBitmap(bmp, rightRect.left, rightRect.top, rightW, rightH)
        return Pair(leftBmp, rightBmp)
    }

    // run eye model (same preprocessing as your original float model)
    private fun runEyeModel(left: Bitmap, right: Bitmap): Triple<Pair<String, Float>, Pair<String, Float>, Float> {
        fun prepareInput(b: Bitmap): Array<Array<Array<FloatArray>>> {
            val resized = Bitmap.createScaledBitmap(b, 224, 224, true)
            val input = Array(1) { Array(224) { Array(224) { FloatArray(3) } } }
            for (y in 0 until 224) for (x in 0 until 224) {
                val p = resized.getPixel(x, y)
                input[0][y][x][0] = ((p shr 16 and 0xFF) / 255f)
                input[0][y][x][1] = ((p shr 8 and 0xFF) / 255f)
                input[0][y][x][2] = ((p and 0xFF) / 255f)
            }
            return input
        }

        val leftOut = Array(1) { FloatArray(5) }
        val rightOut = Array(1) { FloatArray(5) }
        interpreter.run(prepareInput(left), leftOut)
        interpreter.run(prepareInput(right), rightOut)

        val labels = listOf("blepharitis", "cataracts", "conjunctivitis", "darkcircles", "normal")

        fun top(out: FloatArray): Pair<String, Float> {
            var idx = 0
            var mv = out[0]
            for (i in out.indices) if (out[i] > mv) { mv = out[i]; idx = i }
            return labels.getOrNull(idx)?.let { it to mv } ?: ("unknown" to mv)
        }

        val l = top(leftOut[0]); val r = top(rightOut[0])
        val overall = (l.second + r.second) / 2f
        return Triple(l, r, overall)
    }

    private fun loadModel(): Interpreter {
        val afd = assets.openFd(MODEL_NAME)
        val stream = java.io.FileInputStream(afd.fileDescriptor)
        val map: MappedByteBuffer = stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        return Interpreter(map)
    }

    private fun saveBitmapToCache(bmp: Bitmap, name: String): Uri? {
        return try {
            val f = kotlin.io.path.createTempFile(prefix = name, suffix = ".jpg").toFile()
            FileOutputStream(f).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 90, out) }
            Uri.fromFile(f)
        } catch (e: Exception) { e.printStackTrace(); null }
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

    // -- upload & save function for eye (uploads preview + left + right images)
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
}
