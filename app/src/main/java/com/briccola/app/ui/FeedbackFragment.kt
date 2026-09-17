package com.briccola.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import com.briccola.app.BuildConfig
import com.briccola.app.R
import com.briccola.app.databinding.FragmentFeedbackBinding
import com.briccola.app.engine.KeyboardUtils
import com.briccola.app.engine.ReviewManager

class FeedbackFragment : Fragment() {

    private var _binding: FragmentFeedbackBinding? = null
    private val binding get() = _binding!!

    private val categories = arrayOf(
        "Suggerimento per l'app",
        "Mappa & Canali della Laguna",
        "Meteo & Maree",
        "Segnalazione Bug o Errore",
        "Altro"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeedbackBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Menu floating button
        binding.btnMenu.setOnClickListener {
            (activity as? com.briccola.app.MainActivity)?.openDrawer()
        }

        // Tasto Valuta su Play Store
        binding.btnRateStoreDirect.setOnClickListener {
            ReviewManager.openPlayStoreDirectly(requireContext())
        }

        // Spinner Categoria
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, categories)
        binding.spinnerFeedbackCategory.adapter = adapter

        // Tasto Invia Feedback
        binding.btnSendFeedback.setOnClickListener {
            sendFeedbackEmail()
        }

        // Auto-scroll in basso quando l'utente tocca il campo messaggio per iniziare a scrivere
        binding.etFeedbackMessage.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.scrollFeedback.postDelayed({
                    binding.scrollFeedback.fullScroll(View.FOCUS_DOWN)
                }, 250L)
            }
        }

        // Gestione Edge-to-Edge con adattamento automatico per la tastiera virtuale (IME)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom

            vPadding(statusBarHeight)

            // Il margine inferiore diventa l'altezza della tastiera se aperta, o della nav bar
            val bottomInset = maxOf(navBarHeight, imeHeight)
            binding.scrollFeedback.updateLayoutParams<MarginLayoutParams> {
                bottomMargin = bottomInset
            }

            if (imeHeight > 0) {
                binding.scrollFeedback.post {
                    binding.scrollFeedback.fullScroll(View.FOCUS_DOWN)
                }
            }

            insets
        }

        KeyboardUtils.setupKeyboardDismissOnTouch(binding.root)
    }

    private fun vPadding(statusBarHeight: Int) {
        view?.setPadding(0, statusBarHeight, 0, 0)
    }

    private fun sendFeedbackEmail() {
        val message = binding.etFeedbackMessage.text.toString().trim()
        if (message.isEmpty()) {
            Toast.makeText(requireContext(), R.string.feedback_empty_error, Toast.LENGTH_SHORT).show()
            return
        }

        val category = categories.getOrNull(binding.spinnerFeedbackCategory.selectedItemPosition) ?: categories[0]

        val emailBody = buildString {
            append("Messaggio da Briccola App:\n\n")
            append("Categoria: ").append(category).append("\n\n")
            append("Testo del feedback:\n").append(message).append("\n\n")
            append("-----------------------------------\n")
            append("Informazioni Tecniche Dispositivo:\n")
            append("App Versione: v").append(BuildConfig.VERSION_NAME).append(" (Build ").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Dispositivo: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
            append("Android OS: v").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        }

        val destinationEmail = "michele.stevanin.work@gmail.com"
        val subject = "[Briccola Feedback] $category"

        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(destinationEmail))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, emailBody)
            }
            startActivity(Intent.createChooser(intent, "Invia feedback con..."))
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "Impossibile aprire l'app email.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
