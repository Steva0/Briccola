package com.briccola.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.briccola.app.engine.TideData

/** Disegna la curva di marea di oggi (interpolata dagli estremali), con un pallino trascinabile
 *  per vedere il livello previsto alle varie ore — parte da "adesso" ma si può spostare a mano. */
class TideChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var data: TideData? = null

    /** null = mostra "adesso" (default); non-null = orario scelto trascinando il pallino. */
    private var selectedMs: Long? = null

    /** Chiamato ad ogni spostamento del pallino: (istante, valore in metri). */
    var onScrub: ((Long, Double) -> Unit)? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1976D2")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#331976D2")
        style = Paint.Style.FILL
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCCCCC")
        strokeWidth = 2f
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")
        textSize = 26f
    }

    fun setData(tideData: TideData) {
        data = tideData
        selectedMs = null
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val d = data ?: return false
        if (d.curve.isEmpty()) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        if (event.action != MotionEvent.ACTION_DOWN && event.action != MotionEvent.ACTION_MOVE) return true

        val padLeft = 60f
        val w = width.toFloat() - padLeft - 16f
        val t0 = d.curve.first().first
        val t1 = d.curve.last().first
        val frac = ((event.x - padLeft) / w).coerceIn(0f, 1f)
        val targetMs = t0 + ((t1 - t0) * frac).toLong()

        val closest = d.curve.minByOrNull { Math.abs(it.first - targetMs) } ?: return true
        selectedMs = closest.first
        onScrub?.invoke(closest.first, closest.second)
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = data ?: return
        if (d.curve.isEmpty()) return

        val padLeft = 60f
        val padTop = 20f
        val padBottom = 40f
        val w = width.toFloat() - padLeft - 16f
        val h = height.toFloat() - padTop - padBottom

        val minV = minOf(d.curve.minOf { it.second }, 0.0)
        val maxV = maxOf(d.curve.maxOf { it.second }, 0.0)
        val range = (maxV - minV).takeIf { it > 0.01 } ?: 1.0

        val t0 = d.curve.first().first
        val t1 = d.curve.last().first
        val duration = (t1 - t0).takeIf { it > 0 } ?: 1L

        fun x(t: Long) = padLeft + w * (t - t0) / duration.toFloat()
        fun y(v: Double) = padTop + h * (1f - ((v - minV) / range).toFloat())

        // linea dello zero
        val zeroY = y(0.0)
        canvas.drawLine(padLeft, zeroY, padLeft + w, zeroY, axisPaint)
        canvas.drawText("0 m", 4f, zeroY + 8f, labelPaint)

        // curva riempita
        val path = Path()
        val fillPath = Path()
        d.curve.forEachIndexed { i, (t, v) ->
            val px = x(t); val py = y(v)
            if (i == 0) { path.moveTo(px, py); fillPath.moveTo(px, zeroY); fillPath.lineTo(px, py) }
            else { path.lineTo(px, py); fillPath.lineTo(px, py) }
        }
        fillPath.lineTo(x(d.curve.last().first), zeroY)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

        // pallino: sull'orario scelto trascinando, o su "adesso" di default
        val markerMs = selectedMs ?: System.currentTimeMillis().coerceIn(t0, t1)
        val markerValue = selectedMs?.let { ms -> d.curve.minByOrNull { Math.abs(it.first - ms) }?.second } ?: d.nowM
        val markerX = x(markerMs)
        val markerY = y(markerValue)
        canvas.drawLine(markerX, padTop, markerX, padTop + h, axisPaint)
        canvas.drawCircle(markerX, markerY, 12f, nowPaint)

        // etichette ore
        val hoursFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ITALY)
        listOf(0, 1, 2, 3, 4).forEach { i ->
            val t = t0 + duration * i / 4
            canvas.drawText(hoursFmt.format(java.util.Date(t)), x(t) - 20f, padTop + h + 30f, labelPaint)
        }
    }
}
