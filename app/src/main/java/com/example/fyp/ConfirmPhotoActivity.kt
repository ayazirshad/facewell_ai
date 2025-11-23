package com.example.fyp

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
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

import com.example.fyp.utils.NetworkUtils
import com.example.fyp.utils.LocationHelper
import com.example.fyp.weather.WeatherService

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

        // preload recommendations (load both)
        RecommendationProvider.loadFromAssets(this, "both")
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
            // Start EyeReportActivity (which will run the eye model)
            launchActivityWithOptionalWeather(EyeReportActivity::class.java) { intent ->
                // nothing extra
            }
        }

        btnSkin.setOnClickListener {
            d.dismiss()
            // Start SkinReportActivity (which will run the skin model)
            launchActivityWithOptionalWeather(SkinReportActivity::class.java) { intent ->
                // nothing extra
            }
        }

//        btnMood.setOnClickListener {
//            d.dismiss()
//            // open placeholder mood report
//            launchActivityWithOptionalWeather(ReportActivity::class.java) { intent ->
//                intent.putExtra(ReportActivity.EXTRA_TYPE, "mood")
//                intent.putExtra(ReportActivity.EXTRA_IMAGE_URI, uri.toString())
//                intent.putExtra(ReportActivity.EXTRA_SUMMARY, "Working on it")
//            }
//        }

        btnFull.setOnClickListener {
            d.dismiss()
            // Launch eye report with note
            launchActivityWithOptionalWeather(EyeReportActivity::class.java) { intent ->
                intent.putExtra("extra_full_scan_note", "Full scan requested — skin & mood coming soon")
            }
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
                    val cropW = minOf(original.width, (cropH * targetAspect).toInt()).coerceAtLeast(1)
                    val faceCx = face.boundingBox.centerX().toFloat()
                    var left = (faceCx - cropW / 2f).toInt()
                    left = left.coerceIn(0, original.width - cropW)
                    Bitmap.createBitmap(original, left, 0, cropW, cropH)
                } else {
                    val cropH = original.height
                    val cropW = minOf(original.width, (cropH * targetAspect).toInt()).coerceAtLeast(1)
                    val left = ((original.width - cropW) / 2f).toInt().coerceIn(0, original.width - cropW)
                    Bitmap.createBitmap(original, left, 0, cropW, cropH)
                }
                image.setImageBitmap(bmpToShow)
            }
            .addOnFailureListener {
                image.setImageURI(uri)
            }
    }

    // decodeBitmap same as earlier
    private fun decodeBitmap(u: Uri): Bitmap? {
        try {
            val firstStream = contentResolver.openInputStream(u) ?: return null
            val options = android.graphics.BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
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
        } catch (e: Exception) {
            e.printStackTrace(); return null
        }
    }

    private fun launchActivityWithOptionalWeather(target: Class<*>, extraPopulator: (Intent) -> Unit) {
        if (!NetworkUtils.isOnline(this)) {
            Toast.makeText(this, "You are offline. Weather & saving will be unavailable.", Toast.LENGTH_LONG).show()
            val i = Intent(this, target)
            i.putExtra("extra_image_uri", uri.toString())
            extraPopulator(i)
            startActivity(i)
            finish()
            return
        }

        val baseIntent = Intent(this, target).apply { putExtra("extra_image_uri", uri.toString()) }
        extraPopulator(baseIntent)

        if (!LocationHelper.hasLocationPermission(this)) {
            Toast.makeText(this, "Location permission not granted — weather unavailable.", Toast.LENGTH_SHORT).show()
            startActivity(baseIntent); finish(); return
        }

        LocationHelper.getLastLocation(this,
            onSuccess = { loc ->
                if (loc == null) { startActivity(baseIntent); finish(); return@getLastLocation }
                try {
                    val ws = WeatherService(this)
                    ws.fetchCurrent(loc.latitude, loc.longitude) { snap ->
                        if (snap != null) {
                            baseIntent.putExtra("weather_ts", snap.ts)
                            baseIntent.putExtra("weather_temp", snap.tempC)
                            baseIntent.putExtra("weather_humidity", snap.humidity)
                            baseIntent.putExtra("weather_uvi", snap.uvi)
                            baseIntent.putExtra("weather_main", snap.weatherMain)
                        }
                        startActivity(baseIntent); finish()
                    }
                } catch (e: Exception) {
                    e.printStackTrace(); startActivity(baseIntent); finish()
                }
            },
            onFailure = { ex -> ex?.printStackTrace(); startActivity(baseIntent); finish() }
        )
    }
}
