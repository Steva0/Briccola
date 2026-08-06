package com.briccola.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Visualizzatore altezza (profondità) stile Waze.
 * Cerchio specchiato al tachimetro.
 */
class AltitudeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var altitude: Float = 0f
        set(value) { field = value; invalidate() }

    private val bgCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E62F343F")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5")
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return

        val cx = size / 2f
        val cy = size / 2f
        val radius = size / 2f
        val strokeW = size * 0.08f

        // Cambio colore in base all'altezza (profondità)
        // < 0.5m -> Rosso, < 1m -> Arancione, altrimenti Grigio Waze
        val bgColor = when {
            altitude < 0.5f -> Color.parseColor("#CCFF5252") // Rosso
            altitude < 1.0f -> Color.parseColor("#CCFF9800") // Arancione
            else -> Color.parseColor("#E62F343F") // Grigio Waze
        }
        bgCirclePaint.color = bgColor

        canvas.drawCircle(cx, cy, radius - strokeW/2f, bgCirclePaint)

        // Testo valore
        textPaint.textSize = size * 0.35f
        val displayValue = if (altitude > 10) ">10" else "%.1f".format(altitude)
        canvas.drawText(displayValue, cx, cy + size * 0.08f, textPaint)
        
        // Label "m"
        labelPaint.textSize = size * 0.12f
        canvas.drawText("metri", cx, cy + size * 0.22f, labelPaint)
    }
}
