package it.lagunav.openlagunamaps.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import it.lagunav.openlagunamaps.R

class DonateFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_donate, container, false)

        root.findViewById<Button>(R.id.btn_paypal)?.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.paypal.me/MicheleStevanin")))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return root
    }
}