package com.briccola.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Tachimetro stile Waze: cerchio scuro, arco di avanzamento esterno,
 * velocità al centro in grande.
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var maxSpeed: Float = 40f
        set(value) { field = value; invalidate() }

    var speed: Float = 0f
        set(value) { field = value.coerceIn(0f, maxSpeed); invalidate() }

    var unitLabel: String = "kn"
        set(value) { field = value; invalidate() }

    var speedLimit: Float? = null
        set(value) { field = value; invalidate() }

    private val bgCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E62F343F") // Grigio scuro Waze, leggermente trasparente
        style = Paint.Style.FILL
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5")
        textAlign = Paint.Align.CENTER
    }

    private val limitBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val limitBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
    }

    private val limitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val rectF = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return

        val cx = size / 2f
        val cy = size / 2f
        val radius = size / 2f
        val strokeW = size * 0.08f

        // Sfondo cerchio
        canvas.drawCircle(cx, cy, radius - strokeW/2f, bgCirclePaint)

        // Arco progresso
        val pad = strokeW / 2f
        rectF.set(pad, pad, size - pad, size - pad)
        arcPaint.strokeWidth = strokeW
        
        val frac = (speed / maxSpeed).coerceIn(0f, 1f)
        val startAngle = -90f // In alto
        val sweepAngle = 360f * frac
        
        // Se sopra il limite, l'arco diventa rosso o arancione
        val limit = speedLimit
        if (limit != null && speed > limit) {
            arcPaint.color = Color.parseColor("#FF5252") // Rosso
        } else {
            arcPaint.color = Color.WHITE
        }
        
        canvas.drawArc(rectF, startAngle, sweepAngle, false, arcPaint)

        // Testo velocità
        textPaint.textSize = size * 0.35f
        canvas.drawText(Math.round(speed).toString(), cx, cy + size * 0.08f, textPaint)
        
        // Unità
        unitPaint.textSize = size * 0.12f
        canvas.drawText(unitLabel, cx, cy + size * 0.22f, unitPaint)

        // Limite di velocità (badge in alto a destra)
        speedLimit?.let { limitVal ->
            val badgeR = size * 0.18f
            val badgeCx = size - badgeR
            val badgeCy = badgeR
            
            canvas.drawCircle(badgeCx, badgeCy, badgeR, limitBgPaint)
            limitBorderPaint.strokeWidth = size * 0.04f
            canvas.drawCircle(badgeCx, badgeCy, badgeR - limitBorderPaint.strokeWidth / 2f, limitBorderPaint)
            
            limitTextPaint.textSize = badgeR * 1.0f
            canvas.drawText(Math.round(limitVal).toString(), badgeCx, badgeCy + badgeR * 0.35f, limitTextPaint)
        }
    }
}
