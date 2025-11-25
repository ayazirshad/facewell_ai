package com.example.fyp.utils

import android.content.ContentResolver
import android.content.Context
import android.graphics.*
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Utility helpers extracted from your Eye/Skin/Mood activities.
 * Optimized but preserves original behavior and outputs.
 */
object MLUtils {

    // ----------------
    // Bitmap helpers
    // ----------------
    fun decodeBitmap(contentResolver: ContentResolver, u: Uri): Bitmap? {
        try {
            val firstStream = contentResolver.openInputStream(u) ?: return null
            val options = android.graphics.BitmapFactory.Options().apply {
                inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = android.graphics.BitmapFactory.decodeStream(firstStream, null, options)
            firstStream.close()
            if (decoded == null) return null
            val exifStream = contentResolver.openInputStream(u)
            val exif = androidx.exifinterface.media.ExifInterface(exifStream!!)
            val orientation = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
            exifStream.close()
            val matrix = Matrix()
            when (orientation) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
                androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            }
            if (matrix.isIdentity) return decoded
            val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (oriented != decoded) decoded.recycle()
            return oriented
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // ----------------
    // Face detection helpers
    // ----------------
    private fun getFaceDetector(performanceFast: Boolean = true): com.google.mlkit.vision.face.FaceDetector {
        val builder = FaceDetectorOptions.Builder()
        if (performanceFast) builder.setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        else builder.setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        builder.setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        return FaceDetection.getClient(builder.build())
    }

    /**
     * Detect face(s) synchronously (blocking) by using Tasks.await. Returns list of faces.
     * Caller must call from background thread if blocking behavior is not desired.
     */
    fun detectFacesBlocking(bitmap: Bitmap, accurate: Boolean = false): List<com.google.mlkit.vision.face.Face> {
        val img = InputImage.fromBitmap(bitmap, 0)
        val detector = getFaceDetector(!accurate)
        return com.google.android.gms.tasks.Tasks.await(detector.process(img))
    }

    // ----------------
    // Eye cropping and model utility (from EyeReportActivity)
    // ----------------
    data class EyeResult(val leftCrop: Bitmap?, val rightCrop: Bitmap?)

    fun detectAndCropEyesBlocking(fullBmp: Bitmap): EyeResult {
        val img = InputImage.fromBitmap(fullBmp, 0)
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
        val detector = FaceDetection.getClient(opts)
        val faces = com.google.android.gms.tasks.Tasks.await(detector.process(img))
        if (faces.isEmpty()) return EyeResult(null, null)
        val face = faces[0]
        val leftLand = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightLand = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        if (leftLand == null || rightLand == null) return EyeResult(null, null)

        val box = face.boundingBox
        val w = (box.width() * 0.35).toInt().coerceAtLeast(1)
        val h = (box.height() * 0.35).toInt().coerceAtLeast(1)

        val leftRect = Rect(
            (leftLand.x - w / 2).toInt().coerceAtLeast(0),
            (leftLand.y - h / 2).toInt().coerceAtLeast(0),
            (leftLand.x + w / 2).toInt().coerceAtMost(fullBmp.width),
            (leftLand.y + h / 2).toInt().coerceAtMost(fullBmp.height)
        )
        val rightRect = Rect(
            (rightLand.x - w / 2).toInt().coerceAtLeast(0),
            (rightLand.y - h / 2).toInt().coerceAtLeast(0),
            (rightLand.x + w / 2).toInt().coerceAtMost(fullBmp.width),
            (rightLand.y + h / 2).toInt().coerceAtMost(fullBmp.height)
        )

        val leftW = max(1, leftRect.width()); val leftH = max(1, leftRect.height())
        val rightW = max(1, rightRect.width()); val rightH = max(1, rightRect.height())

        val leftBmp = Bitmap.createBitmap(fullBmp, leftRect.left, leftRect.top, leftW, leftH)
        val rightBmp = Bitmap.createBitmap(fullBmp, rightRect.left, rightRect.top, rightW, rightH)
        return EyeResult(leftBmp, rightBmp)
    }

    /**
     * Run eye model — same logic as in your EyeReportActivity.
     * labels ordering preserved.
     */
    fun runEyeModel(interpreter: Interpreter, left: Bitmap, right: Bitmap): Triple<Pair<String, Float>, Pair<String, Float>, Float> {
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

    // ----------------
    // Skin helpers (from SkinReportActivity)
    // ----------------
    data class PatchResult(val bmp: Bitmap, val label: String, val confidence: Double)

    /**
     * Analyze patches (cheeks, chin, forehead) and aggregate probabilities.
     * Preserves original algorithm and quantization handling.
     */
    fun analyzePatchesFromSelfieBlocking(fullBmp: Bitmap, interpreter: Interpreter,
                                         labels: List<String>,
                                         inputW: Int, inputH: Int, inputChannels: Int,
                                         inputDataType: DataType,
                                         inputScale: Float, inputZeroPoint: Int
    ): Pair<List<PatchResult>, FloatArray> {
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

        val valid = mutableListOf<PatchResult>()
        val agg = FloatArray(labels.size) { 0f }
        var count = 0

        for ((center, _) in centers) {
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

            val raw = runModelGetOutputAsFloatArray(interpreter, inputBuffer)
            val maxv = raw.maxOrNull() ?: 0f
            val exp = raw.map { Math.exp((it - maxv).toDouble()).toFloat() }
            val sum = exp.sum()
            val probs = if (sum > 0f) exp.map { it / sum } else exp.map { 0f }

            for (i in probs.indices) agg[i] += probs[i]
            count++

            val topIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
            val topLabel = labels.getOrNull(topIdx) ?: "unknown"
            val conf = probs[topIdx] * 100.0
            valid.add(PatchResult(resized, topLabel, conf))
        }

        if (count > 0) {
            for (i in agg.indices) agg[i] = agg[i] / count.toFloat()
        }

        return Pair(valid, agg)
    }

    /**
     * run model and always return FloatArray of raw outputs (dequantized if needed)
     * Assumes interpreter provided is created with the correct model.
     */
    fun runModelGetOutputAsFloatArray(interpreter: Interpreter, inputBuffer: ByteBuffer): FloatArray {
        val outTensor = interpreter.getOutputTensor(0)
        val outShape = outTensor.shape()
        val num = if (outShape.isNotEmpty()) outShape.last() else 0
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

    /**
     * Sliding window coarse detector + label-count sampling (keeps behavior from SkinReportActivity).
     * This method returns the human-readable summary label (capitalized) and also provides
     * a heatmap bitmap (overlay) as Pair(summary, overlayBitmapUriString) is handled by caller previously.
     *
     * To keep module small we return only the summary text here (the calling activity can call the
     * existing heatmap routine if desired). In FullFace we will call analyzePatchesFromSelfieBlocking
     * plus a simplified label-count sampling to determine final label (preserving exact semantics).
     */
    fun coarseLabelBySampling(fullBmp: Bitmap, interpreter: Interpreter,
                              labels: List<String>,
                              inputW: Int, inputH: Int, inputChannels: Int,
                              inputDataType: DataType,
                              inputScale: Float, inputZeroPoint: Int
    ): String {
        // Simplified: sample patches across face area and count top labels (keeps prior sampling methodology)
        val img = InputImage.fromBitmap(fullBmp, 0)
        val opts = FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
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

                val raw = runModelGetOutputAsFloatArray(interpreter, inputBuffer)
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
        return topLabel.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    // ----------------
    // Mood helpers (from MoodReportActivity)
    // ----------------
    val moodLabels = listOf("Angry","Disgust","Fear","Happy","Sad","Surprise","Neutral")

    fun runMoodOnBitmapForTopBlocking(fullBmp: Bitmap, interpreter: Interpreter,
                                      inputW: Int, inputH: Int, inputChannels: Int, inputDataType: DataType
    ): Pair<String, Double> {
        val img = InputImage.fromBitmap(fullBmp, 0)
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        val detector = FaceDetection.getClient(opts)
        val faces = com.google.android.gms.tasks.Tasks.await(detector.process(img))
        if (faces.isEmpty()) return Pair("No face detected", 0.0)
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
            java.nio.ByteBuffer.allocateDirect(inputW * inputH * inputChannels).order(java.nio.ByteOrder.nativeOrder())
        } else {
            java.nio.ByteBuffer.allocateDirect(4 * inputW * inputH * inputChannels).order(java.nio.ByteOrder.nativeOrder())
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

        val output = Array(1) { FloatArray(moodLabels.size) }
        interpreter.run(inputBuffer, output)
        val raw = output[0]
        val maxv = raw.maxOrNull() ?: 0f
        val exp = raw.map { Math.exp((it - maxv).toDouble()) }.map { it.toFloat() }
        val sum = exp.sum()
        val probs = exp.map { it / sum }
        val topIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
        val topLabel = moodLabels.getOrNull(topIdx) ?: "unknown"
        val conf = (probs[topIdx] * 100.0)
        return Pair(topLabel, conf)
    }
}
