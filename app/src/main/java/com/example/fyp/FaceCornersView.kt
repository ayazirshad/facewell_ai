package com.example.fyp.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.fyp.R

class FaceCornersView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.input_border)
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()

        // Adjusted insets: tighter horizontal, more space from top
        val horizontalInset = w * 0.18f   // increased to pull box closer inward
        val topInset = h * 0.18f          // move box slightly lower (to make text above)
        val bottomInset = h * 0.25f       // keeps bottom visible above toolbar

        val rect = RectF(
            horizontalInset,
            topInset,
            w - horizontalInset,
            h - bottomInset
        )

        val cornerLength = 70f // longer corners for aesthetic balance

        // Top-Left
        c.drawLine(rect.left, rect.top, rect.left + cornerLength, rect.top, paint)
        c.drawLine(rect.left, rect.top, rect.left, rect.top + cornerLength, paint)

        // Top-Right
        c.drawLine(rect.right, rect.top, rect.right - cornerLength, rect.top, paint)
        c.drawLine(rect.right, rect.top, rect.right, rect.top + cornerLength, paint)

        // Bottom-Left
        c.drawLine(rect.left, rect.bottom, rect.left + cornerLength, rect.bottom, paint)
        c.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - cornerLength, paint)

        // Bottom-Right
        c.drawLine(rect.right, rect.bottom, rect.right - cornerLength, rect.bottom, paint)
        c.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - cornerLength, paint)
    }
}
