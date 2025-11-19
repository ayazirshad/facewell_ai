package com.example.fyp

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class ConfirmPhotoActivity : AppCompatActivity() {

    companion object { const val EXTRA_IMAGE_URI = "extra_image_uri" }

    private lateinit var uri: Uri
    private lateinit var image: ShapeableImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_confirm_photo)

        image = findViewById(R.id.image)

        val u = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (u.isNullOrEmpty()) { finish(); return }
        uri = Uri.parse(u)

        // show preview as before
        image.post { loadAndCenterFace() }

        findViewById<View>(R.id.btnRetake).setOnClickListener { showDiscardDialog() }
        findViewById<View>(R.id.btnConfirm).setOnClickListener { showScanDialog() }
    }

    private fun showDiscardDialog() {
        val d = Dialog(this)
        d.setContentView(R.layout.dialog_discard_scan)
        d.findViewById<View>(R.id.btnCancel).setOnClickListener { d.dismiss() }
        d.findViewById<View>(R.id.btnDiscard).setOnClickListener {
            d.dismiss(); finish()
        }
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        d.show()
    }

    /** Show scan options dialog (eyes/skin/mood/full). Only eyes is fully processed now.
     *  Skin/Mood will open ReportActivity with "Working on it" message.
     */
    private fun showScanDialog() {
        val d = Dialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_scan_options, null)
        d.setContentView(view)
        d.setCancelable(true)
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnEyes = view.findViewById<MaterialCardView>(R.id.btnScanEyes)
        val btnSkin = view.findViewById<MaterialCardView>(R.id.btnScanSkin)
        val btnMood = view.findViewById<MaterialCardView>(R.id.btnScanMood)
        val btnFull = view.findViewById<MaterialCardView>(R.id.btnScanFull)
        val btnCancel = view.findViewById<MaterialCardView>(R.id.btnCancel)

        btnEyes.setOnClickListener {
            d.dismiss()
            // Run only eyes model and open report
            runEyesAndOpenReport()
        }

        btnSkin.setOnClickListener {
            d.dismiss()
            // Open ReportActivity with placeholder for skin
            val i = Intent(this, ReportActivity::class.java)
            i.putExtra(ReportActivity.EXTRA_TYPE, "skin")
            i.putExtra(ReportActivity.EXTRA_IMAGE_URI, uri.toString())
            i.putExtra(ReportActivity.EXTRA_SUMMARY, "Working on it")
            // no left/right crops for skin yet
            startActivity(i)
            finish()
        }

        btnMood.setOnClickListener {
            d.dismiss()
            // Open ReportActivity with placeholder for mood
            val i = Intent(this, ReportActivity::class.java)
            i.putExtra(ReportActivity.EXTRA_TYPE, "mood")
            i.putExtra(ReportActivity.EXTRA_IMAGE_URI, uri.toString())
            i.putExtra(ReportActivity.EXTRA_SUMMARY, "Working on it")
            startActivity(i)
            finish()
        }

        btnFull.setOnClickListener {
            d.dismiss()
            // Full-face requested: run eyes model and then open report with eye results.
            // Also open a quick note that other models are pending, included in summary.
            runEyesAndOpenReport(extraSummaryNote = " (Full scan requested — skin & mood coming soon)")
        }

        btnCancel.setOnClickListener { d.dismiss() }

        d.show()
    }

    /** Load bitmap (no EXIF rotation applied) and center-crop to 9:16 for preview as before */
    private fun loadAndCenterFace() {
        val original = decodeBitmap(uri) ?: return
        val targetAspect = 9f / 16f

        val input = InputImage.fromBitmap(original, 0)
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        val detector = FaceDetection.getClient(opts)

        detector.process(input)
            .addOnSuccessListener { faces ->
                val bmpToShow = if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val cropH = original.height
                    val cropW = min(original.width, (cropH * targetAspect).toInt()).coerceAtLeast(1)
                    val faceCx = face.boundingBox.centerX().toFloat()
                    var left = (faceCx - cropW / 2f).toInt()
                    left = left.coerceIn(0, original.width - cropW)
                    Bitmap.createBitmap(original, left, 0, cropW, cropH)
                } else {
                    val cropH = original.height
                    val cropW = min(original.width, (cropH * targetAspect).toInt()).coerceAtLeast(1)
                    val left = ((original.width - cropW) / 2f).toInt().coerceIn(0, original.width - cropW)
                    Bitmap.createBitmap(original, left, 0, cropW, cropH)
                }

                image.setImageBitmap(bmpToShow)
            }
            .addOnFailureListener {
                image.setImageURI(uri)
            }
    }

    // ===== RUN EYES MODEL, save crops, then open ReportActivity =====
    // extraSummaryNote appended to summary (used by Full)
    private fun runEyesAndOpenReport(extraSummaryNote: String = "") {
        val bmp = decodeBitmap(uri) ?: run {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            return
        }
        val img = InputImage.fromBitmap(bmp, 0)

        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
        val detector = FaceDetection.getClient(opts)
        detector.process(img)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    Toast.makeText(this, "No face detected", Toast.LENGTH_SHORT).show(); return@addOnSuccessListener
                }
                val face = faces[0]
                val leftLand = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)?.position
                val rightLand = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)?.position
                if (leftLand == null || rightLand == null) {
                    Toast.makeText(this, "Eyes not clearly detected", Toast.LENGTH_SHORT).show(); return@addOnSuccessListener
                }

                val box = face.boundingBox
                val w = (box.width() * 0.35).toInt().coerceAtLeast(1)
                val h = (box.height() * 0.35).toInt().coerceAtLeast(1)

                val leftRect = Rect(
                    (leftLand.x - w/2).toInt().coerceAtLeast(0),
                    (leftLand.y - h/2).toInt().coerceAtLeast(0),
                    (leftLand.x + w/2).toInt().coerceAtMost(bmp.width),
                    (leftLand.y + h/2).toInt().coerceAtMost(bmp.height)
                )
                val rightRect = Rect(
                    (rightLand.x - w/2).toInt().coerceAtLeast(0),
                    (rightLand.y - h/2).toInt().coerceAtLeast(0),
                    (rightLand.x + w/2).toInt().coerceAtMost(bmp.width),
                    (rightLand.y + h/2).toInt().coerceAtMost(bmp.height)
                )

                val leftW = max(1, leftRect.width()); val leftH = max(1, leftRect.height())
                val rightW = max(1, rightRect.width()); val rightH = max(1, rightRect.height())

                val leftBmp = Bitmap.createBitmap(bmp, leftRect.left, leftRect.top, leftW, leftH)
                val rightBmp = Bitmap.createBitmap(bmp, rightRect.left, rightRect.top, rightW, rightH)

                // run eye model (existing method)
                val (leftResult, rightResult) = runEyeModel(leftBmp, rightBmp)

                // Save crops to cache and get URIs
                val leftUri = saveBitmapToCache(leftBmp, "left_eye_${System.currentTimeMillis()}.jpg")
                val rightUri = saveBitmapToCache(rightBmp, "right_eye_${System.currentTimeMillis()}.jpg")

                // Build summary and confidence: parse percentage from model string if possible
                fun parseLabel(s: String): Pair<String, Float> {
                    return try {
                        val idx = s.indexOf('(')
                        if (idx > 0) {
                            val label = s.substring(0, idx).trim()
                            val pPart = s.substring(idx + 1, s.indexOf('%'))
                            val p = pPart.toIntOrNull() ?: 0
                            label to (p / 100f)
                        } else {
                            s.trim() to 0f
                        }
                    } catch (e: Exception) {
                        s.trim() to 0f
                    }
                }

                val (lLabel, lConf) = parseLabel(leftResult)
                val (rLabel, rConf) = parseLabel(rightResult)
                val overall = (lConf + rConf) / 2f
                val summary = "Left: $leftResult • Right: $rightResult$extraSummaryNote"

                // Launch ReportActivity with eye results
                val i = Intent(this, ReportActivity::class.java)
                i.putExtra(ReportActivity.EXTRA_TYPE, "eye")
                i.putExtra(ReportActivity.EXTRA_IMAGE_URI, uri.toString())
                i.putExtra(ReportActivity.EXTRA_SUMMARY, summary)
                i.putExtra(ReportActivity.EXTRA_LEFT_URI, leftUri?.toString())
                i.putExtra(ReportActivity.EXTRA_RIGHT_URI, rightUri?.toString())
                i.putExtra(ReportActivity.EXTRA_LEFT_LABEL, lLabel)
                i.putExtra(ReportActivity.EXTRA_RIGHT_LABEL, rLabel)
                i.putExtra(ReportActivity.EXTRA_CONFIDENCE, overall)
                startActivity(i)
                finish()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Face processing failed.", Toast.LENGTH_SHORT).show()
            }
    }

    // re-used existing runEyeModel & loadModel implementations (unchanged logic)
    private fun runEyeModel(left: Bitmap, right: Bitmap): Pair<String, String> {
        val interpreter = loadModel()

        fun process(b: Bitmap): Array<Array<Array<FloatArray>>> {
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
        interpreter.run(process(left), leftOut)
        interpreter.run(process(right), rightOut)

        val labels = listOf("blepharitis", "cataracts", "conjunctivitis", "darkcircles", "normal")

        fun top(out: FloatArray): String {
            val idx = out.withIndex().maxByOrNull { it.value }?.index ?: 4
            return "${labels[idx]} (${(out[idx]*100).toInt()}%)"
        }
        return top(leftOut[0]) to top(rightOut[0])
    }

    private fun loadModel(): Interpreter {
        val afd = assets.openFd("eye_disease_model.tflite")
        val stream = java.io.FileInputStream(afd.fileDescriptor)
        val map: MappedByteBuffer = stream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            afd.startOffset, afd.declaredLength
        )
        return Interpreter(map)
    }

    // helper to save a bitmap to cache and return a uri string
    private fun saveBitmapToCache(bmp: Bitmap, name: String): Uri? {
        return try {
            val f = File.createTempFile(name, null, cacheDir)
            FileOutputStream(f).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 90, out) }
            Uri.fromFile(f)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // --- decodeBitmap same as earlier (reads EXIF and returns oriented bitmap) ---
    private fun decodeBitmap(u: Uri): Bitmap? {
        var firstStream: InputStream? = null
        try {
            firstStream = contentResolver.openInputStream(u)
            val options = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeStream(firstStream, null, options)
            firstStream?.close()
            if (decoded == null) return null

            var exifStream: InputStream? = null
            try {
                exifStream = contentResolver.openInputStream(u)
                val exif = androidx.exifinterface.media.ExifInterface(exifStream!!)
                val orientation = exif.getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                )

                val matrix = android.graphics.Matrix()
                when (orientation) {
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL -> { }
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
                    else -> { }
                }

                if (matrix.isIdentity) return decoded
                val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (oriented != decoded) decoded.recycle()
                return oriented
            } finally {
                try { exifStream?.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            try { firstStream?.close() } catch (_: Exception) {}
        }
    }
}
