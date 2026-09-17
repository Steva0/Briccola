package com.briccola.app.engine

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

object KeyboardUtils {
    fun setupKeyboardDismissOnTouch(view: View) {
        if (view !is EditText) {
            view.setOnTouchListener { _, _ ->
                val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                val activity = view.context as? Activity
                val focus = activity?.currentFocus ?: view
                imm?.hideSoftInputFromWindow(focus.windowToken, 0)
                focus.clearFocus()
                view.performClick()
                false
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setupKeyboardDismissOnTouch(view.getChildAt(i))
            }
        }
    }
}
