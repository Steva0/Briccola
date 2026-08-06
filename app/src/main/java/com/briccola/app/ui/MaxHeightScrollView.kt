package com.briccola.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

/**
 * ScrollView che si adatta al contenuto (wrap_content) fino a un'altezza massima (android:maxHeight),
 * oltre la quale diventa scorrevole. Un ScrollView normale non supporta un limite massimo:
 * o è wrap_content (e non scorre mai, cresce quanto serve) o è un'altezza fissa (sempre
 * quella, anche se il contenuto è più corto) — qui invece resta compatto quando il contenuto
 * ci sta, e scorre solo quando serve davvero (es. pannello Settaggi Dev con molti slider).
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    private var maxHeightPx: Int = Int.MAX_VALUE

    init {
        attrs?.let {
            val a = context.obtainStyledAttributes(it, intArrayOf(android.R.attr.maxHeight))
            maxHeightPx = a.getDimensionPixelSize(0, Int.MAX_VALUE)
            a.recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val boundedHeightSpec = if (maxHeightPx != Int.MAX_VALUE) {
            View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)
        } else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, boundedHeightSpec)
    }
}
