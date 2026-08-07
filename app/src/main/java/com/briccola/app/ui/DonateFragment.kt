package com.briccola.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import android.view.ViewGroup.MarginLayoutParams
import com.briccola.app.R

class DonateFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_donate, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Button>(R.id.btn_paypal)?.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.paypal.me/MicheleStevanin")))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        view.findViewById<View>(R.id.btn_menu)?.setOnClickListener {
            (activity as? com.briccola.app.MainActivity)?.openDrawer()
        }

        // Padding per Edge-to-Edge nella schermata Donazioni
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            
            v.setPadding(0, statusBarHeight, 0, 0)
            
            // Aggiunge spazio in fondo per non far coprire il testo finale dai tasti di sistema
            view.findViewById<View>(R.id.scroll_donate)?.updateLayoutParams<MarginLayoutParams> {
                bottomMargin = navBarHeight
            }

            insets
        }
    }
}
