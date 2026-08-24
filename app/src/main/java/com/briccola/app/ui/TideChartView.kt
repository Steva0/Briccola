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
import com.briccola.app.engine.TideExtreme

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
    private val extremePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1976D2")
        textSize = 24f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BDBDBD")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
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

        val padLeft = 80f
        val padTop = 40f
        val padBottom = 60f
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

        // Raccolta di tutte le etichette da disegnare sull'asse Y (zero + estremali)
        class AxisLabel(val value: Double, val text: String, val y: Float, val isZero: Boolean = false, val extreme: TideExtreme? = null)
        
        val labelsList = mutableListOf<AxisLabel>()
        labelsList.add(AxisLabel(0.0, "0 m", zeroY, true))
        d.extremes.forEach { 
            labelsList.add(AxisLabel(it.valueM, "%.2f".format(it.valueM), y(it.valueM), extreme = it))
        }
        
        // Ordiniamo per posizione Y nel grafico (dall'alto in basso)
        val sortedLabels = labelsList.sortedBy { it.y }
        val finalYPositions = mutableMapOf<AxisLabel, Float>()
        val minDistance = 35f

        // 1. Individuiamo l'indice dello zero per ancorarlo
        val zeroIndex = sortedLabels.indexOfFirst { it.isZero }
        
        // 2. Posizione fissa per lo zero (non si muove!)
        val zeroPos = sortedLabels[zeroIndex].y + 8f
        finalYPositions[sortedLabels[zeroIndex]] = zeroPos

        // 3. Gestiamo le etichette SOPRA lo zero (marea positiva) spingendole in su se collidono
        for (i in zeroIndex - 1 downTo 0) {
            val label = sortedLabels[i]
            var pos = label.y + 10f
            val belowY = finalYPositions[sortedLabels[i + 1]]!!
            if (belowY - pos < minDistance) {
                pos = belowY - minDistance
            }
            finalYPositions[label] = pos
        }

        // 4. Gestiamo le etichette SOTTO lo zero (marea negativa) spingendole in giù se collidono
        for (i in zeroIndex + 1 until sortedLabels.size) {
            val label = sortedLabels[i]
            var pos = label.y + 10f
            val aboveY = finalYPositions[sortedLabels[i - 1]]!!
            if (pos - aboveY < minDistance) {
                pos = aboveY + minDistance
            }
            finalYPositions[label] = pos
        }

        // Correzione per non uscire dal bordo inferiore (sposta tutto su se necessario, incluso lo zero purtroppo,
        // ma accade solo se il grafico è molto compresso in basso)
        val maxAllowedY = height.toFloat() - padBottom + 10f
        val lastLabel = sortedLabels.lastOrNull()
        if (lastLabel != null && (finalYPositions[lastLabel] ?: 0f) > maxAllowedY) {
            val shift = (finalYPositions[lastLabel] ?: 0f) - maxAllowedY
            sortedLabels.forEach { label ->
                finalYPositions[label] = (finalYPositions[label] ?: 0f) - shift
            }
        }

        // Disegno etichette e linee
        sortedLabels.forEach { label ->
            val drawY = finalYPositions[label] ?: label.y
            extremePaint.textAlign = Paint.Align.RIGHT
            
            if (label.isZero) {
                canvas.drawText(label.text, padLeft - 8f, drawY, extremePaint)
            } else {
                // Usiamo l'oggetto extreme salvato per evitare errori di duplicati
                val extreme = label.extreme!!
                val ex = x(extreme.timeMs)
                val ey = y(extreme.valueM)
                canvas.drawLine(padLeft, ey, ex, ey, dashPaint)
                canvas.drawCircle(ex, ey, 6f, linePaint)
                canvas.drawText(label.text, padLeft - 8f, drawY, extremePaint)
            }
        }

        // pallino: sull'orario scelto trascinando, o su "adesso" di default
        val now = System.currentTimeMillis()
        val isCurrentDay = now in t0..t1
        
        val markerMs = selectedMs ?: if (isCurrentDay) now else t0
        val markerValue = selectedMs?.let { ms -> 
            d.curve.minByOrNull { Math.abs(it.first - ms) }?.second 
        } ?: if (isCurrentDay) d.nowM else d.curve.first().second

        val markerX = x(markerMs)
        val markerY = y(markerValue)
        canvas.drawLine(markerX, padTop, markerX, padTop + h, axisPaint)
        canvas.drawCircle(markerX, markerY, 12f, nowPaint)

        // etichette ore
        val hoursFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ITALY)
        listOf(0, 1, 2, 3, 4).forEach { i ->
            val t = t0 + duration * i / 4
            var text = hoursFmt.format(java.util.Date(t))
            
            // Se è l'ultima etichetta ed è mezzanotte, mostriamo 24:00 invece di 00:00
            if (i == 4 && text == "00:00") {
                text = "24:00"
            }
            
            val tx = x(t)
            
            labelPaint.textAlign = when(i) {
                0 -> Paint.Align.LEFT
                4 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            
            canvas.drawText(text, tx, padTop + h + 35f, labelPaint)
        }
    }
}
