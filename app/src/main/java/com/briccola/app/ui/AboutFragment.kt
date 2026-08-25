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
import com.briccola.app.MainActivity
import com.briccola.app.R

class AboutFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_about, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        view.findViewById<Button>(R.id.btn_github)?.setOnClickListener {
            openUrl("https://github.com/Steva0/Steva0")
        }
        
        view.findViewById<Button>(R.id.btn_cv)?.setOnClickListener {
            openUrl("https://europa.eu/europass/eportfolio/api/eprofile/shared-profile/michele-stevanin/5f317bb5-67f7-40f7-9dd7-1027464d0870?view=html")
        }

        view.findViewById<Button>(R.id.btn_privacy_policy)?.setOnClickListener {
            openUrl(MainActivity.PRIVACY_POLICY_URL)
        }

        view.findViewById<View>(R.id.btn_menu)?.setOnClickListener {
            (activity as? com.briccola.app.MainActivity)?.openDrawer()
        }

        // Imposta la versione dinamica da BuildConfig
        view.findViewById<android.widget.TextView>(R.id.tv_about_version)?.text = 
            getString(R.string.about_version_label, com.briccola.app.BuildConfig.VERSION_NAME)

        // Padding per Edge-to-Edge nella schermata Crediti
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            
            v.setPadding(0, statusBarHeight, 0, 0)
            
            // Aggiunge spazio in fondo per non far coprire i link legali dai tasti di sistema
            view.findViewById<View>(R.id.scroll_about)?.updateLayoutParams<MarginLayoutParams> {
                bottomMargin = navBarHeight
            }

            insets
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}