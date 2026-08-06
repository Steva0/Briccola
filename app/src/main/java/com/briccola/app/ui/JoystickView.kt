package com.briccola.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Callback: normX/normY in [-1,1], magnitude in [0,1]. */
    var onMove: ((normX: Float, normY: Float, magnitude: Float) -> Unit)? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 50, 180, 255)
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private var cx = 0f; private var cy = 0f
    private var tx = 0f; private var ty = 0f
    private var outerR = 0f

    override fun onSizeChanged(w: Int, h: Int, old: Int, oldH: Int) {
        cx = w / 2f; cy = h / 2f
        tx = cx; ty = cy
        outerR = minOf(w, h) / 2f * 0.88f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, outerR, ringPaint)
        canvas.drawCircle(cx, cy, outerR * 0.1f, centerPaint)
        canvas.drawCircle(tx, ty, outerR * 0.28f, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val dx   = event.x - cx
                val dy   = event.y - cy
                val dist = sqrt(dx * dx + dy * dy)
                val clamped = minOf(dist, outerR)
                val angle = atan2(dy, dx)
                tx = cx + cos(angle) * clamped
                ty = cy + sin(angle) * clamped
                invalidate()
                val normX = (tx - cx) / outerR
                val normY = (ty - cy) / outerR
                onMove?.invoke(normX, normY, minOf(dist / outerR, 1f))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tx = cx; ty = cy
                invalidate()
                onMove?.invoke(0f, 0f, 0f)
            }
        }
        return true
    }
}
