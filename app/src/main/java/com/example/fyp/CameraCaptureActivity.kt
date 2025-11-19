package com.example.fyp.camera

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.example.fyp.ConfirmPhotoActivity
import com.example.fyp.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraCaptureActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var btnFlip: ImageView
    private lateinit var btnTorch: ImageView
    private lateinit var btnClose: ImageView
    private lateinit var tvHint: TextView

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_FRONT
    private lateinit var cameraExecutor: ExecutorService

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val ok = granted.values.all { it }
        if (ok) startCamera() else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_capture)

        previewView = findViewById(R.id.previewView)
        btnCapture = findViewById(R.id.btnCapture)
        btnFlip = findViewById(R.id.btnFlip)
        btnTorch = findViewById(R.id.btnTorch)
        btnClose = findViewById(R.id.btnClose)
        tvHint = findViewById(R.id.tvHint)

        cameraExecutor = Executors.newSingleThreadExecutor()

        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.READ_MEDIA_IMAGES
        requestPerms.launch(perms.toTypedArray())

        btnFlip.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
                CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
            startCamera()
        }

        btnTorch.setOnClickListener {
            camera?.let { cam ->
                val on = cam.cameraInfo.torchState.value == 1
                cam.cameraControl.enableTorch(!on)
            }
        }

        btnClose.setOnClickListener { finish() }
        btnCapture.setOnClickListener { takePhoto() }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            provider.unbindAll()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val selector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                camera = provider.bindToLifecycle(
                    this, selector, preview, imageCapture
                )
            } catch (_: Exception) {
                Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return

        val outputDir = File(cacheDir, "images").apply { mkdirs() }
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val photo = File(outputDir, "$name.jpg")

        val opts = ImageCapture.OutputFileOptions.Builder(photo).build()

        ic.takePicture(
            opts,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(
                        this@CameraCaptureActivity,
                        "Capture failed: ${exception.message ?: ""}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    // File saved by CameraX at 'photo'
                    // Now: read EXIF orientation, apply rotation to pixels, then (if front) mirror,
                    // and overwrite file so Confirm screen sees final upright+mirrored bitmap.
                    try {
                        fixImageOrientationAndMirror(photo.absolutePath, lensFacing == CameraSelector.LENS_FACING_FRONT)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    val uri = FileProvider.getUriForFile(
                        this@CameraCaptureActivity,
                        "${packageName}.fileprovider",
                        photo
                    )

                    val i = Intent(this@CameraCaptureActivity, ConfirmPhotoActivity::class.java)
                        .putExtra(ConfirmPhotoActivity.EXTRA_IMAGE_URI, uri.toString())
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(i)
                }
            }
        )
    }

    /**
     * Read EXIF, rotate pixels accordingly, then mirror if requested, and overwrite file.
     * This makes the saved JPEG's pixels match the preview orientation the user saw.
     */
    private fun fixImageOrientationAndMirror(path: String, mirrorFront: Boolean) {
        // 1) read bitmap (ignore EXIF transform)
        val bmp = BitmapFactory.decodeFile(path) ?: return

        // 2) read exif orientation
        val exif = ExifInterface(path)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )

        val matrix = Matrix()

        // apply rotation based on EXIF tag
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> { /* normal - no rotation */ }
        }

        // if front camera, preview was mirrored for user; mirror horizontally so Confirm matches preview
        if (mirrorFront) {
            // preScale so mirroring happens before rotation/after — preScale is fine
            matrix.postScale(-1f, 1f)
        }

        val transformed = if (matrix.isIdentity) {
            bmp
        } else {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true).also {
                if (it != bmp) bmp.recycle()
            }
        }

        // overwrite file with transformed bitmap (JPEG, good quality)
        FileOutputStream(path).use { out ->
            transformed.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }

        // update EXIF to NORMAL after we've baked transform (so future readers don't re-rotate)
        try {
            val exif2 = ExifInterface(path)
            exif2.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif2.saveAttributes()
        } catch (e: Exception) {
            // ignore if cannot write EXIF
            e.printStackTrace()
        }

        // cleanup
        if (!transformed.isRecycled) {
            transformed.recycle()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }
}
