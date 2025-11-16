package com.example.fyp

import android.app.Dialog
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.InputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class ConfirmPhotoActivity : AppCompatActivity() {

    companion object { const val EXTRA_IMAGE_URI = "extra_image_uri" }

    private lateinit var uri: Uri
    private lateinit var image: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_confirm_photo)

        image = findViewById(R.id.image) // XML should have centerCrop + rounded overlay

        val u = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (u.isNullOrEmpty()) { finish(); return }
        uri = Uri.parse(u)

        // Wait for the ImageView to be laid out so we know its aspect ratio
        image.post { loadAndCenterFace() }

        findViewById<View>(R.id.btnRetake).setOnClickListener { showDiscardDialog() }
        findViewById<View>(R.id.btnConfirm).setOnClickListener { processAndGo() }
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

    /** Load bitmap with EXIF-based rotation and flip fixed, then crop horizontally to center the face. */
    private fun loadAndCenterFace() {
        val original = decodeBitmapWithExif(uri)

        val targetW = image.width.coerceAtLeast(1)
        val targetH = image.height.coerceAtLeast(1)
        val targetAspect = targetW.toFloat() / targetH.toFloat() // width/height

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
                    val cropW = min(original.width, (cropH * targetAspect).toInt())

                    val faceCx = face.boundingBox.centerX().toFloat()
                    var left = (faceCx - cropW / 2f).toInt()
                    left = left.coerceIn(0, original.width - cropW)

                    Bitmap.createBitmap(original, left, 0, cropW, cropH)
                } else {
                    // No face → center crop to aspect
                    val cropH = original.height
                    val cropW = min(original.width, (cropH * targetAspect).toInt())
                    val left = ((original.width - cropW) / 2f).toInt().coerceIn(0, original.width - cropW)
                    Bitmap.createBitmap(original, left, 0, cropW, cropH)
                }

                image.setImageBitmap(bmpToShow)
                // XML centerCrop makes it fill height; rounded corners now visible.
            }
            .addOnFailureListener {
                // Fallback: just show decoded bitmap
                image.setImageBitmap(original)
            }
    }

    // ==== Your original ML flow kept as-is ====
    private fun processAndGo() {
        val bmp = decodeBitmapWithExif(uri) // use corrected bitmap for detection/model
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
                val left = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)?.position
                val right = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)?.position
                if (left == null || right == null) {
                    Toast.makeText(this, "Eyes not clearly detected", Toast.LENGTH_SHORT).show(); return@addOnSuccessListener
                }
                val box = face.boundingBox
                val w = (box.width() * 0.35).toInt()
                val h = (box.height() * 0.35).toInt()

                val leftRect = Rect(
                    (left.x - w/2).toInt().coerceAtLeast(0),
                    (left.y - h/2).toInt().coerceAtLeast(0),
                    (left.x + w/2).toInt().coerceAtMost(bmp.width),
                    (left.y + h/2).toInt().coerceAtMost(bmp.height)
                )
                val rightRect = Rect(
                    (right.x - w/2).toInt().coerceAtLeast(0),
                    (right.y - h/2).toInt().coerceAtLeast(0),
                    (right.x + w/2).toInt().coerceAtMost(bmp.width),
                    (right.y + h/2).toInt().coerceAtMost(bmp.height)
                )

                val leftBmp  = Bitmap.createBitmap(bmp, leftRect.left,  leftRect.top,  leftRect.width(),  leftRect.height())
                val rightBmp = Bitmap.createBitmap(bmp, rightRect.left, rightRect.top, rightRect.width(), rightRect.height())

                val result = runEyeModel(leftBmp, rightBmp)
                Toast.makeText(this, "Left: ${result.first} | Right: ${result.second}", Toast.LENGTH_LONG).show()

                val i = Intent(this, MainActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                i.putExtra("open_tab", "reports")
                startActivity(i)
                finish()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Face processing failed.", Toast.LENGTH_SHORT).show()
            }
    }

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
        val stream = FileInputStream(afd.fileDescriptor)
        val map: MappedByteBuffer = stream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            afd.startOffset, afd.declaredLength
        )
        return Interpreter(map)
    }

    // --- Bitmap helpers: EXIF rotation + optional horizontal flip handling ---
    private fun decodeBitmapWithExif(u: Uri): Bitmap {
        val corrected = decodeBitmap(u)
        val orientation = contentResolver.openInputStream(u)?.use { ExifInterface(it) }?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        ) ?: ExifInterface.ORIENTATION_NORMAL

        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90    -> corrected.rotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180   -> corrected.rotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270   -> corrected.rotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> corrected.flip(horizontal = true)
            ExifInterface.ORIENTATION_TRANSPOSE    -> corrected.rotate(90f).flip(horizontal = true)
            ExifInterface.ORIENTATION_TRANSVERSE   -> corrected.rotate(270f).flip(horizontal = true)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> corrected.flip(horizontal = false)
            else -> corrected
        }
    }

    private fun decodeBitmap(u: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT < 28) {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(contentResolver, u)
        } else {
            val src = ImageDecoder.createSource(contentResolver, u)
            ImageDecoder.decodeBitmap(src) { d, _, _ ->
                d.isMutableRequired = true
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
    }

    private fun Bitmap.rotate(deg: Float): Bitmap {
        val m = Matrix().apply { postRotate(deg) }
        return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
    }

    private fun Bitmap.flip(horizontal: Boolean): Bitmap {
        val m = Matrix().apply { postScale(if (horizontal) -1f else 1f, if (horizontal) 1f else -1f, width / 2f, height / 2f) }
        return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
    }
}
