package com.briccola.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import com.briccola.app.MainActivity
import com.briccola.app.R
import com.briccola.app.databinding.FragmentTutorialBinding

class TutorialFragment : Fragment() {

    private var _binding: FragmentTutorialBinding? = null
    private val binding get() = _binding!!

    private var currentStep = 0

    // I 14 file di immagine salvati in res/drawable/tutorial_1.png ... tutorial_14.png
    private val tutorialImages = intArrayOf(
        R.drawable.tutorial_1,
        R.drawable.tutorial_2,
        R.drawable.tutorial_3,
        R.drawable.tutorial_4,
        R.drawable.tutorial_5,
        R.drawable.tutorial_6,
        R.drawable.tutorial_7,
        R.drawable.tutorial_8,
        R.drawable.tutorial_9,
        R.drawable.tutorial_10,
        R.drawable.tutorial_11,
        R.drawable.tutorial_12,
        R.drawable.tutorial_13,
        R.drawable.tutorial_14
    )

    // Testi di descrizione temporanei/segnaposto (verranno aggiornati con quelli definitivi dell'utente)
    private val tutorialDescriptions = arrayOf(
        "Monitora in tempo reale la profondità del fondale e la tua velocità con i relativi limiti di navigazione",
        "Attiva il livello delle maree sulla mappa e orienta la vista verso nord",
        "Consulta il grafico della marea ed esplora l'altezza dell'acqua lungo l'arco della giornata",
        "Visualizza l'impatto dei picchi di alta o bassa marea sui fondali della laguna",
        "Salva i tuoi punti d'interesse con etichette personalizzate o premi su Itinerario per tracciare la rotta",
        "Controlla distanza, tempo stimato di arrivo e avvia la navigazione guidata lungo i canali",
        "Segui le indicazioni di rotta in tempo reale o tocca in basso per terminare il viaggio",
        "Cerca canali o luoghi e accedi rapidamente alle destinazioni salvate o cercate di recente",
        "Organizza e ritrova velocemente i tuoi posti barca, preferiti e mete da visitare",
        "Tocca l'icona del menu in alto a sinistra per accedere a tutte le sezioni dell'app",
        "Seleziona la sezione Meteo per consultare le condizioni meteomarine dedicate alla laguna",
        "Verifica previsioni, vento, moto ondoso e andamento delle maree",
        "Accedi alle Impostazioni per personalizzare i parametri dell'imbarcazione e le opzioni di navigazione",
        "Imposta pescaggio, unità di misura della velocità e gestisci il download della mappa offline"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTutorialBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentStep = 0

        // Pulsante X per uscire dal tutorial
        binding.btnTutorialClose.setOnClickListener {
            closeTutorial()
        }

        // Pulsanti Avanti e Indietro
        binding.btnTutorialPrev.setOnClickListener {
            if (currentStep > 0) {
                currentStep--
                updateStepUI()
            }
        }

        binding.btnTutorialNext.setOnClickListener {
            if (currentStep < tutorialImages.size - 1) {
                currentStep++
                updateStepUI()
            } else {
                closeTutorial()
            }
        }

        // Gestione Edge-to-Edge
        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutTutorialRoot) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            binding.layoutTutorialRoot.setPadding(0, statusBarHeight, 0, navBarHeight)
            insets
        }

        updateStepUI()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            currentStep = 0
            updateStepUI()
        }
    }

    private fun updateStepUI() {
        val total = tutorialImages.size
        binding.tvTutorialStepCounter.text = "Passo ${currentStep + 1} di $total"
        binding.ivTutorialPhoto.setImageResource(tutorialImages[currentStep])
        binding.tvTutorialDescription.text = tutorialDescriptions.getOrElse(currentStep) { "" }

        // Pulsante Indietro visibile solo dal passo 2 in poi
        binding.btnTutorialPrev.visibility = if (currentStep > 0) View.VISIBLE else View.INVISIBLE

        // Cambia testo pulsante Avanti all'ultimo passo
        if (currentStep == total - 1) {
            binding.btnTutorialNext.text = "Fine"
        } else {
            binding.btnTutorialNext.text = "Avanti"
        }
    }

    private fun closeTutorial() {
        (activity as? MainActivity)?.openMapFragment()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
