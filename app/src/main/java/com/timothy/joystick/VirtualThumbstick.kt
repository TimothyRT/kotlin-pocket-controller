package com.timothy.joystick

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class VirtualThumbstick @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onMove: ((x: Float, y: Float) -> Unit)? = null

    // Current normalised values
    var normX = 0f; private set
    var normY = 0f; private set

    // Paint objects
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 180, 180, 180)
        style = Paint.Style.FILL
    }
    private val baseRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 100, 100, 100)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 60, 60, 60)
        style = Paint.Style.FILL
    }
    private val thumbActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 33, 150, 243)
        style = Paint.Style.FILL
    }

    private var cx = 0f
    private var cy = 0f
    private var baseRadius = 0f
    private var thumbRadius = 0f
    private var thumbX = 0f
    private var thumbY = 0f
    private var active = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        baseRadius = min(w, h) / 2f - 4f
        thumbRadius = baseRadius * 0.38f
        thumbX = cx
        thumbY = cy
    }

    override fun onDraw(canvas: Canvas) {
        // Base
        canvas.drawCircle(cx, cy, baseRadius, basePaint)
        canvas.drawCircle(cx, cy, baseRadius, baseRingPaint)
        // Cross-hair lines
        baseRingPaint.alpha = 40
        canvas.drawLine(cx - baseRadius, cy, cx + baseRadius, cy, baseRingPaint)
        canvas.drawLine(cx, cy - baseRadius, cx, cy + baseRadius, baseRingPaint)
        baseRingPaint.alpha = 100
        // Thumb
        canvas.drawCircle(thumbX, thumbY, thumbRadius, if (active) thumbActivePaint else thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                active = true
                val dx = event.x - cx
                val dy = event.y - cy
                val dist = sqrt(dx * dx + dy * dy)
                val maxDist = baseRadius - thumbRadius

                if (dist <= maxDist) {
                    thumbX = event.x
                    thumbY = event.y
                } else {
                    val angle = atan2(dy, dx)
                    thumbX = cx + cos(angle) * maxDist
                    thumbY = cy + sin(angle) * maxDist
                }

                normX = ((thumbX - cx) / maxDist).coerceIn(-1f, 1f)
                normY = ((thumbY - cy) / maxDist).coerceIn(-1f, 1f)
                onMove?.invoke(normX, normY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = false
                thumbX = cx
                thumbY = cy
                normX = 0f
                normY = 0f
                onMove?.invoke(0f, 0f)
            }
        }
        invalidate()
        return true
    }
}
