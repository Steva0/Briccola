package com.briccola.app.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.briccola.app.engine.TideData
import java.text.SimpleDateFormat
import java.util.*

/**
 * Vista custom che disegna la curva di marea e permette di selezionare un istante temporale
 * tramite uno slider integrato.
 */
class TideSliderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var tideData: TideData? = null
    private var selectedTimeMs: Long = System.currentTimeMillis()
    
    var onTimeChanged: ((Long, Double) -> Unit)? = null

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0091EA")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A0091EA")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#444444")
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0091EA")
        textSize = 32f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0091EA")
        strokeWidth = 4f
    }

    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val chartPath = Path()
    private val curvePath = Path()

    fun setData(data: TideData) {
        this.tideData = data
        // Se il tempo selezionato è fuori dai nuovi dati, resettiamo al presente
        val now = System.currentTimeMillis()
        if (data.curve.isNotEmpty()) {
            val start = data.curve.first().first
            val end = data.curve.last().first
            if (selectedTimeMs < start || selectedTimeMs > end) {
                selectedTimeMs = now
            }
        }
        invalidate()
    }

    fun setSelectedTime(timeMs: Long) {
        selectedTimeMs = timeMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = tideData ?: return
        if (data.curve.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val padding = 40f
        val chartH = h - padding * 2
        
        val startTime = data.curve.first().first
        val endTime = data.curve.last().first
        val timeSpan = (endTime - startTime).toFloat()
        
        // Trova min/max valore per scalare verticalmente
        val minVal = -0.5 // fisso per coerenza visiva? o dinamico? Usiamo -50cm come base
        val maxVal = 1.5  // e +150cm come tetto
        val valSpan = (maxVal - minVal).toFloat()

        fun timeToX(t: Long): Float = (t - startTime) / timeSpan * w
        fun valToY(v: Double): Float = h - padding - ((v - minVal).toFloat() / valSpan * chartH)

        // 1. Disegna area di riempimento sotto la curva
        chartPath.reset()
        chartPath.moveTo(0f, h - padding)
        data.curve.forEach { (t, v) ->
            chartPath.lineTo(timeToX(t), valToY(v))
        }
        chartPath.lineTo(w, h - padding)
        chartPath.close()
        canvas.drawPath(chartPath, fillPaint)

        // 2. Disegna la curva
        curvePath.reset()
        var first = true
        data.curve.forEach { (t, v) ->
            if (first) {
                curvePath.moveTo(timeToX(t), valToY(v))
                first = false
            } else {
                curvePath.lineTo(timeToX(t), valToY(v))
            }
        }
        canvas.drawPath(curvePath, curvePaint)

        // 3. Disegna i picchi (Max/Min)
        data.extremes.forEach { extreme ->
            if (extreme.timeMs in startTime..endTime) {
                val px = timeToX(extreme.timeMs)
                val py = valToY(extreme.valueM)
                peakPaint.color = if (extreme.isMax) Color.parseColor("#2E7D32") else Color.parseColor("#C62828")
                canvas.drawCircle(px, py, 10f, peakPaint)
                
                // Orario sopra/sotto il picco
                val label = (if (extreme.isMax) "↑ " else "↓ ") + timeFormat.format(Date(extreme.timeMs))
                val labelY = if (extreme.isMax) py - 20f else py + 40f
                textPaint.color = peakPaint.color
                canvas.drawText(label, px, labelY, textPaint)
            }
        }

        // 4. Disegna indicatore tempo selezionato
        val selX = timeToX(selectedTimeMs)
        canvas.drawLine(selX, padding, selX, h - padding, indicatorPaint)
        canvas.drawCircle(selX, padding, 12f, indicatorPaint)
        canvas.drawCircle(selX, h - padding, 12f, indicatorPaint)
        
        // Ora selezionata sopra l'indicatore
        val timeStr = timeFormat.format(Date(selectedTimeMs))
        canvas.drawText(timeStr, selX.coerceIn(60f, w - 60f), padding - 10f, timePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val data = tideData ?: return false
                if (data.curve.isEmpty()) return false
                
                val startTime = data.curve.first().first
                val endTime = data.curve.last().first
                val timeSpan = (endTime - startTime).toFloat()
                
                val x = event.x.coerceIn(0f, width.toFloat())
                val newTime = startTime + (x / width * timeSpan).toLong()
                
                selectedTimeMs = newTime
                invalidate()
                
                // Notifica il cambiamento
                val value = interpolateAt(data.curve, newTime)
                onTimeChanged?.invoke(newTime, value)
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun interpolateAt(curve: List<Pair<Long, Double>>, t: Long): Double {
        val before = curve.lastOrNull { it.first <= t } ?: curve.first()
        val after = curve.firstOrNull { it.first > t } ?: curve.last()
        if (after.first == before.first) return before.second
        val frac = (t - before.first).toDouble() / (after.first - before.first)
        return before.second + (after.second - before.second) * frac
    }
}
