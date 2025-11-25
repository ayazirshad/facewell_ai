package com.example.fyp

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.InputStream

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

        image.post { loadAndCenterFace() }

        findViewById<View>(R.id.btnRetake).setOnClickListener { showDiscardDialog() }
        findViewById<View>(R.id.btnConfirm).setOnClickListener { onConfirmPressed() }
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

    private fun onConfirmPressed() {
        val bmp = decodeBitmap(uri)
        if (bmp == null) {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            return
        }

        val dlg = Dialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_simple_loader, null)
        dlg.setContentView(v)
        dlg.setCancelable(false)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.show()

        val img = InputImage.fromBitmap(bmp, 0)
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        val detector = FaceDetection.getClient(opts)
        detector.process(img)
            .addOnSuccessListener { faces ->
                dlg.dismiss()
                if (faces.isEmpty()) {
                    Toast.makeText(this, "No face detected. Please use a selfie/photo with a clear face.", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }
                showScanDialog()
            }
            .addOnFailureListener { e ->
                dlg.dismiss()
                e.printStackTrace()
                Toast.makeText(this, "Face detection failed. Try again.", Toast.LENGTH_SHORT).show()
            }
    }

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
            val i = Intent(this, EyeReportActivity::class.java)
            i.putExtra(EyeReportActivity.EXTRA_IMAGE_URI, uri.toString())
            startActivity(i)
            finish()
        }

        btnSkin.setOnClickListener {
            d.dismiss()
            val i = Intent(this, SkinReportActivity::class.java)
            i.putExtra(SkinReportActivity.EXTRA_IMAGE_URI, uri.toString())
            startActivity(i)
            finish()
        }

        btnMood.setOnClickListener {
            d.dismiss()
            val i = Intent(this, MoodReportActivity::class.java)
            i.putExtra(MoodReportActivity.EXTRA_IMAGE_URI, uri.toString())
            startActivity(i)
            finish()
        }

        btnFull.setOnClickListener {
            d.dismiss()
            val i = Intent(this, FullFaceReportActivity::class.java)
            i.putExtra(FullFaceReportActivity.EXTRA_IMAGE_URI, uri.toString())
            startActivity(i)
            finish()
        }

        btnCancel.setOnClickListener { d.dismiss() }
        d.show()
    }

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
                    val cropW = kotlin.math.min(original.width, (cropH * targetAspect).toInt()).coerceAtLeast(1)
                    val faceCx = face.boundingBox.centerX().toFloat()
                    var left = (faceCx - cropW / 2f).toInt()
                    left = left.coerceIn(0, original.width - cropW)
                    Bitmap.createBitmap(original, left, 0, cropW, cropH)
                } else {
                    val cropH = original.height
                    val cropW = kotlin.math.min(original.width, (cropH * targetAspect).toInt()).coerceAtLeast(1)
                    val left = ((original.width - cropW) / 2f).toInt().coerceIn(0, original.width - cropW)
                    Bitmap.createBitmap(original, left, 0, cropW, cropH)
                }

                image.setImageBitmap(bmpToShow)
            }
            .addOnFailureListener {
                image.setImageURI(uri)
            }
    }

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
